import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { render, screen } from '@testing-library/angular';
import { describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { BLANK_PIXEL, PageImageDirective } from './page-image.directive';

const GIF = new Blob([new Uint8Array([0x47, 0x49, 0x46, 0x38, 0x39, 0x61])], { type: 'image/gif' });

@Component({
  selector: 'hq-host',
  imports: [PageImageDirective],
  template: `<img [hqPageImage]="id()" alt="Page 1" />`,
})
class HostComponent {
  readonly id = signal<string | null>('page-a');
}

describe('hqPageImage', () => {
  let backend: HttpTestingController;

  async function mount() {
    const view = await render(HostComponent, {
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: '' }],
    });
    backend = view.fixture.debugElement.injector.get(HttpTestingController);
    return { view, image: screen.getByAltText('Page 1') };
  }

  it('never puts the protected URL in the DOM, and paints the bytes when they arrive', async () => {
    const { image } = await mount();

    // Before the answer the element is a transparent pixel — no 401, no broken-image icon.
    expect(image.getAttribute('src')).toBe(BLANK_PIXEL);
    expect(image.getAttribute('data-hq-media')).toBe('pending');

    const read = backend.expectOne('/media/pages/page-a');
    expect(read.request.method).toBe('GET');
    expect(read.request.responseType).toBe('blob');
    read.flush(GIF);
    await waitForState(image, 'ready');

    expect(image.getAttribute('src')).toMatch(/^data:image\/gif;base64,/);
    // The alt is the directive's business to leave alone.
    expect(image.getAttribute('alt')).toBe('Page 1');
  });

  it('shows a neutral tile, keeping the alt, when the read is refused', async () => {
    const { image } = await mount();

    backend
      .expectOne('/media/pages/page-a')
      .flush(new Blob(['no']), { status: 401, statusText: 'Unauthorized' });
    await waitForState(image, 'error');

    expect(image.getAttribute('src')).toBe(BLANK_PIXEL);
    expect(image.getAttribute('alt')).toBe('Page 1');
  });

  it('asks again when the id changes, and drops the old picture first', async () => {
    const { view, image } = await mount();
    backend.expectOne('/media/pages/page-a').flush(GIF);
    await waitForState(image, 'ready');

    view.fixture.componentInstance.id.set('page-b');
    view.fixture.detectChanges();

    expect(image.getAttribute('data-hq-media')).toBe('pending');
    backend.expectOne('/media/pages/page-b').flush(GIF);
    await waitForState(image, 'ready');
  });
});

/** The bytes become a data URL through `FileReader`, which resolves on a later task. */
async function waitForState(image: HTMLElement, state: string): Promise<void> {
  for (let attempt = 0; attempt < 60; attempt++) {
    if (image.getAttribute('data-hq-media') === state) return;
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  throw new Error(`stayed at ${image.getAttribute('data-hq-media')}, wanted ${state}`);
}
