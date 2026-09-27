/* hq-flag: none (shell) — gated by `management.people`, the key RM5 puts on
   `GET /management/people/**`. The server's own `ManagementPeopleController` is in
   `FeatureFlagCoverageTest.INFRASTRUCTURE` for the same reason this screen carries no flag:
   knowing who is in your department is not an optional feature of a school, and `FlagKeys` has
   no key that could turn it off. */
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { type Observable, forkJoin, map } from 'rxjs';
import { ManagementPeopleApi } from '../../api';
import { csvOf } from '../../core/download/csv';
import { saveFile } from '../../core/download/download';
import { StaffAreaService } from '../../core/auth/staff-area';
import { activeLang } from '../../core/i18n/active-lang';
import {
  type Tab,
  type TableColumn,
  ButtonComponent,
  EmptyStateComponent,
  InputComponent,
  PageComponent,
  SkeletonComponent,
  TableComponent,
  TabsComponent,
} from '../../ui';
import { CoordinatorReadFailedComponent } from '../coordinator/read-failed.component';
import { translateOr } from '../coordinator/coordinator.labels';

type PeopleTab = 'children' | 'teachers' | 'coordinators';

/** A page of one tab: the rows on screen and how many there are in all. */
interface PeoplePage {
  readonly total: number;
  readonly rows: readonly PersonRow[];
}

/** One line of the directory, whichever tab it came from — the columns differ, the row does not. */
interface PersonRow {
  readonly id: string;
  readonly name: string;
  readonly email: string;
  /** A child's class, a teacher's subjects, a coordinator's tracks. */
  readonly detail: string;
  readonly grade: string;
}

/** The server caps `size` at 100; 25 is a page a person reads rather than scrolls past. */
const PAGE_SIZE = 25;

const EMPTY_PAGE: PeoplePage = { total: 0, rows: [] };

/**
 * People (RM3a, RM5): everyone in her department, in three tabs.
 *
 * **Children** carry their parent's address — two of them, in fact, the account her parent
 * signed up with and the roster's own, which are often different and are exactly what a manager
 * is on this screen to reconcile — plus the class and the grade. **Teachers** and
 * **Coordinators** are the same two lists her other screens draw, paged and searchable, because
 * a directory is a different question from a supervision list: "what is Omar's address" rather
 * than "has 3B been taught today".
 *
 * Paging is the server's (`page`, `size`, `total`), not a slice of a list that was read whole:
 * a department is hundreds of children and a screen that downloads all of them to show
 * twenty-five is a screen that gets slower every September.
 *
 * **Export** is the whole tab, not the page on screen — a CSV of twenty-five rows out of six
 * hundred is a file somebody will mistake for the roster. It re-reads the tab in pages of 100
 * (the server's own cap) and builds the file in the browser, because neither read-only namespace
 * publishes a `.csv`.
 */
@Component({
  selector: 'hq-management-people-page',
  imports: [
    ButtonComponent,
    CoordinatorReadFailedComponent,
    EmptyStateComponent,
    InputComponent,
    PageComponent,
    SkeletonComponent,
    TableComponent,
    TabsComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'nav.people' | transloco" [subtitle]="'management.people.subtitle' | transloco">
      <hq-tabs
        [tabs]="tabs()"
        [selected]="tab()"
        [label]="'management.people.tabsLabel' | transloco"
        (selectedChange)="onTab($event)"
      />

      <div class="mg-filters">
        <div data-hq-search class="mg-filters__search">
          <hq-input
            type="search"
            keycap="/"
            [label]="'management.people.search' | transloco"
            [placeholder]="'management.people.search' | transloco"
            [value]="search()"
            (valueChange)="onSearch($event)"
          />
        </div>
        <hq-button variant="secondary" [disabled]="exporting()" (pressed)="exportCsv()">
          {{ 'management.people.export' | transloco }}
        </hq-button>
      </div>

      @if (directory.isLoading()) {
        <hq-skeleton [loading]="true" [lines]="6" [label]="'ui.loading' | transloco" />
      } @else if (directory.error()) {
        <hq-coordinator-read-failed (retry)="directory.reload()" />
      } @else {
        <hq-table
          [rows]="rows()"
          [columns]="columns()"
          [cellTemplate]="cell"
          [trackBy]="trackRow"
          [label]="'nav.people' | transloco"
        >
          <hq-empty-state table-empty [message]="'management.people.empty' | transloco" />
        </hq-table>

        <nav class="mg-pager" [attr.aria-label]="'management.people.pagerLabel' | transloco">
          <hq-button variant="secondary" [disabled]="page() === 0" (pressed)="page.set(page() - 1)">
            {{ 'ui.previous' | transloco }}
          </hq-button>
          <span class="hq-muted" aria-live="polite">{{ pageLine() }}</span>
          <hq-button variant="secondary" [disabled]="!hasNext()" (pressed)="page.set(page() + 1)">
            {{ 'ui.next' | transloco }}
          </hq-button>
        </nav>
      }

      <ng-template #cell let-row let-column="column">
        @switch (column.key) {
          @case ('name') {
            {{ row.name }}
          }
          @case ('email') {
            {{ row.email }}
          }
          @case ('detail') {
            {{ row.detail }}
          }
          @case ('grade') {
            {{ row.grade }}
          }
        }
      </ng-template>
    </hq-page>
  `,
  styles: `
    .mg-filters {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      gap: var(--hq-space-16);
      margin-block: var(--hq-space-16);
    }

    .mg-filters__search {
      flex: 1 1 auto;
    }

    .mg-pager {
      display: flex;
      align-items: center;
      gap: var(--hq-space-16);
      margin-block-start: var(--hq-space-16);
    }
  `,
})
export class ManagementPeoplePage {
  private readonly api = inject(ManagementPeopleApi);
  private readonly staff = inject(StaffAreaService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  protected readonly tab = signal<PeopleTab>('children');
  protected readonly search = signal('');
  protected readonly page = signal(0);
  protected readonly exporting = signal(false);

  protected readonly directory = rxResource({
    params: () => {
      if (this.staff.area() !== 'management') return undefined;
      return { tab: this.tab(), q: this.search().trim(), page: this.page() };
    },
    stream: ({ params }) => this.read(params.tab, params.q, params.page, PAGE_SIZE),
    defaultValue: EMPTY_PAGE,
  });

  protected readonly tabs = computed<readonly Tab<PeopleTab>[]>(() => {
    this.lang();
    return [
      { id: 'children', label: this.t('management.people.children') },
      { id: 'teachers', label: this.t('nav.teachers') },
      { id: 'coordinators', label: this.t('nav.coordinators') },
    ];
  });

  protected readonly rows = computed<readonly PersonRow[]>(() => this.directory.value().rows);

  /**
   * Four columns whatever the tab, with two of the headers naming what the tab put in them: a
   * child's parent address and class, a teacher's own address and subjects, a coordinator's
   * tracks. Four tables of three columns each would have been four empty states, four pagers and
   * four exports to keep in step.
   */
  protected readonly columns = computed<readonly TableColumn<PersonRow>[]>(() => {
    this.lang();
    const tab = this.tab();
    const email =
      tab === 'children' ? 'management.people.columns.parentEmail' : 'coordinator.teachers.columns.email';
    const detail =
      tab === 'children'
        ? 'management.people.columns.class'
        : tab === 'teachers'
          ? 'coordinator.teachers.columns.subjects'
          : 'management.coordinators.columns.tracks';
    const trailing =
      tab === 'children'
        ? 'management.people.columns.grade'
        : tab === 'teachers'
          ? 'coordinator.teachers.columns.sections'
          : 'management.people.columns.sectionCount';
    return [
      { key: 'name', header: this.t('management.people.columns.name'), width: '26%' },
      { key: 'email', header: this.t(email), width: '28%' },
      { key: 'detail', header: this.t(detail) },
      { key: 'grade', header: this.t(trailing), width: '16%' },
    ];
  });

  protected readonly hasNext = computed(() => (this.page() + 1) * PAGE_SIZE < this.directory.value().total);

  protected readonly pageLine = computed(() => {
    this.lang();
    const body = this.directory.value();
    const first = body.rows.length === 0 ? 0 : this.page() * PAGE_SIZE + 1;
    // The last row *she has*, not the last this page could have held: a final page of three out
    // of sixty must not read "51–75 of 60".
    return this.transloco.translate<string>('management.people.page', {
      first,
      last: first === 0 ? 0 : first + body.rows.length - 1,
      total: body.total,
    });
  });

  protected readonly trackRow = (row: PersonRow): string => row.id;

  /**
   * A new tab is a new list, from the first page and with no needle.
   *
   * The search is cleared as well as the page: a name typed to find a child is not a name that
   * means anything among the coordinators, and a tab that opened on "no coordinator matches that
   * search" would read as a department with no coordinators in it.
   */
  protected onTab(tab: PeopleTab): void {
    this.tab.set(tab);
    this.search.set('');
    this.page.set(0);
  }

  /** A new needle is a new list, so page 1 of it — not page 12 of a list that no longer exists. */
  protected onSearch(value: string): void {
    this.search.set(value);
    this.page.set(0);
  }

  protected exportCsv(): void {
    this.exporting.set(true);
    const tab = this.tab();
    const q = this.search().trim();
    const total = this.directory.value().total;
    const pages = Math.max(1, Math.ceil(total / 100));
    forkJoin(Array.from({ length: pages }, (_unused, index) => this.read(tab, q, index, 100)))
      .pipe(map((parts) => parts.flatMap((part) => part.rows)))
      .subscribe({
        next: (rows) => {
          const headers = this.columns().map((column) => column.header);
          const body = rows.map((row) => [row.name, row.email, row.detail, row.grade]);
          saveFile(csvOf(headers, body), `${tab}.csv`, 'text/csv;charset=utf-8');
          this.exporting.set(false);
        },
        // The error interceptor has already put the refusal in the red band; this only puts the
        // button back, so a failed export is something she can try again rather than a dead
        // control.
        error: () => this.exporting.set(false),
      });
  }

  private read(tab: PeopleTab, q: string, page: number, size: number): Observable<PeoplePage> {
    const needle = q === '' ? undefined : q;
    if (tab === 'teachers') {
      return this.api.directoryTeachers(needle, page, size).pipe(
        map((body) => ({
          total: body.total ?? 0,
          rows: (body.rows ?? []).map((person) => ({
            id: person.userId ?? '',
            name: person.displayName ?? '',
            email: person.email ?? '',
            detail: (person.subjects ?? [])
              .map((subject) => translateOr(this.transloco, `subject.${subject}`, subject))
              .join(' · '),
            grade: (person.sections ?? []).map((section) => section.className ?? '').join(' · '),
          })),
        })),
      );
    }
    if (tab === 'coordinators') {
      return this.api.directoryCoordinators(needle, page, size).pipe(
        map((body) => ({
          total: body.total ?? 0,
          rows: (body.rows ?? []).map((person) => ({
            id: person.userId ?? '',
            name: person.displayName ?? '',
            email: person.email ?? '',
            detail: (person.curricula ?? [])
              .map((track) => translateOr(this.transloco, `curriculum.${track}`, track))
              .join(' · '),
            grade: String(person.sections ?? 0),
          })),
        })),
      );
    }
    return this.api.directoryChildren(undefined, needle, page, size).pipe(
      map((body) => ({
        total: body.total ?? 0,
        rows: (body.rows ?? []).map((child) => ({
          id: child.childId ?? '',
          name: child.name ?? '',
          // The account her parent signed up with, falling back to the roster's own address.
          // Two columns would be two mostly identical ones; the roster's is the one that exists
          // before a parent has ever opened the app, so it is the fallback rather than the head.
          email: child.parentEmail ?? child.rosterEmail ?? '',
          detail: child.className ?? '',
          grade:
            child.grade === undefined
              ? ''
              : this.transloco.translate<string>('coordinator.classes.grade', { grade: child.grade }),
        })),
      })),
    );
  }

  private t(key: string): string {
    return this.transloco.translate<string>(key);
  }
}
