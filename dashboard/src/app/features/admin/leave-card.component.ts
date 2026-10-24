import { ChangeDetectionStrategy, Component, computed, inject, input, model } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { FLAGS, FlagService } from '../../core/flags/flag.service';
import { activeLang } from '../../core/i18n/active-lang';
import { type SelectOption, CardComponent, InputComponent, SelectComponent, ToggleComponent } from '../../ui';
import {
  CATEGORIES,
  LEAVE_CHAINS,
  LEAVE_DAYS_MAX,
  PERMISSION_MAX_LIMIT,
  type Category,
  type DraftErrors,
  type LeaveDraft,
  type LeaveType,
  type LeaveTypeDraft,
  balanceKey,
  isLeaveChain,
} from './attendance-settings.models';

/**
 * **Leave and permission** (feature 34, `docs/features/34-leave-and-permission-requests.md`) — the
 * card of the Attendance settings page that says what a member of staff may ask for: which leave
 * types the school offers and which of them needs a document after N days, the yearly allowance
 * per type and staff category, who approves, and the longest permission in one day.
 *
 * **Its own component, the page's one Save.** Feature 23's and feature 31's cards each own a route
 * of their own and save themselves; these four settings are fields of the *same*
 * `PUT /admin/attendance-settings` the page already sends, which replaces the document as a whole.
 * A second PUT from inside the card would therefore re-send whatever the card last read for the
 * school pin, the hours and the holidays — and undo an edit the Admin made above it. So the card
 * is self-contained in markup, styles and labels, and edits the page's one draft through
 * {@link leave}: one dirty state, one validity, one Save.
 *
 * Behind `staffLeave`: with the flag off the card is absent, because every route that reads these
 * settings answers 404 and there is nothing older to fall back to. The draft still carries what
 * the server sent, so the page's Save sends it back unchanged.
 */
@Component({
  selector: 'hq-leave-card',
  imports: [CardComponent, InputComponent, SelectComponent, ToggleComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (enabled()) {
      <hq-card [title]="'admin.attendanceSettings.leave.title' | transloco" data-hq-leave-card>
        <p class="hq-muted">{{ 'admin.attendanceSettings.leave.hint' | transloco }}</p>

        <!-- The types the school offers, each with its document rule. -->
        <fieldset class="leave__types">
          <legend>{{ 'admin.attendanceSettings.leave.types' | transloco }}</legend>
          @for (type of leave().types; track type.type) {
            <div class="leave__type">
              <hq-toggle
                [label]="typeLabel(type.type)"
                [checked]="type.enabled"
                [disabled]="disabled()"
                (checkedChange)="setEnabled(type.type, $event)"
              />
              <hq-input
                type="number"
                [label]="documentLabel(type.type)"
                [hint]="'admin.attendanceSettings.leave.documentHint' | transloco"
                [placeholder]="'admin.attendanceSettings.leave.noDocument' | transloco"
                [min]="'0'"
                [max]="daysMax"
                [value]="type.documentAfterDays"
                [disabled]="disabled() || !type.enabled"
                [error]="error('leave.document.' + type.type)"
                (valueChange)="setDocument(type.type, $event)"
              />
            </div>
          }
        </fieldset>

        <!-- The yearly allowance, per type and category. Empty is "no balance": unpaid and other
             leave are unlimited unless the Admin gives them one. -->
        <fieldset class="leave__balances">
          <legend>{{ 'admin.attendanceSettings.leave.balances' | transloco }}</legend>
          <p class="hq-muted">{{ 'admin.attendanceSettings.leave.balancesHint' | transloco }}</p>
          <div class="leave__grid">
            @for (category of categories; track category) {
              <span class="leave__head">{{ categoryLabel(category) }}</span>
            }
            @for (type of leave().types; track type.type) {
              <span class="leave__row-head">{{ typeLabel(type.type) }}</span>
              @for (category of categories; track category) {
                <hq-input
                  type="number"
                  [label]="cellLabel(type.type, category)"
                  [hideLabel]="true"
                  [placeholder]="'admin.attendanceSettings.leave.noBalance' | transloco"
                  [min]="'0'"
                  [max]="daysMax"
                  [value]="balance(type.type, category)"
                  [disabled]="disabled()"
                  [error]="error('leave.balance.' + balanceKeyOf(type.type, category))"
                  (valueChange)="setBalance(type.type, category, $event)"
                />
              }
            }
          </div>
        </fieldset>

        <div class="leave__rules">
          <hq-select
            [label]="'admin.attendanceSettings.leave.chain' | transloco"
            [hint]="'admin.attendanceSettings.leave.chainHint' | transloco"
            [options]="chainOptions()"
            [value]="leave().chain"
            [disabled]="disabled()"
            (valueChange)="setChain($event)"
          />
          <hq-input
            type="number"
            [label]="'admin.attendanceSettings.leave.permissionMax' | transloco"
            [required]="true"
            [hint]="'admin.attendanceSettings.leave.permissionMaxHint' | transloco"
            [min]="'1'"
            [max]="permissionLimit"
            [value]="leave().permissionMax"
            [disabled]="disabled()"
            [error]="error('leave.permissionMax')"
            (valueChange)="setPermissionMax($event)"
          />
        </div>
      </hq-card>
    }
  `,
  styles: `
    .leave__types,
    .leave__balances {
      margin: var(--hq-space-16) 0 0;
      border: 0;
      padding: 0;

      legend {
        padding: 0;
        font-weight: var(--hq-font-label-weight);
      }
    }

    .leave__type {
      display: grid;
      gap: var(--hq-space-12);
      grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
      align-items: start;
      margin-block-start: var(--hq-space-12);

      @media (max-width: 48rem) {
        grid-template-columns: minmax(0, 1fr);
      }
    }

    .leave__grid {
      display: grid;
      grid-template-columns: minmax(6rem, 1fr) repeat(3, minmax(5rem, 1fr));
      gap: var(--hq-space-8) var(--hq-space-12);
      align-items: center;
      margin-block-start: var(--hq-space-12);
    }

    .leave__head {
      grid-column: span 1;
      font-weight: var(--hq-font-label-weight);
    }

    /* The empty top-left corner of the grid. */
    .leave__head:first-of-type {
      grid-column-start: 2;
    }

    .leave__row-head {
      grid-column-start: 1;
    }

    .leave__rules {
      display: grid;
      gap: var(--hq-space-12);
      grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
      align-items: start;
      margin-block-start: var(--hq-space-16);

      @media (max-width: 48rem) {
        grid-template-columns: minmax(0, 1fr);
      }
    }
  `,
})
export class LeaveCardComponent {
  private readonly transloco = inject(TranslocoService);
  private readonly flagService = inject(FlagService);
  private readonly lang = activeLang();

  /** The page's own draft slice: edited here, saved by the page's one PUT. */
  readonly leave = model.required<LeaveDraft>();
  readonly disabled = input(false);
  readonly errors = input<DraftErrors>({});

  protected readonly enabled = computed(() => this.flagService.isOn(FLAGS.staffLeave));

  protected readonly categories = CATEGORIES;
  protected readonly daysMax = String(LEAVE_DAYS_MAX);
  protected readonly permissionLimit = String(PERMISSION_MAX_LIMIT);

  protected readonly chainOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return LEAVE_CHAINS.map((chain) => ({
      value: chain,
      label: this.t(`admin.attendanceSettings.leave.chainOption.${chain}`),
    }));
  });

  protected balanceKeyOf = balanceKey;

  protected balance(type: LeaveType, category: Category): string {
    return this.leave().balances[balanceKey(type, category)] ?? '';
  }

  protected typeLabel(type: string): string {
    this.lang();
    return this.t(`admin.attendanceSettings.leave.type.${type}`);
  }

  protected categoryLabel(category: string): string {
    this.lang();
    return this.t(`admin.attendanceSettings.category.${category}`);
  }

  /** Each type's own document field, so the five of them are told apart by name. */
  protected documentLabel(type: LeaveType): string {
    this.lang();
    return this.t('admin.attendanceSettings.leave.documentFor', { type: this.typeLabel(type) });
  }

  /** The hidden label of one cell: a screen reader hears "Annual leave, teachers". */
  protected cellLabel(type: LeaveType, category: Category): string {
    this.lang();
    return this.t('admin.attendanceSettings.leave.cell', {
      type: this.typeLabel(type),
      category: this.categoryLabel(category),
    });
  }

  protected error(field: string): string | null {
    this.lang();
    const key = this.errors()[field];
    return key
      ? this.t(`admin.attendanceSettings.error.${key}`, { max: LEAVE_DAYS_MAX, limit: PERMISSION_MAX_LIMIT })
      : null;
  }

  // ---- edits ----------------------------------------------------------------------------------

  protected setEnabled(type: LeaveType, enabled: boolean): void {
    this.patchType(type, { enabled });
  }

  protected setDocument(type: LeaveType, documentAfterDays: string): void {
    this.patchType(type, { documentAfterDays });
  }

  private patchType(type: LeaveType, patch: Partial<LeaveTypeDraft>): void {
    this.leave.update((draft) => ({
      ...draft,
      types: draft.types.map((one) => (one.type === type ? { ...one, ...patch } : one)),
    }));
  }

  /** An emptied cell takes the balance away: the pair is left out of the PUT and deleted. */
  protected setBalance(type: LeaveType, category: Category, value: string): void {
    this.leave.update((draft) => ({
      ...draft,
      balances: { ...draft.balances, [balanceKey(type, category)]: value },
    }));
  }

  protected setChain(value: string): void {
    if (isLeaveChain(value)) this.leave.update((draft) => ({ ...draft, chain: value }));
  }

  protected setPermissionMax(permissionMax: string): void {
    this.leave.update((draft) => ({ ...draft, permissionMax }));
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
