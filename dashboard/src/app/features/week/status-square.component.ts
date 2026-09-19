import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import type { CellStatus } from './week.models';

/**
 * The 8 px status dot — §4's one glyph for "where is this lesson".
 *
 * Badge language rather than the old square: §1 says nothing in this system is sharp, and the
 * dot's job is to sit *inside* a status pill beside its own word. A ring for a day with
 * nothing on it, then three fills off the band scale: `draft` looks, `ready` is mid,
 * `published` is good. It carries no text of its own — every place that draws one puts
 * `week.status.<status>` beside it, so the colour is never the only signal.
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
      inline-size: var(--hq-space-8);
      block-size: var(--hq-space-8);
      border-radius: var(--hq-radius-pill);
      box-shadow: inset 0 0 0 var(--hq-size-rule-thin) currentColor;
      color: var(--hq-color-ink-muted);
    }

    :host(.square--draft) {
      background: var(--hq-color-band-look);
      color: var(--hq-color-band-look);
    }

    :host(.square--ready) {
      background: var(--hq-color-band-mid);
      color: var(--hq-color-band-mid);
    }

    :host(.square--published) {
      background: var(--hq-color-band-good);
      color: var(--hq-color-band-good);
    }

    // A copy the server has not answered for yet: same fill, held back to half.
    :host(.square--pending) {
      opacity: 0.5;
    }
  `,
})
export class StatusSquareComponent {
  readonly status = input.required<CellStatus>();
  /** An optimistic card whose id is still a placeholder — drawn, but not yet real. */
  readonly pending = input(false);
}
