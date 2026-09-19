import { DestroyRef, InjectionToken, Injectable, inject } from '@angular/core';
import { Observable, throwError } from 'rxjs';
import { catchError, shareReplay, switchMap, tap } from 'rxjs/operators';
import { MediaApi } from '../../api';
import { silentErrors } from '../http/error.interceptor';

/**
 * How much {@link MediaService} may hold.
 *
 * `bytes` is the binding one in practice: a page crop is a lossless PNG of a scanned page, so
 * one can be several megabytes and a `data:` URL carries it a third larger again. A dozen
 * entries would otherwise be tens of megabytes of live string in a tab that stays open all day.
 *
 * A token rather than two constants so a spec can set them to two and three bytes and watch the
 * eviction happen, instead of allocating sixteen megabytes to prove it.
 */
export interface MediaCacheLimits {
  readonly entries: number;
  readonly bytes: number;
}

export const MEDIA_CACHE_LIMITS = new InjectionToken<MediaCacheLimits>('hq.media.cacheLimits', {
  providedIn: 'root',
  factory: (): MediaCacheLimits => ({ entries: 16, bytes: 16 * 1024 * 1024 }),
});

interface Entry {
  readonly bytes: Observable<string>;
  /** The length of the data URL once it has arrived; 0 while it is still in flight. */
  size: number;
}

/**
 * Reads a protected page crop — `GET /media/pages/{id}`, `media.page.read` — and answers a URL
 * an `<img>` can carry.
 *
 * `MediaController` has required a bearer since P1.9 and an `<img src>` sends no `Authorization`
 * header, so every picture in the lesson editor answered 401 and nothing was drawn
 * (`docs/reports/tailadmin-restyle.md` §4.1). The bytes are fetched here instead, through the
 * generated {@link MediaApi} — the auth interceptor attaches the token and refreshes it exactly
 * as it does for any other call, and the id is resolved against the client's own `BASE_PATH`
 * rather than the absolute `publicUrl` baked into `PageImage.url` by `LessonStore`.
 *
 * **Why a `data:` URL and not `URL.createObjectURL`.** The shipped CSP is
 * `img-src 'self' https: data:` (`server/.../DashboardController.java:55`, mirrored by
 * `e2e/local/serve.mjs`), which has no `blob:`. An object URL would be the cheaper carrier — no
 * base64 tax, no copy — but the browser would refuse to paint it on QA and in production, which
 * is the bug this service exists to fix.
 *
 * **What holds the cache down.** Three things, because one teacher's scanned pages are not a
 * few kilobytes and the dashboard is a tab that stays open all day:
 *
 * 1. {@link scopeTo} — the lesson page declares which lesson it is showing, and moving to
 *    another one drops the first one's crops. Crops belong to a lesson and are never asked for
 *    again once it is closed.
 * 2. {@link MEDIA_CACHE_LIMITS}, evicting least-recently-used. A `Map` keeps insertion order,
 *    so a hit re-inserts and the front of the map is always the oldest.
 * 3. {@link clear}, called by `AuthService.forget()`. The root injector is never destroyed in a
 *    single-page app, so signing out is the only moment that reliably ends a session — and one
 *    teacher's pages must not survive into the next teacher's tab.
 *
 * A failure is not cached: the next mount asks again.
 */
@Injectable({ providedIn: 'root' })
export class MediaService {
  private readonly media = inject(MediaApi);
  private readonly limits = inject(MEDIA_CACHE_LIMITS);
  private readonly cache = new Map<string, Entry>();
  private held = 0;
  private scope: string | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.clear());
  }

  /** The page crop as a `data:` URL. Multicast: repeat callers share one request. */
  pageImage(id: string): Observable<string> {
    return this.remember(id, (key) =>
      this.read(key, this.media.pageImage(id, 'body', false, { context: silentErrors() })),
    );
  }

  /**
   * A child's saved work — a retell recording, a drawing, a photographed answer — as a `data:`
   * URL (N4.2). `/media/child/{id}` is behind the bearer like every other media route, so an
   * `<img src>` or an `<audio src>` pointed straight at it answers 401: the bytes have to come
   * through the generated client, which is what this does.
   *
   * Keyed apart from the crops (`child:`) because the two id spaces are different tables, and a
   * collision would serve a drawing where a page was asked for.
   */
  childMedia(id: string): Observable<string> {
    return this.remember(`child:${id}`, (key) =>
      this.read(key, this.media.childMedia(id, 'body', false, { context: silentErrors() })),
    );
  }

  private remember(key: string, read: (key: string) => Observable<string>): Observable<string> {
    const cached = this.cache.get(key);
    if (cached) {
      // Re-inserting is what makes the map an LRU: the oldest entry is the one at the front.
      this.cache.delete(key);
      this.cache.set(key, cached);
      return cached.bytes;
    }

    const entry: Entry = { bytes: read(key), size: 0 };
    this.cache.set(key, entry);
    return entry.bytes;
  }

  /**
   * Say which lesson the crops being asked for belong to. A different one empties the cache;
   * `null` is "no lesson on screen", which also empties it.
   */
  scopeTo(lessonId: string | null): void {
    if (lessonId === this.scope) return;
    this.scope = lessonId;
    this.clear();
  }

  /** Forget every crop. Called on sign-out — see the class comment. */
  clear(): void {
    this.cache.clear();
    this.held = 0;
  }

  /** How much is held, in bytes of data URL. For the specs, and for anyone measuring. */
  get bytesHeld(): number {
    return this.held;
  }

  private read(key: string, request: Observable<string>): Observable<string> {
    // The generated signature says `string` because the contract's response is `*/*`; the
    // generator picks `responseType: 'blob'` for it, so what actually arrives is a Blob.
    return (request as unknown as Observable<Blob>).pipe(
      switchMap((blob) => dataUrlOf(blob)),
      tap((dataUrl) => this.account(key, dataUrl.length)),
      catchError((error: unknown) => {
        this.cache.delete(key);
        return throwError(() => error);
      }),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
  }

  /** Record what an entry weighs, then evict from the front until both caps are met. */
  private account(id: string, size: number): void {
    const entry = this.cache.get(id);
    if (!entry) return; // cleared out from under us while the bytes were in flight
    entry.size = size;
    this.held += size;

    while (this.cache.size > this.limits.entries || this.held > this.limits.bytes) {
      const oldest = this.cache.keys().next();
      if (oldest.done || oldest.value === id) break; // never evict the one just asked for
      this.held -= this.cache.get(oldest.value)?.size ?? 0;
      this.cache.delete(oldest.value);
    }
  }
}

/**
 * The `{id}` out of a `…/media/child/{id}` link, or `null` if the URL is not one.
 *
 * The server hands out **absolute** media links, built from its own `publicUrl` — a QA hostname
 * in QA, a localhost port in the e2e run. The dashboard must not fetch them as given: the
 * generated client resolves against `BASE_PATH`, which is same-origin, and that is what carries
 * the bearer and survives a token refresh. So the link is read for its id and thrown away.
 */
export function childMediaIdOf(url: string | null | undefined): string | null {
  if (!url) return null;
  const match = /\/media\/child\/([^/?#]+)/.exec(url);
  return match?.[1] ?? null;
}

function dataUrlOf(blob: Blob): Observable<string> {
  return new Observable<string>((subscriber) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = reader.result;
      if (typeof result === 'string') subscriber.next(result);
      else subscriber.error(new Error('the page crop did not read as a data URL'));
      subscriber.complete();
    };
    reader.onerror = () => subscriber.error(reader.error ?? new Error('unreadable page crop'));
    reader.readAsDataURL(blob);
    return () => {
      if (reader.readyState === FileReader.LOADING) reader.abort();
    };
  });
}
