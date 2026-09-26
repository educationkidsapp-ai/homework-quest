/* hq-flag: none (shell) — gated by `coordinator.read`, the key R2 puts on
   `GET /coordinator/classes` and `GET /coordinator/calendar`. The sections she supervises are
   the role; there is no flag that could take them away. */
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { type ClassCalendarDay, CoordinatorApi } from '../../api';
import { activeLang } from '../../core/i18n/active-lang';
import { PlatformService } from '../../core/platform/platform.service';
import {
  type SelectOption,
  CardComponent,
  PageComponent,
  SelectComponent,
  SkeletonComponent,
} from '../../ui';
import { ClassCalendarComponent } from '../classes/class-calendar.component';
import { calendarCells } from '../classes/classes.models';
import { StatusSquareComponent } from '../week/status-square.component';
import { CoordinatorService } from './coordinator.service';
import { scopeLabel } from './coordinator.labels';

/**
 * Classes + calendar (R5, `docs/coordinator-flow.md` §4).
 *
 * Two halves. Above, every section in scope with its grade, track, teacher and roster size, and
 * whether today has happened in it. Below, **one** section's month, drawn by the same
 * `hq-class-calendar` the teacher's class page uses — in read-only mode, so U1's hidden
 * non-teaching columns and its gap marking are hers for free while the `+` and the past-day
 * marker are not drawn at all.
 *
 * One class at a time is a deliberate limit, not an omission. `CalendarCell` holds at most one
 * lesson per day, which is exactly right for a section (a class has one lesson a day) and wrong
 * for six grades at once; a month that silently showed one of four lessons on a square would be
 * worse than a month that says which class it is about. The filter is how she reaches the rest,
 * and R6's screens are where "all classes at once" belongs if the owner asks for it.
 *
 * `GET /coordinator/calendar?from&to` answers for *every* class in scope, so the month is
 * fetched once and filtered here rather than re-fetched per class: switching class is instant
 * and costs nothing, and paging the month costs one request.
 */
@Component({
  selector: 'hq-coordinator-classes-page',
  imports: [
    CardComponent,
    ClassCalendarComponent,
    PageComponent,
    SelectComponent,
    SkeletonComponent,
    StatusSquareComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'nav.classes' | transloco" [subtitle]="scopeLine()">
      @if (co.loading()) {
        <hq-skeleton [loading]="true" [lines]="6" [label]="'ui.loading' | transloco" />
      } @else {
        <hq-card [title]="'coordinator.classes.sections' | transloco">
          @if (co.classes().length === 0) {
            <p class="hq-muted">{{ 'coordinator.classes.empty' | transloco }}</p>
          } @else {
            <ul class="co-sections">
              @for (row of co.classes(); track row.classId) {
                <li class="co-sections__row">
                  <hq-status-square [status]="row.todayStatus" />
                  <span class="co-sections__name">{{ row.className }}</span>
                  <span class="hq-muted">{{ courseOf(row.subject, row.curriculum, row.grade) }}</span>
                  <span class="hq-muted">{{ row.teacherName }}</span>
                  <span class="hq-muted">
                    {{ 'coordinator.classes.children' | transloco: { count: row.childrenCount } }}
                  </span>
                </li>
              }
            </ul>
          }
        </hq-card>

        @if (classOptions().length > 0) {
          <hq-select
            [label]="'coordinator.classes.filter' | transloco"
            [options]="classOptions()"
            [value]="selectedClassId()"
            (valueChange)="selectedClassId.set($event)"
          />

          <hq-class-calendar
            [classId]="selectedClassId()"
            [readOnly]="true"
            lessonBase="/coordinator/lessons"
            [year]="year()"
            [month]="month()"
            [cells]="cells()"
            [gaps]="gaps()"
            [loading]="calendar.isLoading()"
            (monthShift)="shiftMonth($event)"
          />
        }
      }
    </hq-page>
  `,
  styles: `
    .co-sections {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-8);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .co-sections__row {
      display: flex;
      align-items: center;
      gap: var(--hq-space-12);
      min-block-size: var(--hq-size-touch-target);
      border-block-end: var(--hq-size-rule-thin) solid var(--hq-color-divider);
    }

    .co-sections__name {
      font-weight: var(--hq-text-weight-medium);
    }
  `,
})
export class CoordinatorClassesPage {
  protected readonly co = inject(CoordinatorService);
  private readonly api = inject(CoordinatorApi);
  private readonly platform = inject(PlatformService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  /** The month on screen, as the school reckons it — not the browser's idea of today. */
  private readonly today = new Date(
    `${new Intl.DateTimeFormat('en-CA', { timeZone: this.platform.timezone() }).format(new Date())}T00:00:00Z`,
  );
  protected readonly year = signal(this.today.getUTCFullYear());
  protected readonly month = signal(this.today.getUTCMonth() + 1);

  protected readonly selectedClassId = signal('');

  constructor() {
    // The first class in scope, once the list has landed. An effect rather than a computed, because
    // after that the choice is hers and a computed would keep pulling it back to the first row.
    effect(() => {
      const first = this.co.classes()[0]?.classId ?? '';
      if (this.selectedClassId() === '' && first !== '') this.selectedClassId.set(first);
    });
  }

  private readonly range = computed(() => {
    const year = this.year();
    const month = this.month();
    const last = new Date(Date.UTC(year, month, 0)).getUTCDate();
    const pad = (value: number) => String(value).padStart(2, '0');
    return { from: `${year}-${pad(month)}-01`, to: `${year}-${pad(month)}-${pad(last)}` };
  });

  protected readonly calendar = rxResource({
    params: () => this.range(),
    stream: ({ params }) => this.api.coordinatorCalendar(params.from, params.to),
  });

  /**
   * The selected class's month, in the shape `calendarCells` already knows how to lay out.
   *
   * `schoolDay` comes off the server's day and is never re-derived — the school week is an Admin
   * setting (U1 item 6). A **gap** is not on this response the way it is on the teacher's, so it
   * is derived here from the one rule the teacher's server-side copy uses: a school day that has
   * arrived with nothing on it. Never a future day, which is not a failure.
   */
  protected readonly cells = computed(() => {
    const classId = this.selectedClassId();
    const iso = this.today.toISOString().slice(0, 10);
    const days: ClassCalendarDay[] = (this.calendar.value()?.days ?? []).map((day) => {
      const lesson = (day.lessons ?? []).find((entry) => entry.classId === classId);
      const schoolDay = day.schoolDay ?? false;
      return {
        date: day.date,
        schoolDay,
        lessonId: lesson?.lessonId,
        status: lesson?.status,
        title: lesson?.title,
        type: lesson?.type,
        playedCount: lesson?.playedCount ?? 0,
        gap: schoolDay && lesson === undefined && (day.date ?? '') <= iso,
      };
    });
    return calendarCells({ year: this.year(), month: this.month(), days });
  });

  protected readonly gaps = computed(() => this.cells().filter((cell) => cell.gap).length);

  protected readonly classOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return this.co.classes().map((row) => ({
      value: row.classId,
      label: `${row.className} · ${this.courseOf(row.subject, row.curriculum, row.grade)}`,
    }));
  });

  protected readonly scopeLine = computed(() => {
    this.lang();
    const chips = this.co.scopes().map((scope) => scopeLabel(this.transloco, scope));
    return chips.length === 0 ? null : chips.join(' · ');
  });

  protected shiftMonth(delta: number): void {
    const next = new Date(Date.UTC(this.year(), this.month() - 1 + delta, 1));
    this.year.set(next.getUTCFullYear());
    this.month.set(next.getUTCMonth() + 1);
  }

  protected courseOf(subject: string, curriculum: string, grade: number): string {
    this.lang();
    const label = scopeLabel(this.transloco, { subject, curriculum: curriculum || null });
    return `${this.transloco.translate<string>('coordinator.classes.grade', { grade })} · ${label}`;
  }
}
