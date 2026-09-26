import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  input,
  model,
  signal,
} from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { activeLang } from '../../core/i18n/active-lang';

interface DayCell {
  readonly iso: string;
  readonly day: number;
  readonly inMonth: boolean;
  readonly today: boolean;
}

function isoOf(date: Date): string {
  return date.toISOString().slice(0, 10);
}

/**
 * Today where the teacher is, not in UTC.
 *
 * The cells are UTC midnights — a calendar day has no time zone — but *which* day is today does:
 * `toISOString()` in `+03` says yesterday until 03:00, so the ring sat on the wrong cell for the
 * first three hours of every school morning.
 */
function todayIso(): string {
  const now = new Date();
  return isoOf(new Date(Date.UTC(now.getFullYear(), now.getMonth(), now.getDate())));
}

/** Midnight UTC for an ISO day, or today when the string is not one. */
function dayOf(iso: string): Date {
  const parsed = new Date(`${iso}T00:00:00Z`);
  return Number.isNaN(parsed.getTime()) ? new Date(`${todayIso()}T00:00:00Z`) : parsed;
}

function addDays(iso: string, days: number): string {
  const date = dayOf(iso);
  date.setUTCDate(date.getUTCDate() + days);
  return isoOf(date);
}

/**
 * A month of days, big enough to hit with a thumb (owner, 2026-09-26).
 *
 * The date fields on this dashboard are `hq-input type="date"`, whose calendar is the browser's
 * own popup: 11 px numerals in ~24 px cells, and nothing a stylesheet can reach. "Lesson day" is
 * the one date a teacher picks on the way into every lesson, so it gets a real grid instead —
 * 56 px cells (`--hq-size-row-height`, the same target as a table row) with 20 px numerals, laid
 * out inline so there is no popup to open at all.
 *
 * Nothing new is imported for it: a grid of buttons, the generated tokens, and `Intl` for the
 * month and weekday names, which is what makes it right in Arabic as well.
 *
 * Keyboard: the arrows move a day or a week, Page Up/Down a month, Home/End the row's ends —
 * each of them *selects*, which is the ARIA date-grid pattern (one tab stop, a roving `tabindex`).
 */
@Component({
  selector: 'hq-day-picker',
  imports: [TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="picker">
      <p class="picker__label" [id]="labelId">{{ label() }}</p>

      <div class="picker__nav">
        <button
          type="button"
          class="picker__nav-button"
          [disabled]="disabled()"
          [attr.aria-label]="'ui.dayPicker.prevMonth' | transloco"
          (click)="shiftMonth(-1)"
        >
          ‹
        </button>
        <p class="picker__month" aria-live="polite">{{ monthLabel() }}</p>
        <button
          type="button"
          class="picker__nav-button"
          [disabled]="disabled()"
          [attr.aria-label]="'ui.dayPicker.nextMonth' | transloco"
          (click)="shiftMonth(1)"
        >
          ›
        </button>
      </div>

      <div class="picker__grid" role="grid" [attr.aria-labelledby]="labelId">
        <div class="picker__row" role="row">
          @for (weekday of weekdays(); track $index) {
            <span class="picker__weekday" role="columnheader">{{ weekday }}</span>
          }
        </div>
        @for (week of weeks(); track $index) {
          <div class="picker__row" role="row">
            @for (cell of week; track cell.iso) {
              <span role="gridcell">
                <button
                  type="button"
                  class="picker__day"
                  [class.picker__day--outside]="!cell.inMonth"
                  [class.picker__day--today]="cell.today"
                  [class.picker__day--selected]="cell.iso === value()"
                  [disabled]="disabled()"
                  [attr.tabindex]="cell.iso === tabStop() ? 0 : -1"
                  [attr.aria-selected]="cell.iso === value()"
                  [attr.aria-label]="longLabel(cell.iso)"
                  [attr.data-hq-day]="cell.iso"
                  (click)="pick(cell.iso)"
                  (keydown)="onKeydown($event)"
                >
                  {{ cell.day }}
                </button>
              </span>
            }
          </div>
        }
      </div>

      <p class="picker__chosen">{{ longLabel(value()) }}</p>
    </div>
  `,
  styles: `
    @use 'mixins' as m;

    .picker {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-8);
    }

    .picker__label {
      font-size: var(--hq-font-label-size);
      line-height: calc(var(--hq-font-label-line) / var(--hq-font-label-size));
      font-weight: var(--hq-font-label-weight);
      color: var(--hq-color-ink-strong);
    }

    .picker__nav {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--hq-space-8);
    }

    .picker__nav-button {
      @include m.control;
      @include m.focus-ring;
      inline-size: var(--hq-size-button-height);
      block-size: var(--hq-size-button-height);
      background: var(--hq-color-surface);
      color: var(--hq-color-ink);
      font-size: var(--hq-text-theme-xl);
      cursor: pointer;
      @include m.hover-tint(var(--hq-color-surface-sunken));
    }

    // The chevrons are direction, not text: RTL puts "previous" on the right, where a glyph
    // pointing left would mean the opposite of what it does. The labels are already logical.
    :host-context([dir='rtl']) .picker__nav-button {
      transform: scaleX(-1);
    }

    .picker__month {
      font-size: var(--hq-text-card-title);
      line-height: calc(var(--hq-text-card-title-line) / var(--hq-text-card-title));
      font-weight: var(--hq-text-weight-medium);
    }

    .picker__grid {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-4);
    }

    .picker__row {
      display: grid;
      grid-template-columns: repeat(7, minmax(0, 1fr));
      gap: var(--hq-space-4);
    }

    .picker__weekday {
      text-align: center;
      font-size: var(--hq-text-theme-2xs);
      font-weight: var(--hq-text-weight-medium);
      color: var(--hq-color-ink-soft);
      text-transform: uppercase;
    }

    // The whole point: a 56 px target with a 20 px numeral in it, instead of the browser's 24 px.
    .picker__day {
      @include m.focus-ring;
      inline-size: 100%;
      min-block-size: var(--hq-size-row-height);
      border: var(--hq-size-rule-thin) solid transparent;
      border-radius: var(--hq-radius-control);
      background: var(--hq-color-surface);
      color: var(--hq-color-ink);
      font-family: inherit;
      font-size: var(--hq-text-theme-xl);
      line-height: calc(var(--hq-text-theme-xl-line) / var(--hq-text-theme-xl));
      cursor: pointer;
      @include m.hover-tint(var(--hq-color-surface-sunken));
    }

    .picker__day--outside {
      color: var(--hq-color-ink-soft);
    }

    .picker__day--today {
      border-color: var(--hq-color-rule);
      font-weight: var(--hq-text-weight-semibold);
    }

    .picker__day--selected {
      background: var(--hq-color-accent-soft);
      border-color: var(--hq-color-accent);
      color: var(--hq-color-accent-on-soft);
      font-weight: var(--hq-text-weight-semibold);
    }

    .picker__day:disabled {
      color: var(--hq-color-disabled);
      cursor: not-allowed;
    }

    .picker__chosen {
      font-size: var(--hq-text-theme-xs);
      color: var(--hq-color-ink-soft);
    }
  `,
})
export class DayPickerComponent {
  private static sequence = 0;

  private readonly transloco = inject(TranslocoService);
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly lang = activeLang();

  readonly label = input.required<string>();
  readonly disabled = input(false);
  /** The chosen day as `YYYY-MM-DD` — the shape every lesson endpoint takes. */
  readonly value = model.required<string>();

  protected readonly labelId = `hq-day-picker-${DayPickerComponent.sequence++}`;

  /** The month on screen. It follows the value, and the nav buttons move it on its own. */
  private readonly monthShift = signal(0);

  private readonly firstOfMonth = computed(() => {
    const day = dayOf(this.value());
    return new Date(Date.UTC(day.getUTCFullYear(), day.getUTCMonth() + this.monthShift(), 1));
  });

  protected readonly monthLabel = computed(() => {
    this.lang();
    return new Intl.DateTimeFormat(this.transloco.getActiveLang(), {
      month: 'long',
      year: 'numeric',
      timeZone: 'UTC',
    }).format(this.firstOfMonth());
  });

  protected readonly weekdays = computed(() => {
    this.lang();
    const format = new Intl.DateTimeFormat(this.transloco.getActiveLang(), {
      weekday: 'short',
      timeZone: 'UTC',
    });
    // From Sunday, because the school week here is Sunday–Thursday.
    return [0, 1, 2, 3, 4, 5, 6].map((offset) => format.format(new Date(Date.UTC(2026, 8, 6 + offset))));
  });

  protected readonly weeks = computed<readonly (readonly DayCell[])[]>(() => {
    const first = this.firstOfMonth();
    const month = first.getUTCMonth();
    const start = new Date(first);
    start.setUTCDate(1 - first.getUTCDay());
    const today = todayIso();

    return [0, 1, 2, 3, 4, 5].map((week) =>
      [0, 1, 2, 3, 4, 5, 6].map((day) => {
        const date = new Date(start);
        date.setUTCDate(start.getUTCDate() + week * 7 + day);
        const iso = isoOf(date);
        return { iso, day: date.getUTCDate(), inMonth: date.getUTCMonth() === month, today: iso === today };
      }),
    );
  });

  /**
   * The one cell Tab lands on.
   *
   * Usually the selected day — but ‹ / › can walk to a month that does not contain it, and when
   * every cell was `-1` the whole picker dropped out of the tab order. Today stands in when it is
   * on screen, and the 1st of the month shown otherwise; both are always in the grid.
   */
  protected readonly tabStop = computed(() => {
    const shown = this.weeks().flat();
    const selected = this.value();
    if (shown.some((cell) => cell.iso === selected)) return selected;
    return (shown.find((cell) => cell.today && cell.inMonth) ?? shown.find((cell) => cell.inMonth))?.iso ?? selected;
  });

  /** "Thursday 17 September 2026" — the cell's accessible name and the line under the grid. */
  protected longLabel(iso: string): string {
    this.lang();
    return new Intl.DateTimeFormat(this.transloco.getActiveLang(), {
      weekday: 'long',
      day: 'numeric',
      month: 'long',
      year: 'numeric',
      timeZone: 'UTC',
    }).format(dayOf(iso));
  }

  protected shiftMonth(months: number): void {
    this.monthShift.update((current) => current + months);
  }

  protected pick(iso: string): void {
    this.monthShift.set(0);
    this.value.set(iso);
  }

  /** The nearest declared direction — the language service puts `dir` on `<html>`. */
  private rtl(): boolean {
    return (this.host.nativeElement as HTMLElement).closest('[dir]')?.getAttribute('dir') === 'rtl';
  }

  protected onKeydown(event: KeyboardEvent): void {
    // APG maps a date grid's arrows to the *visual* direction, and the grid is mirrored in
    // Arabic: Left has to move to the day that is drawn on the left, which is the next one.
    const key = this.rtl() ? (MIRRORED[event.key] ?? event.key) : event.key;
    const step = KEY_STEPS[key];
    if (step === undefined) return;
    event.preventDefault();
    const iso = this.value();
    const next =
      step === 'monthBack'
        ? this.shiftedByMonth(iso, -1)
        : step === 'monthOn'
          ? this.shiftedByMonth(iso, 1)
          : step === 'weekStart'
            ? addDays(iso, -dayOf(iso).getUTCDay())
            : step === 'weekEnd'
              ? addDays(iso, 6 - dayOf(iso).getUTCDay())
              : addDays(iso, step);
    this.pick(next);
    // The grid is re-rendered around the new value, so focus follows it on the next frame.
    requestAnimationFrame(() => {
      const host = this.host.nativeElement as HTMLElement;
      host.querySelector<HTMLButtonElement>(`[data-hq-day="${next}"]`)?.focus();
    });
  }

  private shiftedByMonth(iso: string, months: number): string {
    const date = dayOf(iso);
    const day = date.getUTCDate();
    const moved = new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + months, 1));
    const lastDay = new Date(Date.UTC(moved.getUTCFullYear(), moved.getUTCMonth() + 1, 0)).getUTCDate();
    moved.setUTCDate(Math.min(day, lastDay));
    return isoOf(moved);
  }
}

/** The two keys whose meaning follows the writing direction rather than the calendar. */
const MIRRORED: Readonly<Record<string, string>> = {
  ArrowLeft: 'ArrowRight',
  ArrowRight: 'ArrowLeft',
};

/** Every key the grid answers to, and what it moves by. */
const KEY_STEPS: Readonly<Record<string, number | 'monthBack' | 'monthOn' | 'weekStart' | 'weekEnd'>> = {
  ArrowLeft: -1,
  ArrowRight: 1,
  ArrowUp: -7,
  ArrowDown: 7,
  PageUp: 'monthBack',
  PageDown: 'monthOn',
  Home: 'weekStart',
  End: 'weekEnd',
};
