import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ChildButtonComponent } from '../child-button.component';
import { ChildCardComponent } from '../child-card.component';
import { ExplainStop } from '../stop.model';
import { cursor } from './answer-state';

/**
 * The lesson intro (`docs/design.md` §6, screen 3): one spoken sentence of explanation, then the
 * worked examples revealed one at a time, then "Let's go".
 */
@Component({
  selector: 'hq-stop-explain',
  imports: [ChildButtonComponent, ChildCardComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="explain">
      <p class="explain__lead">{{ stop().explanation }}</p>

      @for (example of shown(); track example.prompt) {
        <hq-child-card>
          <div class="explain__example">
            <p class="explain__prompt">{{ example.prompt }}</p>
            <ul class="explain__steps">
              @for (step of example.steps; track $index) {
                <li>{{ step }}</li>
              }
            </ul>
            <p class="explain__answer">= {{ example.answer }}</p>
          </div>
        </hq-child-card>
      }

      @if (revealed() < stop().workedExamples.length - 1) {
        <hq-child-button
          label="Show me another"
          emoji="👀"
          tone="lavender"
          (pressed)="revealed.set(revealed() + 1)"
        />
      } @else {
        <hq-child-button label="Let's go" emoji="✅" />
      }
    </div>
  `,
  styles: `
    @use '../child-tokens' as child;

    .explain {
      @include child.child-stack(var(--hq-space-12));

      padding-inline: var(--hq-space-16);
    }

    .explain__lead {
      @include child.child-prompt;
    }

    .explain__example {
      @include child.child-stack(var(--hq-space-4));

      text-align: center;
    }

    .explain__prompt {
      @include child.child-title;

      margin: 0;
    }

    .explain__steps {
      margin: 0;
      padding: 0;
      list-style: none;
      color: var(--hq-child-ink-soft);
      font-size: var(--hq-child-label);
      line-height: var(--hq-child-label-line);
    }

    .explain__answer {
      @include child.child-title;

      margin: 0;
    }
  `,
})
export class ExplainStopComponent {
  readonly stop = input.required<ExplainStop>();

  protected readonly revealed = cursor(() => this.stop().id);
  protected readonly shown = computed(() => this.stop().workedExamples.slice(0, this.revealed() + 1));
}
