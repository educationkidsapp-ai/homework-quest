import { TestBed } from '@angular/core/testing';
import { Subject, firstValueFrom, of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { RefreshLock } from './refresh-lock';

/**
 * T2 item (e), the second half: **only one tab rotates the token at a time.**
 *
 * jsdom has no `navigator.locks`, so what these exercise is the `localStorage` stand-in — which is
 * the path that has to be right anyway, because it is what Safari 15.3 and a prerender get.
 */
describe('RefreshLock', () => {
  let lock: RefreshLock;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ providers: [RefreshLock] });
    lock = TestBed.inject(RefreshLock);
  });

  /** Every pending microtask, and nothing of the 50 ms poll the waiting tab uses. */
  const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

  it('runs the work and releases the lock afterwards', async () => {
    expect(await firstValueFrom(lock.run(() => of('done')))).toBe('done');
    // Released, not left behind for the next 10 seconds: a lock nobody holds must not make the
    // next refresh wait out the staleness timeout.
    expect(localStorage.getItem('hq.refresh.lock')).toBeNull();
  });

  it('releases the lock when the work fails, so the next tab is not locked out', async () => {
    await expect(
      firstValueFrom(lock.run(() => throwError(() => new Error('refresh refused')))),
    ).rejects.toThrow('refresh refused');
    expect(localStorage.getItem('hq.refresh.lock')).toBeNull();
  });

  it('holds the second caller until the first has finished', async () => {
    const order: string[] = [];
    const first = new Subject<string>();

    const a = firstValueFrom(lock.run(() => first)).then(() => order.push('a'));
    // The claim is a microtask, so let it land — and prove it, because a `next` before the
    // subscription would be lost and the test would pass for the wrong reason.
    await settle();
    expect(first.observed).toBe(true);

    const b = firstValueFrom(
      lock.run(() => {
        order.push('b-started');
        return of('b');
      }),
    ).then(() => order.push('b'));

    // The second refresh has not begun: the token the first is about to write is the one it has
    // to spend, and starting now is exactly the revocation this lock exists to prevent.
    await settle();
    expect(order).toEqual([]);

    first.next('a');
    first.complete();
    await Promise.all([a, b]);
    expect(order).toEqual(['a', 'b-started', 'b']);
  });

  it('takes over a lock a dead tab left behind rather than waiting for ever', async () => {
    // A tab that was killed mid-refresh eleven seconds ago (STALE_MS is ten).
    localStorage.setItem('hq.refresh.lock', `${Date.now() - 11_000}:ghost`);

    expect(await firstValueFrom(lock.run(() => of('done')))).toBe('done');
  });
});
