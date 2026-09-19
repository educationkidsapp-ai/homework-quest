import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { type ProseBlock, parseProse } from './stop-prose';

/**
 * CR5: what the stop *says*, read back as a page rather than a document format.
 *
 * It sits above the editor's textarea so a teacher sees the shape of what she is writing while
 * she writes it — the same reason a form shows its own preview. Every block is interpolated, so
 * nothing a teacher (or the model) puts in the text can become markup; see `stop-prose.ts`.
 */
@Component({
  selector: 'hq-stop-prose',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="prose" data-hq-stop-prose>
      @for (block of blocks(); track $index) {
        @switch (block.kind) {
          @case ('heading') {
            @if (block.level === 1) {
              <h3 class="prose__h prose__h--1">{{ block.text }}</h3>
            } @else if (block.level === 2) {
              <h4 class="prose__h prose__h--2">{{ block.text }}</h4>
            } @else {
              <h5 class="prose__h prose__h--3">{{ block.text }}</h5>
            }
          }
          @case ('list') {
            @if (block.ordered) {
              <ol class="prose__list">
                @for (item of block.items; track $index) {
                  <li>{{ item }}</li>
                }
              </ol>
            } @else {
              <ul class="prose__list">
                @for (item of block.items; track $index) {
                  <li>{{ item }}</li>
                }
              </ul>
            }
          }
          @default {
            <p class="prose__p">{{ block.text }}</p>
          }
        }
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
    }

    .prose {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-8);
      padding: var(--hq-space-16);
      border: var(--hq-size-rule-thin) solid var(--hq-color-rule);
      background: var(--hq-color-surface-sunken);
      font-size: var(--hq-text-theme-sm);
      line-height: calc(var(--hq-text-theme-sm-line) / var(--hq-text-theme-sm));
      color: var(--hq-color-ink-strong);
    }

    .prose__h {
      font-weight: var(--hq-text-weight-semibold);
      color: var(--hq-color-ink-strong);
    }

    .prose__h--1 {
      font-size: var(--hq-text-card-title);
      line-height: calc(var(--hq-text-card-title-line) / var(--hq-text-card-title));
    }

    .prose__h--2 {
      font-size: var(--hq-text-theme-xl);
    }

    .prose__h--3 {
      font-size: var(--hq-text-theme-sm);
      text-transform: uppercase;
      letter-spacing: var(--hq-font-letter-spacing-label);
      color: var(--hq-color-ink-soft);
    }

    /* A run of lines between blank lines is one paragraph, and its line breaks are the author's. */
    .prose__p {
      white-space: pre-line;
      margin: 0;
    }

    /* The app's reset strips markers from every list; this is a list, so it has them back —
       without the disc a "- " line reads as another paragraph, which is the one thing the
       renderer exists to prevent. Block flow rather than flex: a flex item is not a
       list item, so the marker would go again. */
    .prose__list {
      margin: 0;
      padding-inline-start: var(--hq-space-24);
      list-style-position: outside;
    }

    .prose__list li {
      display: list-item;
      list-style-type: disc;
    }

    .prose__list li + li {
      margin-block-start: var(--hq-space-4);
    }

    ol.prose__list li {
      list-style-type: decimal;
    }
  `,
})
export class StopProseComponent {
  readonly text = input('');

  protected readonly blocks = computed<readonly ProseBlock[]>(() => parseProse(this.text()));
}
