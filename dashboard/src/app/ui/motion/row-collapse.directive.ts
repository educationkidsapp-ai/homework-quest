import { Directive, ElementRef, inject } from '@angular/core';
import { MOTION_EASING, MOTION_MS } from './motion';
import { MotionService } from './motion.service';

/**
 * `rowCollapse` — height to 0 over 250 ms, then the caller removes the row.
 *
 * A table row cannot animate its own removal declaratively (the DOM node is gone
 * before any exit animation could run), so the caller awaits `collapse()` and
 * deletes the model afterwards. Reduced motion resolves immediately.
 */
@Directive({
  selector: '[hqRowCollapse]',
  exportAs: 'hqRowCollapse',
})
export class RowCollapseDirective {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly motion = inject(MotionService);

  /** Resolves when the row has finished collapsing. */
  async collapse(): Promise<void> {
    const element = this.host.nativeElement;
    const duration = this.motion.duration(MOTION_MS.base);
    if (duration === 0 || typeof element.animate !== 'function') return;

    const from = `${element.getBoundingClientRect().height}px`;
    const animation = element.animate(
      [
        { blockSize: from, opacity: 1 },
        { blockSize: '0px', opacity: 0 },
      ],
      { duration, easing: MOTION_EASING.ease, fill: 'forwards' },
    );
    await animation.finished.catch(() => undefined);
  }
}
