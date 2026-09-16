import { ChangeDetectionStrategy, Component, computed, forwardRef, input } from '@angular/core';
import { StopPreviewComponent } from '../stop-preview.component';
import { ExitTicketStop } from '../stop.model';
import { cursor } from './answer-state';

/**
 * The exit ticket: three ordinary questions in a row with a dot per question, so the child sees
 * how near the end they are. It renders whatever those questions are by going back through
 * `hq-stop-preview` — the forward reference is the recursion the app has too, since an exit ticket
 * is the one stop whose content is other stops.
 */
@Component({
  selector: 'hq-stop-exit-ticket',
  imports: [forwardRef(() => StopPreviewComponent)],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="exit">
      <ol class="exit__dots" [attr.aria-label]="'Question ' + (index() + 1) + ' of ' + count()">
        @for (question of stop().questions; track question.id) {
          <li
            class="exit__dot"
            [class.exit__dot--done]="$index < index()"
            [class.exit__dot--now]="$index === index()"
          ></li>
        }
      </ol>

      @if (question(); as current) {
        <hq-stop-preview [stop]="current" />
      }

      @if (index() < count() - 1) {
        <button type="button" class="exit__next" (click)="index.set(index() + 1)">Next question</button>
      }
    </div>
  `,
  styles: `
    @use '../child-tokens' as child;

    .exit {
      @include child.child-stack(var(--hq-space-16));
    }

    .exit__dots {
      display: flex;
      gap: var(--hq-space-8);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .exit__dot {
      inline-size: var(--hq-space-12);
      block-size: var(--hq-space-12);
      border-radius: 50%;
      background: var(--hq-child-ink-soft);
      opacity: 0.3;
    }

    .exit__dot--done {
      background: var(--hq-child-mint);
      opacity: 1;
    }

    .exit__dot--now {
      background: var(--hq-child-sun);
      opacity: 1;
    }

    // Not part of the app: the app advances itself when a question is answered, and the preview
    // has no player to do that, so the editor gets a plain way through the three questions.
    .exit__next {
      @include child.child-target;
      @include child.child-label;

      padding-inline: var(--hq-space-24);
      border: none;
      border-radius: var(--hq-child-radius-chip);
      background: var(--hq-child-cream);
      color: var(--hq-child-ink-soft);
    }
  `,
})
export class ExitTicketStopComponent {
  readonly stop = input.required<ExitTicketStop>();

  protected readonly index = cursor(() => this.stop().id);
  protected readonly count = computed(() => this.stop().questions.length);
  protected readonly question = computed(() => this.stop().questions[this.index()] ?? null);
}
