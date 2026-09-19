import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { PageImageDirective } from '../../media/page-image.directive';
import { ChildButtonComponent } from '../child-button.component';
import { ChildCardComponent } from '../child-card.component';
import { IllustrationComponent } from '../illustration.component';
import { PreviewImages } from '../preview-images';
import { RetellStop } from '../stop.model';
import { opened } from './answer-state';

/**
 * Retell: one card per stage of the story, tapped as the child tells it. When the stop asks for a
 * recording there is a Record button too — no wrong answer exists here, only an attempt.
 */
@Component({
  selector: 'hq-stop-retell',
  imports: [ChildButtonComponent, ChildCardComponent, IllustrationComponent, PageImageDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="retell">
      <p class="retell__prompt">{{ stop().prompt }}</p>

      @for (cue of stop().cues; track cue.stage) {
        <hq-child-card
          interactive
          [tone]="told.has(cue.stage) ? 'mint' : 'cream'"
          (pressed)="told.open(cue.stage)"
        >
          <div class="retell__cue">
            @if (pictureId(cue.pageImageId); as pictureId) {
              <img class="retell__image" [hqPageImage]="pictureId" alt="" />
            } @else if (cue.illustrationKey; as key) {
              <hq-illustration [key]="key" size="sm" />
            }
            <span class="retell__words">
              <span class="retell__stage">{{ cue.stage }}</span>
              <span class="retell__text">{{ cue.cue }}</span>
            </span>
          </div>
        </hq-child-card>
      }

      @if (stop().record) {
        <hq-child-button label="Record" emoji="🎙️" tone="lavender" />
      }
      <hq-child-button label="I told it!" emoji="✅" [disabled]="!allTold()" />
    </div>
  `,
  styles: `
    @use '../child-tokens' as child;

    .retell {
      @include child.child-stack(var(--hq-space-12));

      padding-inline: var(--hq-space-16);
    }

    .retell__prompt {
      @include child.child-prompt;
    }

    .retell__cue {
      display: flex;
      align-items: center;
      gap: var(--hq-space-12);
    }

    .retell__image {
      inline-size: var(--hq-child-illus-sm);
      block-size: var(--hq-child-illus-sm);
      object-fit: cover;
      border-radius: var(--hq-child-radius-tile);
    }

    .retell__words {
      display: flex;
      flex-direction: column;
    }

    .retell__stage {
      @include child.child-label;

      color: var(--hq-child-ink-soft);
      text-transform: capitalize;
    }

    .retell__text {
      font-size: var(--hq-child-body);
      line-height: var(--hq-child-body-line);
    }
  `,
})
export class RetellStopComponent {
  private readonly pictures = inject(PreviewImages, { optional: true });

  readonly stop = input.required<RetellStop>();

  protected readonly told = opened(() => this.stop().id);
  protected readonly allTold = computed(() => this.told.ids().length === this.stop().cues.length);

  protected pictureId(imageId: string | null | undefined): string | null {
    return this.pictures?.has(imageId) ? (imageId ?? null) : null;
  }
}
