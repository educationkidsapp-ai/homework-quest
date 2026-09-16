import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ChildCardComponent } from '../child-card.component';
import { TileGridComponent, TileSpec } from '../tile-grid.component';
import { TrueFalseStop } from '../stop.model';
import { singleAnswer } from './answer-state';

/** The ids the contract gives the two answers (`Stop.TrueFalse.TRUE_ID` / `FALSE_ID`). */
const TRUE_ID = 'true';
const FALSE_ID = 'false';

const TILES: readonly TileSpec[] = [
  { id: TRUE_ID, label: '👍 True' },
  { id: FALSE_ID, label: '👎 False' },
];

/** The statement on a card, thumbs-up and thumbs-down below it. */
@Component({
  selector: 'hq-stop-true-false',
  imports: [ChildCardComponent, TileGridComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="tf">
      <hq-child-card>
        <p class="tf__statement">{{ stop().statement }}</p>
      </hq-child-card>
      <hq-tile-grid
        [tiles]="tiles"
        [dimmed]="answer.dimmed()"
        [lit]="lit()"
        (tapped)="answer.answer($event, correctId())"
      />
    </div>
  `,
  styles: `
    @use '../child-tokens' as child;

    .tf {
      @include child.child-stack(var(--hq-space-24));

      padding-inline: var(--hq-space-16);
    }

    .tf__statement {
      @include child.child-title;

      margin: 0;
      text-align: center;
    }
  `,
})
export class TrueFalseStopComponent {
  readonly stop = input.required<TrueFalseStop>();

  protected readonly tiles = TILES;
  protected readonly answer = singleAnswer(() => this.stop().id);
  protected readonly correctId = computed(() => (this.stop().answer ? TRUE_ID : FALSE_ID));
  protected readonly lit = computed(() => {
    const solved = this.answer.solved();
    return solved ? [solved] : [];
  });
}
