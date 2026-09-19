import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  computed,
  effect,
  input,
  output,
  signal,
} from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { MOTION_MS } from '../motion';
import { ButtonComponent } from '../button/button.component';

const TICK_MS = 100;

/**
 * "Deleted 3 lessons — Undo", with a 10 s countdown bar.
 *
 * Everything non-destructive in this system is undoable rather than confirmed, so the
 * strip is the counterpart of the red band: it appears after the action, not before.
 * When the bar runs out the change is committed (`expired`).
 */
@Component({
  selector: 'hq-undo-strip',
  imports: [TranslocoPipe, ButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (open()) {
      <div class="undo" role="status">
        <span class="undo__countdown" aria-hidden="true" [style.inline-size.%]="remainingPercent()"></span>
        <p class="undo__message">{{ message() }}</p>
        <hq-button variant="quiet" (pressed)="undone.emit()">{{ 'ui.undo' | transloco }}</hq-button>
      </div>
    }
  `,
  styles: `
    @use 'mixins' as m;

    :host {
      display: block;
    }

    .undo {
      position: relative;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--hq-space-16);
      overflow: hidden;
      padding: var(--hq-space-8) var(--hq-space-16);
      // A strip that floats over the page, so it takes the raised surface and the 'lg'
      // elevation rather than the card's flat rule (§1 Elevation, §5).
      background: var(--hq-color-surface-raised);
      border: var(--hq-size-rule-thin) solid var(--hq-color-rule);
      border-radius: var(--hq-radius-card);
      box-shadow: var(--hq-shadow-lg);
      color: var(--hq-color-ink);
      z-index: var(--hq-z-undo);
    }

    .undo__countdown {
      position: absolute;
      inset-block-end: 0;
      inset-inline-start: 0;
      block-size: var(--hq-size-rule);
      background: var(--hq-color-accent);
      @include m.motion-safe('inline-size');
    }

    .undo__message {
      font-size: var(--hq-text-theme-sm);
    }
  `,
})
export class UndoStripComponent implements OnDestroy {
  private readonly elapsed = signal(0);
  private timer: ReturnType<typeof setInterval> | null = null;

  readonly open = input.required<boolean>();
  /** What just happened, e.g. "Lesson deleted". */
  readonly message = input.required<string>();
  /** How long the user has to change their mind; 10 s by design. */
  readonly durationMs = input<number>(MOTION_MS.undoWindow);

  readonly undone = output<void>();
  readonly expired = output<void>();

  protected readonly remainingPercent = computed(() => {
    const total = this.durationMs();
    if (total <= 0) return 0;
    return Math.max(0, 100 - (this.elapsed() / total) * 100);
  });

  constructor() {
    effect(() => {
      const open = this.open();
      this.clear();
      this.elapsed.set(0);
      if (!open) return;

      this.timer = setInterval(() => {
        this.elapsed.update((value) => value + TICK_MS);
        if (this.elapsed() >= this.durationMs()) {
          this.clear();
          this.expired.emit();
        }
      }, TICK_MS);
    });
  }

  ngOnDestroy(): void {
    this.clear();
  }

  private clear(): void {
    if (this.timer !== null) clearInterval(this.timer);
    this.timer = null;
  }
}
