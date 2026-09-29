/* hq-flag: none (shell) — `GET /school/usage` carries no feature flag; it is gated by
   `usage.school`, the key the row in `core/nav/screens.ts` puts on the route. A school cannot
   switch off knowing how much of itself is being used. */
import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  type OnDestroy,
  computed,
  inject,
  linkedSignal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { DashboardDataApi } from '../../api';
import { saveFile } from '../../core/download/download';
import { csvOf } from '../../core/download/csv';
import { activeLang } from '../../core/i18n/active-lang';
import { PlatformService } from '../../core/platform/platform.service';
import {
  type TableColumn,
  ButtonComponent,
  CardComponent,
  CountUpDirective,
  EmptyStateComponent,
  InputComponent,
  PageComponent,
  SkeletonComponent,
  TableComponent,
} from '../../ui';
import { CoordinatorReadFailedComponent } from '../coordinator/read-failed.component';
import { defaultStatsRange, windowDays } from './management-stats';
import { type UsageTeacherRow, usageSummary, usageTeacherRows } from './school-usage.models';

/**
 * `Reports.MAX_DAYS` — the server answers 400 for a longer window, so the screen says so and
 * does not send it. Named here rather than shared with `MAX_WINDOW_DAYS` (186) because that one
 * is `/management/stats`'s limit and the two endpoints do not have to agree.
 */
const MAX_DAYS = 400;

/**
 * How long a typed date waits before it is asked about.
 *
 * A `type="date"` input emits a *complete* value while the year is still being typed — `0002`,
 * then `0020`, then `0202` — so "only ask for a full date" is not a guard at all: each of those
 * is a full date, a window of two thousand years, and a 400 from the server. She stops typing
 * for a moment, or she leaves the field, and then it is a question. 300 ms is the People
 * screen's own number for the same reason.
 */
const COMMIT_MS = 300;

/**
 * School usage (MG2a item 5) — `GET /school/usage`, over a window she picks.
 *
 * **It is the school's, not her department's.** `DashboardDataController.mySchoolUsage` resolves
 * the caller's own school and nothing narrower: there is no curriculum or grade axis on
 * `SchoolUsage`, so a manager of the British department sees the American one's teachers in the
 * table too. The subtitle says so in words rather than letting her read her department's name
 * into somebody else's numbers — a department-scoped read is a server change, not a filter this
 * screen may invent.
 *
 * **No AI tokens or cost here.** Those live on `PlatformUsage` (`GET /admin/usage/platform`),
 * behind `usage.platform`, which only an Admin holds; what a school's own row carries is
 * children, active families, lessons published per week, plays per day and the per-teacher
 * consistency this table is.
 *
 * The CSV is built in the browser (`core/download/csv.ts`) from the rows already on screen,
 * because `/school/usage` publishes no `.csv` of its own — the People export's rule.
 */
@Component({
  selector: 'hq-school-usage-page',
  imports: [
    ButtonComponent,
    CardComponent,
    CoordinatorReadFailedComponent,
    CountUpDirective,
    DatePipe,
    EmptyStateComponent,
    InputComponent,
    PageComponent,
    SkeletonComponent,
    TableComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'nav.schoolUsage' | transloco" [subtitle]="'management.usage.scope' | transloco">
      <div class="em-dashboard">
        <div class="mg-filters">
          <hq-input
            type="date"
            [label]="'coordinator.lessons.from' | transloco"
            [value]="from()"
            (valueChange)="onFrom($event)"
            (blurred)="commit()"
          />
          <hq-input
            type="date"
            [label]="'coordinator.lessons.to' | transloco"
            [value]="to()"
            (valueChange)="onTo($event)"
            (blurred)="commit()"
          />
          <hq-button variant="secondary" [disabled]="rows().length === 0" (pressed)="exportCsv()">
            {{ 'management.usage.export' | transloco }}
          </hq-button>
        </div>

        @if (backwards()) {
          <!-- Four zero tiles and an empty table are a statement about the school; a window that
               ends before it starts has made no such statement. -->
          <hq-empty-state [message]="'management.usage.backwards' | transloco" />
        } @else if (tooWide()) {
          <!-- The server refuses more than 400 days with a 400; the Attendance page's own
               sentence says the number, and the request is not sent. -->
          <hq-empty-state [message]="'coordinator.attendance.tooWide' | transloco: { days: maxDays }" />
        } @else if (usage.isLoading()) {
          <hq-skeleton [loading]="true" [lines]="6" [label]="'ui.loading' | transloco" />
        } @else if (usage.error()) {
          <hq-coordinator-read-failed (retry)="usage.reload()" />
        } @else {
          <section class="em-stats-grid" [attr.aria-label]="'management.usage.tilesLabel' | transloco">
            @for (tile of tiles(); track tile.key) {
              <div class="em-stat-card">
                <div class="em-stat-info">
                  <p class="em-stat-label">{{ 'management.usage.tile.' + tile.key | transloco }}</p>
                  <h3 class="em-stat-value"><span [hqCountUp]="tile.value"></span></h3>
                </div>
              </div>
            }
          </section>

          <hq-card [title]="'management.usage.teachers' | transloco">
            <hq-table
              [rows]="rows()"
              [columns]="columns()"
              [cellTemplate]="cell"
              [trackBy]="trackRow"
              [wrapHeaders]="true"
              [label]="'management.usage.teachers' | transloco"
            >
              <hq-empty-state
                table-empty
                [message]="'management.usage.empty' | transloco"
                [detail]="'management.usage.emptyHint' | transloco"
              />
            </hq-table>
          </hq-card>
        }
      </div>

      <ng-template #cell let-row let-column="column">
        @switch (column.key) {
          @case ('displayName') {
            {{ row.displayName }}
          }
          @case ('lessonsPublished') {
            {{ row.lessonsPublished }}
          }
          @case ('weeksWithALesson') {
            {{ row.weeksWithALesson }} / {{ row.weeks }}
          }
          @case ('consistency') {
            {{ percent(row.consistency) }}
          }
          @case ('lastPublishedAt') {
            {{ row.lastPublishedAt === null ? '—' : (row.lastPublishedAt | date: 'mediumDate') }}
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
      margin-block-end: var(--hq-space-16);
    }
  `,
})
export class SchoolUsagePage implements OnDestroy {
  private readonly api = inject(DashboardDataApi);
  private readonly platform = inject(PlatformService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  /** Today where the school is, not where the laptop is set (`ManagementHomePage`'s reason). */
  private readonly schoolToday = computed(() =>
    new Intl.DateTimeFormat('en-CA', { timeZone: this.platform.timezone() }).format(new Date()),
  );
  private readonly defaults = computed(() => defaultStatsRange(this.schoolToday()));
  /** What the two boxes show — every keystroke of it. */
  protected readonly from = linkedSignal(() => this.defaults().from);
  protected readonly to = linkedSignal(() => this.defaults().to);
  /** What a request is made for: the pair she has finished typing (see {@link COMMIT_MS}). */
  private readonly chosen = linkedSignal(() => this.defaults());
  private debounce: ReturnType<typeof setTimeout> | null = null;

  protected readonly maxDays = MAX_DAYS;
  /** Both refusals are computed on the *chosen* pair, so neither flashes while she types. */
  protected readonly tooWide = computed(() => windowDays(this.chosen().from, this.chosen().to) > MAX_DAYS);
  protected readonly backwards = computed(() => {
    const { from, to } = this.chosen();
    return from !== '' && to !== '' && from > to;
  });

  protected readonly usage = rxResource({
    // Both bounds or neither, and neither of the two windows the screen has already refused in
    // words: a request the page knows will be a 400 buys a red retry band with no reason on it.
    params: () => {
      const { from, to } = this.chosen();
      if (from === '' || to === '' || this.backwards() || this.tooWide()) return undefined;
      return { from, to };
    },
    stream: ({ params }) => this.api.mySchoolUsage(params.from, params.to),
  });

  protected onFrom(value: string): void {
    this.from.set(value);
    this.schedule();
  }

  protected onTo(value: string): void {
    this.to.set(value);
    this.schedule();
  }

  /** Leaving the field is finishing with it, so it is asked about at once. */
  protected commit(): void {
    this.stopDebounce();
    this.chosen.set({ from: this.from(), to: this.to() });
  }

  private schedule(): void {
    this.stopDebounce();
    this.debounce = setTimeout(() => this.commit(), COMMIT_MS);
  }

  private stopDebounce(): void {
    if (this.debounce !== null) clearTimeout(this.debounce);
    this.debounce = null;
  }

  ngOnDestroy(): void {
    this.stopDebounce();
  }

  protected readonly tiles = computed(() => {
    const summary = usageSummary(this.usage.value());
    return [
      { key: 'children', value: summary.children },
      { key: 'activeFamilies', value: summary.activeFamilies },
      { key: 'lessonsPublished', value: summary.lessonsPublished },
      { key: 'plays', value: summary.plays },
    ];
  });

  protected readonly rows = computed(() => usageTeacherRows(this.usage.value()));

  protected readonly columns = computed<readonly TableColumn<UsageTeacherRow>[]>(() => {
    this.lang();
    return [
      { key: 'displayName', header: this.t('management.usage.columns.teacher'), width: '34%' },
      { key: 'lessonsPublished', header: this.t('management.usage.columns.published') },
      { key: 'weeksWithALesson', header: this.t('management.usage.columns.weeks') },
      { key: 'consistency', header: this.t('management.usage.columns.consistency') },
      { key: 'lastPublishedAt', header: this.t('management.usage.columns.last') },
    ];
  });

  protected readonly trackRow = (row: UsageTeacherRow): string => row.key;

  /** A dash for a window with no weeks in it — never a flattering 0 %. */
  protected percent(value: number | null): string {
    return value === null ? '—' : `${value}%`;
  }

  protected exportCsv(): void {
    const headers = this.columns().map((column) => column.header);
    const body = this.rows().map((row) => [
      row.displayName,
      String(row.lessonsPublished),
      `${row.weeksWithALesson} / ${row.weeks}`,
      this.percent(row.consistency),
      // `en-CA` in the *browser's* zone — the same day the table's `DatePipe` drew, where
      // `toISOString()` would print yesterday's for anyone east of Greenwich near midnight.
      row.lastPublishedAt === null ? '' : new Date(row.lastPublishedAt).toLocaleDateString('en-CA'),
    ]);
    const { from, to } = this.chosen();
    saveFile(csvOf(headers, body), `school-usage-${from}-${to}.csv`, 'text/csv;charset=utf-8');
  }

  private t(key: string): string {
    return this.transloco.translate<string>(key);
  }
}
