import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import type { Band } from './results.models';

/**
 * §7's level band — *emerging · developing · secure · exceeding* — as a badge or as a grid
 * square.
 *
 * **Never colour alone** (the UI spec's own rule, and WCAG 1.4.1). Every band carries a short
 * word from the bundle as well as its colour, and the full band name is in the element's title
 * and in its accessible text, so the grid reads the same to a screen reader, on a projector, and
 * to the third of male teachers who cannot separate the red from the green.
 *
 * The `square` variant is the gradebook's cell: fixed width, the score printed under the band's
 * short word, and the automatic score struck through beside it when the teacher has overridden
 * it (that part is the gradebook's own markup — this draws the band).
 */
@Component({
  selector: 'hq-level-band',
  imports: [TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span
      class="level"
      [class.level--square]="square()"
      [attr.data-band]="band() ?? 'none'"
      [attr.title]="full() | transloco"
    >
      <span aria-hidden="true">{{ short() | transloco }}</span>
      <span class="hq-sr-only">{{ full() | transloco }}</span>
      @if (square() && score() !== null) {
        <span class="level__score" aria-hidden="true">{{ score() }}</span>
        <span class="hq-sr-only">{{ 'results.band.score' | transloco: { score: score() } }}</span>
      }
    </span>
  `,
  styles: `
    :host {
      display: inline-flex;
    }

    .level {
      display: inline-flex;
      align-items: center;
      gap: var(--hq-space-badge-gap);
      padding: var(--hq-space-badge);
      border-radius: var(--hq-radius-pill);
      font-size: var(--hq-text-theme-2xs);
      line-height: calc(var(--hq-text-theme-2xs-line) / var(--hq-text-theme-2xs));
      font-weight: var(--hq-text-weight-medium);
      background: var(--hq-color-neutral-soft);
      color: var(--hq-color-neutral-ink);
      white-space: nowrap;
    }

    .level--square {
      justify-content: center;
      inline-size: 100%;
      min-inline-size: var(--hq-size-cell-tile);
      padding: var(--hq-space-4);
      border-radius: var(--hq-radius-xs);
    }

    .level__score {
      font-weight: var(--hq-text-weight-semibold);
    }

    .level[data-band='emerging'] {
      background: var(--hq-color-error-soft);
      color: var(--hq-color-error-ink);
    }

    .level[data-band='developing'] {
      background: var(--hq-color-warning-soft);
      color: var(--hq-color-warning-ink);
    }

    .level[data-band='secure'] {
      background: var(--hq-color-accent-soft);
      color: var(--hq-color-accent-on-soft);
    }

    .level[data-band='exceeding'] {
      background: var(--hq-color-success-soft);
      color: var(--hq-color-success-ink);
    }
  `,
})
export class LevelBandComponent {
  readonly band = input<Band | null>(null);
  /** Printed inside the square variant; the badge variant leaves the number to its neighbour. */
  readonly score = input<number | null>(null);
  readonly square = input(false);

  protected readonly full = computed(() => `results.band.${this.band() ?? 'none'}`);
  protected readonly short = computed(() => `results.bandShort.${this.band() ?? 'none'}`);
}
