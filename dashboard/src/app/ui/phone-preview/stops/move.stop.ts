import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ChildButtonComponent } from '../child-button.component';
import { MoveStop } from '../stop.model';
import { opened } from './answer-state';

/** Three or four physical actions, one big card each — the warm-up before the thinking starts. */
@Component({
  selector: 'hq-stop-move',
  imports: [ChildButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="move">
      @for (action of stop().actions; track action.text) {
        <button
          type="button"
          class="move__action"
          [class.move__action--done]="done.has(action.text)"
          (click)="done.open(action.text)"
        >
          <span class="move__emoji" aria-hidden="true">{{ action.emoji }}</span>
          <span class="move__text">{{ action.text }}</span>
          @if (done.has(action.text)) {
            <span class="move__tick" aria-hidden="true">✓</span>
          }
        </button>
      }
      <hq-child-button label="Done" emoji="✅" [disabled]="!allDone()" />
    </div>
  `,
  styles: `
    @use '../child-tokens' as child;

    .move {
      @include child.child-stack(var(--hq-space-12));

      padding-inline: var(--hq-space-16);
    }

    .move__action {
      @include child.child-card;
      @include child.child-target;

      display: flex;
      align-items: center;
      gap: var(--hq-space-16);
    }

    .move__action--done {
      background: var(--hq-child-mint);
    }

    .move__emoji {
      font-size: var(--hq-child-display);
      line-height: 1;
    }

    .move__text {
      flex: 1;
      font-size: var(--hq-child-body);
      line-height: var(--hq-child-body-line);
    }

    .move__tick {
      font-size: var(--hq-child-title);
    }
  `,
})
export class MoveStopComponent {
  readonly stop = input.required<MoveStop>();

  protected readonly done = opened(() => this.stop().id);
  protected readonly allDone = computed(() => this.done.ids().length === this.stop().actions.length);
}
