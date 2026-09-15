import { Directive, ElementRef, effect, inject, input } from '@angular/core';
import { MOTION } from './motion';
import { MotionService } from './motion.service';

/** Which way the user moved: forward slides in from the trailing edge, back from the leading edge. */
export type NavDirection = 'forward' | 'back';

/**
 * `pageEnter` — 24 px slide in the navigation direction plus a fade, 400 ms.
 *
 * The slide is expressed as `--hq-page-enter-direction` (±1) multiplied by the
 * token offset, so RTL mirrors for free: `dir=rtl` flips the sign.
 */
@Directive({
  selector: '[hqPageEnter]',
  host: { '[class.hq-anim-page-enter]': 'true' },
})
export class PageEnterDirective {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly motion = inject(MotionService);

  /** Navigation direction; `forward` by default. */
  readonly hqPageEnter = input<NavDirection | ''>('forward');

  constructor() {
    effect(() => {
      const rtl = this.host.nativeElement.ownerDocument.documentElement.dir === 'rtl';
      const forward = this.hqPageEnter() !== 'back';
      const sign = (forward ? 1 : -1) * (rtl ? -1 : 1);
      this.host.nativeElement.style.setProperty('--hq-page-enter-direction', String(sign));
      // Re-running the animation on a re-entered route is the directive's job, not CSS's.
      if (!this.motion.reduced()) {
        this.host.nativeElement.classList.remove(MOTION.pageEnter);
        void this.host.nativeElement.offsetWidth;
        this.host.nativeElement.classList.add(MOTION.pageEnter);
      }
    });
  }
}
