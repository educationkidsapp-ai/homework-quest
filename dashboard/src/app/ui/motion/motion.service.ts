import { DOCUMENT, Injectable, computed, inject, signal } from '@angular/core';

/**
 * The single place that decides whether motion runs.
 *
 * `prefers-reduced-motion: reduce` (or the styleguide's manual override) sets
 * `data-hq-reduced-motion="true"` on `<html>`; every duration in `_motion.scss`
 * collapses to `0ms` under that attribute, and the TypeScript animations read
 * `duration()` so they do the same.
 */
@Injectable({ providedIn: 'root' })
export class MotionService {
  private readonly doc = inject(DOCUMENT);
  private readonly systemReduced = signal(false);
  private readonly override = signal<boolean | null>(null);

  /** True when animations must collapse to an instant cross-fade. */
  readonly reduced = computed(() => this.override() ?? this.systemReduced());

  constructor() {
    const win = this.doc.defaultView;
    if (win?.matchMedia) {
      const query = win.matchMedia('(prefers-reduced-motion: reduce)');
      this.systemReduced.set(query.matches);
      query.addEventListener('change', (event) => {
        this.systemReduced.set(event.matches);
        this.sync();
      });
    }
    this.sync();
  }

  /** Force reduced motion on/off; `null` hands control back to the OS setting. */
  setOverride(value: boolean | null): void {
    this.override.set(value);
    this.sync();
  }

  /** `ms` unless motion is reduced, in which case 0. */
  duration(ms: number): number {
    return this.reduced() ? 0 : ms;
  }

  private sync(): void {
    this.doc.documentElement.dataset['hqReducedMotion'] = String(this.reduced());
  }
}
