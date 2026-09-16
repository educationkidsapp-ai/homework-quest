import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ChildButtonComponent } from '../child-button.component';
import { TileGridComponent, tileSpec } from '../tile-grid.component';
import { MultiSelectStop } from '../stop.model';
import { pickAnswer } from './answer-state';

/**
 * Tap exactly `pick` tiles, then Check. Right picks stay lit and are never taken back; wrong ones
 * dim in place, and the child keeps going until all the right ones are lit.
 */
@Component({
  selector: 'hq-stop-multi-select',
  imports: [ChildButtonComponent, TileGridComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="multi">
      <p class="multi__prompt">{{ stop().prompt }}</p>
      <p class="multi__remaining">{{ remaining() }} more to tap</p>
      <hq-tile-grid
        [tiles]="tiles()"
        [dimmed]="pick.dimmed()"
        [lit]="pick.lit()"
        [selected]="pick.selected()"
        (tapped)="pick.toggle($event, stop().pick)"
      />
      <hq-child-button
        label="Check"
        emoji="👀"
        [disabled]="pick.selected().length === 0 || pick.done()"
        (pressed)="pick.check(stop().correctIds)"
      />
    </div>
  `,
  styles: `
    @use '../child-tokens' as child;

    .multi {
      @include child.child-stack(var(--hq-space-16));

      padding-inline: var(--hq-space-16);
    }

    .multi__prompt {
      @include child.child-prompt;
    }

    .multi__remaining {
      @include child.child-label;

      margin: 0;
      color: var(--hq-child-ink-soft);
    }
  `,
})
export class MultiSelectStopComponent {
  readonly stop = input.required<MultiSelectStop>();

  protected readonly pick = pickAnswer(() => this.stop().id);
  protected readonly tiles = computed(() => this.stop().options.map(tileSpec));
  protected readonly remaining = computed(() => Math.max(0, this.stop().pick - this.pick.lit().length));
}
