import { Directive, ElementRef, effect, inject, input } from '@angular/core';
import { MOTION } from './motion';
import { MotionService } from './motion.service';

/**
 * `shake` — one shake, 6 px, 300 ms, whenever `hqShake` changes to a new truthy token.
 *
 * Pass a counter or an error id; the same value twice does not replay, which is
 * what you want when a signal re-emits an unchanged error.
 */
@Directive({
  selector: '[hqShake]',
})
export class ShakeDirective {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly motion = inject(MotionService);

  /** Change this to trigger a shake. Falsy values never shake. */
  readonly hqShake = input<string | number | null | undefined>(null);

  constructor() {
    effect(() => {
      const token = this.hqShake();
      if (!token || this.motion.reduced()) return;
      const element = this.host.nativeElement;
      element.classList.remove(MOTION.shake);
      // Force a reflow so the animation restarts rather than being deduplicated.
      void element.offsetWidth;
      element.classList.add(MOTION.shake);
      element.addEventListener('animationend', () => element.classList.remove(MOTION.shake), {
        once: true,
      });
    });
  }
}
