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

  /** The URL for an image id, or `null` when the caller did not supply that picture. */
  url(id: string | null | undefined): string | null {
    if (!id) return null;
    return this.images().find((image) => image.id === id)?.url ?? null;
  }

  /** The alternative text for an image id, if the caller gave one. */
  describe(id: string | null | undefined): string | null {
    if (!id) return null;
    return this.images().find((image) => image.id === id)?.description ?? null;
  }
}
