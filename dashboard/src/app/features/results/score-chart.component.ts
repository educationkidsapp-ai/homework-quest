import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import type { ChildTrendPoint } from '../../api';
import { activeLang } from '../../core/i18n/active-lang';
import { chartOf } from './child.models';
import { LevelBandComponent } from './level-band.component';

/**
 * A child's scores over time — §4 step 9's "score chart over time", as inline SVG.
 *
 * **No chart library.** A dozen bars do not justify 300 kB on a page a teacher opens once per
 * child; the geometry is in `child.models.ts` and tested there, and the bars take their colours
 * from the same band tokens as the gradebook's squares, so the two screens agree.
 *
 * **The table is not a fallback, it is the other half.** The SVG is `aria-hidden` and the same
 * numbers follow it in a real `<table>`: that is what a screen reader reads, what prints, and
 * what a teacher reads out at a parents' evening. Gridlines sit on §7's band boundaries (40, 60
 * and 85) rather than at round numbers, so a bar's height answers the question actually being
 * asked — which band is she in.
 */
@Component({
  selector: 'hq-score-chart',
  imports: [LevelBandComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (chart().bars.length > 0) {
      <div class="chart__scroll">
        <svg
          class="chart"
          aria-hidden="true"
          focusable="false"
          [attr.viewBox]="'0 0 ' + chart().width + ' ' + chart().height"
          [style.inline-size.px]="chart().width"
        >
          @for (line of chart().lines; track line.score) {
            <line
              class="chart__line"
              [attr.x1]="0"
              [attr.x2]="chart().width"
              [attr.y1]="line.y"
              [attr.y2]="line.y"
            />
            <text class="chart__tick" [attr.x]="4" [attr.y]="line.y - 4">{{ line.score }}</text>
          }
          <line
            class="chart__baseline"
            [attr.x1]="0"
            [attr.x2]="chart().width"
            [attr.y1]="chart().baseline"
            [attr.y2]="chart().baseline"
          />
          @for (bar of chart().bars; track bar.lessonId) {
            <rect
              class="chart__bar"
              [attr.data-band]="bar.band ?? 'none'"
              [attr.x]="bar.x"
              [attr.y]="bar.y"
              [attr.width]="bar.width"
              [attr.height]="bar.height"
              [attr.rx]="radius"
            />
            <text class="chart__value" [attr.x]="bar.centre" [attr.y]="bar.y - 6">
              {{ bar.score }}
            </text>
            <text class="chart__label" [attr.x]="bar.centre" [attr.y]="chart().baseline + 16">
              {{ day(bar.date) }}
            </text>
          }
        </svg>
      </div>

      <table class="chart__table">
        <caption class="hq-sr-only">
          {{
            'results.child.chartCaption' | transloco
          }}
        </caption>
        <thead>
          <tr>
            <th scope="col">{{ 'results.child.lesson' | transloco }}</th>
            <th scope="col">{{ 'results.child.date' | transloco }}</th>
            <th scope="col">{{ 'results.table.score' | transloco }}</th>
            <th scope="col">{{ 'results.table.band' | transloco }}</th>
          </tr>
        </thead>
        <tbody>
          @for (bar of chart().bars; track bar.lessonId) {
            <tr>
              <th scope="row">{{ bar.title }}</th>
              <td>{{ day(bar.date) }}</td>
              <td>{{ bar.score }}</td>
              <td><hq-level-band [band]="bar.band" /></td>
            </tr>
          }
        </tbody>
      </table>
    } @else {
      <p class="chart__none">{{ 'results.child.noScores' | transloco }}</p>
    }
  `,
  styles: `
    :host {
      display: block;
    }

    // The chart scrolls inside its card when a term is longer than the screen; the page does not.
    .chart__scroll {
      max-inline-size: 100%;
      overflow-x: auto;
    }

    .chart {
      display: block;
      max-inline-size: none;
      block-size: auto;
    }

    .chart__line {
      stroke: var(--hq-color-divider);
      stroke-width: 1;
    }

    .chart__baseline {
      stroke: var(--hq-color-rule);
      stroke-width: 1;
    }

    .chart__tick {
      fill: var(--hq-color-ink-muted);
      font-size: 11px;
    }

    .chart__value {
      fill: var(--hq-color-ink);
      font-size: 12px;
      font-weight: var(--hq-text-weight-medium);
      text-anchor: middle;
    }

    .chart__label {
      fill: var(--hq-color-ink-soft);
      font-size: 11px;
      text-anchor: middle;
    }

    .chart__bar {
      fill: var(--hq-color-ink-muted);
    }

    .chart__bar[data-band='emerging'] {
      fill: var(--hq-color-error-500);
    }

    .chart__bar[data-band='developing'] {
      fill: var(--hq-color-warning-400);
    }

    .chart__bar[data-band='secure'] {
      fill: var(--hq-color-accent);
    }

    .chart__bar[data-band='exceeding'] {
      fill: var(--hq-color-success-500);
    }

    .chart__table {
      inline-size: 100%;
      margin-block-start: var(--hq-space-16);
      border-collapse: collapse;
      font-size: var(--hq-text-theme-xs);
      line-height: calc(var(--hq-text-theme-xs-line) / var(--hq-text-theme-xs));
    }

    .chart__table th,
    .chart__table td {
      padding: var(--hq-space-8);
      border-block-start: var(--hq-size-rule-thin) solid var(--hq-color-divider);
      text-align: start;
    }

    .chart__table thead th {
      color: var(--hq-color-ink-soft);
      font-size: var(--hq-text-theme-2xs);
      font-weight: var(--hq-text-weight-medium);
      border-block-start: 0;
    }

    .chart__none {
      margin: 0;
      color: var(--hq-color-ink-soft);
    }
  `,
})
export class ScoreChartComponent {
  private readonly lang = activeLang();

  readonly points = input.required<readonly ChildTrendPoint[]>();

  protected readonly chart = computed(() => chartOf(this.points()));
  /** The spec's 4 px bar cap, as a number the `rx` attribute takes. */
  protected readonly radius = 4;

  protected day(date: string): string {
    if (!date) return '';
    return new Intl.DateTimeFormat(this.lang(), { day: 'numeric', month: 'short' }).format(
      new Date(`${date}T00:00:00Z`),
    );
  }
}
