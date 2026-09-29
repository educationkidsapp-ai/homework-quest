/* hq-flag: none (shell) — `GET /school/usage` carries no feature flag; it is gated by
   `usage.school`, the key the row in `core/nav/screens.ts` puts on the route. A school cannot
   switch off knowing how much of itself is being used. */
import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal } from '@angular/core';
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
import { defaultStatsRange } from './management-stats';
import { type UsageTeacherRow, usageSummary, usageTeacherRows } from './school-usage.models';

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
            (valueChange)="from.set($event)"
          />
          <hq-input
            type="date"
            [label]="'coordinator.lessons.to' | transloco"
            [value]="to()"
            (valueChange)="to.set($event)"
          />
          <hq-button variant="secondary" [disabled]="rows().length === 0" (pressed)="exportCsv()">
            {{ 'management.usage.export' | transloco }}
          </hq-button>
        </div>

        @if (usage.isLoading()) {
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
export class SchoolUsagePage {
  private readonly api = inject(DashboardDataApi);
  private readonly platform = inject(PlatformService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  /** Today where the school is, not where the laptop is set (`ManagementHomePage`'s reason). */
  private readonly schoolToday = computed(() =>
    new Intl.DateTimeFormat('en-CA', { timeZone: this.platform.timezone() }).format(new Date()),
  );
  private readonly window = computed(() => defaultStatsRange(this.schoolToday()));
  protected readonly from = linkedSignal(() => this.window().from);
  protected readonly to = linkedSignal(() => this.window().to);

  protected readonly usage = rxResource({
    // Both bounds or neither: a half-typed date would ask for a window nobody has chosen yet.
    params: () => {
      const from = this.from();
      const to = this.to();
      return from === '' || to === '' || from > to ? undefined : { from, to };
    },
    stream: ({ params }) => this.api.mySchoolUsage(params.from, params.to),
  });

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
      row.lastPublishedAt === null ? '' : new Date(row.lastPublishedAt).toISOString().slice(0, 10),
    ]);
    saveFile(csvOf(headers, body), `school-usage-${this.from()}-${this.to()}.csv`, 'text/csv;charset=utf-8');
  }

  private t(key: string): string {
    return this.transloco.translate<string>(key);
  }
}
