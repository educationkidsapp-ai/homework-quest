import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { PipComponent } from './pip.component';
import { Theme } from './stop.model';

/** An island is today's, already done, or still asleep (`docs/design.md` §6, screen 2). */
export type IslandState = 'today' | 'done' | 'locked';

/** One island on the map — one day's lesson. */
export interface MapIsland {
  readonly id: string;
  readonly label: string;
  readonly state: IslandState;
  /** Stars earned, for an island that is done. */
  readonly stars?: number;
}

/**
 * The world map: sea, one island per day, today's island glowing, the done ones showing their
 * stars and the locked ones asleep in `night` with Pip curled up on them.
 *
 * With no islands it is the empty state the app shows when no grown-up has added a lesson yet:
 * Pip asleep on a raft, and one sentence about it.
 */
@Component({
  selector: 'hq-world-map-preview',
  imports: [PipComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="map">
      @if (theme(); as pot) {
        <header class="map__header">
          <span class="map__pot" aria-hidden="true">{{ pot.potEmoji }}</span>
          <span class="map__dish">{{ pot.dishName }}</span>
        </header>
      }

      <p class="map__greeting">Hi {{ childName() }}!</p>

      @if (islands().length === 0) {
        <div class="map__empty">
          <div class="map__raft">
            <hq-pip pose="sleeping" size="md" label="Pip is asleep on a raft" />
          </div>
          <p class="map__empty-text">{{ emptyMessage() }}</p>
        </div>
      } @else {
        <ul class="map__islands">
          @for (island of islands(); track island.id) {
            <li class="map__slot">
              <button
                type="button"
                [class]="'island island--' + island.state"
                [attr.data-hq-island]="island.state"
                [attr.aria-current]="island.state === 'today' ? 'step' : null"
                [attr.aria-label]="name(island)"
              >
                <span class="island__label">{{ island.label }}</span>

                @switch (island.state) {
                  @case ('done') {
                    <span class="island__stars" aria-hidden="true">
                      @for (star of starsOf(island); track $index) {
                        <span class="island__star">★</span>
                      }
                    </span>
                  }
                  @case ('locked') {
                    <hq-pip pose="sleeping" size="sm" />
                  }
                  @default {
                    <hq-pip pose="waving" size="sm" />
                  }
                }
              </button>
            </li>
          }
        </ul>
      }
    </div>
  `,
  styles: `
    @use 'mixins' as m;
    @use './child-tokens' as child;

    :host {
      display: block;
      min-block-size: 100%;
      background: var(--hq-child-sea);
    }

    .map {
      @include child.child-stack(var(--hq-space-16));

      padding: var(--hq-space-16);
    }

    .map__header {
      display: flex;
      align-items: center;
      gap: var(--hq-space-8);
      inline-size: 100%;
      padding: var(--hq-space-8) var(--hq-space-12);
      border-radius: var(--hq-child-radius-chip);
      background: var(--hq-child-cream);
    }

    .map__pot {
      font-size: var(--hq-child-title);
      line-height: 1;
    }

    .map__dish {
      @include child.child-label;
    }

    .map__greeting {
      @include child.child-display;

      margin: 0;
      color: var(--hq-child-ink);
    }

    .map__empty {
      @include child.child-stack(var(--hq-space-16));

      padding-block: var(--hq-space-32);
    }

    // The raft: a plank of sand under a sleeping Pip.
    .map__raft {
      display: grid;
      justify-items: center;
      padding-block-end: var(--hq-space-12);
      border-block-end: var(--hq-space-12) solid var(--hq-child-sand);
      border-radius: var(--hq-child-radius-tile);
    }

    .map__empty-text {
      margin: 0;
      max-inline-size: var(--hq-child-illus-xl);
      color: var(--hq-child-ink);
      text-align: center;
    }

    .map__islands {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-24);
      inline-size: 100%;
      margin: 0;
      padding: 0;
      list-style: none;
    }

    // The islands zig-zag down the sea, as they do on the app's map.
    .map__slot:nth-child(even) {
      align-self: flex-end;
    }

    .island {
      @include child.child-target;

      display: flex;
      flex-direction: column;
      align-items: center;
      gap: var(--hq-space-4);
      padding: var(--hq-space-12) var(--hq-space-16);
      border: none;
      border-radius: var(--hq-child-radius-card);
      background: var(--hq-child-sand);
      color: var(--hq-child-ink);
      @include m.motion-safe('box-shadow, background-color', var(--hq-motion-base));
    }

    .island--today {
      @include child.child-animation(hq-island-glow 1600ms var(--hq-motion-ease) infinite alternate);

      box-shadow: 0 0 0 var(--hq-child-rule) var(--hq-child-sun);
    }

    .island--done {
      background: var(--hq-child-mint);
    }

    .island--locked {
      background: var(--hq-child-night);
      color: var(--hq-color-on-ink);
    }

    .island__label {
      @include child.child-label;
    }

    .island__star {
      color: var(--hq-child-sun-deep);
      font-size: var(--hq-child-label);
    }

    @keyframes hq-island-glow {
      from {
        box-shadow: 0 0 0 var(--hq-child-rule) var(--hq-child-sun);
      }

      to {
        box-shadow: 0 0 0 var(--hq-space-12) var(--hq-child-sun);
      }
    }
  `,
})
export class WorldMapPreviewComponent {
  readonly islands = input<readonly MapIsland[]>([]);
  readonly theme = input<Theme | null>(null);
  readonly childName = input<string>('Pip');
  /** The app's own empty-state sentence; the caller may word it differently. */
  readonly emptyMessage = input<string>('No quest today yet. Ask a grown-up to add today’s lesson.');

  protected starsOf(island: MapIsland): readonly number[] {
    return Array.from({ length: Math.max(0, island.stars ?? 0) }, (_, index) => index);
  }

  protected name(island: MapIsland): string {
    switch (island.state) {
      case 'today':
        return island.label + ', today';
      case 'done':
        return island.label + ', done, ' + String(island.stars ?? 0) + ' stars';
      case 'locked':
        return island.label + ', still asleep';
    }
  }
}
