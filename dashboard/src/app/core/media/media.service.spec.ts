import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { AuthService } from '../auth/auth.service';
import { SessionStore } from '../auth/session.store';
import { SILENT_ERRORS } from '../http/error.interceptor';
import { MEDIA_CACHE_LIMITS, MediaService, type MediaCacheLimits } from './media.service';

/** One GIF header, so the data URL the service answers is predictable. */
const GIF = new Blob([new Uint8Array([0x47, 0x49, 0x46, 0x38, 0x39, 0x61])], { type: 'image/gif' });

function setUp(limits?: MediaCacheLimits) {
  // The two cap tests re-build the bed with their own limits, after `beforeEach` made one.
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: BASE_PATH, useValue: '' },
      ...(limits ? [{ provide: MEDIA_CACHE_LIMITS, useValue: limits }] : []),
    ],
  });
  return {
    media: TestBed.inject(MediaService),
    backend: TestBed.inject(HttpTestingController),
  };
}

/** Ask for a crop and let its bytes arrive, so the entry is weighed and the caps apply. */
async function fetched(media: MediaService, backend: HttpTestingController, id: string): Promise<string> {
  const read = firstValueFrom(media.pageImage(id));
  backend.expectOne(`/media/pages/${id}`).flush(GIF);
  return read;
}

describe('MediaService', () => {
  let media: MediaService;
  let backend: HttpTestingController;

  beforeEach(() => ({ media, backend } = setUp()));

  it('reads the crop as a blob through the generated client, so the bearer is attached', async () => {
    const read = firstValueFrom(media.pageImage('page-a'));

    const request = backend.expectOne('/media/pages/page-a');
    expect(request.request.method).toBe('GET');
    expect(request.request.responseType).toBe('blob');
    // A missing crop is a hole in a preview, not a red band across the page.
    expect(request.request.context.get(SILENT_ERRORS)).toBe(true);
    request.flush(GIF);

    expect(await read).toMatch(/^data:image\/gif;base64,/);
  });

  it('asks once per id however many components want the same picture', async () => {
    const first = firstValueFrom(media.pageImage('page-a'));
    const second = firstValueFrom(media.pageImage('page-a'));
    backend.expectOne('/media/pages/page-a').flush(GIF);

    expect(await first).toBe(await second);

    // And a third caller, after the bytes have arrived, is served from memory.
    expect(await firstValueFrom(media.pageImage('page-a'))).toBe(await first);
    backend.verify();
  });

  it('keys the cache by id: a second picture is a second request', () => {
    media.pageImage('page-a').subscribe();
    media.pageImage('page-b').subscribe();
    backend.expectOne('/media/pages/page-a').flush(GIF);
    backend.expectOne('/media/pages/page-b').flush(GIF);
    backend.verify();
  });

  it('does not cache a failure — the next mount tries again', async () => {
    const refused = firstValueFrom(media.pageImage('page-a'));
    backend
      .expectOne('/media/pages/page-a')
      .flush(new Blob(['no']), { status: 401, statusText: 'Unauthorized' });
    await expect(refused).rejects.toBeTruthy();

    media.pageImage('page-a').subscribe();
    backend.expectOne('/media/pages/page-a').flush(GIF);
    backend.verify();
  });

  it('counts what it is holding, and stops holding it when told to forget', async () => {
    await fetched(media, backend, 'page-a');
    expect(media.bytesHeld).toBeGreaterThan(0);

    media.clear();

    expect(media.bytesHeld).toBe(0);
    await fetched(media, backend, 'page-a'); // asked for again: the cache really is empty
    backend.verify();
  });

  it('empties itself when a lesson is closed or another one is opened', async () => {
    media.scopeTo('lesson-1');
    await fetched(media, backend, 'page-a');

    media.scopeTo('lesson-1'); // the same lesson is not a change
    expect(media.bytesHeld).toBeGreaterThan(0);

    media.scopeTo('lesson-2');
    expect(media.bytesHeld).toBe(0);

    await fetched(media, backend, 'page-a');
    media.scopeTo(null); // the editor was left
    expect(media.bytesHeld).toBe(0);
    backend.verify();
  });

  it("signing out forgets one teacher's pages before the next one's tab", async () => {
    // The root injector is never destroyed in a single-page app, so `forget()` is the only
    // moment a session reliably ends — and these are scans of someone's classroom material.
    const auth = TestBed.inject(AuthService);
    TestBed.inject(SessionStore).set({ token: 'a', refreshToken: 'r' });
    await fetched(media, backend, 'page-a');
    expect(media.bytesHeld).toBeGreaterThan(0);

    auth.forget();

    expect(media.bytesHeld).toBe(0);
    await fetched(media, backend, 'page-a');
    backend.verify();
  });

  it('evicts the least recently used once the entry cap is reached', async () => {
    ({ media, backend } = setUp({ entries: 2, bytes: 1_000_000 }));

    await fetched(media, backend, 'page-a');
    await fetched(media, backend, 'page-b');
    // Touching `page-a` puts `page-b` at the front, so `page-b` is what a third entry pushes out.
    await firstValueFrom(media.pageImage('page-a'));
    await fetched(media, backend, 'page-c');

    await firstValueFrom(media.pageImage('page-a')); // still held
    await firstValueFrom(media.pageImage('page-c')); // still held
    backend.verify();

    await fetched(media, backend, 'page-b'); // gone, and asked for again
  });

  it('evicts on bytes too, because a crop is a scan and not a thumbnail', async () => {
    const one = 'data:image/gif;base64,R0lGODlh'.length;
    ({ media, backend } = setUp({ entries: 99, bytes: one }));

    await fetched(media, backend, 'page-a');
    await fetched(media, backend, 'page-b');

    // Two crops are over the cap, so the older one went and the one just asked for stayed.
    expect(media.bytesHeld).toBeLessThanOrEqual(one);
    await fetched(media, backend, 'page-a');
    backend.verify();
  });
});
