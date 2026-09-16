import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TileGridComponent, tileSpec } from '../tile-grid.component';
import { ChoiceStop } from '../stop.model';
import { singleAnswer } from './answer-state';

/** One question, one right tile (`docs/design.md` §6, the practice screens). */
@Component({
  selector: 'hq-stop-choice',
  imports: [TileGridComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="choice">
      <p class="choice__question">{{ stop().question }}</p>
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

    .choice {
      @include child.child-stack(var(--hq-space-24));
    }

    .choice__question {
      @include child.child-prompt;
    }
  `,
})
export class ChoiceStopComponent {
  readonly stop = input.required<ChoiceStop>();

  protected readonly answer = singleAnswer(() => this.stop().id);
  protected readonly tiles = computed(() => this.stop().options.map(tileSpec));
  protected readonly lit = computed(() => {
    const solved = this.answer.solved();
    return solved ? [solved] : [];
  });
}
