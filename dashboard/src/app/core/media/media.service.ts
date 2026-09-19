import { DestroyRef, Injectable, inject } from '@angular/core';
import { Observable, throwError } from 'rxjs';
import { catchError, shareReplay, switchMap } from 'rxjs/operators';
import { MediaApi } from '../../api';
import { silentErrors } from '../http/error.interceptor';

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
 * is the bug this service exists to fix. A `data:` URL costs about a third over the bytes and
 * needs no revoking; the cache below is what bounds how many are held.
 *
 * The cache is keyed by image id, so the several components inside one phone preview asking for
 * the same crop make one request and stepping back to a stop repaints from memory. Entries go
 * when the injector that owns the service is destroyed, and the oldest is evicted past
 * {@link CACHE_LIMIT} so a long editing session cannot grow without end. A failure is not
 * cached: the next mount asks again.
 */
@Injectable({ providedIn: 'root' })
export class MediaService {
  private readonly media = inject(MediaApi);
  private readonly cache = new Map<string, Observable<string>>();

  constructor() {
    inject(DestroyRef).onDestroy(() => this.cache.clear());
  }

  /** The page crop as a `data:` URL. Multicast: repeat callers share one request. */
  pageImage(id: string): Observable<string> {
    const cached = this.cache.get(id);
    if (cached) return cached;

    // The generated signature says `string` because the contract's response is `*/*`; the
    // generator picks `responseType: 'blob'` for it, so what actually arrives is a Blob.
    const bytes = (
      this.media.pageImage(id, 'body', false, { context: silentErrors() }) as unknown as Observable<Blob>
    ).pipe(
      switchMap((blob) => dataUrlOf(blob)),
      catchError((error: unknown) => {
        this.cache.delete(id);
        return throwError(() => error);
      }),
      shareReplay({ bufferSize: 1, refCount: false }),
    );

    if (this.cache.size >= CACHE_LIMIT) {
      const oldest = this.cache.keys().next();
      if (!oldest.done) this.cache.delete(oldest.value);
    }
    this.cache.set(id, bytes);
    return bytes;
  }
}

/** Page crops are a few hundred kB each; forty-eight of them is a generous lesson. */
const CACHE_LIMIT = 48;

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
