import { Directive, ElementRef, effect, inject, input, signal } from '@angular/core';
import { MediaService } from '../../core/media/media.service';
import { BLANK_PIXEL, type MediaState } from './page-image.directive';

const SKELETON_DELAY_MS = 300;

/**
 * How far outside the viewport a picture starts loading.
 *
 * Enough that a row is usually painted by the time it is scrolled to, small enough that the twelve
 * weeks below the fold are not fetched by arriving on the screen.
 */
const NEAR_VIEWPORT = '200px';

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
 *
 * **It loads when it is looked at.** Unlike the other two, this one is drawn many at a time: the
 * archive's default window is twelve weeks and a department has a plan per grade in each of them,
 * so a six-grade department opened seventy-odd downloads of up to 5 MB by arriving on the screen.
 * There is no thumbnail variant on the server to ask for instead, so the fix is not to ask at all
 * until the row is near the viewport — `loading="lazy"` cannot help, because `src` is assigned
 * imperatively and the browser only defers a `src` it was given. {@link MediaQueue} then bounds
 * what a fast scroll can start at once.
 *
 * `eager` is for the handful that are the reason the screen was opened — the manager's "this week
 * at a glance" cards, which are above the fold and are the Sunday-morning question. A browser with
 * no `IntersectionObserver` loads everything eagerly, which is the old behaviour rather than a
 * blank screen.
 */
@Directive({ selector: 'img[hqAttachmentImage]', exportAs: 'hqAttachmentImage' })
export class AttachmentImageDirective {
  private readonly media = inject(MediaService);
  private readonly image = inject<ElementRef<HTMLImageElement>>(ElementRef).nativeElement;

  readonly hqAttachmentImage = input.required<string | null | undefined>();
  /** Load on sight rather than on scroll — for a picture that is the point of the screen. */
  readonly eager = input(false);
  /** D4: ask the server for a picture no wider than this many pixels (a thumbnail), or the original. */
  readonly downscale = input<number | null>(null);
  /**
   * `pending` until it is near the viewport, then `loading` / `ready` / `error`.
   *
   * Read through the exported directive (`#plan="hqAttachmentImage"`) by a host that draws a
   * placeholder in the element's own box until the bytes are there.
   */
  readonly state = signal<MediaState>('pending');

  constructor() {
    effect((onCleanup) => {
      const id = this.hqAttachmentImage();
      const eager = this.eager();
      const width = this.downscale();
      this.paint(BLANK_PIXEL, 'pending');
      if (!id) return;

      let timer: ReturnType<typeof setTimeout> | null = null;
      let reading: { unsubscribe(): void } | null = null;

      const load = (): void => {
        timer = setTimeout(() => this.paint(BLANK_PIXEL, 'loading'), SKELETON_DELAY_MS);
        reading = this.media.attachmentImage(id, width).subscribe({
          next: (data) => {
            if (timer !== null) clearTimeout(timer);
            this.paint(data, 'ready');
          },
          error: () => {
            if (timer !== null) clearTimeout(timer);
            this.paint(BLANK_PIXEL, 'error');
          },
        });
      };

      const watcher = eager ? null : this.watch(load);
      if (watcher === null) load();

      onCleanup(() => {
        watcher?.disconnect();
        if (timer !== null) clearTimeout(timer);
        reading?.unsubscribe();
      });
    });
  }

  /**
   * Call `load` once this element is near the viewport, or `null` when the browser cannot say —
   * in which case the caller loads at once rather than never.
   */
  private watch(load: () => void): IntersectionObserver | null {
    if (typeof IntersectionObserver !== 'function') return null;
    const observer = new IntersectionObserver(
      (entries) => {
        if (!entries.some((entry) => entry.isIntersecting)) return;
        // Once: the bytes are cached by id, but a second subscription would still queue behind the
        // gate and hold a slot a row that has none is waiting for.
        observer.disconnect();
        load();
      },
      { rootMargin: NEAR_VIEWPORT },
    );
    observer.observe(this.image);
    return observer;
  }

  private paint(source: string, state: MediaState): void {
    this.state.set(state);
    this.image.src = source;
    this.image.setAttribute('data-hq-media', state);
  }
}
