import { Directive, ElementRef, effect, inject, input } from '@angular/core';
import { MOTION_EASING, MOTION_MS } from './motion';
import { MotionService } from './motion.service';

/**
 * `expandBand` — the red band grows from 0 height over 250 ms and collapses the same way.
 *
 * Errors and destructive confirmations are bands, never toasts, so the expansion
 * has to push the page rather than float over it: this animates `block-size`, not
 * a transform. `hq-band` drives it; the directive is separate so a feature screen
 * can expand its own inline band without re-implementing the timing.
 */
@Directive({
  selector: '[hqExpandBand]',
})
export class ExpandBandDirective {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly motion = inject(MotionService);
  private previous: boolean | null = null;

  /** Whether the band is showing. */
  readonly hqExpandBand = input.required<boolean>();

  constructor() {
    effect(() => {
      const open = this.hqExpandBand();
      const first = this.previous === null;
      const changed = this.previous !== open;
      this.previous = open;

      const element = this.host.nativeElement;
      element.hidden = !open;
      // The first render must not animate, or every page load flashes its bands open.
      if (first || !changed || !open) return;

      const duration = this.motion.duration(MOTION_MS.base);
      if (duration === 0 || typeof element.animate !== 'function') return;

      element.animate(
        [
          { blockSize: '0px', opacity: 0 },
          { blockSize: `${element.scrollHeight}px`, opacity: 1 },
        ],
        { duration, easing: MOTION_EASING.ease },
      );
    });
  }
}
