import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * Progress in child mode: seven stars filling up (`docs/design.md` §7). Never a percentage, never
 * a score out of a hundred, never a clock.
 */
@Component({
  selector: 'hq-child-stars',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <p class="stars" [attr.aria-label]="label()">
      @for (star of stars(); track $index) {
        <span class="stars__star" [class.stars__star--on]="star" aria-hidden="true">★</span>
      }
    </p>
  `,
  styles: `
    @use 'mixins' as m;

    :host {
      display: block;
    }

    .stars {
      display: flex;
      gap: var(--hq-space-4);
      margin: 0;
    }

    .stars__star {
      font-size: var(--hq-child-title);
      line-height: 1;
      color: var(--hq-child-cream);
      @include m.motion-safe('color', var(--hq-motion-base));
    }

    .stars__star--on {
      color: var(--hq-child-sun);
    }
  `,
})
export class ChildStarsComponent {
  /** How many of the seven are filled. */
  readonly filled = input<number>(0);
  readonly total = input<number>(7);
  /** Accessible name, e.g. "3 of 7 stars" — the caller's wording. */
  readonly label = input<string | null>(null);

  protected readonly stars = computed(() =>
    Array.from({ length: this.total() }, (_, index) => index < this.filled()),
  );
}
