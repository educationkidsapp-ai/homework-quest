import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ChildButtonComponent } from '../child-button.component';
import { TileGridComponent } from '../tile-grid.component';
import { WordStop } from '../stop.model';
import { singleAnswer } from './answer-state';

/**
 * Practice — word (`docs/design.md` §6, screen 8): a Listen button and three word tiles. The word
 * itself is never written above the tiles, or there would be nothing to listen for.
 */
@Component({
  selector: 'hq-stop-word',
  imports: [ChildButtonComponent, TileGridComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="word">
      <div class="word__listen">
        <hq-child-button label="Listen" emoji="🔊" tone="lavender" />
      </div>
      <hq-tile-grid
        [tiles]="tiles()"
        [dimmed]="answer.dimmed()"
        [lit]="lit()"
        (tapped)="answer.answer($event, stop().correctOptionId)"
      />
    </div>
  `,
  styles: `
    @use '../child-tokens' as child;

    .word {
      @include child.child-stack(var(--hq-space-32));
    }

    .word__listen {
      inline-size: calc(var(--hq-child-tile-inline) + var(--hq-space-48));
      max-inline-size: 100%;
    }
  `,
})
export class WordStopComponent {
  readonly stop = input.required<WordStop>();

  protected readonly answer = singleAnswer(() => this.stop().id);
  protected readonly tiles = computed(() =>
    this.stop().options.map((option) => ({ id: option.id, label: option.label })),
  );
  protected readonly lit = computed(() => {
    const solved = this.answer.solved();
    return solved ? [solved] : [];
  });
}
