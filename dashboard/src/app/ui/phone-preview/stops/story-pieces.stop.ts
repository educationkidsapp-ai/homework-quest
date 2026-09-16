import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ChildButtonComponent } from '../child-button.component';
import { pieceEmoji } from '../illustrations';
import { StoryPiecesStop } from '../stop.model';
import { opened } from './answer-state';

/**
 * Six tappable cards — title, genre, characters, setting, plot, problem. Each one speaks its
 * definition and its answer, and turns mint once the child has opened it.
 */
@Component({
  selector: 'hq-stop-story-pieces',
  imports: [ChildButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="pieces">
      <div class="pieces__grid">
        @for (card of stop().cards; track card.piece) {
          <button
            type="button"
            class="piece"
            [class.piece--open]="cards.has(card.piece)"
            (click)="cards.open(card.piece)"
          >
            <span class="piece__emoji" aria-hidden="true">{{ emoji(card.piece) }}</span>
            <span class="piece__name">{{ card.piece }}</span>
            @if (cards.has(card.piece)) {
              <span class="piece__answer">{{ card.answer }}</span>
            }
          </button>
        }
      </div>
      <hq-child-button label="Done" emoji="✅" [disabled]="!allOpen()" />
    </div>
  `,
  styles: `
    @use '../child-tokens' as child;

    .pieces {
      @include child.child-stack(var(--hq-space-16));

      padding-inline: var(--hq-space-16);
    }

    .pieces__grid {
      display: grid;
      grid-template-columns: repeat(2, 1fr);
      gap: var(--hq-space-12);
      inline-size: 100%;
    }

    .piece {
      @include child.child-target;

      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: var(--hq-space-4);
      min-block-size: calc(var(--hq-child-touch) + var(--hq-space-48));
      padding: var(--hq-space-8);
      border: none;
      border-radius: var(--hq-child-radius-tile);
      background: var(--hq-child-cream);
      color: var(--hq-child-ink);
      text-align: center;
    }

    .piece--open {
      background: var(--hq-child-mint);
    }

    .piece__emoji {
      font-size: var(--hq-child-title);
      line-height: 1;
    }

    .piece__name {
      @include child.child-label;

      text-transform: capitalize;
    }

    .piece__answer {
      font-size: var(--hq-child-label);
      line-height: 1.2;
    }
  `,
})
export class StoryPiecesStopComponent {
  readonly stop = input.required<StoryPiecesStop>();

  protected readonly cards = opened(() => this.stop().id);
  protected readonly allOpen = computed(() => this.cards.ids().length === this.stop().cards.length);

  protected emoji(piece: string): string {
    return pieceEmoji(piece);
  }
}
