import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { SILENT_ERRORS } from '../http/error.interceptor';
import { MediaService } from './media.service';

/** One GIF header, so the data URL the service answers is predictable. */
const GIF = new Blob([new Uint8Array([0x47, 0x49, 0x46, 0x38, 0x39, 0x61])], { type: 'image/gif' });

describe('MediaService', () => {
  let media: MediaService;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: '' }],
    });
    backend = TestBed.inject(HttpTestingController);
    media = TestBed.inject(MediaService);
  });

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
});
