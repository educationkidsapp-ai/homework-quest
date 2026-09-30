import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { MEDIA_CONCURRENCY, MediaQueue } from './media-queue.service';

/**
 * MH2: the gate that stops a fast scroll through the plan archive starting a dozen multi-megabyte
 * downloads at once. Two behaviours matter — a slot is held for the whole read, and giving up on a
 * queued read takes it out of the queue rather than running it for nobody.
 */
describe('the media concurrency gate', () => {
  function queueOf(limit: number): MediaQueue {
    TestBed.configureTestingModule({ providers: [{ provide: MEDIA_CONCURRENCY, useValue: limit }] });
    return TestBed.inject(MediaQueue);
  }

  it('runs up to the limit and holds the rest until one finishes', () => {
    const queue = queueOf(2);
    const sources = [new Subject<string>(), new Subject<string>(), new Subject<string>()];
    const started: number[] = [];
    const seen: string[] = [];

    sources.forEach((source, index) =>
      queue
        .run(() => {
          started.push(index);
          return source;
        })
        .subscribe((value) => seen.push(value)),
    );

    // Two in flight, the third waiting — not started, so its bytes are not on the wire either.
    expect(started).toEqual([0, 1]);
    expect(queue.waiting).toBe(1);

    sources[0]!.next('a');
    sources[0]!.complete();
    expect(started).toEqual([0, 1, 2]);
    expect(queue.waiting).toBe(0);
    expect(seen).toEqual(['a']);
  });

  it('frees the slot when a read fails, not only when it succeeds', () => {
    const queue = queueOf(1);
    const first = new Subject<string>();
    const started: number[] = [];
    queue
      .run(() => {
        started.push(0);
        return first;
      })
      .subscribe({ error: () => undefined });
    queue
      .run(() => {
        started.push(1);
        return new Subject<string>();
      })
      .subscribe();

    expect(started).toEqual([0]);
    first.error(new Error('401'));
    // A 401 on one picture must not stop every picture below it from ever being asked for.
    expect(started).toEqual([0, 1]);
  });

  it('drops a queued read that nobody is waiting for any more', () => {
    const queue = queueOf(1);
    const first = new Subject<string>();
    const started: number[] = [];
    queue.run(() => first).subscribe();
    const abandoned = queue
      .run(() => {
        started.push(1);
        return new Subject<string>();
      })
      .subscribe();

    expect(queue.waiting).toBe(1);
    // The row scrolled back out of view before its turn came.
    abandoned.unsubscribe();
    expect(queue.waiting).toBe(0);
    first.complete();
    expect(started).toEqual([]);
  });
});
