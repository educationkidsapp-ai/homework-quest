import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ChildButtonComponent } from '../child-button.component';
import { TileGridComponent, tileSpec } from '../tile-grid.component';
import { SelectAllStop } from '../stop.model';
import { pickAnswer } from './answer-state';

/**
 * Tap every tile that fits — no count is given, which is the whole difficulty. Otherwise it plays
 * exactly like multiSelect: Check, right ones light, wrong ones dim and stay.
 */
@Component({
  selector: 'hq-stop-select-all',
  imports: [ChildButtonComponent, TileGridComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="all">
      <p class="all__prompt">{{ stop().prompt }}</p>
      <hq-tile-grid
        [tiles]="tiles()"
        [dimmed]="pick.dimmed()"
        [lit]="pick.lit()"
        [selected]="pick.selected()"
        (tapped)="pick.toggle($event, null)"
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

    .all {
      @include child.child-stack(var(--hq-space-16));

      padding-inline: var(--hq-space-16);
    }

    .all__prompt {
      @include child.child-prompt;
    }
  `,
})
export class SelectAllStopComponent {
  readonly stop = input.required<SelectAllStop>();

  protected readonly pick = pickAnswer(() => this.stop().id);
  protected readonly tiles = computed(() => this.stop().options.map(tileSpec));
}
