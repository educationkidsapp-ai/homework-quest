import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ChildButtonComponent } from '../child-button.component';
import { ChildCardComponent } from '../child-card.component';
import { IllustrationComponent } from '../illustration.component';
import { WordCardsStop } from '../stop.model';
import { cursor, opened } from './answer-state';

/** New words, one card at a time: the picture, what it means, and the sentence it came from. */
@Component({
  selector: 'hq-stop-word-cards',
  imports: [ChildButtonComponent, ChildCardComponent, IllustrationComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (card(); as current) {
      <div class="words">
        <hq-child-card>
          <div class="words__card">
            <hq-illustration [key]="current.illustrationKey" size="lg" />
            <p class="words__word">{{ current.word }}</p>
            <p class="words__meaning">{{ current.meaning }}</p>
            <p class="words__sentence">&ldquo;{{ current.sentence }}&rdquo;</p>
          </div>
        </hq-child-card>

        <ol class="words__dots" [attr.aria-label]="'Word ' + (index() + 1) + ' of ' + count()">
          @for (word of stop().words; track word.word) {
            <li class="words__dot" [class.words__dot--on]="seen.has(word.word)"></li>
          }
        </ol>

        <div class="words__actions">
          <hq-child-button label="Listen" emoji="🔊" tone="lavender" compact />
          @if (index() < count() - 1) {
            <hq-child-button label="Next" emoji="➡️" compact (pressed)="next()" />
          }
        </div>

        <hq-child-button label="Done" emoji="✅" [disabled]="!allSeen()" />
      </div>
    }
  `,
  styles: `
    @use '../child-tokens' as child;

    .words {
      @include child.child-stack(var(--hq-space-12));

      padding-inline: var(--hq-space-16);
    }

    .words__card {
      @include child.child-stack(var(--hq-space-8));

      text-align: center;
    }

    .words__word {
      @include child.child-display;

      margin: 0;
    }

    .words__meaning {
      margin: 0;
      color: var(--hq-child-ink-soft);
    }

    .words__sentence {
      margin: 0;
    }

    .words__dots {
      display: flex;
      gap: var(--hq-space-8);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .words__dot {
      inline-size: var(--hq-space-12);
      block-size: var(--hq-space-12);
      border-radius: 50%;
      background: var(--hq-child-ink-soft);
      opacity: 0.3;
    }

    .words__dot--on {
      background: var(--hq-child-sun-deep);
      opacity: 1;
    }

    .words__actions {
      display: flex;
      gap: var(--hq-space-12);
      inline-size: 100%;
    }
  `,
})
export class WordCardsStopComponent {
  readonly stop = input.required<WordCardsStop>();

  protected readonly index = cursor(() => this.stop().id);
  protected readonly seen = opened(() => this.stop().id);

  protected readonly count = computed(() => this.stop().words.length);
  protected readonly card = computed(() => this.stop().words[this.index()] ?? null);
  protected readonly allSeen = computed(
    () =>
      this.stop().words.length > 0 &&
      this.stop().words.every((word, index) => index === 0 || this.seen.has(word.word)),
  );

  protected next(): void {
    const next = Math.min(this.index() + 1, this.count() - 1);
    this.index.set(next);
    const word = this.stop().words[next];
    if (word) this.seen.open(word.word);
  }
}
