import { type OnDestroy, ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { type FloorChild, FloorSupervisorApi } from '../../api';
import { FLAGS } from '../../core/flags/flag.service';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import {
  type TableColumn,
  BandComponent,
  ButtonComponent,
  EmptyStateComponent,
  InputComponent,
  PageComponent,
  SkeletonComponent,
  TableComponent,
} from '../../ui';
import { CoordinatorReadFailedComponent } from '../coordinator/read-failed.component';
import { StaffThreadService } from '../management/staff-thread.service';

/** One row: the child, where she sits, and the link to her report. */
interface ChildRow {
  readonly childId: string;
  readonly name: string;
  readonly className: string;
  readonly floorName: string;
  readonly link: string;
}

function rowOf(one: FloorChild): ChildRow {
  const id = one.id ?? '';
  return {
    childId: id,
    name: one.name ?? '',
    className: one.className ?? '',
    floorName: one.floorName ?? '',
    link: `/floor/children/${id}`,
  };
}

/** 250 ms — `ScreenSearchService`'s own number, and `/floor/children?q=` searches the server side. */
const DEBOUNCE_MS = 250;

/**
 * **Children of her floor** (feature 20): every child in the classes on her floors, searchable,
 * with the two things she does with one — read the report, or write to the parent.
 *
 * **Read-only, and that is the role.** `GET /floor/children/{id}` answers the same `ChildReport`
 * the coordinator and the manager read, and the page it opens is the teacher's own child page in
 * read-only mode (`features/results/child.page.ts`) — reached through `ResultsApiService`, which
 * knows `/floor/**` is a fourth namespace for the same shape.
 *
 * **Message** is the second tap of the two-tap rule: `POST /floor/chat/threads {childId}` answers
 * the conversation with that child's parent — the existing one, or a new one — and her Messages
 * screen opens on it. `FloorChild` carries no parent, so a child whose parent never signed up is
 * a 404 the band says in a sentence ({@link StaffThreadService.failed}) rather than a button that
 * could have been disabled.
 *
 * Behind `floorSupervisor`: `featureGuard` on the row, and `*hqFeature` over the contents here.
 *
 * The search is the server's, not a filter over a list read whole: a floor is hundreds of
 * children, and 250 ms behind the keyboard is one request for a name rather than eight.
 */
@Component({
  selector: 'hq-floor-children-page',
  imports: [
    BandComponent,
    ButtonComponent,
    CanDirective,
    CoordinatorReadFailedComponent,
    EmptyStateComponent,
    FeatureDirective,
    InputComponent,
    PageComponent,
    RouterLink,
    SkeletonComponent,
    TableComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './floor-children.page.html',
  styleUrl: './floor-children.page.scss',
})
export class FloorChildrenPage implements OnDestroy {
  private readonly api = inject(FloorSupervisorApi);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();
  protected readonly threads = inject(StaffThreadService);

  protected readonly floorFlag = FLAGS.floorSupervisor;

  /** What the box shows, on every keystroke, so typing is never laggy. */
  protected readonly search = signal('');
  /** What is actually asked for: trimmed, and 250 ms behind the keyboard. */
  private readonly needle = signal('');
  private debounce: ReturnType<typeof setTimeout> | null = null;

  protected readonly children = rxResource({
    params: () => ({ q: this.needle() }),
    stream: ({ params }) => this.api.floorChildren(params.q === '' ? undefined : params.q),
    defaultValue: [],
  });

  protected readonly rows = computed<readonly ChildRow[]>(() => this.children.value().map(rowOf));

  protected readonly columns = computed<readonly TableColumn<ChildRow>[]>(() => {
    this.lang();
    return [
      { key: 'name', header: this.t('floor.children.table.name'), width: '32%' },
      { key: 'className', header: this.t('floor.children.table.class'), width: '18%' },
      { key: 'floorName', header: this.t('floor.children.table.floor'), width: '18%' },
      { key: 'actions', header: this.t('ui.actions'), width: '20%' },
    ];
  });

  protected readonly trackRow = (row: ChildRow): string => row.childId;

  protected message(row: ChildRow): void {
    this.threads.open(row.childId, { childId: row.childId });
  }

  /**
   * **Clearing is not debounced**, the way the header's box is not: emptying the field puts the
   * whole floor back at once, and there is nothing to type ahead of.
   */
  protected onSearch(value: string): void {
    this.search.set(value);
    this.stopDebounce();
    if (value.trim() === '') {
      this.needle.set('');
      return;
    }
    this.debounce = setTimeout(() => {
      this.debounce = null;
      this.needle.set(value.trim());
    }, DEBOUNCE_MS);
  }

  ngOnDestroy(): void {
    this.stopDebounce();
  }

  private stopDebounce(): void {
    if (this.debounce !== null) clearTimeout(this.debounce);
    this.debounce = null;
  }

  private t(key: string): string {
    return this.transloco.translate<string>(key);
  }
}
