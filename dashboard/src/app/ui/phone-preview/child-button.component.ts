import { ChangeDetectionStrategy, Component, booleanAttribute, input, output } from '@angular/core';

/** The four fills a child button comes in — never the parent red. */
export type ChildButtonTone = 'sun' | 'lavender' | 'cream' | 'coral';

/**
 * The big rounded button of the child app (`BigButton` in `shared-ui`): an emoji, a word, and a
 * target no smaller than 64 px (`docs/design.md` §7).
 */
@Component({
  selector: 'hq-child-button',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button
      type="button"
      class="cbtn"
      [class]="'cbtn--' + tone()"
      [class.cbtn--compact]="compact()"
      [disabled]="disabled()"
      (click)="pressed.emit()"
    >
      @if (emoji(); as glyph) {
        <span class="cbtn__emoji" aria-hidden="true">{{ glyph }}</span>
      }
      <span class="cbtn__label">{{ label() }}</span>
    </button>
  `,
  styles: `
    @use 'mixins' as m;
    @use './child-tokens' as child;

    :host {
      display: block;
    }

    .cbtn {
      @include child.child-target;
      @include child.child-label;

      display: flex;
      align-items: center;
      justify-content: center;
      gap: var(--hq-space-8);
      inline-size: 100%;
      padding-inline: var(--hq-space-24);
      border: none;
      border-radius: var(--hq-child-radius-chip);
      background: var(--hq-child-sun);
      color: var(--hq-child-ink);
      @include m.motion-safe('background-color, opacity');

      &:disabled {
        @include child.child-dimmed;
      }
    }

    .cbtn--compact {
      min-block-size: var(--hq-child-touch);
    }

    .cbtn--lavender {
      background: var(--hq-child-lavender);
    }

    .cbtn--cream {
      background: var(--hq-child-cream);
    }

    .cbtn--coral {
      background: var(--hq-child-coral);
    }

    .cbtn__emoji {
      font-size: var(--hq-child-title);
      line-height: 1;
    }
  `,
})
export class ChildButtonComponent {
  readonly label = input.required<string>();
  readonly emoji = input<string | null>(null);
  readonly tone = input<ChildButtonTone>('sun');
  readonly disabled = input(false, { transform: booleanAttribute });
  /** A button that sits in a row rather than across the screen. */
  readonly compact = input(false, { transform: booleanAttribute });

  readonly pressed = output<void>();
}
