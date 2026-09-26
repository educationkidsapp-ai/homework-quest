import { Injectable, OnDestroy, computed, signal } from '@angular/core';

/** Long enough that a word is typed before the grid re-filters, short enough to feel live. */
const DEBOUNCE_MS = 250;

/**
 * The header's search box, over whatever screen is showing (U1 item 1).
 *
 * The box was drawn on every screen and wired to nothing — a teacher typed into This week and
 * the grid did not move. It is now claimed, one screen at a time: a screen that can filter
 * itself calls {@link claim} with its own placeholder, reads {@link term}, and releases the box
 * when it goes away. A screen that claims nothing gets no box at all, which is the honest
 * answer — a control that does nothing is worse than no control.
 *
 * {@link query} is what the input shows (every keystroke, so typing is not laggy) and
 * {@link term} is the debounced, trimmed value the screen filters on. Clearing is *not*
 * debounced: emptying the box puts every row back at once.
 */
@Injectable({ providedIn: 'root' })
export class ScreenSearchService implements OnDestroy {
  private readonly typed = signal('');
  private readonly settled = signal('');
  private readonly placeholder = signal<string | null>(null);
  private timer: ReturnType<typeof setTimeout> | null = null;

  /** What the input shows, updated on every keystroke. */
  readonly query = this.typed.asReadonly();

  /** What the screen filters on: trimmed, and 250 ms behind the keyboard. */
  readonly term = this.settled.asReadonly();

  /** True while a screen has claimed the box; the header draws nothing otherwise. */
  readonly active = computed(() => this.placeholder() !== null);

  /** The claiming screen's own placeholder key, so the box says what it searches. */
  readonly placeholderKey = computed(() => this.placeholder() ?? 'shell.searchPlaceholder');

  set(value: string): void {
    this.typed.set(value);
    this.stop();
    if (value.trim() === '') {
      this.settled.set('');
      return;
    }
    this.timer = setTimeout(() => {
      this.timer = null;
      this.settled.set(value.trim());
    }, DEBOUNCE_MS);
  }

  clear(): void {
    this.set('');
  }

  /** A screen takes the box for as long as it is on screen. */
  claim(placeholderKey: string): void {
    this.placeholder.set(placeholderKey);
    this.clear();
  }

  release(): void {
    this.placeholder.set(null);
    this.clear();
  }

  ngOnDestroy(): void {
    this.stop();
  }

  private stop(): void {
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
  }
}
