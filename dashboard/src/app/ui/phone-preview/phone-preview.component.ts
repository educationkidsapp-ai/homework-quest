import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { PhoneFrameComponent } from '../phone-frame/phone-frame.component';
import { ChildStarsComponent } from './stars.component';
import { PipComponent } from './pip.component';
import { ReadAloudComponent } from './read-aloud.component';
import { StopPreviewComponent } from './stop-preview.component';
import { PreviewImage, Stop, Theme } from './stop.model';
import { MapIsland, WorldMapPreviewComponent } from './world-map.preview';
import { WrongAnswerPreviewComponent } from './wrong-answer.preview';

/** Which of the three child screens the preview is showing. */
export type PreviewScreen = 'stop' | 'map' | 'wrongAnswer';

/**
 * What the child will see, inside the device frame: the lesson editor's plays tab renders the
 * selected stop here, live, as the teacher edits it.
 *
 * The three screens are the three the editor needs to answer "will this work?" — the practice
 * question itself, the map it is reached from, and the hint sheet a wrong answer opens. Everything
 * in them is drawn from the `--hq-*` custom properties, so the Theme tab's 300 ms colour change
 * lands here without a rebuild.
 *
 * The content is the child's, and the child's content is never translated: the strings inside
 * belong to the lesson. The few words of chrome the preview adds are inputs, so the caller decides
 * their wording and their language.
 */
@Component({
  selector: 'hq-phone-preview',
  imports: [
    PhoneFrameComponent,
    ChildStarsComponent,
    PipComponent,
    ReadAloudComponent,
    StopPreviewComponent,
    WorldMapPreviewComponent,
    WrongAnswerPreviewComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-phone-frame [label]="label()" [caption]="caption()">
      <div class="child" [attr.data-subject]="subject()" [attr.data-hq-screen]="screen()">
        @switch (screen()) {
          @case ('map') {
            <hq-world-map-preview [islands]="islands()" [theme]="theme()" [childName]="childName()" />
          }
          @case ('wrongAnswer') {
            <div class="wrong">
              <header class="practice__top">
                <hq-child-stars [filled]="stars()" [label]="stars() + ' of 7 stars'" />
                <hq-read-aloud [label]="readAloudLabel()" />
              </header>
              @if (stop(); as current) {
                <hq-stop-preview [stop]="current" [images]="images()" [subject]="subject()" />
              }
              <hq-wrong-answer-preview [stop]="stop()" />
            </div>
          }
          @default {
            <div class="practice">
              <header class="practice__top">
                <hq-child-stars [filled]="stars()" [label]="stars() + ' of 7 stars'" />
                <hq-read-aloud [label]="readAloudLabel()" />
              </header>

              @if (stop(); as current) {
                <hq-stop-preview [stop]="current" [images]="images()" [subject]="subject()" />
                <footer class="practice__pip">
                  <hq-pip pose="idle" size="sm" />
                </footer>
              } @else {
                <div class="practice__placeholder">
                  <hq-pip pose="idle" size="md" />
                  <p class="practice__placeholder-text">{{ placeholder() }}</p>
                </div>
              }
            </div>
          }
        }
      </div>
    </hq-phone-frame>
  `,
  styles: `
    @use './child-tokens' as child;

    :host {
      @include child.child-tokens;

      display: block;
    }

    .child {
      @include child.child-surface;

      display: flex;
      flex-direction: column;
      min-block-size: 100%;
    }

    .child[data-subject='math'] {
      @include child.world-math;
    }

    // The map fills the screen it is drawn in; the sea has no edge.
    hq-world-map-preview {
      display: flex;
      flex: 1;
      flex-direction: column;
    }

    .practice {
      display: flex;
      flex-direction: column;
      flex: 1;
      gap: var(--hq-space-12);
      padding-block-end: var(--hq-space-24);
    }

    .practice__top {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--hq-space-12);
      padding: var(--hq-space-12) var(--hq-space-16);
    }

    .practice__pip {
      display: flex;
      justify-content: center;
      margin-block-start: auto;
      padding-block-start: var(--hq-space-24);
    }

    .practice__placeholder {
      display: flex;
      flex: 1;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: var(--hq-space-16);
      padding: var(--hq-space-32);
      text-align: center;
    }

    .practice__placeholder-text {
      margin: 0;
      color: var(--hq-child-ink-soft);
      font-size: var(--hq-child-body);
      line-height: var(--hq-child-body-line);
    }

    // The hint sheet sits over the question it came from, which stays on screen behind it.
    .wrong {
      display: flex;
      flex: 1;
      flex-direction: column;
      min-block-size: 100%;
    }
  `,
})
export class PhonePreviewComponent {
  /** Which replica to draw. */
  readonly screen = input<PreviewScreen>('stop');
  /** The stop to render on the practice frame, and the stop the hint sheet belongs to. */
  readonly stop = input<Stop | null>(null);
  /** Picks the world palette. */
  readonly subject = input<'math' | 'english'>('english');
  readonly childName = input<string>('Pip');
  /** Resolves a stop's `imageId` / `pageImageId` to a picture. */
  readonly images = input<readonly PreviewImage[]>([]);
  /** The play's pot and dish, named in the map header. */
  readonly theme = input<Theme | null>(null);
  /** Accessible name for the device frame, e.g. "Child app preview". */
  readonly label = input.required<string>();
  /** Shown on the practice frame when no stop is selected. */
  readonly placeholder = input<string>('Pick a stop to see it here.');
  /** The islands of the map screen; none of them is the map's own empty state. */
  readonly islands = input<readonly MapIsland[]>([]);
  /** How many of the seven stars are filled on the practice frame. */
  readonly stars = input<number>(0);
  /** Accessible name of the read-aloud circle. */
  readonly readAloudLabel = input<string>('Read it to me');
  /** Caption under the device frame. */
  readonly caption = input<string | null>(null);
}
