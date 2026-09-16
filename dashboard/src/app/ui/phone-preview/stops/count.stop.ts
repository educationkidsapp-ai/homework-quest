import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { glyphOf, tintOf } from '../illustrations';
import { TileGridComponent } from '../tile-grid.component';
import { CountStop } from '../stop.model';
import { singleAnswer } from './answer-state';

/**
 * Practice — count (`docs/design.md` §6, screen 5): groups of the same object, so the child counts
 * them two (or three, or four) at a time rather than one by one.
 */
@Component({
  selector: 'hq-stop-count',
  imports: [TileGridComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="count">
      <div class="count__groups">
        @for (size of stop().groupSizes; track $index) {
          <div
            [class]="'count__group tint-' + tint()"
            [attr.aria-label]="'group of ' + size + ' ' + stop().objectKey"
            role="group"
          >
            @for (one of repeat(size); track $index) {
              <span class="count__object" aria-hidden="true">{{ glyph() }}</span>
            }
          </div>
        }
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

    @include child.child-tints;

    .count {
      @include child.child-stack(var(--hq-space-24));
    }

    .count__groups {
      display: flex;
      flex-wrap: wrap;
      justify-content: center;
      gap: var(--hq-space-12);
    }

    .count__group {
      display: flex;
      gap: var(--hq-space-4);
      padding: var(--hq-space-8);
      border-radius: var(--hq-child-radius-tile);
    }

    .count__object {
      font-size: var(--hq-child-title);
      line-height: 1;
    }
  `,
})
export class CountStopComponent {
  readonly stop = input.required<CountStop>();

  protected readonly answer = singleAnswer(() => this.stop().id);
  protected readonly glyph = computed(() => glyphOf(this.stop().objectKey));
  protected readonly tint = computed(() => tintOf(this.stop().objectKey));
  protected readonly tiles = computed(() =>
    this.stop().options.map((option) => ({ id: option.id, label: option.label })),
  );
  protected readonly lit = computed(() => {
    const solved = this.answer.solved();
    return solved ? [solved] : [];
  });

  protected repeat(size: number): readonly number[] {
    return Array.from({ length: Math.max(0, size) }, (_, index) => index);
  }
}
