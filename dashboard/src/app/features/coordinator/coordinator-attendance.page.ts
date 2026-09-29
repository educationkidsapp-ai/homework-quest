/* hq-flag: none (shell) — gated by `coordinator.attendance.read`, the key R2 puts on
   `GET /coordinator/classes/{id}/attendance`. Attendance is not behind a toggle for anybody:
   the teacher's own marking screen is a tab of her class page with no flag either, and a school
   that has classes has registers. */
import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe } from '@jsverse/transloco';
import { PlatformService } from '../../core/platform/platform.service';
import {
  EmptyStateComponent,
  InputComponent,
  PageComponent,
  SelectComponent,
  SkeletonComponent,
} from '../../ui';
import { ClassAttendanceComponent } from '../classes/class-attendance.component';
import { defaultAttendanceWeek } from '../classes/attendance-range';
import { normalise } from '../results/gradebook.models';
import { ResultsApiService } from '../results/results-api.service';
import { classPicker } from './coordinator-class-picker';
import { CoordinatorReadFailedComponent } from './read-failed.component';
import { StaffScopeService } from './staff-scope.service';

/** The server refuses more than 62 days (R3), so the screen refuses to ask for them. */
const MAX_DAYS = 62;

/**
 * Attendance (R6, `docs/coordinator-flow.md`): one of her sections, a range of days, read only.
 *
 * The table is the teacher's own `hq-class-attendance` in its range mode — the same roster, the
 * same avatars, the same four words for a mark — with the status pills, the notes, "Mark all
 * present" and Save simply not rendered. A register is the one thing on her screens she has a
 * reason to take away with her, and `/coordinator/**` publishes no `.csv`, so the component
 * builds one from the rows already on the screen.
 *
 * The default range is **this week**, Monday to today. A term of columns is a sideways scroll
 * nobody reads, and the question she opens this screen with is whether her sections have been
 * marked at all this week.
 */
@Component({
  selector: 'hq-coordinator-attendance-page',
  imports: [
    ClassAttendanceComponent,
    CoordinatorReadFailedComponent,
    EmptyStateComponent,
    InputComponent,
    PageComponent,
    SelectComponent,
    SkeletonComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'nav.attendance' | transloco" [subtitle]="co.scoped('attendance.subtitle') | transloco">
      @if (co.loading()) {
        <hq-skeleton [loading]="true" [lines]="6" [label]="'ui.loading' | transloco" />
      } @else if (co.failed()) {
        <hq-coordinator-read-failed (retry)="co.reload()" />
      } @else if (picker.options().length === 0) {
        <hq-empty-state [message]="co.scoped('classes.empty') | transloco" />
      } @else {
        <div class="co-filters">
          <hq-select
            [label]="'coordinator.lessons.byClass' | transloco"
            [options]="picker.options()"
            [value]="picker.classId()"
            (valueChange)="picker.select($event)"
          />
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
          <hq-empty-state [message]="'coordinator.attendance.tooWide' | transloco: { days: maxDays }" />
        } @else if (days.isLoading()) {
          <hq-skeleton [loading]="true" [lines]="6" [label]="'attendance.loading' | transloco" />
        } @else if (days.error()) {
          <!-- Its own request, its own error: "nothing was marked" is a statement about her
               school, and a read that never happened has made no such statement. -->
          <hq-coordinator-read-failed (retry)="days.reload()" />
        } @else {
          <hq-class-attendance
            [classId]="picker.classId()"
            [className]="picker.className()"
            [readOnly]="true"
            [days]="days.value()"
            [childBase]="childBase"
          />
        }
      }
    </hq-page>
  `,
  styles: `
    .co-filters {
      display: flex;
      flex-wrap: wrap;
      gap: var(--hq-space-16);
      margin-block-end: var(--hq-space-16);
    }
  `,
})
export class CoordinatorAttendancePage {
  protected readonly co = inject(StaffScopeService);
  private readonly reads = inject(ResultsApiService);
  private readonly platform = inject(PlatformService);
  protected readonly picker = classPicker();

  /**
   * MG2a: `null` for a manager — `children/:childId` is one of her retired rows
   * (`core/nav/screens.ts`), so a linked name here would be a click that lands on her Home.
   * The coordinator keeps hers: her child report never left her area.
   */
  protected readonly childBase = this.co.isManager() ? null : `${this.co.base()}/children`;

  /** Named in the sentence the screen refuses with, so the copy cannot drift from the limit. */
  protected readonly maxDays = MAX_DAYS;

  /**
   * Today **where the children are**, not where the laptop is set (`PlatformService.timezone`).
   *
   * `linkedSignal` rather than `signal`: `/platform-settings` lands after the screen does, so a
   * plain signal would be seeded from `UTC` and never corrected — and a coordinator in +04 opening
   * the screen just after midnight would be shown last week. The dates she types are kept until
   * the source changes, and the source changes exactly once, on that first settings response —
   * before there is a range of hers to lose.
   */
  private readonly schoolToday = computed(() =>
    new Intl.DateTimeFormat('en-CA', { timeZone: this.platform.timezone() }).format(new Date()),
  );

  private readonly week = computed(() => defaultAttendanceWeek(this.schoolToday()));
  protected readonly from = linkedSignal(() => this.week().from);
  protected readonly to = linkedSignal(() => this.week().to);

  /** 63 days would be a 400 from the server; it is a sentence on the screen instead. */
  protected readonly tooWide = computed(() => {
    const range = normalise({ from: this.from(), to: this.to() });
    if (!range) return false;
    const span = Date.parse(`${range.to}T00:00:00Z`) - Date.parse(`${range.from}T00:00:00Z`);
    return span / 86_400_000 + 1 > MAX_DAYS;
  });

  protected readonly days = rxResource({
    params: () => {
      const range = normalise({ from: this.from(), to: this.to() });
      const classId = this.picker.classId();
      if (!range || classId === '' || this.tooWide() || !this.reads.ready()) return undefined;
      return { classId, ...range };
    },
    stream: ({ params }) => this.reads.attendanceRange(params.classId, params.from, params.to),
    defaultValue: [],
  });
}
