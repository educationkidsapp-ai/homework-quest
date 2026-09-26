import { ChangeDetectionStrategy, Component, OnDestroy, effect, input, output } from '@angular/core';

/** The strip's two readings: an ordinary "that happened", or a success in green. */
export type ToastTone = 'neutral' | 'success';

/** Long enough to read six words, short enough that nobody waits for it. */
const DEFAULT_MS = 4000;

/**
 * "Question added" — the one thing this system says after a success that has nothing to undo.
 *
 * It is `hq-undo-strip` with the Undo taken off, and that is the whole design: the strip is
 * already what the dashboard uses to report something that just happened, so a success notice
 * that looked like anything else would read as a second kind of message. The rule the house
 * style keeps is the other one — a *failure* is never a toast. A failure rolls the change back
 * and says so in a red band, in the place it happened, where it cannot be missed.
 *
 * `role="status"` rather than `alert`: it is polite, so a screen reader finishes the sentence it
 * is on. Nothing here takes focus, and nothing waits for a dismissal.
 */
@Component({
  selector: 'hq-toast',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (open()) {
      <p class="toast" [class.toast--success]="tone() === 'success'" role="status">{{ message() }}</p>
    }
  `,
  styles: `
    :host {
      display: block;
    }

    .toast {
      display: flex;
      align-items: center;
      gap: var(--hq-space-16);
      padding: var(--hq-space-8) var(--hq-space-16);
      // A strip that floats over the page, as hq-undo-strip does: raised surface, 'lg' elevation.
      background: var(--hq-color-surface-raised);
      border: var(--hq-size-rule-thin) solid var(--hq-color-rule);
      border-radius: var(--hq-radius-card);
      box-shadow: var(--hq-shadow-lg);
      color: var(--hq-color-ink);
      font-size: var(--hq-text-theme-sm);
      z-index: var(--hq-z-undo);
    }

    // U1 item 5: a save that worked says so in the success role's own green. The tone is opt-in
    // because the neutral strip is still the default for "this happened, nothing to undo".
    .toast--success {
      background: var(--hq-color-success-soft);
      border-color: var(--hq-color-band-good);
      color: var(--hq-color-success-ink);
    }
  `,
})
export class ToastComponent implements OnDestroy {
  private timer: ReturnType<typeof setTimeout> | null = null;

  readonly open = input.required<boolean>();
  /** What just happened, e.g. "Question added". */
  readonly message = input.required<string>();
  readonly durationMs = input<number>(DEFAULT_MS);
  /** `success` paints the strip in the success role's green; `neutral` is the raised surface. */
  readonly tone = input<ToastTone>('neutral');

  /** The time is up. The host clears `open`; nothing here closes itself behind its owner's back. */
  readonly expired = output<void>();

  constructor() {
    effect(() => {
      const open = this.open();
      this.clear();
      if (!open) return;
      this.timer = setTimeout(() => {
        this.timer = null;
        this.expired.emit();
      }, this.durationMs());
    });
  }

  ngOnDestroy(): void {
    this.clear();
  }

  private clear(): void {
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
  }
}
