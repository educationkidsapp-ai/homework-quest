import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TileGridComponent } from '../tile-grid.component';
import { SequenceStop } from '../stop.model';
import { singleAnswer } from './answer-state';

/**
 * Practice — sequence (`docs/design.md` §6, screen 4): the number chips with a gap, then the
 * answer tiles. The gap chip is the `sun` one, because that is the thing being asked about.
 */
@Component({
  selector: 'hq-stop-sequence',
  imports: [TileGridComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="seq">
      <ol class="seq__chips">
        @for (chip of stop().chips; track $index) {
          <li
            class="seq__chip"
            [class.seq__chip--gap]="chip === null"
            [attr.aria-label]="chip === null ? 'missing number' : null"
          >
            {{ chip ?? '?' }}
          </li>
        }
      </ol>
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

    .seq {
      @include child.child-stack(var(--hq-space-32));
    }

    .seq__chips {
      display: flex;
      flex-wrap: wrap;
      justify-content: center;
      gap: var(--hq-space-8);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .seq__chip {
      display: grid;
      place-items: center;
      inline-size: var(--hq-child-touch);
      block-size: var(--hq-child-touch);
      border-radius: var(--hq-child-radius-tile);
      background: var(--hq-child-cream);
      font-size: var(--hq-child-title);
      font-weight: 700;
    }

    .seq__chip--gap {
      background: var(--hq-child-sun);
    }
  `,
})
export class SequenceStopComponent {
  readonly stop = input.required<SequenceStop>();

  protected readonly answer = singleAnswer(() => this.stop().id);
  protected readonly tiles = computed(() =>
    this.stop().options.map((option) => ({ id: option.id, label: option.label })),
  );
  protected readonly lit = computed(() => {
    const solved = this.answer.solved();
    return solved ? [solved] : [];
  });
}
