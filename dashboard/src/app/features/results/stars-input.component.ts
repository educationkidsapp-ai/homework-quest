import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

/** §7 marks an open stop out of three: `null` is "not marked yet", and 1–3 is the mark. */
export const STAR_VALUES = [1, 2, 3] as const;

/**
 * The 1–3 star mark a teacher gives a retell, a drawing or an open answer.
 *
 * A radio group, not three toggles: exactly one of the three is true at a time, and a keyboard
 * should reach the group once with Tab and choose inside it with the arrow keys, which is what
 * `role="radio"` gives for free. Pressing the star that is already chosen clears the mark —
 * the way back from "2 stars" to "not marked yet", without a fourth control called None.
 */
@Component({
  selector: 'hq-stars-input',
  imports: [TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="stars" role="radiogroup" [attr.aria-label]="label()">
      @for (star of values; track star) {
        <button
          type="button"
          class="stars__star"
          role="radio"
          [class.is-on]="(stars() ?? 0) >= star"
          [attr.aria-checked]="stars() === star"
          [attr.aria-label]="'results.marking.stars' | transloco: { count: star }"
          [disabled]="disabled()"
          (click)="choose(star)"
        >
          ★
        </button>
      }
    </div>
  `,
  styles: `
    @use 'mixins' as m;

    .stars {
      display: inline-flex;
      gap: var(--hq-space-4);
    }

    .stars__star {
      @include m.motion-safe('color');

      min-inline-size: var(--hq-size-touch-target);
      min-block-size: var(--hq-size-touch-target);
      padding: 0;
      border: 0;
      border-radius: var(--hq-radius-control);
      background: none;
      color: var(--hq-color-ink-muted);
      font-size: var(--hq-text-theme-xl);
      line-height: 1;
      cursor: pointer;

      &:hover:not(:disabled) {
        background: var(--hq-color-hover);
      }

      &.is-on {
        color: var(--hq-color-warning-500);
      }

      &:disabled {
        cursor: not-allowed;
        opacity: 0.5;
      }
    }
  `,
})
export class StarsInputComponent {
  readonly stars = model<number | null>(null);
  readonly label = input('');
  readonly disabled = input(false);

  protected readonly values = STAR_VALUES;

  protected choose(star: number): void {
    this.stars.set(this.stars() === star ? null : star);
  }
}
