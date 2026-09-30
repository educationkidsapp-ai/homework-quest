/* hq-flag: none (shell) — gated by `management.people`, the key RM5 puts on
   `GET /management/people/**`. The server's own `ManagementPeopleController` is in
   `FeatureFlagCoverageTest.INFRASTRUCTURE` for the same reason this screen carries no flag:
   knowing who is in your department is not an optional feature of a school, and `FlagKeys` has
   no key that could turn it off. */
import { type OnDestroy, ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { type Observable, from, map, mergeMap, toArray } from 'rxjs';
import { ManagementPeopleApi } from '../../api';
import { csvOf } from '../../core/download/csv';
import { saveFile } from '../../core/download/download';
import { StaffAreaService } from '../../core/auth/staff-area';
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
import { StaffThreadService } from './staff-thread.service';

/** A page of the list: the rows on screen and how many there are in all. */
interface ChildPage {
  readonly total: number;
  readonly rows: readonly ChildRow[];
}

/** One child of the department, with the parent a manager would ring or write to. */
interface ChildRow {
  readonly childId: string;
  readonly name: string;
  readonly className: string;
  readonly grade: string;
  readonly parentEmail: string;
  readonly parentPhone: string;
  /**
   * `null` ⇔ no registered parent (MH1).
   *
   * The whole reason the action can be disabled: `POST /management/chat/threads {childId}` opens
   * the thread with *the child's parent*, and a roster row whose parent has never signed up has
   * nobody on the other end — the server answers 404 `no_parent`. A button that posts that is a
   * button that fails, so it is disabled with the reason on it instead.
   */
  readonly parentId: string | null;
}

/** The server caps `size` at 100; 25 is a page a person reads rather than scrolls past. */
const PAGE_SIZE = 25;

/**
 * How long the needle waits behind the keyboard, and how wide the export's pages are.
 *
 * 250 ms is `ScreenSearchService`'s own number, and for its own reason: long enough that a word
 * is typed before anything is asked for, short enough to feel live. It matters more here than it
 * does there — the header's box filters rows already in the browser, while every character typed
 * into this one is a server-side search across the whole department.
 */
const DEBOUNCE_MS = 250;

/**
 * The export reads the list in pages of the server's own maximum, four at a time, and stops.
 *
 * `mergeMap` with a ceiling rather than a `forkJoin` over every page: a six-hundred-child
 * department is six requests and a five-thousand-child one was fifty, all in flight at once,
 * against an endpoint that searches. Four keeps a big department quick without being the reason
 * the API is slow for everyone else, and {@link EXPORT_MAX_PAGES} is where it gives up and says
 * so rather than issuing two hundred reads nobody asked to wait for.
 */
export const EXPORT_PAGE_SIZE = 100;
export const EXPORT_CONCURRENCY = 4;
export const EXPORT_MAX_PAGES = 30;

/** How many rows one file may carry, named once so the copy cannot drift from the cap. */
export const EXPORT_MAX_ROWS = EXPORT_PAGE_SIZE * EXPORT_MAX_PAGES;

/**
 * How many pages this export will read, and whether that is short of the department.
 *
 * Its own function because it is the decision, not the plumbing: the cap is the difference
 * between a file somebody files as "the roster" and one that quietly stops three thousand rows
 * in, and `truncated` is what puts that on the screen.
 */
export function exportPlan(total: number): { readonly pages: number; readonly truncated: boolean } {
  const wanted = Math.max(1, Math.ceil(total / EXPORT_PAGE_SIZE));
  const pages = Math.min(wanted, EXPORT_MAX_PAGES);
  return { pages, truncated: pages < wanted };
}

const EMPTY_PAGE: ChildPage = { total: 0, rows: [] };

/**
 * **Children** (RM5, renamed and narrowed by MH2 item 3).
 *
 * RM5 built this as "People", three tabs: children, teachers, coordinators. Two of those three had
 * since grown screens of their own — Coordinators and Teachers are rail rows with phone numbers, a
 * supervision column and a Message action — so the tabs were the same two lists, worse, one click
 * further away. What is left is the one list nothing else draws: every child of her department,
 * with the class, the grade and **the parent's email and phone**, which is what a manager is on
 * this screen to find.
 *
 * `/management/people` still resolves: a bookmark and the runbook's own URL redirect to
 * `/management/children` (`core/nav/screens.ts`).
 *
 * Paging is the server's (`page`, `size`, `total`), not a slice of a list that was read whole:
 * a department is hundreds of children and a screen that downloads all of them to show
 * twenty-five is a screen that gets slower every September.
 *
 * **Export** is the whole list, not the page on screen — a CSV of twenty-five rows out of six
 * hundred is a file somebody will mistake for the roster. It re-reads in pages of 100 (the
 * server's own cap) and builds the file in the browser, because neither read-only namespace
 * publishes a `.csv`.
 */
@Component({
  selector: 'hq-management-children-page',
  imports: [
    BandComponent,
    ButtonComponent,
    CanDirective,
    CoordinatorReadFailedComponent,
    EmptyStateComponent,
    InputComponent,
    PageComponent,
    SkeletonComponent,
    TableComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'nav.children' | transloco" [subtitle]="'management.children.subtitle' | transloco">
      @if (threads.failed()) {
        <hq-band
          variant="error"
          [open]="true"
          [title]="'band.failed' | transloco"
          (dismissed)="threads.failed.set(false)"
        >
          {{ 'management.message.failed' | transloco }}
        </hq-band>
      }

      <div class="mg-filters">
        <div data-hq-search class="mg-filters__search">
          <hq-input
            type="search"
            keycap="/"
            [label]="'management.children.search' | transloco"
            [placeholder]="'management.children.search' | transloco"
            [value]="search()"
            (valueChange)="onSearch($event)"
          />
        </div>
        <hq-button variant="secondary" [disabled]="exporting()" (pressed)="exportCsv()">
          {{ 'management.people.export' | transloco }}
        </hq-button>
      </div>

      @if (exportTruncated()) {
        <!-- A file that is short of the department says so on the screen that made it: a CSV
             somebody files as "the roster" must not quietly stop at three thousand rows. -->
        <hq-band
          variant="notice"
          [open]="true"
          [dismissible]="false"
          [title]="'management.people.tooMany' | transloco"
        >
          {{ 'management.people.tooManyBody' | transloco: { rows: maxExportRows } }}
        </hq-band>
      }

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
          [label]="'nav.children' | transloco"
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
          @case ('className') {
            {{ row.className }}
          }
          @case ('grade') {
            {{ row.grade }}
          }
          @case ('parentEmail') {
            {{ row.parentEmail || '—' }}
          }
          @case ('parentPhone') {
            @if (row.parentPhone) {
              <a [href]="'tel:' + row.parentPhone" dir="ltr">{{ row.parentPhone }}</a>
            } @else {
              <span class="hq-muted">—</span>
            }
          }
          @case ('actions') {
            <hq-button
              *hqCan="'management.chat'"
              variant="secondary"
              [disabled]="row.parentId === null"
              [reason]="row.parentId === null ? ('management.message.noParent' | transloco) : null"
              [loading]="threads.pending() === row.childId"
              (pressed)="message(row)"
            >
              {{ 'management.message.parent' | transloco }}
            </hq-button>
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
export class ManagementChildrenPage implements OnDestroy {
  private readonly api = inject(ManagementPeopleApi);
  private readonly staff = inject(StaffAreaService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();
  protected readonly threads = inject(StaffThreadService);

  /** What the box shows, on every keystroke, so typing is never laggy. */
  protected readonly search = signal('');
  /** What is actually asked for: trimmed, and 250 ms behind the keyboard. */
  private readonly needle = signal('');
  private debounce: ReturnType<typeof setTimeout> | null = null;
  protected readonly page = signal(0);
  protected readonly exporting = signal(false);
  protected readonly exportTruncated = signal(false);

  protected readonly directory = rxResource({
    params: () => {
      if (this.staff.area() !== 'management') return undefined;
      return { q: this.needle(), page: this.page() };
    },
    stream: ({ params }) => this.read(params.q, params.page, PAGE_SIZE),
    defaultValue: EMPTY_PAGE,
  });

  protected readonly rows = computed<readonly ChildRow[]>(() => this.directory.value().rows);

  protected readonly columns = computed<readonly TableColumn<ChildRow>[]>(() => {
    this.lang();
    return [
      { key: 'name', header: this.t('management.people.columns.name'), width: '20%' },
      { key: 'className', header: this.t('management.people.columns.class'), width: '12%' },
      { key: 'grade', header: this.t('management.people.columns.grade'), width: '12%' },
      { key: 'parentEmail', header: this.t('management.people.columns.parentEmail') },
      { key: 'parentPhone', header: this.t('management.columns.parentPhone'), width: '16%' },
      { key: 'actions', header: this.t('ui.actions'), width: '16%' },
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

  protected readonly trackRow = (row: ChildRow): string => row.childId;

  /** Named in the sentence the band says, so the copy cannot drift from the cap. */
  protected readonly maxExportRows = EXPORT_MAX_ROWS;

  protected message(row: ChildRow): void {
    if (row.parentId === null) return;
    this.threads.open(row.childId, { childId: row.childId });
  }

  /**
   * A new needle is a new list, so page 1 of it — not page 12 of a list that no longer exists.
   *
   * The request waits {@link DEBOUNCE_MS} behind the keystroke, so "Mohammed" is one server-side
   * search and not eight. **Clearing is not debounced**, the way the header's box is not:
   * emptying the field puts the whole department back at once, and there is nothing to type
   * ahead of.
   */
  protected onSearch(value: string): void {
    this.search.set(value);
    this.page.set(0);
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

  /**
   * The whole list as a file, not the page on screen: twenty-five rows out of six hundred is a
   * file somebody will mistake for the roster.
   */
  protected exportCsv(): void {
    this.exporting.set(true);
    const q = this.needle();
    const { pages, truncated } = exportPlan(this.directory.value().total);
    this.exportTruncated.set(truncated);
    from(Array.from({ length: pages }, (_unused, index) => index))
      .pipe(
        mergeMap((page) => this.read(q, page, EXPORT_PAGE_SIZE), EXPORT_CONCURRENCY),
        toArray(),
        map((parts) => parts.flatMap((part) => part.rows)),
      )
      .subscribe({
        next: (rows) => {
          // The columns she is looking at, minus the one that holds a button rather than a value.
          const columns = this.columns().filter((column) => column.key !== 'actions');
          saveFile(
            csvOf(
              columns.map((column) => column.header),
              rows.map((row) => [row.name, row.className, row.grade, row.parentEmail, row.parentPhone]),
            ),
            'children.csv',
            'text/csv;charset=utf-8',
          );
          this.exporting.set(false);
        },
        // The error interceptor has already put the refusal in the red band; this only puts the
        // button back, so a failed export is something she can try again rather than a dead
        // control.
        error: () => this.exporting.set(false),
      });
  }

  private read(q: string, page: number, size: number): Observable<ChildPage> {
    const needle = q === '' ? undefined : q;
    return this.api.directoryChildren(undefined, needle, page, size).pipe(
      map((body) => ({
        total: body.total ?? 0,
        rows: (body.rows ?? []).map((child) => ({
          childId: child.childId ?? '',
          name: child.name ?? '',
          className: child.className ?? '',
          grade:
            child.grade === undefined
              ? ''
              : this.transloco.translate<string>('coordinator.classes.grade', { grade: child.grade }),
          // The account her parent signed up with, falling back to the roster's own address.
          // Two columns would be two mostly identical ones; the roster's is the one that exists
          // before a parent has ever opened the app, so it is the fallback rather than the head.
          parentEmail: child.parentEmail ?? child.rosterEmail ?? '',
          parentPhone: child.parentPhone ?? '',
          parentId: child.parentId ?? null,
        })),
      })),
    );
  }

  private t(key: string): string {
    return this.transloco.translate<string>(key);
  }
}
