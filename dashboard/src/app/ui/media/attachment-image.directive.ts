import { Directive, ElementRef, effect, inject, input, signal } from '@angular/core';
import { MediaService } from '../../core/media/media.service';
import { BLANK_PIXEL, type MediaState } from './page-image.directive';

const SKELETON_DELAY_MS = 300;

/**
 * `<img [hqAttachmentImage]="id">` — an uploaded attachment, which since MH2 is what a weekly plan
 * *is* (a grade, a week and a picture).
 *
 * The third of the media directives, and it exists for the reason the other two do:
 * `GET /media/attachments/{id}` requires a bearer, an `<img src>` sends none, and the `url` the DTO
 * carries is the server's own absolute `publicUrl` — so pointing an element at it answers 401 and
 * draws a broken-image glyph on every card. The bytes come through the generated client instead
 * and arrive as a `data:` URL, which is what the shipped CSP's `img-src 'self' https: data:`
 * allows.
 *
 * It takes the **id** rather than the URL: `BroadcastAttachment` carries both, and the id is the
 * one the client resolves against its own same-origin `BASE_PATH`.
 */
@Directive({ selector: 'img[hqAttachmentImage]', exportAs: 'hqAttachmentImage' })
export class AttachmentImageDirective {
  private readonly media = inject(MediaService);
  private readonly image = inject<ElementRef<HTMLImageElement>>(ElementRef).nativeElement;

  readonly hqAttachmentImage = input.required<string | null | undefined>();
  readonly state = signal<MediaState>('pending');

  constructor() {
    effect((onCleanup) => {
      const id = this.hqAttachmentImage();
      this.paint(BLANK_PIXEL, 'pending');
      if (!id) return;

      const timer = setTimeout(() => this.paint(BLANK_PIXEL, 'loading'), SKELETON_DELAY_MS);
      const reading = this.media.attachmentImage(id).subscribe({
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
