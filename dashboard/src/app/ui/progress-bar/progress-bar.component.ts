import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * A square 6 px progress bar. Determinate when `value` is a number, indeterminate when null.
 *
 * Progress is never red — red is reserved for failure — so the fill uses the ink colour
 * and the `band-*` tokens stay available for the app's own progress bands.
 */
@Component({
  selector: 'hq-progress-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div
      class="bar"
      role="progressbar"
      [class.bar--indeterminate]="value() === null"
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
      background: var(--hq-color-rule);
    }

    .bar__fill {
      display: block;
      block-size: 100%;
      background: var(--hq-color-ink);
      @include m.motion-safe('inline-size', var(--hq-motion-base));
    }

    .bar--indeterminate .bar__fill {
      inline-size: 40%;
      background: linear-gradient(
        90deg,
        var(--hq-color-rule) 0%,
        var(--hq-color-ink) 50%,
        var(--hq-color-rule) 100%
      );
      background-size: 250% 100%;
      animation: hq-shimmer var(--hq-motion-shimmer) linear infinite;

      @include m.reduced-motion {
        animation: none;
        inline-size: 100%;
        background: var(--hq-color-rule);
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

  protected readonly percent = computed(() => {
    const value = this.value();
    if (value === null) return 40;
    return Math.max(0, Math.min(100, (value / this.max()) * 100));
  });
}
