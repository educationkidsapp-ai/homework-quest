import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TileGridComponent } from '../tile-grid.component';
import { CompareStop } from '../stop.model';
import { singleAnswer } from './answer-state';

/**
 * Practice — compare (`docs/design.md` §6, screen 6): two number cards with the gap between them,
 * and the three sign tiles. The signs are drawn big, because the sign is the answer.
 */
@Component({
  selector: 'hq-stop-compare',
  imports: [TileGridComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cmp">
      <div class="cmp__row">
        <p class="cmp__number" [attr.aria-label]="'number ' + stop().left">{{ stop().left }}</p>
        <p class="cmp__gap" aria-label="missing sign">?</p>
        <p class="cmp__number" [attr.aria-label]="'number ' + stop().right">{{ stop().right }}</p>
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

    .cmp {
      @include child.child-stack(var(--hq-space-32));
    }

    .cmp__row {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: var(--hq-space-16);
    }

    .cmp__number {
      display: grid;
      place-items: center;
      margin: 0;
      inline-size: calc(var(--hq-child-touch) + var(--hq-space-48));
      block-size: calc(var(--hq-child-tile-block) + var(--hq-space-32));
      border-radius: var(--hq-child-radius-card);
      background: var(--hq-child-cream);
      font-size: var(--hq-child-display);
      font-weight: 700;
    }

    .cmp__gap {
      display: grid;
      place-items: center;
      margin: 0;
      inline-size: var(--hq-child-touch);
      block-size: var(--hq-child-touch);
      border-radius: var(--hq-child-radius-tile);
      background: var(--hq-child-sun);
      font-size: var(--hq-child-title);
      font-weight: 700;
    }
  `,
})
export class CompareStopComponent {
  readonly stop = input.required<CompareStop>();

  protected readonly answer = singleAnswer(() => this.stop().id);
  protected readonly tiles = computed(() =>
    this.stop().options.map((option) => ({ id: option.id, label: option.label })),
  );
  protected readonly lit = computed(() => {
    const solved = this.answer.solved();
    return solved ? [solved] : [];
  });
}
