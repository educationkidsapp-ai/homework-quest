import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ReadAloudComponent } from '../read-aloud.component';
import { TileGridComponent } from '../tile-grid.component';
import { ReadTapStop } from '../stop.model';
import { singleAnswer } from './answer-state';

/**
 * Practice — readTap (`docs/design.md` §6, screen 10): the word to read, a speaker beside it in
 * case the child gets stuck, and three picture cards.
 */
@Component({
  selector: 'hq-stop-read-tap',
  imports: [ReadAloudComponent, TileGridComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="rt">
      <div class="rt__row">
        <p class="rt__word">{{ stop().word }}</p>
        <hq-read-aloud [label]="'Say ' + stop().word" />
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

    .rt {
      @include child.child-stack(var(--hq-space-32));
    }

    .rt__row {
      display: flex;
      align-items: center;
      gap: var(--hq-space-16);
    }

    .rt__word {
      @include child.child-display;

      margin: 0;
      padding: var(--hq-space-16) var(--hq-space-24);
      border-radius: var(--hq-child-radius-card);
      background: var(--hq-child-cream);
    }
  `,
})
export class ReadTapStopComponent {
  readonly stop = input.required<ReadTapStop>();

  protected readonly answer = singleAnswer(() => this.stop().id);
  protected readonly tiles = computed(() =>
    this.stop().options.map((option) => ({ id: option.id, picture: option.illustrationKey })),
  );
  protected readonly lit = computed(() => {
    const solved = this.answer.solved();
    return solved ? [solved] : [];
  });
}
