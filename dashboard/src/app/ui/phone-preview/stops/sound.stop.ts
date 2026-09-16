import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { IllustrationComponent } from '../illustration.component';
import { TileGridComponent } from '../tile-grid.component';
import { SoundStop } from '../stop.model';
import { singleAnswer } from './answer-state';

/**
 * Practice — sound (`docs/design.md` §6, screen 7): the picture big at the top, two or three
 * sound tiles below it.
 */
@Component({
  selector: 'hq-stop-sound',
  imports: [IllustrationComponent, TileGridComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="sound">
      <hq-illustration [key]="stop().illustrationKey" size="xl" />
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

    .sound {
      @include child.child-stack(var(--hq-space-32));
    }
  `,
})
export class SoundStopComponent {
  readonly stop = input.required<SoundStop>();

  protected readonly answer = singleAnswer(() => this.stop().id);
  protected readonly tiles = computed(() =>
    this.stop().options.map((option) => ({ id: option.id, label: option.label })),
  );
  protected readonly lit = computed(() => {
    const solved = this.answer.solved();
    return solved ? [solved] : [];
  });
}
