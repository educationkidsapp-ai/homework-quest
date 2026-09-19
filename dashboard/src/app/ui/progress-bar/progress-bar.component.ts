import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * §3's progress bar: an 8 px pill track on the inner-divider grey, with the fill coloured by
 * threshold — good at 70 % and over, mid from 50, and the error ramp below that.
 *
 * `plain` opts out of the thresholds and keeps the brand, for a bar that measures work done
 * rather than a result: a red "uploading" bar says a failure that has not happened.
 */
@Component({
  selector: 'hq-progress-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div
      class="bar"
      role="progressbar"
      [class.bar--indeterminate]="value() === null"
      [class]="'bar--' + tone()"
      [attr.aria-label]="label()"
      [attr.aria-valuenow]="value()"
      [attr.aria-valuemin]="value() === null ? null : 0"
      [attr.aria-valuemax]="value() === null ? null : max()"
    >
      <span class="bar__fill" [style.inline-size.%]="percent()"></span>
    </div>
  `,
  styles: `
    @use 'mixins' as m;

    :host {
      display: block;
    }

    .bar {
      block-size: var(--hq-size-progress-bar);
      inline-size: 100%;
      overflow: hidden;
      border-radius: var(--hq-radius-pill);
      background: var(--hq-color-divider);
    }

    .bar__fill {
      display: block;
      block-size: 100%;
      border-radius: var(--hq-radius-pill);
      background: var(--hq-color-accent);
      @include m.motion-safe('inline-size, background-color', var(--hq-motion-base));
    }

    .bar--good .bar__fill {
      background: var(--hq-color-band-good);
    }

    .bar--mid .bar__fill {
      background: var(--hq-color-band-mid);
    }

    .bar--look .bar__fill {
      background: var(--hq-color-band-look);
    }

    .bar--indeterminate .bar__fill {
      inline-size: 40%;
      background: linear-gradient(
        90deg,
        var(--hq-color-divider) 0%,
        var(--hq-color-accent) 50%,
        var(--hq-color-divider) 100%
      );
      background-size: 250% 100%;
      animation: hq-shimmer var(--hq-motion-shimmer) linear infinite;

      @include m.reduced-motion {
        animation: none;
        inline-size: 100%;
        background: var(--hq-color-divider);
      }
    }
  `,
})
export class ProgressBarComponent {
  /** 0…`max`, or null for an indeterminate bar. */
  readonly value = input<number | null>(null);
  readonly max = input(100);
  /** Accessible name — say what is progressing. */
  readonly label = input.required<string>();
  /** Keep the brand fill instead of §3's thresholds — for progress, not for a result. */
  readonly plain = input(false);

  /** §3: `#12b76a` ≥ 70 %, `#fdb022` 50–69 %, `#f04438` below — as roles, not as literals. */
  protected readonly tone = computed(() => {
    if (this.plain() || this.value() === null) return 'plain';
    const percent = this.percent();
    if (percent >= 70) return 'good';
    return percent >= 50 ? 'mid' : 'look';
  });

  protected readonly percent = computed(() => {
    const value = this.value();
    if (value === null) return 40;
    return Math.max(0, Math.min(100, (value / this.max()) * 100));
  });
}
