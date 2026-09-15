import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  computed,
  effect,
  input,
  signal,
} from '@angular/core';
import { MOTION_MS } from '../motion';

/**
 * Square grey placeholder blocks with a 1.2 s shimmer.
 *
 * Shown only after 300 ms: a fast response should never flash a skeleton, which reads
 * as a slower page than no placeholder at all.
 */
@Component({
  selector: 'hq-skeleton',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (visible()) {
      <div class="skeleton" aria-hidden="true">
        @for (line of lineArray(); track line) {
          <span
            class="skeleton__block"
            [style.block-size]="height()"
            [style.inline-size]="line === lines() - 1 ? lastLineWidth() : '100%'"
          ></span>
        }
      </div>
      <span role="status" class="hq-sr-only">{{ label() }}</span>
    }
  `,
  styles: `
    @use 'mixins' as m;

    :host {
      display: block;
    }

    .skeleton {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-8);
    }

    .skeleton__block {
      display: block;
      background: linear-gradient(
        90deg,
        var(--hq-color-skeleton) 0%,
        var(--hq-color-skeleton-shine) 50%,
        var(--hq-color-skeleton) 100%
      );
      background-size: 250% 100%;
      animation: hq-shimmer var(--hq-motion-shimmer) linear infinite;

      @include m.reduced-motion {
        animation: none;
        background: var(--hq-color-skeleton);
      }
    }
  `,
})
export class SkeletonComponent implements OnDestroy {
  private readonly delayed = signal(false);
  private timer: ReturnType<typeof setTimeout> | null = null;

  /** While true the skeleton is (eventually) shown; false hides it immediately. */
  readonly loading = input(true);
  readonly lines = input(3);
  readonly height = input('var(--hq-space-16)');
  readonly lastLineWidth = input('60%');
  /** Announced to screen readers while loading, e.g. "Loading lessons". */
  readonly label = input.required<string>();

  protected readonly visible = this.delayed.asReadonly();
  protected readonly lineArray = computed(() => Array.from({ length: this.lines() }, (_, index) => index));

  constructor() {
    effect(() => {
      const loading = this.loading();
      this.clear();
      if (!loading) {
        this.delayed.set(false);
        return;
      }
      // The 300 ms delay is a timing rule, not an animation: reduced motion keeps it.
      this.timer = setTimeout(() => this.delayed.set(true), MOTION_MS.skeletonDelay);
    });
  }

  ngOnDestroy(): void {
    this.clear();
  }

  private clear(): void {
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
  }
}
