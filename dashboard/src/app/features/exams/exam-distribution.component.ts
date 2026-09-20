import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import type { ExamBand } from '../../api';
import { LevelBandComponent } from '../results/level-band.component';
import { distributionBars } from './exams.models';

/** The drawing's own geometry, in user units; the SVG scales itself to the card. */
const WIDTH = 520;
const HEIGHT = 180;
const BASELINE = 140;
const SLOT = WIDTH / 4;
const BAR = 64;

/**
 * §8's "class average and distribution" — how many children landed in each of the four bands.
 *
 * **Four columns, always the same four**, in §7's order, even when a band is empty: that is what
 * lets a teacher compare this exam with the last one at a glance, and a chart that silently
 * drops its left-hand column reads as a class with no strugglers.
 *
 * **No chart library**, for the reason `hq-score-chart` gives: four bars do not justify a
 * megabyte on a page opened once per exam. The geometry is four rectangles; the arithmetic is
 * `distributionBars`, tested on its own.
 *
 * **The table is the other half, not a fallback.** The SVG is `aria-hidden` and the same numbers
 * follow it in a real table — what a screen reader reads, what prints, and what gets read out in
 * a staff meeting.
 */
@Component({
  selector: 'hq-exam-distribution',
  imports: [LevelBandComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (anybody()) {
      <div class="dist__scroll">
        <svg class="dist" aria-hidden="true" focusable="false" [attr.viewBox]="'0 0 ' + width + ' ' + height">
          <line
            class="dist__baseline"
            [attr.x1]="0"
            [attr.x2]="width"
            [attr.y1]="baseline"
            [attr.y2]="baseline"
          />
          @for (bar of bars(); track bar.band; let i = $index) {
            <rect
              class="dist__bar"
              [attr.data-band]="bar.band"
              [attr.x]="x(i)"
              [attr.y]="y(bar.percent)"
              [attr.width]="barWidth"
              [attr.height]="heightOf(bar.percent)"
              [attr.rx]="radius"
            />
            <text class="dist__value" [attr.x]="centre(i)" [attr.y]="y(bar.percent) - 6">
              {{ bar.children }}
            </text>
            <text class="dist__label" [attr.x]="centre(i)" [attr.y]="baseline + 18">
              {{ 'results.bandShort.' + bar.band | transloco }}
            </text>
            <text class="dist__share" [attr.x]="centre(i)" [attr.y]="baseline + 34">{{ bar.share }}%</text>
          }
        </svg>
      </div>

      <table class="dist__table">
        <caption class="hq-sr-only">
          {{
            'exams.results.distribution.caption' | transloco
          }}
        </caption>
        <thead>
          <tr>
            <th scope="col">{{ 'results.table.band' | transloco }}</th>
            <th scope="col">{{ 'exams.results.distribution.children' | transloco }}</th>
            <th scope="col">{{ 'exams.results.distribution.share' | transloco }}</th>
          </tr>
        </thead>
        <tbody>
          @for (bar of bars(); track bar.band) {
            <tr>
              <th scope="row"><hq-level-band [band]="bar.band" /></th>
              <td>{{ bar.children }}</td>
              <td>{{ bar.share }}%</td>
            </tr>
          }
        </tbody>
      </table>
    } @else {
      <p class="dist__none">{{ 'exams.results.distribution.empty' | transloco }}</p>
    }
  `,
  styles: `
    :host {
      display: block;
    }

    .dist__scroll {
      max-inline-size: 100%;
      overflow-x: auto;
    }

    .dist {
      display: block;
      inline-size: 100%;
      block-size: auto;
    }

    .dist__baseline {
      stroke: var(--hq-color-rule);
      stroke-width: 1;
    }

    .dist__value {
      fill: var(--hq-color-ink);
      font-size: 14px;
      font-weight: var(--hq-text-weight-semibold);
      text-anchor: middle;
    }

    .dist__label {
      fill: var(--hq-color-ink-soft);
      font-size: 12px;
      text-anchor: middle;
    }

    .dist__share {
      fill: var(--hq-color-ink-muted);
      font-size: 11px;
      text-anchor: middle;
    }

    .dist__bar {
      fill: var(--hq-color-ink-muted);
    }

    .dist__bar[data-band='emerging'] {
      fill: var(--hq-color-error-500);
    }

    .dist__bar[data-band='developing'] {
      fill: var(--hq-color-warning-400);
    }

    .dist__bar[data-band='secure'] {
      fill: var(--hq-color-accent);
    }

    .dist__bar[data-band='exceeding'] {
      fill: var(--hq-color-success-500);
    }

    .dist__table {
      inline-size: 100%;
      margin-block-start: var(--hq-space-16);
      border-collapse: collapse;
      font-size: var(--hq-text-theme-xs);
      line-height: calc(var(--hq-text-theme-xs-line) / var(--hq-text-theme-xs));
    }

    .dist__table th,
    .dist__table td {
      padding: var(--hq-space-8);
      border-block-start: var(--hq-size-rule-thin) solid var(--hq-color-divider);
      text-align: start;
    }

    .dist__table thead th {
      color: var(--hq-color-ink-soft);
      font-size: var(--hq-text-theme-2xs);
      font-weight: var(--hq-text-weight-medium);
      border-block-start: 0;
    }

    .dist__none {
      margin: 0;
      color: var(--hq-color-ink-soft);
    }
  `,
})
export class ExamDistributionComponent {
  readonly distribution = input<readonly ExamBand[]>([]);

  protected readonly bars = computed(() => distributionBars(this.distribution()));
  protected readonly anybody = computed(() => this.bars().some((bar) => bar.children > 0));

  protected readonly width = WIDTH;
  protected readonly height = HEIGHT;
  protected readonly baseline = BASELINE;
  protected readonly barWidth = BAR;
  protected readonly radius = 4;

  protected x(index: number): number {
    return index * SLOT + (SLOT - BAR) / 2;
  }

  protected centre(index: number): number {
    return index * SLOT + SLOT / 2;
  }

  /** A band with children in it is never drawn as nothing: two units of stub say "not zero". */
  protected heightOf(percent: number): number {
    return percent === 0 ? 0 : Math.max(2, Math.round((percent / 100) * (BASELINE - 24)));
  }

  protected y(percent: number): number {
    return BASELINE - this.heightOf(percent);
  }
}
