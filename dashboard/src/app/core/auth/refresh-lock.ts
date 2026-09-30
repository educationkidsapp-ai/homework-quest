import { DOCUMENT, Injectable, inject } from '@angular/core';
import { Observable, defer, from } from 'rxjs';
import { finalize, switchMap } from 'rxjs/operators';

/** The name both the Web Locks lock and its localStorage stand-in are keyed by. */
const LOCK_NAME = 'hq.refresh';
const FALLBACK_KEY = 'hq.refresh.lock';
/** A tab that crashed mid-refresh must not hold the lock for ever. */
const STALE_MS = 10_000;
const POLL_MS = 50;
/** After this the lock is given up on and the refresh goes ahead anyway (T2, item e). */
const WAIT_MS = 5_000;

/**
 * **One tab refreshes at a time, across every tab of the browser profile.**
 *
 * The refresh token is single-use and rotated on the server, and presenting an already-rotated
 * one is read as theft: every live token of that user is revoked (runbook, "Refresh rotation").
 * {@link AuthService.refresh} has always been single-flight *inside* one tab, which is why one
 * Home's six parallel 401s recover — but two tabs of the same account are two independent
 * single-flights, and the second one to arrive spends a token the first has already rotated.
 * That is the "logged out after a few minutes" the owner saw: nothing expired, the account was
 * revoked by its own second tab.
 *
 * So the rotation is serialised here. The winner refreshes and writes the new refresh token to
 * `localStorage`; the loser is handed the lock afterwards and re-reads the token from storage
 * (`SessionStore.refreshToken` is kept current by the `storage` listener in
 * {@link SessionSyncService}), so it spends the *current* token rather than the spent one.
 *
 * The **Web Locks API** is the mechanism where it exists — it is held by the tab rather than by
 * a value, so a tab that is closed or killed releases it with no timeout to guess. Safari before
 * 15.4 and any prerender have no `navigator.locks`, so there is a `localStorage` stand-in: a
 * timestamped key, claimed by reading it free and writing in the same synchronous turn. Both
 * halves of that turn run under the storage mutex, so no other tab can interleave inside it —
 * which is the whole of why a stamp works at all. A tab that dies holding it is covered by
 * {@link STALE_MS} rather than by the browser, and a wait that outlasts {@link WAIT_MS} gives up
 * and refreshes anyway: the lock is what makes the common race safe, not a correctness gate the
 * session depends on. The claim is deliberately free of an `await`, so an uncontended refresh is
 * still one microtask rather than a timer.
 */
@Injectable({ providedIn: 'root' })
export class RefreshLock {
  private readonly doc = inject(DOCUMENT);

  /** Runs `work` with the lock held, and releases it however `work` ends. */
  run<T>(work: () => Observable<T>): Observable<T> {
    return defer(() => from(this.acquire())).pipe(switchMap((release) => work().pipe(finalize(release))));
  }

  private async acquire(): Promise<() => void> {
    const locks = this.doc.defaultView?.navigator?.locks;
    if (!locks) return this.acquireByStamp();
    let release: () => void = () => undefined;
    const held = new Promise<void>((resolve) => (release = resolve));
    await new Promise<void>((granted) => {
      void locks.request(LOCK_NAME, () => {
        granted();
        return held;
      });
    });
    return release;
  }

  private async acquireByStamp(): Promise<() => void> {
    const storage = this.storage();
    if (!storage) return () => undefined;
    const deadline = Date.now() + WAIT_MS;
    const mine = `${Date.now()}:${Math.random().toString(36).slice(2)}`;
    for (;;) {
      if (this.stampIsFree(storage)) {
        storage.setItem(FALLBACK_KEY, mine);
        // Only the holder clears it: a tab whose claim was stale-collected must not remove the
        // stamp of the tab that took it over.
        return () => {
          if (storage.getItem(FALLBACK_KEY) === mine) storage.removeItem(FALLBACK_KEY);
        };
      }
      if (Date.now() > deadline) return () => undefined;
      await pause(POLL_MS);
    }
  }

  private stampIsFree(storage: Storage): boolean {
    const value = storage.getItem(FALLBACK_KEY);
    if (value === null) return true;
    const held = Number(value.split(':', 1)[0]);
    return !Number.isFinite(held) || Date.now() - held > STALE_MS;
  }

  /** `localStorage` throws in private-mode Safari and is absent when prerendering. */
  private storage(): Storage | null {
    try {
      return this.doc.defaultView?.localStorage ?? null;
    } catch {
      return null;
    }
  }
}

function pause(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
