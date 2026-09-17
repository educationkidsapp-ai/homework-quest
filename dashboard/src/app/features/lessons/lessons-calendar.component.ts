import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { activeLang } from '../../core/i18n/active-lang';
import { SkeletonComponent } from '../../ui';
import { type CalendarDay, type Curriculum, isSchoolDay } from './lessons.models';

interface Cell {
  readonly date: Date;
  readonly iso: string;
  readonly inMonth: boolean;
  readonly math: boolean;
  readonly english: boolean;
  readonly gap: boolean;
}

/**
 * One class's month, dots per subject, gaps flagged — §6 screen 12's calendar view.
 *
 * A dumb grid: the page owns the request (`GET /admin/calendar`) and month navigation state;
 * this only lays the days out. See `lessons.models.ts` for why `gap` is computed here instead
 * of read off the response.
 */
@Component({
  selector: 'hq-lessons-calendar',
  imports: [TranslocoPipe, SkeletonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="calendar">
      <div class="calendar__nav">
        <button
          type="button"
          class="calendar__nav-button"
          [attr.aria-label]="'lessons.calendar.prevMonth' | transloco"
          (click)="shift(-1)"
        >
          ‹
        </button>
        <p class="calendar__month">{{ monthLabel() }}</p>
        <button
          type="button"
          class="calendar__nav-button"
          [attr.aria-label]="'lessons.calendar.nextMonth' | transloco"
          (click)="shift(1)"
        >
          ›
        </button>
      </div>

      @if (loading()) {
        <hq-skeleton [loading]="true" [lines]="5" [label]="'lessons.loading' | transloco" />
      } @else {
        <div class="calendar__grid" role="grid" [attr.aria-label]="monthLabel()">
          @for (weekday of weekdays(); track weekday) {
            <div class="calendar__weekday" role="columnheader">{{ weekday }}</div>
          }
          @for (cell of cells(); track cell.iso) {
            <div
              class="calendar__cell"
              role="gridcell"
              [attr.data-date]="cell.iso"
              [class.calendar__cell--outside]="!cell.inMonth"
              [class.calendar__cell--gap]="cell.gap"
            >
              <span class="calendar__day">{{ cell.date.getDate() }}</span>
              @if (cell.math || cell.english) {
                <span class="calendar__dots" aria-hidden="true">
                  @if (cell.math) {
                    <span class="calendar__dot calendar__dot--math"></span>
                  }
                  @if (cell.english) {
                    <span class="calendar__dot calendar__dot--english"></span>
                  }
                </span>
                <span class="hq-sr-only">
                  {{ (cell.math ? 'subject.math' : 'subject.english') | transloco }}
                </span>
              } @else if (cell.gap) {
                <span class="calendar__gap-label">{{ 'lessons.calendar.gap' | transloco }}</span>
              }
            </div>
          }
        </div>
      }
    </div>
  `,
  styles: `
    @use 'mixins' as m;

    :host {
      display: block;
    }

    .calendar__nav {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: var(--hq-space-16);
      margin-block-end: var(--hq-space-16);
    }

    .calendar__nav-button {
      min-inline-size: var(--hq-size-touch-target);
      min-block-size: var(--hq-size-touch-target);
      background: none;
      border: var(--hq-size-rule) solid var(--hq-color-line);
      cursor: pointer;
      @include m.hover-tint;
      @include m.focus-ring;
    }

    .calendar__month {
      @include m.label;
      min-inline-size: 12ch;
      text-align: center;
    }

    .calendar__grid {
      display: grid;
      grid-template-columns: repeat(7, 1fr);
      gap: var(--hq-size-rule-thin);
      background: var(--hq-color-rule);
      border: var(--hq-size-rule) solid var(--hq-color-line);
    }

    .calendar__weekday {
      @include m.label;
      padding: var(--hq-space-8);
      text-align: center;
      background: var(--hq-color-bg);
      color: var(--hq-color-ink-soft);
    }

    .calendar__cell {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: var(--hq-space-4);
      min-block-size: var(--hq-size-course-card);
      padding: var(--hq-space-8);
      background: var(--hq-color-surface);
    }

    .calendar__cell--outside {
      background: var(--hq-color-bg);
      color: var(--hq-color-disabled);
    }

    .calendar__cell--gap {
      background: var(--hq-color-accent-soft);
    }

    .calendar__dots {
      display: flex;
      gap: var(--hq-space-4);
    }

    .calendar__dot {
      inline-size: var(--hq-space-8);
      block-size: var(--hq-space-8);
      border-radius: 50%;
      background: var(--hq-color-ink);
    }

    .calendar__dot--english {
      background: var(--hq-color-ink-soft);
    }

    .calendar__gap-label {
      font-size: var(--hq-font-label-size);
      color: var(--hq-color-accent-strong);
    }
  `,
})
export class LessonsCalendarComponent {
  private readonly lang = activeLang();

  readonly curriculum = input.required<Curriculum>();
  readonly grade = input.required<number>();
  readonly year = input.required<number>();
  readonly month = input.required<number>();
  readonly days = input<readonly CalendarDay[]>([]);
  readonly loading = input(false);

  readonly monthChange = output<{ year: number; month: number }>();

  protected readonly weekdays = computed(() => {
    this.lang();
    // Sunday first — the Gulf week `isSchoolDay` assumes.
    const formatter = new Intl.DateTimeFormat(this.lang(), { weekday: 'short', timeZone: 'UTC' });
    return Array.from({ length: 7 }, (_, index) => formatter.format(new Date(Date.UTC(2023, 0, 1 + index))));
  });

  protected readonly monthLabel = computed(() => {
    this.lang();
    const formatter = new Intl.DateTimeFormat(this.lang(), {
      month: 'long',
      year: 'numeric',
      timeZone: 'UTC',
    });
    return formatter.format(new Date(Date.UTC(this.year(), this.month() - 1, 1)));
  });

  protected readonly cells = computed<readonly Cell[]>(() => {
    const year = this.year();
    const month = this.month();
    const byDate = new Map(this.days().map((day) => [day.date, day]));
    const first = new Date(Date.UTC(year, month - 1, 1));
    const start = new Date(first);
    start.setUTCDate(first.getUTCDate() - first.getUTCDay());
    const today = new Date();
    today.setUTCHours(0, 0, 0, 0);

    return Array.from({ length: 42 }, (_, index) => {
      const date = new Date(start);
      date.setUTCDate(start.getUTCDate() + index);
      const iso = date.toISOString().slice(0, 10);
      const entry = byDate.get(iso);
      const inMonth = date.getUTCMonth() === month - 1;
      const gap = inMonth && !entry && isSchoolDay(date) && date.getTime() <= today.getTime();
      return { date, iso, inMonth, math: entry?.math ?? false, english: entry?.english ?? false, gap };
    });
  });

  protected shift(delta: number): void {
    const next = new Date(Date.UTC(this.year(), this.month() - 1 + delta, 1));
    this.monthChange.emit({ year: next.getUTCFullYear(), month: next.getUTCMonth() + 1 });
  }
}
