import { Injectable, signal } from '@angular/core';
import { PreviewImage } from './stop.model';

/**
 * Resolves a stop's `imageId` / `pageImageId` to a URL, for every component inside one preview.
 *
 * The app does the same with a `LocalStopImageLoader` composition local: the player resolves an id
 * against the published lesson, the editor against `/media/pages/{id}`. Here it is a service the
 * entry components provide, so a stop component deep in the tree needs no image input of its own —
 * and a spec that renders one stop on its own simply gets no pictures and falls back to the glyph,
 * exactly like the app's `NoStopImages`.
 */
@Injectable()
export class PreviewImages {
  private readonly images = signal<readonly PreviewImage[]>([]);

  set(images: readonly PreviewImage[]): void {
    this.images.set(images);
  }

  /**
   * Whether the caller supplied that picture at all.
   *
   * The bytes are not this service's business: an `<img>` cannot fetch `/media/pages/{id}`
   * itself (it carries no bearer), so `hqPageImage` reads them by id through `MediaService`
   * and `PageImage.url` is never put in the DOM. What a component still needs from here is the
   * yes-or-no — a picture, or the app's own glyph in its place.
   */
  has(id: string | null | undefined): boolean {
    if (!id) return false;
    return this.images().some((image) => image.id === id);
  }

  /** The alternative text for an image id, if the caller gave one. */
  describe(id: string | null | undefined): string | null {
    if (!id) return null;
    return this.images().find((image) => image.id === id)?.description ?? null;
  }
}
