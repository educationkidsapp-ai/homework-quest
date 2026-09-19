import { Directive, ElementRef, effect, inject, input, signal } from '@angular/core';
import { MediaService, childMediaIdOf } from '../../core/media/media.service';
import { BLANK_PIXEL, type MediaState } from './page-image.directive';

const SKELETON_DELAY_MS = 300;

/**
 * `<img hqChildImage="…/media/child/{id}">` — a child's saved drawing or photographed answer.
 *
 * The twin of {@link PageImageDirective} against the other media route (N4.2). Same reasons: the
 * route is behind the bearer, so the bytes come through the generated client and arrive as a
 * `data:` URL, which is what the shipped CSP's `img-src 'self' https: data:` allows; a blank
 * pixel stands in while they are in flight, and a failure leaves the pixel rather than the
 * browser's broken-image glyph — a retell that will not load is a hole in a panel, not a red
 * band across the page.
 *
 * It takes the **URL** the DTO carries rather than an id, because that is what `workUrl` and
 * `ChildWork.url` are; the id is read out of it by `childMediaIdOf`.
 */
@Directive({ selector: 'img[hqChildImage]', exportAs: 'hqChildImage' })
export class ChildImageDirective {
  private readonly media = inject(MediaService);
  private readonly image = inject<ElementRef<HTMLImageElement>>(ElementRef).nativeElement;

  readonly hqChildImage = input.required<string | null | undefined>();
  readonly state = signal<MediaState>('pending');

  constructor() {
    effect((onCleanup) => {
      const id = childMediaIdOf(this.hqChildImage());
      this.paint(BLANK_PIXEL, 'pending');
      if (!id) return;

      const timer = setTimeout(() => this.paint(BLANK_PIXEL, 'loading'), SKELETON_DELAY_MS);
      const reading = this.media.childMedia(id).subscribe({
        next: (data) => {
          clearTimeout(timer);
          this.paint(data, 'ready');
        },
        error: () => {
          clearTimeout(timer);
          this.paint(BLANK_PIXEL, 'error');
        },
      });
      onCleanup(() => {
        clearTimeout(timer);
        reading.unsubscribe();
      });
    });
  }

  private paint(source: string, state: MediaState): void {
    this.state.set(state);
    this.image.src = source;
    this.image.setAttribute('data-hq-media', state);
  }
}
