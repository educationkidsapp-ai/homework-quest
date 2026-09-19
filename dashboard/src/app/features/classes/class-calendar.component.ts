import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { FLAGS } from '../../core/flags/flag.service';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
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
 * * **which days are school days** (an Admin setting, `schoolWeek`), so a Friday is dimmed here
 *   and offers no `+` without this screen knowing anything about the Gulf week; and
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
  template: `
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
          <div class="cal__grid" role="grid" [attr.aria-label]="monthLabel()">
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
                      <a class="cal__lesson" [routerLink]="['/teacher/lessons', lessonId]">
                        <hq-status-square [status]="cell.status" />
                        <span class="cal__lesson-text">
                          <span class="cal__lesson-title">{{ titleOf(cell) }}</span>
                          <span class="cal__lesson-meta">{{ 'week.status.' + cell.status | transloco }}</span>
                        </span>
                        <span class="hq-sr-only">{{ dayLabel(cell.iso) }}</span>
                      </a>
                      <!-- §4 step 3's Results column: how many played, and the way in to their
                           scores once the lesson is published (N4.2). -->
                      @if (cell.status === 'published') {
                        <span *hqFeature="gradebookFlag">
                          <a
                            *hqCan="'results.read'"
                            class="cal__results cal__results--link"
                            [routerLink]="['/teacher/lessons', lessonId, 'results']"
                          >
                            {{ 'classes.calendar.played' | transloco: { count: cell.playedCount } }}
                          </a>
                        </span>
                      } @else {
                        <span class="cal__results">
                          {{ 'classes.calendar.played' | transloco: { count: cell.playedCount } }}
                        </span>
                      }
                    } @else if (cell.schoolDay) {
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
      grid-template-columns: repeat(7, minmax(0, 1fr));
      gap: var(--hq-size-rule-thin);
      min-inline-size: calc(var(--hq-size-grade-card) * 7);
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

    .cal__gap-label {
      font-size: var(--hq-text-theme-xs);
      color: var(--hq-color-error-ink);
    }
  `,
})
export class ClassCalendarComponent {
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();
  /** N4.2: the Results link on a published day carries the same flag the route does. */
  protected readonly gradebookFlag = FLAGS.gradebook;

  readonly classId = input.required<string>();
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

  protected readonly weekdays = computed(() => {
    // Sunday first, matching `calendarCells`' 42-cell layout.
    const formatter = new Intl.DateTimeFormat(this.lang(), { weekday: 'short', timeZone: 'UTC' });
    return Array.from({ length: 7 }, (_, index) => formatter.format(new Date(Date.UTC(2023, 0, 1 + index))));
  });

  protected readonly monthLabel = computed(() =>
    new Intl.DateTimeFormat(this.lang(), { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(
      new Date(Date.UTC(this.year(), this.month() - 1, 1)),
    ),
  );

  protected readonly weeks = computed<readonly (readonly CalendarCell[])[]>(() => {
    const all = this.cells();
    const rows: (readonly CalendarCell[])[] = [];
    for (let index = 0; index < all.length; index += 7) rows.push(all.slice(index, index + 7));
    return rows;
  });

  /**
   * What the cell says next to the square: the lesson's title.
   *
   * An untitled lesson falls back to its type — "Homework", "Exam" — rather than to an empty
   * cell that looks like a free day, and to "Lesson" when the server sends neither.
   */
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
