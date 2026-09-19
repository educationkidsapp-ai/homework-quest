import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  model,
  output,
  signal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { type RosterChild, TeacherRosterApi, apiErrorOf } from '../../api';
import { BandService } from '../../core/band/band.service';
import { activeLang } from '../../core/i18n/active-lang';
import {
  ButtonComponent,
  DialogComponent,
  EmptyStateComponent,
  InputComponent,
  SkeletonComponent,
} from '../../ui';

/**
 * **Place an existing child** — the other half of the Children tab's Add.
 *
 * The owner registers a child in the parents' app before anybody has drawn a class for her, so
 * she lands in the school with a curriculum and a grade and no section. Until somebody places
 * her she is on every section's roster at once as far as the lessons are concerned — she sees
 * one copy of the day's lesson per section of her grade — and no screen in the dashboard could
 * fix it. This is that screen.
 *
 * `GET /teacher/classes/{id}/children/unassigned` already answers only the children the server
 * would accept for *this* section (same curriculum, same grade, on no roster), so nothing here
 * filters on a rule the server owns: the list is what may be placed, and the search box narrows
 * it by name or parent email for a grade with sixty children in it.
 *
 * One decision per row, so the footer has no primary action — `confirmLabel` is left null and
 * the Place button on the row is the primary. The row that is being placed is the only busy
 * one, and `placing` refuses a second press anywhere while a `POST` is in the air: attach is
 * idempotent on the server, but a double-press that placed two children because the list moved
 * under the cursor would not be.
 */
@Component({
  selector: 'hq-place-child',
  imports: [
    DialogComponent,
    ButtonComponent,
    InputComponent,
    EmptyStateComponent,
    SkeletonComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-dialog
      [(open)]="open"
      [sheet]="true"
      [title]="'classes.children.place.title' | transloco"
      [cancelLabel]="'classes.children.place.close' | transloco"
    >
      @if (children.isLoading()) {
        <hq-skeleton
          [loading]="true"
          [lines]="4"
          height="var(--hq-size-row-height)"
          [label]="'classes.children.place.loading' | transloco"
        />
      } @else if (children.value().length === 0) {
        <hq-empty-state [message]="'classes.children.place.empty' | transloco" />
      } @else {
        <hq-input
          type="search"
          [label]="'classes.children.place.search' | transloco"
          [placeholder]="'classes.children.place.searchPlaceholder' | transloco"
          [value]="query()"
          (valueChange)="query.set($event)"
        />

        @if (matches().length === 0) {
          <p class="place__none">{{ 'classes.children.place.noMatch' | transloco: { query: query() } }}</p>
        }

        <ul class="place__list">
          @for (child of matches(); track child.id) {
            <li class="place__row">
              <span class="place__who">
                <span class="place__name">{{ child.name }}</span>
                @if (child.parentEmail) {
                  <span class="place__email">{{ child.parentEmail }}</span>
                }
                @if (child.hasParent) {
                  <span class="hq-badge hq-badge--light">{{
                    'classes.children.place.fromApp' | transloco
                  }}</span>
                }
              </span>
              <hq-button
                variant="primary"
                [loading]="placing() === child.id"
                [disabled]="placing() !== null && placing() !== child.id"
                (pressed)="place(child)"
              >
                {{ 'classes.children.place.place' | transloco }}
                <span class="hq-sr-only">{{
                  'classes.children.place.actionOf' | transloco: { name: child.name }
                }}</span>
              </hq-button>
            </li>
          }
        </ul>
      }
    </hq-dialog>
  `,
  styles: `
    .place__list {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-8);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .place__row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--hq-space-16);
      min-block-size: var(--hq-size-row-height);
      padding-inline: var(--hq-space-12);
      border: var(--hq-size-rule-thin) solid var(--hq-color-rule);
    }

    .place__who {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-4);
      align-items: flex-start;
    }

    .place__name {
      font-weight: var(--hq-text-weight-medium);
    }

    .place__email,
    .place__none {
      font-size: var(--hq-text-theme-xs);
      color: var(--hq-color-ink-soft);
    }

    .place__none {
      margin: 0;
    }
  `,
})
export class PlaceChildComponent {
  private readonly rosterApi = inject(TeacherRosterApi);
  private readonly band = inject(BandService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  readonly open = model(false);
  readonly classId = input.required<string>();

  /** The child the server attached, for the toast the host shows and the reload it runs. */
  readonly placed = output<RosterChild>();

  /**
   * The list, fetched on each open and dropped on each close.
   *
   * A parent may add a child while the dialog is shut, so a list kept from the last open is a
   * list that is already wrong; and the request must not fire for a tab nobody has opened.
   */
  protected readonly children = rxResource({
    params: () => (this.open() ? this.classId() : undefined),
    stream: ({ params }) => this.rosterApi.unassignedForMyClass(params),
    defaultValue: [] as RosterChild[],
  });

  protected readonly query = signal('');
  protected readonly placing = signal<string | null>(null);

  protected readonly matches = computed<readonly RosterChild[]>(() => {
    const needle = this.query().trim().toLowerCase();
    const all = this.children.value();
    if (needle === '') return all;
    return all.filter((child) =>
      `${child.name ?? ''} ${child.parentEmail ?? ''}`.toLowerCase().includes(needle),
    );
  });

  protected place(child: RosterChild): void {
    if (this.placing() !== null) return;
    const childId = child.id ?? '';
    if (!childId) return;
    this.placing.set(childId);
    this.rosterApi.attachToMyClass(this.classId(), { childId }).subscribe({
      next: (attached: RosterChild) => {
        this.placing.set(null);
        this.query.set('');
        this.open.set(false);
        this.placed.emit({ ...child, ...attached });
      },
      error: (error: unknown) => {
        this.placing.set(null);
        // The dialog stays open: the band is on the page behind it, and a list that vanished
        // would leave her with an error and no way back to the row that failed.
        this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
      },
    });
  }

  private t(key: string, params?: Record<string, unknown>): string {
    this.lang();
    return this.transloco.translate<string>(key, params);
  }
}
