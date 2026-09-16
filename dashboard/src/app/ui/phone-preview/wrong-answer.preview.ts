import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ChildButtonComponent } from './child-button.component';
import { NumberLineComponent } from './number-line.component';
import { PipComponent } from './pip.component';
import { NumberLine, Stop, hintOf } from './stop.model';

/**
 * The hint sheet (`docs/design.md` §6, screen 11): what a child sees after a wrong answer.
 *
 * Pip thinking, the hint in one sentence, the number line when the question was a numeric one, and
 * **Try again**. There is no red ✗, no score, no timer and no "wrong" anywhere on it — the tile
 * the child tapped is already dimmed on the question behind, and the question comes straight back.
 */
@Component({
  selector: 'hq-wrong-answer-preview',
  imports: [ChildButtonComponent, NumberLineComponent, PipComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="sheet" [attr.aria-label]="label()">
      <hq-pip pose="thinking" size="sm" />

      <p class="sheet__hint">{{ hint() }}</p>

      @if (numberLine(); as line) {
        <hq-number-line [line]="line" />
      }

      <hq-child-button [label]="tryAgainLabel()" emoji="🔁" />
    </section>
  `,
  styles: `
    @use 'mixins' as m;
    @use './child-tokens' as child;

    :host {
      display: block;
      block-size: 100%;
      background: var(--hq-child-sky);
    }

    .sheet {
      @include child.child-stack(var(--hq-space-16));
      @include child.child-animation(hq-sheet-rise var(--hq-motion-base) var(--hq-motion-ease) 1);

      position: sticky;
      inset-block-end: 0;
      margin-block-start: auto;
      padding: var(--hq-space-24) var(--hq-space-16);
      background: var(--hq-child-peach);
      border-start-start-radius: var(--hq-child-radius-sheet);
      border-start-end-radius: var(--hq-child-radius-sheet);
    }

    .sheet__hint {
      @include child.child-title;

      margin: 0;
      text-align: center;
    }

    @keyframes hq-sheet-rise {
      from {
        transform: translateY(var(--hq-space-32));
        opacity: 0;
      }

      to {
        transform: translateY(0);
        opacity: 1;
      }
    }
  `,
})
export class WrongAnswerPreviewComponent {
  /** The stop the child just got wrong; its hint and its number line are what the sheet shows. */
  readonly stop = input<Stop | null>(null);
  /** Shown when the stop has no hint of its own. */
  readonly fallbackHint = input<string>('Have another look — you can do this.');
  readonly tryAgainLabel = input<string>('Try again');
  readonly label = input<string>('Hint');

  protected readonly hint = computed(() => {
    const stop = this.stop();
    return (stop && hintOf(stop)) ?? this.fallbackHint();
  });

  protected readonly numberLine = computed<NumberLine | null>(() => {
    const stop = this.stop();
    if (!stop) return null;
    return 'numberLine' in stop ? stop.numberLine : null;
  });
}
