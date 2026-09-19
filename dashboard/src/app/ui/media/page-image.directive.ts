import { Directive, ElementRef, effect, inject, input, signal } from '@angular/core';
import { MediaService } from '../../core/media/media.service';

/** A transparent 1 × 1 GIF: an `<img>` with no bytes yet, and never a broken-image icon. */
export const BLANK_PIXEL = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';

/** Skeletons are shown only after 300 ms — the same rule, and the same number, as `hq-skeleton`. */
const SKELETON_DELAY_MS = 300;

/** What the element is showing, mirrored onto it as `data-hq-media` for `styles.scss` to paint. */
export type MediaState = 'pending' | 'loading' | 'ready' | 'error';

/**
 * `<img [hqPageImage]="id">` — what `[src]` cannot be for a picture the server guards.
 *
 * `[src]` puts a URL in the DOM and the browser fetches it with no `Authorization` header;
 * `/media/pages/{id}` answers 401 and the element draws a broken-image icon
 * (`docs/reports/tailadmin-restyle.md` §4.1). This asks {@link MediaService} for the bytes
 * instead and paints them when they arrive, so the element's own classes, `alt` and box are
 * untouched at every call site.
 *
 * The four states it writes to `data-hq-media`:
 *
 * - `pending` — asked for, under 300 ms, nothing drawn. A crop that arrives quickly must not
 *   flash a placeholder first.
 * - `loading` — past 300 ms: the shimmer, in the element's own box.
 * - `ready` — the picture.
 * - `error` — a neutral tile. The `alt` stays on the element, so a reader still says what is
 *   missing rather than reading nothing at all.
 *
 * `src` and the attribute are written together, imperatively, because they are one state and
 * must never disagree — and because the element must never hold the protected URL at all.
 */
@Directive({ selector: 'img[hqPageImage]', exportAs: 'hqPageImage' })
export class PageImageDirective {
  private readonly media = inject(MediaService);
  private readonly image = inject<ElementRef<HTMLImageElement>>(ElementRef).nativeElement;

  /** The id of the crop — `PageImage.id`, the `{id}` of `/media/pages/{id}`. */
  readonly hqPageImage = input.required<string | null | undefined>();

  /** Readable by a host that wants to say something about the picture it asked for. */
  readonly state = signal<MediaState>('pending');

  constructor() {
    effect((onCleanup) => {
      const id = this.hqPageImage();
      this.paint(BLANK_PIXEL, 'pending');
      if (!id) return;

      const timer = setTimeout(() => this.paint(BLANK_PIXEL, 'loading'), SKELETON_DELAY_MS);
      const reading = this.media.pageImage(id).subscribe({
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
