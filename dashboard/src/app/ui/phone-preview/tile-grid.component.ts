import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { PageImageDirective } from '../media/page-image.directive';
import { IllustrationComponent } from './illustration.component';
import { PreviewImages } from './preview-images';
import { Tile } from './stop.model';

/** One answer tile: a word, a picture, or both. */
export interface TileSpec {
  readonly id: string;
  readonly label?: string | undefined;
  readonly picture?: string | undefined;
  readonly imageId?: string | undefined;
}

/** A `Tile` from the contract as this grid wants it. */
export function tileSpec(tile: Tile): TileSpec {
  return { id: tile.id, label: tile.label, picture: tile.illustrationKey, imageId: tile.pageImageId };
}

interface RenderedTile extends TileSpec {
  readonly dimmed: boolean;
  readonly lit: boolean;
  readonly selected: boolean;
  /** The page-image id to draw, when the caller supplied that picture. */
  readonly pictureId: string | null;
  readonly long: boolean;
}

/**
 * The 2-up grid of 176 × 100 answer tiles with 12 px gaps (`docs/design.md` §4).
 *
 * `dimmed` is the wrong-answer state: the tile stays where it was, greyed and disabled, because a
 * child who taps a vanished tile has learnt nothing (§7). `lit` is a tile that turned out right,
 * `selected` one the child has picked but not yet checked.
 */
@Component({
  selector: 'hq-tile-grid',
  imports: [IllustrationComponent, PageImageDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="grid" role="group">
      @for (tile of rendered(); track tile.id) {
        <button
          type="button"
          class="tile"
          [class.tile--dimmed]="tile.dimmed"
          [class.tile--lit]="tile.lit"
          [class.tile--selected]="tile.selected"
          [class.tile--long]="tile.long"
          [attr.aria-pressed]="tile.selected"
          [attr.data-hq-tile]="tile.id"
          [disabled]="tile.dimmed"
          (click)="tapped.emit(tile.id)"
        >
          @if (tile.pictureId; as pictureId) {
            <img class="tile__image" [hqPageImage]="pictureId" [alt]="tile.label ?? ''" />
          } @else if (tile.picture; as key) {
            <hq-illustration [key]="key" [size]="tile.label ? 'sm' : 'md'" />
          }
          @if (tile.label; as label) {
            <span class="tile__label">{{ label }}</span>
          }
        </button>
      }
    </div>
  `,
  styles: `
    @use 'mixins' as m;
    @use './child-tokens' as child;

    :host {
      display: block;
      inline-size: 100%;
    }

    .grid {
      display: flex;
      flex-wrap: wrap;
      justify-content: center;
      gap: var(--hq-space-12);
    }

    .tile {
      @include child.child-target;

      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: var(--hq-space-4);
      inline-size: var(--hq-child-tile-inline);
      block-size: var(--hq-child-tile-block);
      padding: var(--hq-space-8);
      border: none;
      border-radius: var(--hq-child-radius-tile);
      background: var(--hq-child-cream);
      color: var(--hq-child-ink);
      font-size: var(--hq-child-display);
      font-weight: 700;
      line-height: 1.1;
      @include m.motion-safe('background-color, opacity');
    }

    .tile--long {
      font-size: var(--hq-child-body);
    }

    .tile--lit {
      background: var(--hq-child-mint);
    }

    .tile--selected {
      background: var(--hq-child-sun);
    }

    .tile--dimmed {
      @include child.child-dimmed;
    }

    .tile__image {
      max-inline-size: 100%;
      max-block-size: 60%;
      object-fit: contain;
    }

    .tile__label {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      max-inline-size: 100%;
    }
  `,
})
export class TileGridComponent {
  private readonly pictures = inject(PreviewImages, { optional: true });

  readonly tiles = input.required<readonly TileSpec[]>();
  readonly dimmed = input<readonly string[]>([]);
  readonly lit = input<readonly string[]>([]);
  readonly selected = input<readonly string[]>([]);

  readonly tapped = output<string>();

  protected readonly rendered = computed<readonly RenderedTile[]>(() =>
    this.tiles().map((tile) => ({
      ...tile,
      dimmed: this.dimmed().includes(tile.id),
      lit: this.lit().includes(tile.id),
      selected: this.selected().includes(tile.id),
      pictureId: this.pictures?.has(tile.imageId) ? (tile.imageId ?? null) : null,
      long: (tile.label?.length ?? 0) > 8,
    })),
  );
}
