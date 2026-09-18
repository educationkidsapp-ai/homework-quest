import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import type { CellStatus } from './week.models';

/**
 * The 12 px status square — §4's one glyph for "where is this lesson".
 *
 * Empty outline for a day with nothing on it, then three fills off the band scale: `draft`
 * looks, `ready` is mid, `published` is good. It carries no text of its own: every place that
 * draws one puts `week.status.<status>` beside it, so the colour is never the only signal.
 *
 * Lives in `features/week/` because This week is where it was born (N2.2); the class page's
 * calendar (N2.3) draws the same square from the same `CellStatus`, so it is a component
 * rather than a second copy of five CSS rules that could drift apart.
 */
@Component({
  selector: 'hq-status-square',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '',
  host: {
    'aria-hidden': 'true',
    '[class]': "'square--' + status()",
    '[class.square--pending]': 'pending()',
  },
  styles: `
    :host {
      display: block;
      flex: none;
      inline-size: var(--hq-space-12);
      block-size: var(--hq-space-12);
      border: var(--hq-size-rule-thin) solid var(--hq-color-ink);
      background: var(--hq-color-surface);
    }

    :host(.square--draft) {
      background: var(--hq-color-band-look);
    }

    :host(.square--ready) {
      background: var(--hq-color-band-mid);
    }

    :host(.square--published) {
      background: var(--hq-color-band-good);
    }

    // A copy the server has not answered for yet: same fill, dashed edge.
    :host(.square--pending) {
      border-style: dashed;
    }
  `,
})
export class StatusSquareComponent {
  readonly status = input.required<CellStatus>();
  /** An optimistic card whose id is still a placeholder — drawn, but not yet real. */
  readonly pending = input(false);
}
