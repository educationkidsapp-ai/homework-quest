/* hq-flag: none (shell) — a role's Home. It is gated by `management.read`, the key RM1 puts on
   `GET /management/me` and `GET /management/stats` themselves; a flag that could empty it would
   leave `/` with nowhere to send her. Same reasoning as `features/home/home.page.ts`. */
import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ManagementApi } from '../../api';
import { activeLang } from '../../core/i18n/active-lang';
import { PlatformService } from '../../core/platform/platform.service';
import {
  type TableColumn,
  CardComponent,
  CountUpDirective,
  EmptyStateComponent,
  InputComponent,
  PageComponent,
  SkeletonComponent,
  TableComponent,
} from '../../ui';
import { CoordinatorReadFailedComponent } from '../coordinator/read-failed.component';
import { StaffScopeService } from '../coordinator/staff-scope.service';
import { scopeLabel } from '../coordinator/coordinator.labels';
import {
  type StatsRow,
  MAX_WINDOW_DAYS,
  defaultStatsRange,
  quietTeachers,
  statsRows,
  windowDays,
} from './management-stats';

/**
 * The department manager's Home (RM3a, DR5 — `docs/management-flow.md`).
 *
 * Her question is not the coordinator's. A coordinator opens her Home to find out whether
 * anything is wrong in six classes; a manager runs a whole track and opens hers to find out how
 * the department is *doing* — so the screen leads with `GET /management/stats`: a row per grade
 * and the department's own total, over a window she chooses, with "what needs you" underneath it.
 *
 * **Every rate can be genuinely absent.** `attendanceRate` is null rather than 100 when nothing
 * was marked, and an empty grade has no exam average; both draw a dash. The exam average is an
 * approximation on this screen by the server's own admission — the exact figure is on the exam's
 * results page, which is one click away from Exams.
 */
@Component({
  selector: 'hq-management-home-page',
  imports: [
    CardComponent,
    CoordinatorReadFailedComponent,
    CountUpDirective,
    EmptyStateComponent,
    InputComponent,
    PageComponent,
    RouterLink,
    SkeletonComponent,
    TableComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="greeting()" [subtitle]="scopeLine()">
      @if (staff.loading()) {
        <hq-skeleton [loading]="true" [lines]="6" [label]="'home.loading' | transloco" />
      } @else if (staff.failed()) {
        <hq-coordinator-read-failed (retry)="staff.reload()" />
      } @else {
        <div class="em-dashboard">
          <section
            class="em-stats-grid"
            [attr.aria-label]="'home.cardsLabel' | transloco"
            data-hq-tour="cards"
          >
            @for (card of cards(); track card.key) {
              <div class="em-stat-card">
                <div class="em-stat-info">
                  <p class="em-stat-label">{{ 'management.count.' + card.key | transloco }}</p>
                  <h3 class="em-stat-value"><span [hqCountUp]="card.value"></span></h3>
                </div>
              </div>
            }
          </section>

          <hq-card [title]="'management.stats.title' | transloco">
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
            </div>

            @if (tooWide()) {
              <!-- The server refuses more than a term (186 days) with a 400; the screen names
                   that number and does not send the request. -->
              <hq-empty-state [message]="'management.stats.tooWide' | transloco: { days: maxDays }" />
            } @else if (stats.isLoading()) {
              <hq-skeleton [loading]="true" [lines]="6" [label]="'management.stats.loading' | transloco" />
            } @else if (stats.error()) {
              <!-- Its own request, its own band: the counts above it are perfectly readable, and
                   a window that answered nothing is not a department with nothing in it. -->
              <hq-coordinator-read-failed (retry)="stats.reload()" />
            } @else {
              <hq-table
                [rows]="rows()"
                [columns]="columns()"
                [cellTemplate]="cell"
                [trackBy]="trackRow"
                [wrapHeaders]="true"
                [isSelected]="isTotal"
                [label]="'management.stats.title' | transloco"
              />
            }
          </hq-card>

          <hq-card [title]="'management.quiet.title' | transloco">
            @if (quiet().length === 0) {
              <p class="hq-muted">{{ 'management.quiet.empty' | transloco }}</p>
            } @else {
              <p class="hq-muted">{{ 'management.quiet.subtitle' | transloco }}</p>
              <ul class="mg-list">
                @for (teacher of quiet(); track teacher.userId) {
                  <li class="mg-list__row">
                    <span>{{ teacher.displayName }}</span>
                    <span class="hq-muted">{{ teacher.email }}</span>
                  </li>
                }
              </ul>
            }
          </hq-card>

          <!-- What needs her: the lessons of her department that failed, then the ones waiting
               for a review, then the sections with nothing on today. The **complaints** of her
               department belong at the top of this list and are RM3b's: RM2 shipped the server
               half (GET /management/chat/threads) while this package was being written, and no
               screen of hers reads it yet — a line built from nothing would be a promise this
               screen cannot keep, so the slot is left open rather than filled with a zero. -->
          <hq-card [title]="'home.needsYou' | transloco">
            @if (staff.needs().length === 0) {
              <p class="hq-muted">{{ 'home.allClear' | transloco }}</p>
            } @else {
              <ul class="mg-list">
                @for (need of staff.needs().slice(0, 12); track $index) {
                  <li class="mg-list__row">
                    @if (need.link; as link) {
                      <a [routerLink]="link">{{ needLine(need) }}</a>
                    } @else {
                      <!-- MG2a: her lesson page went with All lessons, so the line is the fact
                           and the next step is a message, which is what it always was. -->
                      <span>{{ needLine(need) }}</span>
                    }
                    <span class="hq-badge" [class.hq-badge--error]="need.kind === 'error'">
                      {{ 'coordinator.need.' + need.kind | transloco }}
                    </span>
                  </li>
                }
              </ul>
            }
          </hq-card>
        </div>
      }

      <ng-template #cell let-row let-column="column">
        @switch (column.key) {
          @case ('grade') {
            <span [class.mg-total]="row.grade === null">{{ gradeLabel(row) }}</span>
          }
          @case ('children') {
            {{ row.children }}
          }
          @case ('sections') {
            {{ row.sections }}
          }
          @case ('attendanceRate') {
            {{ percent(row.attendanceRate) }}
          }
          @case ('lessonsPublished') {
            {{ row.lessonsPublished }}
          }
          @case ('lessonsPlayed') {
            {{ row.lessonsPlayed }}
          }
          @case ('exams') {
            {{ row.exams }}
          }
          @case ('examAverage') {
            {{ percent(row.examAverage) }}
          }
          @case ('examPassRate') {
            {{ percent(row.examPassRate) }}
          }
        }
      </ng-template>
    </hq-page>
  `,
  styles: `
    .mg-filters {
      display: flex;
      flex-wrap: wrap;
      gap: var(--hq-space-16);
      margin-block-end: var(--hq-space-16);
    }

    .mg-list {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-8);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .mg-list__row {
      display: flex;
      align-items: center;
      gap: var(--hq-space-12);
      min-block-size: var(--hq-size-touch-target);
      border-block-end: var(--hq-size-rule-thin) solid var(--hq-color-divider);
    }

    .mg-total {
      font-weight: var(--hq-text-weight-medium);
    }
  `,
})
export class ManagementHomePage {
  protected readonly staff = inject(StaffScopeService);
  private readonly api = inject(ManagementApi);
  private readonly platform = inject(PlatformService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  /**
   * Today **where the school is**, not where the laptop is set.
   *
   * `linkedSignal` for `AttendanceRange`'s reason: `/platform-settings` lands after the screen
   * does, so a plain signal would be seeded from UTC and never corrected — and a manager in +04
   * opening the screen just after midnight would be shown a window that ended yesterday.
   */
  private readonly schoolToday = computed(() =>
    new Intl.DateTimeFormat('en-CA', { timeZone: this.platform.timezone() }).format(new Date()),
  );
  private readonly window = computed(() => defaultStatsRange(this.schoolToday()));
  protected readonly from = linkedSignal(() => this.window().from);
  protected readonly to = linkedSignal(() => this.window().to);

  protected readonly stats = rxResource({
    params: () => {
      const from = this.from();
      const to = this.to();
      // Both bounds or neither: `GET /management/stats` defaults to the month ending today, and
      // a half-typed date would otherwise ask for a window the person has not finished choosing.
      if (!this.staff.isManager() || from === '' || to === '' || from > to) return undefined;
      if (this.tooWide()) return undefined;
      return { from, to };
    },
    stream: ({ params }) => this.api.managementStats(params.from, params.to),
  });

  /** 187 days would be a 400 from the server; it is a sentence on the screen instead. */
  protected readonly maxDays = MAX_WINDOW_DAYS;
  protected readonly tooWide = computed(() => windowDays(this.from(), this.to()) > MAX_WINDOW_DAYS);

  protected readonly rows = computed<readonly StatsRow[]>(() => statsRows(this.stats.value()));
  protected readonly quiet = computed(() => quietTeachers(this.stats.value()));

  protected readonly greeting = computed(() => {
    this.lang();
    return this.transloco.translate<string>('home.greeting', { name: this.staff.displayName() });
  });

  /** "British", or "British · American" for a manager of two departments (RM1). */
  protected readonly scopeLine = computed(() => {
    this.lang();
    const chips = this.staff.scopes().map((scope) => scopeLabel(this.transloco, scope));
    return chips.length === 0 ? null : chips.join(' · ');
  });

  protected readonly cards = computed(() => {
    const counts = this.staff.counts();
    return [
      { key: 'sections', value: counts.sections },
      { key: 'teachers', value: counts.teachers },
      { key: 'coordinators', value: counts.coordinators },
      { key: 'children', value: counts.children },
    ];
  });

  protected readonly columns = computed<readonly TableColumn<StatsRow>[]>(() => {
    this.lang();
    return [
      { key: 'grade', header: this.t('management.stats.columns.grade'), width: '14%' },
      { key: 'children', header: this.t('management.stats.columns.children') },
      { key: 'sections', header: this.t('management.stats.columns.sections') },
      { key: 'attendanceRate', header: this.t('management.stats.columns.attendance') },
      { key: 'lessonsPublished', header: this.t('management.stats.columns.published') },
      { key: 'lessonsPlayed', header: this.t('management.stats.columns.played') },
      { key: 'exams', header: this.t('management.stats.columns.exams') },
      { key: 'examAverage', header: this.t('management.stats.columns.examAverage') },
      { key: 'examPassRate', header: this.t('management.stats.columns.examPassRate') },
    ];
  });

  protected readonly trackRow = (row: StatsRow): string => row.key;
  protected readonly isTotal = (row: StatsRow): boolean => row.grade === null;

  /** A dash, not a zero and not a 100: nothing was marked, or there was nothing to measure. */
  protected percent(value: number | null): string {
    return value === null ? '—' : `${value}%`;
  }

  protected gradeLabel(row: StatsRow): string {
    this.lang();
    return row.grade === null
      ? this.t('management.stats.total')
      : this.transloco.translate<string>('coordinator.classes.grade', { grade: row.grade });
  }

  protected needLine(need: { readonly title: string; readonly className: string }): string {
    this.lang();
    const title = need.title || this.t('lessons.untitled');
    return need.className ? `${title} · ${need.className}` : title;
  }

  private t(key: string): string {
    return this.transloco.translate<string>(key);
  }
}
