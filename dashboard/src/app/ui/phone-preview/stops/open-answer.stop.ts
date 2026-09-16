import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ChildButtonComponent } from '../child-button.component';
import { OpenAnswerStop } from '../stop.model';

/** The six colours the drawing pad offers, in the app's order. */
const COLOURS = ['ink', 'coral', 'sun', 'mint', 'sea', 'lavender'] as const;

/**
 * Open answer: say it, draw it, or both. There is no right tile and no wrong tile — Pip
 * celebrates the attempt, which is the only child screen where that is the whole design.
 *
 * The drawing pad is drawn but not drawable: strokes are the app's canvas, and a teacher checking
 * a lesson needs to see that the child will be asked to draw, not to draw it themselves.
 */
@Component({
  selector: 'hq-stop-open-answer',
  imports: [ChildButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="open">
      <p class="open__prompt">{{ stop().prompt }}</p>

      @if (speaks()) {
        <p class="open__glyph" aria-hidden="true">🗣️</p>
        <p class="open__aside">Say your idea out loud.</p>
        <hq-child-button label="Record" emoji="🎙️" tone="lavender" />
      }

      @if (draws()) {
        <ul class="open__colours" aria-label="drawing colours">
          @for (colour of colours; track colour) {
            <li [class]="'open__colour open__colour--' + colour" [attr.aria-label]="colour"></li>
          }
        </ul>
        <div class="open__pad" role="img" aria-label="drawing canvas"></div>
      }

      <hq-child-button label="I'm done!" emoji="✅" />
    </div>
  `,
  styles: `
    @use '../child-tokens' as child;

    .open {
      @include child.child-stack(var(--hq-space-12));

      padding-inline: var(--hq-space-16);
    }

    .open__prompt {
      @include child.child-prompt;
    }

    .open__glyph {
      margin: 0;
      font-size: var(--hq-child-pip-sm);
      line-height: 1;
    }

    .open__aside {
      margin: 0;
      color: var(--hq-child-ink-soft);
      text-align: center;
    }

    .open__colours {
      display: flex;
      gap: var(--hq-space-8);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .open__colour {
      inline-size: var(--hq-size-touch-target);
      block-size: var(--hq-size-touch-target);
      border-radius: 50%;
      background: var(--hq-child-ink);
    }

    .open__colour--coral {
      background: var(--hq-child-coral);
    }

    .open__colour--sun {
      background: var(--hq-child-sun);
    }

    .open__colour--mint {
      background: var(--hq-child-mint);
    }

    .open__colour--sea {
      background: var(--hq-child-sea);
    }

    .open__colour--lavender {
      background: var(--hq-child-lavender);
    }

    .open__pad {
      inline-size: 100%;
      aspect-ratio: 1;
      border-radius: var(--hq-child-radius-card);
      background: var(--hq-color-surface);
    }
  `,
})
export class OpenAnswerStopComponent {
  readonly stop = input.required<OpenAnswerStop>();

  protected readonly colours = COLOURS;
  protected readonly speaks = computed(() => this.stop().mode !== 'draw');
  protected readonly draws = computed(() => this.stop().mode !== 'speak');
}
