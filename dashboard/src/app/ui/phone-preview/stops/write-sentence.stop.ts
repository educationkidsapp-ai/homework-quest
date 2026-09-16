import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ChildButtonComponent } from '../child-button.component';
import { ChildCardComponent } from '../child-card.component';
import { TileGridComponent } from '../tile-grid.component';
import { WriteSentenceStop } from '../stop.model';
import { singleAnswer } from './answer-state';

/**
 * A sentence with a gap. At Level 2 the child taps the word that fits; at Level 3 (`free`) the
 * same frame is traced by hand, which is why this one type is sometimes single and sometimes open.
 */
@Component({
  selector: 'hq-stop-write-sentence',
  imports: [ChildButtonComponent, ChildCardComponent, TileGridComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="write">
      <hq-child-card>
        <p class="write__frame">{{ stop().frame }}</p>
      </hq-child-card>

      @if (free()) {
        <div class="write__lane">
          <p class="write__answer" [attr.aria-label]="'trace ' + stop().answer">
            {{ stop().answer }}
          </p>
        </div>
        <hq-child-button label="Done" emoji="✅" />
      } @else {
        <hq-tile-grid
          [tiles]="tiles()"
          [dimmed]="answer.dimmed()"
          [lit]="lit()"
          (tapped)="answer.answer($event, stop().answer)"
        />
      }
    </div>
  `,
  styles: `
    @use '../child-tokens' as child;

    .write {
      @include child.child-stack(var(--hq-space-24));

      padding-inline: var(--hq-space-16);
    }

    .write__frame {
      @include child.child-title;

      margin: 0;
      text-align: center;
    }

    .write__lane {
      display: grid;
      place-items: center;
      inline-size: 100%;
      aspect-ratio: 3 / 2;
      border: var(--hq-child-rule) dashed var(--hq-child-sea);
      border-radius: var(--hq-child-radius-card);
      background: var(--hq-child-cream);
    }

    .write__answer {
      margin: 0;
      font-size: var(--hq-child-pip-sm);
      font-weight: 700;
      line-height: 1;
      color: transparent;
      -webkit-text-stroke: var(--hq-size-rule) var(--hq-child-ink-soft);
    }
  `,
})
export class WriteSentenceStopComponent {
  readonly stop = input.required<WriteSentenceStop>();

  protected readonly answer = singleAnswer(() => this.stop().id);
  protected readonly free = computed(() => this.stop().free === true);
  // The options are plain words; each is its own id, exactly as the app treats them.
  protected readonly tiles = computed(() =>
    (this.stop().options ?? []).map((word) => ({ id: word, label: word })),
  );
  protected readonly lit = computed(() => {
    const solved = this.answer.solved();
    return solved ? [solved] : [];
  });
}
