import { AfterViewInit, Directive, ElementRef, inject } from '@angular/core';
import { MOTION } from './motion';

/**
 * `listStagger` — children fade and rise 30 ms apart.
 *
 * First load only: the class and the per-child `--hq-stagger-index` are set once,
 * then removed when the last child finishes, so re-sorting or filtering a table
 * never replays the entrance.
 */
@Directive({
  selector: '[hqListStagger]',
})
export class ListStaggerDirective implements AfterViewInit {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  ngAfterViewInit(): void {
    const element = this.host.nativeElement;
    const children = Array.from(element.children) as HTMLElement[];
    children.forEach((child, index) => child.style.setProperty('--hq-stagger-index', String(index)));
    element.classList.add(MOTION.listStagger);

    const last = children.at(-1);
    if (!last) return;
    last.addEventListener('animationend', () => element.classList.remove(MOTION.listStagger), {
      once: true,
    });
  }
}
