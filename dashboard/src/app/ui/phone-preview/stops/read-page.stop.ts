import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { glyphOf, tintOf } from '../illustrations';
import { PreviewImages } from '../preview-images';
import { ChildButtonComponent } from '../child-button.component';
import { ReadPageStop } from '../stop.model';
import { cursor, opened } from './answer-state';

/**
 * A page of the book turned into a screen (`docs/design.md` §6): the picture, the sentences read
 * one at a time, and — when the page has one — a tap task over the picture.
 */
@Component({
  selector: 'hq-stop-read-page',
  imports: [ChildButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div
        class="page__picture"
        [class]="'page__picture tint-' + tint()"
        [attr.aria-label]="stop().pictureDescription ?? 'picture'"
        role="img"
      >
        @if (url(); as source) {
          <img class="page__img" [src]="source" [alt]="stop().pictureDescription ?? ''" />
        } @else if (!stop().tapTask) {
          <span class="page__glyph" aria-hidden="true">{{ glyph() }}</span>
        }

        @if (stop().tapTask; as task) {
          @for (spot of task.hotspots; track spot.id) {
            <button
              type="button"
              class="page__spot"
              [class.page__spot--found]="found.has(spot.id)"
              [style.inset-inline-start.%]="spot.x * 100"
              [style.inset-block-start.%]="spot.y * 100"
              [style.inline-size.%]="spot.w * 100"
              [style.block-size.%]="spot.h * 100"
              [attr.aria-label]="spot.label"
              [attr.aria-pressed]="found.has(spot.id)"
              (click)="tap(spot.id, task.correctIds)"
            >
              <span aria-hidden="true">{{ spotGlyph(spot.label) }}</span>
            </button>
          }
        }
      </div>

      <p class="page__number">Page {{ stop().pageNumber }}</p>

      <ul class="page__sentences">
        @for (sentence of stop().sentences; track $index) {
          <li>
            <button
              type="button"
              class="page__sentence"
              [class.page__sentence--reading]="readingIndex() === $index"
              (click)="readingIndex.set($index)"
            >
              {{ sentence }}
            </button>
          </li>
        }
      </ul>

      @if (stop().tapTask; as task) {
        <p class="page__task">{{ task.prompt }} ({{ found.ids().length }} of {{ task.correctIds.length }})</p>
      }

      <hq-child-button label="Read to me" emoji="🔊" tone="lavender" />
      <hq-child-button label="Done" emoji="✅" [disabled]="!taskDone()" />
    </div>
  `,
  styles: `
    @use '../child-tokens' as child;

    @include child.child-tints;

    .page {
      @include child.child-stack(var(--hq-space-12));

      padding-inline: var(--hq-space-16);
    }

    .page__picture {
      position: relative;
      display: grid;
      place-items: center;
      inline-size: 100%;
      aspect-ratio: 3 / 2;
      overflow: hidden;
      border-radius: var(--hq-child-radius-card);
      background: var(--hq-child-tint-1);
    }

    .page__img {
      inline-size: 100%;
      block-size: 100%;
      object-fit: contain;
    }

    .page__glyph {
      font-size: var(--hq-child-pip-sm);
      line-height: 1;
    }

    .page__spot {
      position: absolute;
      display: grid;
      place-items: center;
      padding: 0;
      border: none;
      border-radius: var(--hq-child-radius-tile);
      background: var(--hq-child-cream);
      font-size: var(--hq-child-title);
      cursor: pointer;
    }

    .page__spot--found {
      background: var(--hq-child-mint);
    }

    .page__number {
      @include child.child-label;

      margin: 0;
      color: var(--hq-child-ink-soft);
    }

    .page__sentences {
      margin: 0;
      padding: 0;
      list-style: none;
      inline-size: 100%;
    }

    .page__sentence {
      inline-size: 100%;
      padding: var(--hq-space-4) var(--hq-space-8);
      border: none;
      border-radius: var(--hq-space-12);
      background: transparent;
      color: var(--hq-child-ink);
      font-family: inherit;
      font-size: var(--hq-child-body);
      line-height: var(--hq-child-body-line);
      text-align: center;
      cursor: pointer;
    }

    .page__sentence--reading {
      background: var(--hq-child-sun);
    }

    .page__task {
      @include child.child-label;

      margin: 0;
      color: var(--hq-child-ink-soft);
      text-align: center;
    }
  `,
})
export class ReadPageStopComponent {
  private readonly pictures = inject(PreviewImages, { optional: true });

  readonly stop = input.required<ReadPageStop>();

  protected readonly found = opened(() => this.stop().id);
  protected readonly readingIndex = cursor(() => this.stop().id, -1);

  protected readonly url = computed(
    () => this.pictures?.url(this.stop().imageId ?? this.stop().pageImageId) ?? null,
  );
  protected readonly glyph = computed(() => glyphOf(this.stop().illustrationKey ?? 'book'));
  protected readonly tint = computed(() => tintOf(this.stop().illustrationKey ?? 'book'));
  protected readonly taskDone = computed(() => {
    const task = this.stop().tapTask;
    return !task || task.correctIds.every((id) => this.found.has(id));
  });

  protected spotGlyph(label: string): string {
    return glyphOf(label.replace(/s$/, ''));
  }

  protected tap(id: string, correctIds: readonly string[]): void {
    if (correctIds.includes(id)) this.found.open(id);
  }
}
