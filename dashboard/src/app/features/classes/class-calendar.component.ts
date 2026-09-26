import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { FLAGS } from '../../core/flags/flag.service';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import { PlatformService } from '../../core/platform/platform.service';
import { SkeletonComponent } from '../../ui';
import { StatusSquareComponent } from '../week/status-square.component';
import type { CalendarCell } from './classes.models';

/**
 * One class's month (`docs/teacher-flow.md` §4 step 3): a square per day, the lesson's status
 * on it, and the gaps in red.
 *
 * A dumb grid — the page owns the request and which month is showing. Two rules it does *not*
 * decide for itself, because the server already has:
 *
 * * **which days are school days** (an Admin setting, `schoolWeek`), so a Friday is not drawn at
 *   all here — U1 item 6 hides the school's non-teaching columns rather than dimming them —
 *   without this screen knowing anything about the Gulf week; and
 * * **what counts as a gap** — a school day that has arrived with nothing on it. A future
 *   Tuesday is not a failure, and colouring it red would make every new month look like one.
 *
 * Each cell holds at most one focusable control: the lesson's link, or the empty school day's
 * `+`. That is what makes Tab walk the month in reading order without a roving tabindex.
 */
@Component({
  selector: 'hq-class-calendar',
  imports: [
    RouterLink,
    SkeletonComponent,
    StatusSquareComponent,
    FeatureDirective,
    CanDirective,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template:
    `
    <div class="cal">
      <div class="cal__nav">
        <button
          type="button"
          class="cal__nav-button"
          [attr.aria-label]="'classes.calendar.prevMonth' | transloco"
          (click)="monthShift.emit(-1)"
        >
          ‹
        </button>
        <p class="cal__month">{{ monthLabel() }}</p>
        <button
          type="button"
          class="cal__nav-button"
          [attr.aria-label]="'classes.calendar.nextMonth' | transloco"
          (click)="monthShift.emit(1)"
        >
          ›
        </button>
      </div>

      @if (gaps() > 0) {
        <p class="cal__gaps">{{ 'classes.calendar.gapsCount' | transloco: { count: gaps() } }}</p>
      }

      @if (loading()) {
        <hq-skeleton [loading]="true" [lines]="6" [label]="'classes.calendar.loading' | transloco" />
      } @else {
        <div class="cal__scroll">
          <div
            class="cal__grid"
            role="grid"
            [attr.aria-label]="monthLabel()"
            [style.--hq-cal-columns]="weekdays().length"
          >
            <div class="cal__row" role="row">
              @for (weekday of weekdays(); track weekday) {
                <div class="cal__weekday" role="columnheader">{{ weekday }}</div>
              }
            </div>
            @for (week of weeks(); track $index) {
              <div class="cal__row" role="row">
                @for (cell of week; track cell.iso) {
                  <div
                    class="cal__cell"
                    role="gridcell"
                    [attr.data-date]="cell.iso"
                    [class.cal__cell--outside]="!cell.inMonth"
                    [class.cal__cell--off]="cell.inMonth && !cell.schoolDay"
                    [class.cal__cell--gap]="cell.gap"
                  >
                    <span class="cal__day">{{ cell.day }}</span>

                    @if (cell.lessonId; as lessonId) {
                      <a class="cal__lesson" [routerLink]="[lessonBase(), lessonId]">
                        <hq-status-square [status]="cell.status" />
                        <span class="cal__lesson-text">
                          <!-- N4.4: the exam ribbon This week already wears (§4 step 10), on the
                               month grid too, so a teacher scanning a month finds the exam
                               without reading thirty titles. -->
                          @if (isExam(cell)) {
                            <span class="cal__ribbon">{{ 'exams.ribbon' | transloco }}</span>
                          }
                          <span class="cal__lesson-title">{{ titleOf(cell) }}</span>
                          <span class="cal__lesson-meta">{{ 'week.status.' + cell.status | transloco }}</span>
                        </span>
                        <span class="hq-sr-only">{{ dayLabel(cell.iso) }}</span>
                      </a>
                      <!-- §4 step 3's Results column: how many played, and the way in to their
                           scores once the lesson is published (N4.2). -->
                      @if (cell.status === 'published') {
                        <span *hqFeature="isExam(cell) ? examsFlag : gradebookFlag">
                          <a
                            *hqCan="'results.read'"
                            class="cal__results cal__results--link"
                            [routerLink]="resultsLink(cell)"
                          >
                            {{ 'classes.calendar.played' | transloco: { count: cell.playedCount } }}
                          </a>
                        </span>
                      } @else {
                        <span class="cal__results">
                          {{ 'classes.calendar.played' | transloco: { count: cell.playedCount } }}
                        </span>
                      }
                    } @else if (readOnly()) {
                      <!--
                        R5: a coordinator's month. She plans nothing and removes nothing (DR2), so
                        an empty day carries no ` +
    ` and no "this day has passed" explanation of a
                        ` +
    ` she was never offered — only the gaps, which are the whole reason she
                        is looking.
                      -->
                      @if (cell.gap) {
                        <span class="cal__gap-label">{{ 'classes.calendar.gap' | transloco }}</span>
                      }
                    } @else if (cell.schoolDay && !isPast(cell.iso)) {
                      <a
                        class="cal__add"
                        [routerLink]="['/teacher/lessons/new']"
                        [queryParams]="newLessonParams(cell)"
                        [attr.aria-label]="'classes.calendar.addOn' | transloco: { day: dayLabel(cell.iso) }"
                      >
                        +
                      </a>
                      @if (cell.gap) {
                        <span class="cal__gap-label">{{ 'classes.calendar.gap' | transloco }}</span>
                      }
                    } @else if (cell.schoolDay) {
                      <!--
                        U1 item 6: a school day that has already gone takes nothing. No +, so
                        there is nothing to click, and the cell says why rather than leaving a
                        teacher wondering where the + went. The reason is a title *and* a
                        screen-reader line, because a tooltip alone is not an explanation.
                      -->
                      <span
                        class="cal__past"
                        [attr.title]="'classes.calendar.pastDay' | transloco"
                        [attr.aria-label]="
                          'classes.calendar.pastDayOn' | transloco: { day: dayLabel(cell.iso) }
                        "
                      >
                        <span aria-hidden="true">—</span>
                      </span>
                      @if (cell.gap) {
                        <span class="cal__gap-label">{{ 'classes.calendar.gap' | transloco }}</span>
                      }
                    }
                  </div>
                }
              </div>
            }
          </div>
        </div>
      }
    </div>
  `,
  styles: `
    @use 'mixins' as m;

    :host {
      display: block;
    }

    .cal {
      padding: var(--hq-space-card);
      background: var(--hq-color-surface);
      border: var(--hq-size-rule-thin) solid var(--hq-color-rule);
      border-radius: var(--hq-radius-card);
    }

    .cal__nav {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: var(--hq-space-16);
      margin-block-end: var(--hq-space-16);
    }

    // §3's icon button: 44 × 44, radius 8, on the container rule.
    .cal__nav-button {
      inline-size: var(--hq-size-button-height);
      block-size: var(--hq-size-button-height);
      background: var(--hq-color-surface);
      border: var(--hq-size-rule-thin) solid var(--hq-color-rule);
      border-radius: var(--hq-radius-control);
      color: var(--hq-color-ink-soft);
      cursor: pointer;
      @include m.hover-tint(var(--hq-color-surface-sunken));
      @include m.focus-ring;
    }

    .cal__month {
      @include m.card-title;
      min-inline-size: 12ch;
      text-align: center;
    }

    .cal__gaps {
      margin-block-end: var(--hq-space-16);
      font-size: var(--hq-text-theme-sm);
      color: var(--hq-color-error-ink);
    }

    // The defect this slice fixes: seven '1fr' tracks take their minimum from their content,
    // so a cell holding "Published" and "0 played" gave the month a floor near 800 px and the
    // *page* a horizontal scrollbar under about 900 px. Two changes, both needed:
    // 'minmax(0, 1fr)' lets a track shrink and its text ellipsize, and the floor moves to an
    // explicit 'min-inline-size' on the grid inside an 'overflow-x' pane — so under it the
    // month scrolls inside its own card and the page never does.
    .cal__scroll {
      // 'position: relative' is load-bearing, not decoration. A '.hq-sr-only' inside a card here
      // is 'position: absolute', and an absolutely positioned box is clipped by an ancestor's
      // overflow only when that ancestor is its containing block. Without this, the screen-reader
      // line beside a published lesson escaped the pane at its static position — 700-odd pixels
      // along a grid that scrolls — and gave the *page* a horizontal scrollbar at 375 px, with
      // nothing visible anywhere to explain it.
      position: relative;
      max-inline-size: 100%;
      overflow-x: auto;
    }

    .cal__grid {
      display: grid;
      // U1 item 6: the school's own teaching days are the columns — five of them in a Gulf week,
      // because Friday and Saturday are hidden rather than dimmed.
      grid-template-columns: repeat(var(--hq-cal-columns, 7), minmax(0, 1fr));
      gap: var(--hq-size-rule-thin);
      min-inline-size: calc(var(--hq-size-grade-card) * var(--hq-cal-columns, 7));
      background: var(--hq-color-divider);
      border: var(--hq-size-rule-thin) solid var(--hq-color-rule);
      border-radius: var(--hq-radius-control);
      overflow: hidden;
    }

    // A real ARIA row without breaking the grid's column tracks.
    .cal__row {
      display: contents;
    }

    .cal__weekday {
      padding: var(--hq-space-8);
      font-size: var(--hq-text-theme-xs);
      line-height: calc(var(--hq-text-theme-xs-line) / var(--hq-text-theme-xs));
      font-weight: var(--hq-text-weight-medium);
      text-align: center;
      background: var(--hq-color-surface-sunken);
      color: var(--hq-color-ink-soft);
    }

    .cal__cell {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-4);
      min-inline-size: 0;
      min-block-size: var(--hq-size-course-card);
      padding: var(--hq-space-8);
      background: var(--hq-color-surface);
    }

    .cal__cell--outside,
    .cal__cell--off {
      background: var(--hq-color-surface-sunken);
      color: var(--hq-color-ink-muted);
    }

    .cal__cell--gap {
      background: var(--hq-color-error-soft);
    }

    .cal__day {
      font-size: var(--hq-text-theme-xs);
      color: var(--hq-color-ink-soft);
    }

    .cal__lesson {
      display: flex;
      align-items: center;
      gap: var(--hq-space-8);
      min-inline-size: 0;
      padding: var(--hq-space-4);
      border-radius: var(--hq-radius-control);
      color: var(--hq-color-ink);
      text-decoration: none;
      @include m.hover-tint(var(--hq-color-surface-sunken));
      @include m.focus-ring;
    }

    .cal__lesson-text {
      display: flex;
      flex-direction: column;
      min-inline-size: 0;
    }

    // The exam ribbon: the accent, a word, and the title under it — never colour alone.
    .cal__ribbon {
      align-self: flex-start;
      padding: var(--hq-space-badge);
      border-radius: var(--hq-radius-pill);
      background: var(--hq-color-accent);
      color: var(--hq-color-on-accent);
      font-size: var(--hq-text-theme-2xs);
      line-height: calc(var(--hq-text-theme-2xs-line) / var(--hq-text-theme-2xs));
      font-weight: var(--hq-text-weight-medium);
      text-transform: uppercase;
    }

    .cal__lesson-title {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .cal__lesson-meta,
    .cal__results {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-size: var(--hq-text-theme-xs);
      color: var(--hq-color-ink-soft);
    }

    // A link, but the quiet sort: the cell's own lesson link is the loud one, and two buttons
    // in a 100 px square would make the square a menu.
    .cal__results--link {
      display: block;
      color: var(--hq-color-accent-strong);
      text-decoration: underline;
    }

    .cal__add {
      display: flex;
      align-items: center;
      justify-content: center;
      min-block-size: var(--hq-size-touch-target);
      border-radius: var(--hq-radius-control);
      color: var(--hq-color-ink-muted);
      text-decoration: none;
      @include m.hover-tint(var(--hq-color-accent-soft));
      @include m.focus-ring;
    }

    // A day that has gone: the same 44 px the + held, so the month keeps its rhythm.
    .cal__past {
      display: flex;
      align-items: center;
      justify-content: center;
      min-block-size: var(--hq-size-touch-target);
      color: var(--hq-color-ink-muted);
      cursor: default;
    }

    .cal__gap-label {
      font-size: var(--hq-text-theme-xs);
      color: var(--hq-color-error-ink);
    }
  `,
})
export class ClassCalendarComponent {
  private readonly transloco = inject(TranslocoService);
  private readonly platform = inject(PlatformService);
  private readonly lang = activeLang();
  /** N4.2: the Results link on a published day carries the same flag the route does. */
  protected readonly gradebookFlag = FLAGS.gradebook;
  protected readonly examsFlag = FLAGS.exams;

  readonly classId = input.required<string>();
  /**
   * R5: the area this month's lessons open in — `/teacher/lessons` for the teacher who owns them,
   * `/coordinator/lessons` for the coordinator who may only read them. An input rather than a
   * role lookup inside the grid, because the grid is dumb and this is the page's decision.
   */
  readonly lessonBase = input('/teacher/lessons');
  /** No `+`, no past-day marker, nothing to press: the month as a report (DR2). */
  readonly readOnly = input(false);
  readonly curriculum = input('');
  readonly grade = input(0);
  readonly subject = input('');
  readonly year = input.required<number>();
  readonly month = input.required<number>();
  readonly cells = input<readonly CalendarCell[]>([]);
  readonly gaps = input(0);
  readonly loading = input(false);

  /** `-1` or `+1` month. The page owns which month is showing; this only asks. */
  readonly monthShift = output<number>();

  /**
   * Which of the seven weekday slots the month draws (U1 item 6).
   *
   * The school's non-teaching days — Friday and Saturday in a Gulf week — are **hidden**, not
   * dimmed: a column a teacher can put nothing in is a column she reads past forty times a
   * month. Which days those are is still the server's answer and never a hard-coded Fri/Sat:
   * `schoolDay` comes off `GET /teacher/classes/{id}/calendar`, so a school that teaches on a
   * Saturday keeps its Saturday column. A month the server has not answered for yet has no
   * school days at all, and then all seven are drawn rather than none.
   */
  protected readonly columns = computed<readonly number[]>(() => {
    const cells = this.cells();
    const teaching = new Set<number>();
    cells.forEach((cell, index) => {
      if (cell.inMonth && cell.schoolDay) teaching.add(index % 7);
    });
    const shown = [...teaching].sort((a, b) => a - b);
    return shown.length > 0 ? shown : [0, 1, 2, 3, 4, 5, 6];
  });

  protected readonly weekdays = computed(() => {
    // Sunday first, matching `calendarCells`' 42-cell layout.
    const formatter = new Intl.DateTimeFormat(this.lang(), { weekday: 'short', timeZone: 'UTC' });
    return this.columns().map((index) => formatter.format(new Date(Date.UTC(2023, 0, 1 + index))));
  });

  protected readonly monthLabel = computed(() =>
    new Intl.DateTimeFormat(this.lang(), { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(
      new Date(Date.UTC(this.year(), this.month() - 1, 1)),
    ),
  );

  protected readonly weeks = computed<readonly (readonly CalendarCell[])[]>(() => {
    const all = this.cells();
    const shown = this.columns();
    const rows: (readonly CalendarCell[])[] = [];
    for (let index = 0; index < all.length; index += 7) {
      const week = all.slice(index, index + 7);
      const row = shown
        .map((column) => week[column])
        .filter((cell): cell is CalendarCell => cell !== undefined);
      if (row.length > 0) rows.push(row);
    }
    return rows;
  });

  /**
   * A day that has already gone, in the **school's** timezone (U1 item 6).
   *
   * Nothing may be planned onto it: `POST /teacher/lessons` for a past date is not what a
   * teacher means, and the cell offers no `+` at all rather than a link that opens an editor
   * she then has to back out of. Today itself is not past — a lesson for this afternoon is
   * ordinary.
   */
  protected isPast(iso: string): boolean {
    return iso < this.today();
  }

  /** Today where the children are, as `YYYY-MM-DD`; `en-CA` is the locale that formats that way. */
  private readonly today = computed(() =>
    new Intl.DateTimeFormat('en-CA', { timeZone: this.platform.timezone() }).format(new Date()),
  );

  /**
   * What the cell says next to the square: the lesson's title.
   *
   * An untitled lesson falls back to its type — "Homework", "Exam" — rather than to an empty
   * cell that looks like a free day, and to "Lesson" when the server sends neither.
   */
  /** `type` comes off the server's day; an exam says so on the square as well as in the editor. */
  protected isExam(cell: CalendarCell): boolean {
    return cell.type === 'exam';
  }

  /** An exam's numbers live on its own results page (§8), a homework's on the lesson's (§7). */
  protected resultsLink(cell: CalendarCell): readonly string[] {
    const id = cell.lessonId ?? '';
    return this.isExam(cell) ? ['/teacher/exams', id, 'results'] : ['/teacher/lessons', id, 'results'];
  }

  protected titleOf(cell: CalendarCell): string {
    this.lang();
    const title = (cell.title ?? '').trim();
    if (title) return title;
    const type = cell.type ?? '';
    if (!type) return this.transloco.translate<string>('classes.calendar.lesson');
    const key = `classes.calendar.type.${type}`;
    const word = this.transloco.translate<string>(key);
    return word === key ? type : word;
  }

  protected dayLabel(iso: string): string {
    this.lang();
    const date = new Date(`${iso}T00:00:00Z`);
    if (Number.isNaN(date.getTime())) return iso;
    return new Intl.DateTimeFormat(this.lang(), {
      weekday: 'long',
      day: 'numeric',
      month: 'long',
      timeZone: 'UTC',
    }).format(date);
  }

  /** The `+` opens the editor already knowing the class, the course and the day. */
  protected newLessonParams(cell: CalendarCell): Record<string, string> {
    return {
      classId: this.classId(),
      curriculum: this.curriculum(),
      grade: String(this.grade()),
      subject: this.subject(),
      date: cell.iso,
    };
  }
}
