import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import type { SourceFileInfo } from '../../api';
import { activeLang } from '../../core/i18n/active-lang';
import { type FileConvertView, viewOf } from './file-conversion';

/** One row: the file, its pill, and the words that go with it, all resolved for the template. */
interface SourceRow {
  readonly file: SourceFileInfo;
  readonly view: FileConvertView;
  /** `hq-badge` plus its tone modifier; the neutral pill is the base class and has no modifier. */
  readonly badgeClass: string;
  readonly label: string;
  readonly methodHint: string | null;
  readonly reason: string | null;
}

/**
 * CR4 §4: the lesson's source files, and what became of each of them.
 *
 * Between Upload and Analyse the pipeline now turns every file into text on our own machines,
 * and this is the only place a teacher meets that: a pill that says *Converting…*, then *Ready ·
 * 1,240 words*, or *Couldn't read this file* with one sentence saying what to do about it. She
 * never reads the words Markdown, anydoc or OCR-failed; `file-conversion.ts` is where those
 * become hers.
 *
 * Its own component rather than more of `lesson.page.html` because the mapping it renders is the
 * thing under test — `file-conversion.spec.ts` covers every error code without a screen, and
 * this only puts words to it — and because the two ways out of a failure that follow in this
 * change set bring state the page has no other use for.
 */
@Component({
  selector: 'hq-lesson-sources',
  imports: [TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (files().length === 0) {
      <p class="sources__empty">{{ 'lessons.detail.files.empty' | transloco }}</p>
    } @else {
      <ul class="sources" data-hq-sources>
        @for (row of rows(); track row.file.id) {
          <li
            class="sources__row"
            [class.sources__row--deleted]="row.file.deleted"
            [attr.data-hq-file]="row.file.fileName"
          >
            <div class="sources__line">
              <span class="sources__name">{{ row.file.fileName }}</span>

              <!-- One live region per row, present from the first render: a region created at the
                   same moment its text changes is announced by no screen reader. -->
              <span class="sources__state" aria-live="polite">
                <span [class]="row.badgeClass" [attr.data-hq-file-status]="row.view.status">
                  @if (row.view.status === 'converting') {
                    <span class="sources__spinner" aria-hidden="true"></span>
                  }
                  {{ row.label }}
                </span>
                @if (row.methodHint) {
                  <span class="sources__hint">{{ row.methodHint }}</span>
                }
              </span>
            </div>

            <div class="sources__line sources__line--meta">
              <span class="sources__meta">
                {{ 'lessons.detail.files.pages' | transloco: { count: row.file.pageCount } }}
                @if (row.file.cacheHit) {
                  · {{ 'lessons.detail.files.cacheHit' | transloco }}
                }
                @if (row.file.deleted) {
                  · {{ 'lessons.detail.files.deleted' | transloco }}
                }
              </span>
            </div>

            @if (row.reason) {
              <p class="sources__reason">{{ row.reason }}</p>
            }
          </li>
        }
      </ul>
    }
  `,
  styles: `
    :host {
      display: block;
    }

    .sources__empty {
      color: var(--hq-color-ink-soft);
    }

    .sources {
      border-block-start: var(--hq-size-rule-thin) solid var(--hq-color-divider);
    }

    .sources__row {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-4);
      min-block-size: var(--hq-size-row-height);
      padding-block: var(--hq-space-8);
      border-block-end: var(--hq-size-rule-thin) solid var(--hq-color-divider);
    }

    .sources__row--deleted .sources__name {
      color: var(--hq-color-ink-soft);
      text-decoration: line-through;
    }

    .sources__line {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--hq-space-12);
      flex-wrap: wrap;
    }

    .sources__name {
      font-weight: var(--hq-font-label-weight);
    }

    .sources__state {
      display: inline-flex;
      align-items: center;
      gap: var(--hq-space-8);
    }

    .sources__meta,
    .sources__hint {
      font-size: var(--hq-font-label-size);
      color: var(--hq-color-ink-soft);
    }

    /* The same shimmer the skeletons use, in the size of a badge glyph: the pill already says
       "Converting…", so this only has to move — it carries no meaning of its own. */
    .sources__spinner {
      inline-size: var(--hq-space-8);
      block-size: var(--hq-space-8);
      border-radius: var(--hq-radius-pill);
      background-image: linear-gradient(
        90deg,
        var(--hq-color-skeleton) 0%,
        var(--hq-color-skeleton-shine) 50%,
        var(--hq-color-skeleton) 100%
      );
      background-size: 250% 100%;
      animation: hq-shimmer var(--hq-motion-shimmer) linear infinite;
    }

    @media (prefers-reduced-motion: reduce) {
      .sources__spinner {
        animation: none;
      }
    }

    .sources__reason {
      color: var(--hq-color-ink-strong);
      font-size: var(--hq-font-label-size);
    }
  `,
})
export class LessonSourcesComponent {
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  readonly lessonId = input.required<string>();
  readonly files = input.required<readonly SourceFileInfo[]>();

  protected readonly rows = computed<readonly SourceRow[]>(() => {
    this.lang();
    return this.files().map((file) => {
      const view = viewOf(file);
      return {
        file,
        view,
        badgeClass: view.tone === 'neutral' ? 'hq-badge' : `hq-badge hq-badge--${view.tone}`,
        label: this.t(view.labelKey, { words: this.number(view.words ?? 0) }),
        methodHint: view.methodKey ? this.t(view.methodKey) : null,
        reason: view.reasonKey ? this.t(view.reasonKey) : null,
      };
    });
  });

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }

  /** "1,240" in English, "١٬٢٤٠" in Arabic — the count is read, so it is localised. */
  private number(value: number): string {
    return new Intl.NumberFormat(this.lang()).format(value);
  }
}
