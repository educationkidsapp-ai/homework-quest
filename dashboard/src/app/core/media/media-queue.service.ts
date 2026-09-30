import { InjectionToken, Injectable, inject } from '@angular/core';
import { Observable, type Subscription } from 'rxjs';

/**
 * How many protected media reads may be in flight at once.
 *
 * Three, because the bytes are **full-size scans**: a weekly plan is whatever the department
 * photographed, up to the server's 5 MB cap, and there is no thumbnail variant to ask for instead.
 * A screen with a dozen of them on it is a dozen multi-megabyte downloads competing for one uplink,
 * and the last of them finishes no sooner for having started first. Three keeps the pictures she is
 * actually looking at arriving quickly.
 *
 * A token rather than a constant so a spec can set it to one and watch the queue hold the second.
 */
export const MEDIA_CONCURRENCY = new InjectionToken<number>('hq.media.concurrency', {
  providedIn: 'root',
  factory: () => 3,
});

/**
 * A concurrency gate for {@link MediaService}'s reads.
 *
 * Lazy loading decides *whether* a picture is fetched; this decides *when*. Both are needed: an
 * archive of twelve weeks scrolled quickly would otherwise start every row's download as it passed
 * through the viewport, and cancelling an unsubscribed one does not un-send the bytes already on
 * the wire.
 *
 * Subscribing queues the work; a slot is held until the inner observable completes or errors, and
 * unsubscribing before it starts takes it out of the queue rather than running it for nobody.
 */
@Injectable({ providedIn: 'root' })
export class MediaQueue {
  private readonly limit = inject(MEDIA_CONCURRENCY);
  private queue: (() => void)[] = [];
  private running = 0;

  /** For a spec, and for anyone measuring: how many reads are waiting for a slot. */
  get waiting(): number {
    return this.queue.length;
  }

  run<T>(work: () => Observable<T>): Observable<T> {
    return new Observable<T>((subscriber) => {
      let inner: Subscription | null = null;
      let queued: (() => void) | null = null;
      let holding = false;

      const release = (): void => {
        if (!holding) return;
        holding = false;
        this.running -= 1;
        this.start();
      };

      const task = (): void => {
        queued = null;
        holding = true;
        this.running += 1;
        inner = work().subscribe({
          next: (value) => subscriber.next(value),
          error: (error: unknown) => {
            release();
            subscriber.error(error);
          },
          complete: () => {
            release();
            subscriber.complete();
          },
        });
      };

      queued = task;
      if (this.running < this.limit) task();
      else this.queue.push(task);

      return () => {
        if (queued !== null) this.queue = this.queue.filter((waiting) => waiting !== queued);
        inner?.unsubscribe();
        release();
      };
    });
  }

  /** Fill every free slot from the front of the queue. */
  private start(): void {
    while (this.running < this.limit) {
      const next = this.queue.shift();
      if (next === undefined) return;
      next();
    }
  }
}
