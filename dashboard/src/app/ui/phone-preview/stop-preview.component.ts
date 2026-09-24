import { ChangeDetectionStrategy, Component, effect, inject, input } from '@angular/core';
import { PreviewImages } from './preview-images';
import { StopPictureComponent } from './stop-picture.component';
import { PreviewImage, Stop } from './stop.model';
import { ChoiceStopComponent } from './stops/choice.stop';
import { CompareStopComponent } from './stops/compare.stop';
import { CountStopComponent } from './stops/count.stop';
import { ExitTicketStopComponent } from './stops/exit-ticket.stop';
import { ExplainStopComponent } from './stops/explain.stop';
import { MatchStopComponent } from './stops/match.stop';
import { MoveStopComponent } from './stops/move.stop';
import { MultiSelectStopComponent } from './stops/multi-select.stop';
import { OpenAnswerStopComponent } from './stops/open-answer.stop';
import { OrderStopComponent } from './stops/order.stop';
import { ReadPageStopComponent } from './stops/read-page.stop';
import { ReadTapStopComponent } from './stops/read-tap.stop';
import { RetellStopComponent } from './stops/retell.stop';
import { SelectAllStopComponent } from './stops/select-all.stop';
import { SequenceStopComponent } from './stops/sequence.stop';
import { SoundStopComponent } from './stops/sound.stop';
import { StoryPiecesStopComponent } from './stops/story-pieces.stop';
import { TraceStopComponent } from './stops/trace.stop';
import { TrueFalseStopComponent } from './stops/true-false.stop';
import { WordCardsStopComponent } from './stops/word-cards.stop';
import { WordStopComponent } from './stops/word.stop';
import { WriteSentenceStopComponent } from './stops/write-sentence.stop';

/**
 * Renders any one of the twenty-two stops, by type (`StopContent` in `shared-ui`).
 *
 * One component per type, and a `@switch` rather than a "generic tiles" fallback: the point of the
 * preview is that a teacher sees the screen their class will see, and a sequence stop does not
 * look like a sound stop. A missing branch is a compile error, not a blank screen.
 *
 * A stop that carries a picture shows it above its body — except `readPage`, which has a picture
 * area of its own.
 */
@Component({
  selector: 'hq-stop-preview',
  imports: [
    StopPictureComponent,
    ReadPageStopComponent,
    StoryPiecesStopComponent,
    WordCardsStopComponent,
    MoveStopComponent,
    ExplainStopComponent,
    ChoiceStopComponent,
    TrueFalseStopComponent,
    SequenceStopComponent,
    CountStopComponent,
    CompareStopComponent,
    SoundStopComponent,
    WordStopComponent,
    ReadTapStopComponent,
    MultiSelectStopComponent,
    SelectAllStopComponent,
    MatchStopComponent,
    OrderStopComponent,
    TraceStopComponent,
    RetellStopComponent,
    OpenAnswerStopComponent,
    WriteSentenceStopComponent,
    ExitTicketStopComponent,
  ],
  providers: [PreviewImages],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[attr.data-subject]': 'subject()',
    '[attr.data-hq-stop-type]': 'stop().type',
  },
  template: `
    @let current = stop();

    <div class="stop">
      @if (current.imageId && current.type !== 'readPage') {
        <hq-stop-picture [imageId]="current.imageId" />
      }

      @switch (current.type) {
        @case ('readPage') {
          <hq-stop-read-page [stop]="current" />
        }
        @case ('storyPieces') {
          <hq-stop-story-pieces [stop]="current" />
        }
        @case ('wordCards') {
          <hq-stop-word-cards [stop]="current" />
        }
        @case ('move') {
          <hq-stop-move [stop]="current" />
        }
        @case ('explain') {
          <hq-stop-explain [stop]="current" />
        }
        @case ('choice') {
          <hq-stop-choice [stop]="current" />
        }
        @case ('trueFalse') {
          <hq-stop-true-false [stop]="current" />
        }
        @case ('sequence') {
          <hq-stop-sequence [stop]="current" />
        }
        @case ('count') {
          <hq-stop-count [stop]="current" />
        }
        @case ('compare') {
          <hq-stop-compare [stop]="current" />
        }
        @case ('sound') {
          <hq-stop-sound [stop]="current" />
        }
        @case ('word') {
          <hq-stop-word [stop]="current" />
        }
        @case ('readTap') {
          <hq-stop-read-tap [stop]="current" />
        }
        @case ('multiSelect') {
          <hq-stop-multi-select [stop]="current" />
        }
        @case ('selectAll') {
          <hq-stop-select-all [stop]="current" />
        }
        @case ('match') {
          <hq-stop-match [stop]="current" />
        }
        @case ('order') {
          <hq-stop-order [stop]="current" />
        }
        @case ('trace') {
          <hq-stop-trace [stop]="current" />
        }
        @case ('retell') {
          <hq-stop-retell [stop]="current" />
        }
        @case ('openAnswer') {
          <hq-stop-open-answer [stop]="current" />
        }
        @case ('writeSentence') {
          <hq-stop-write-sentence [stop]="current" />
        }
        @case ('exitTicket') {
          <hq-stop-exit-ticket [stop]="current" />
        }
      }
    </div>
  `,
  styles: `
    @use './child-tokens' as child;

    :host {
      @include child.child-tokens;
      @include child.child-surface;

      display: block;
      padding-block: var(--hq-space-16);
    }

    :host([data-subject='math']) {
      @include child.world-math;
    }

    .stop {
      @include child.child-stack(var(--hq-space-16));
    }
  `,
})
export class StopPreviewComponent {
  private readonly pictures = inject(PreviewImages);

  readonly stop = input.required<Stop>();
  /** Resolves this stop's `imageId` / `pageImageId` to something to draw. */
  readonly images = input<readonly PreviewImage[]>([]);
  /** Picks the world palette — a maths lesson is a sea world, an English one a lavender world. */
  readonly subject = input<string>('english');

  private readonly syncImages = effect(() => this.pictures.set(this.images()));
}
