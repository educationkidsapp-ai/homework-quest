import { Directive, ElementRef, OnDestroy, effect, inject, input } from '@angular/core';
import { MOTION_MS } from './motion';
import { MotionService } from './motion.service';

/**
 * `countUp` — 0 to the value over 600 ms on first paint.
 *
 * Home cards use it for their headline numbers. The element keeps the final value
 * in `aria-label`-free plain text, so a screen reader reads the settled number;
 * under reduced motion the number is written once with no animation.
 */
@Directive({
  selector: '[hqCountUp]',
})
export class CountUpDirective implements OnDestroy {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly motion = inject(MotionService);
  private frame = 0;
  private started = false;

  /** The number to land on. */
  readonly hqCountUp = input.required<number>();
  /** Decimal places to render; whole numbers by default. */
  readonly countUpFractionDigits = input(0);

  constructor() {
    effect(() => {
      const target = this.hqCountUp();
      const digits = this.countUpFractionDigits();
      // Only the first paint animates; later updates snap, so a live figure does not crawl.
      if (this.started || this.motion.reduced()) {
        this.write(target, digits);
        this.started = true;
        return;
      }
      this.started = true;
      this.run(target, digits);
    });
  }

  ngOnDestroy(): void {
    cancelAnimationFrame(this.frame);
  }

  private run(target: number, digits: number): void {
    const duration = this.motion.duration(MOTION_MS.countUp);
    const win = this.host.nativeElement.ownerDocument.defaultView;
    if (duration === 0 || !win?.requestAnimationFrame) {
      this.write(target, digits);
      return;
    }

    const start = win.performance.now();
    const step = (now: number) => {
      const t = Math.min(1, (now - start) / duration);
      // Matches --hq-motion-ease closely enough for a number; exact easing is invisible here.
      const eased = 1 - Math.pow(1 - t, 3);
      this.write(target * eased, digits);
      if (t < 1) this.frame = win.requestAnimationFrame(step);
      else this.write(target, digits);
    };
    this.frame = win.requestAnimationFrame(step);
  }

  private write(value: number, digits: number): void {
    this.host.nativeElement.textContent = value.toLocaleString(undefined, {
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    });
  }
}
