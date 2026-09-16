import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { PreviewImages } from './preview-images';

/**
 * The picture a stop carries above its content (`StopPicture` in `shared-ui`): a rounded 3:2 card
 * on the cream ground, letterboxing whatever the page image turns out to be.
 *
 * With no picture for the id — a spec, or an editor that has not loaded the page images yet — it
 * shows the app's own placeholder glyph rather than an empty hole.
 */
@Component({
  selector: 'hq-stop-picture',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <figure class="picture">
      @if (url(); as source) {
        <img class="picture__img" [src]="source" [alt]="description() ?? ''" />
      } @else {
        <span class="picture__placeholder" aria-hidden="true">🖼️</span>
      }
    </figure>
  `,
  styles: `
    :host {
      display: block;
      inline-size: 100%;
      padding-inline: var(--hq-space-16);
    }

    .picture {
      display: grid;
      place-items: center;
      margin: 0;
      aspect-ratio: 3 / 2;
      overflow: hidden;
      background: var(--hq-child-cream);
      border-radius: var(--hq-child-radius-card);
    }

    .picture__img {
      inline-size: 100%;
      block-size: 100%;
      object-fit: contain;
    }

    .picture__placeholder {
      font-size: var(--hq-child-display);
      line-height: 1;
    }
  `,
})
export class StopPictureComponent {
  private readonly pictures = inject(PreviewImages, { optional: true });

  readonly imageId = input.required<string>();
  /** Overrides the description the caller gave with the image. */
  readonly alt = input<string | null>(null);

  protected readonly url = computed(() => this.pictures?.url(this.imageId()) ?? null);
  protected readonly description = computed(
    () => this.alt() ?? this.pictures?.describe(this.imageId()) ?? null,
  );
}
