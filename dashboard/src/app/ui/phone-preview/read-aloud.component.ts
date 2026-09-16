import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

/**
 * The read-aloud button: a 64 px `sun` circle with a speaker glyph, top-right of every child
 * screen (`docs/design.md` §4). Every instruction can be heard again, as often as the child likes.
 */
@Component({
  selector: 'hq-read-aloud',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button type="button" class="speak" [attr.aria-label]="label()" (click)="pressed.emit()">
      <span class="speak__glyph" aria-hidden="true">🔊</span>
    </button>
  `,
  styles: `
    @use 'mixins' as m;
    @use './child-tokens' as child;

    :host {
      display: block;
    }

    .speak {
      @include child.child-target;

      display: grid;
      place-items: center;
      inline-size: var(--hq-child-touch);
      block-size: var(--hq-child-touch);
      padding: 0;
      border: none;
      border-radius: 50%;
      background: var(--hq-child-sun);
      @include m.motion-safe('background-color');
    }

    .speak__glyph {
      font-size: var(--hq-child-title);
      line-height: 1;
    }
  `,
})
export class ReadAloudComponent {
  /** Accessible name — the caller's wording, since child content is never translated here. */
  readonly label = input<string>('Read it to me');

  readonly pressed = output<void>();
}
