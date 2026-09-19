import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { type AdminLesson, type SourceFileInfo, apiErrorOf, readableServerText } from '../../api';
import { BandService } from '../../core/band/band.service';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import { ButtonComponent, DialogComponent, SkeletonComponent, TextareaComponent } from '../../ui';
import { type FileConvertView, viewOf } from './file-conversion';
import { LessonApiService } from './lesson-api.service';
import { StopProseComponent } from './stop-prose.component';

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

/** Which dialog is open, and over which file — one at a time, by construction. */
type OpenDialog =
  | { readonly kind: 'none' }
  | { readonly kind: 'preview'; readonly file: SourceFileInfo }
  | { readonly kind: 'type'; readonly file: SourceFileInfo };

/**
 * CR4 §4: the lesson's source files, and what became of each of them.
 *
 * Between Upload and Analyse the pipeline now turns every file into text on our own machines,
 * and this is the only place a teacher meets that: a pill that says *Converting…*, then *Ready ·
 * 1,240 words*, or *Couldn't read this file* with one sentence saying what to do. Three things
 * hang off it — a preview of the exact text the model will be given, "Read with OCR" for pages
 * that turned out to be pictures, and "Type the text" for everything else.
 *
 * Its own component rather than more of `lesson.page.html` because it owns state the page has no
 * other use for (two dialogs, a fetched Markdown body, a per-file busy flag) and because the
 * mapping it renders is the thing under test: `file-conversion.spec.ts` covers every error code
 * without a screen, and this only puts words to it.
 *
 * It never reloads the lesson itself. Both retries answer with the **whole** lesson, so it hands
 * that up through {@link lessonChanged} and the page swaps it in — the pill, the step strip and
 * the status move in one frame instead of through a half-state a reload would show.
 */
@Component({
  selector: 'hq-lesson-sources',
  imports: [
    ButtonComponent,
    CanDirective,
    DialogComponent,
    SkeletonComponent,
    StopProseComponent,
    TextareaComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (files().length === 0) {
      <p class="sources__empty">{{ 'lessons.detail.files.empty' | transloco }}</p>
    } @else {
      @if (showCheckNow()) {
        <p class="sources__helper">{{ 'lessons.detail.files.convert.checkNow' | transloco }}</p>
      }

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
              @if (row.view.canPreview && !row.file.deleted) {
                <hq-button data-hq-preview variant="quiet" (pressed)="openPreview(row.file)">
                  {{ 'lessons.detail.files.convert.preview' | transloco }}
                </hq-button>
              }
            </div>

            @if (row.reason) {
              <p class="sources__reason">{{ row.reason }}</p>
              <div class="sources__actions" *hqCan="'lesson.write'">
                @if (row.view.canOcr) {
                  <hq-button
                    data-hq-ocr
                    [disabled]="busyFileId() !== null"
                    [loading]="busyFileId() === row.file.id"
                    (pressed)="readWithOcr(row.file)"
                  >
                    {{ 'lessons.detail.files.convert.ocr' | transloco }}
                  </hq-button>
                }
                @if (row.view.canTypeText) {
                  <hq-button
                    data-hq-type-text
                    variant="quiet"
                    [disabled]="busyFileId() !== null"
                    (pressed)="openTypeText(row.file)"
                  >
                    {{ 'lessons.detail.files.convert.typeText' | transloco }}
                  </hq-button>
                }
              </div>
            }
          </li>
        }
      </ul>
    }

    @if (dialog().kind === 'preview') {
      <hq-dialog
        data-hq-preview-dialog
        [open]="true"
        [sheet]="true"
        [title]="dialogTitle()"
        [confirmLabel]="'lessons.detail.files.convert.previewUse' | transloco"
        (confirmed)="closeDialog()"
        (openChange)="onDialogOpenChange($event)"
      >
        <div class="sources__preview-bar">
          <p class="sources__preview-caption">
            {{ 'lessons.detail.files.convert.previewCaption' | transloco }}
          </p>
          <hq-button *hqCan="'lesson.write'" data-hq-edit-text variant="quiet" (pressed)="editFromPreview()">
            {{ 'lessons.detail.files.convert.previewEdit' | transloco }}
          </hq-button>
        </div>
        @if (markdownLoading()) {
          <hq-skeleton [loading]="true" [lines]="6" [label]="'lessons.loading' | transloco" />
        } @else if (markdownError(); as message) {
          <p class="sources__reason">{{ message }}</p>
        } @else if (markdown().trim() === '') {
          <p class="sources__reason">{{ 'lessons.detail.files.convert.previewEmpty' | transloco }}</p>
        } @else {
          <!-- dir="auto": a converted file's language is the document's, not the screen's. -->
          <hq-stop-prose [text]="markdown()" dir="auto" data-hq-markdown-preview />
        }
      </hq-dialog>
    }

    @if (dialog().kind === 'type') {
      <hq-dialog
        data-hq-type-dialog
        [open]="true"
        [sheet]="true"
        [title]="dialogTitle()"
        [confirmLabel]="'lessons.detail.files.convert.typeSave' | transloco"
        [confirmDisabled]="typed().trim() === ''"
        [confirmReason]="typed().trim() === '' ? typeReason() : null"
        [loading]="busyFileId() !== null"
        (confirmed)="saveTypedText()"
        (openChange)="onDialogOpenChange($event)"
      >
        @if (markdownLoading()) {
          <hq-skeleton [loading]="true" [lines]="4" [label]="'lessons.loading' | transloco" />
        } @else {
          <hq-textarea
            [label]="'lessons.detail.files.convert.typeLabel' | transloco"
            [hint]="'lessons.detail.files.convert.typeHint' | transloco"
            [rows]="10"
            [required]="true"
            dir="auto"
            [value]="typed()"
            (valueChange)="typed.set($event)"
          />
        }
      </hq-dialog>
    }
  `,
  styles: `
    :host {
      display: block;
    }

    .sources__empty,
    .sources__helper {
      color: var(--hq-color-ink-soft);
    }

    .sources__helper {
      margin-block-end: var(--hq-space-12);
      font-size: var(--hq-font-label-size);
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

    .sources__actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--hq-space-12);
      margin-block-start: var(--hq-space-4);
    }

    .sources__preview-bar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--hq-space-12);
      flex-wrap: wrap;
    }

    .sources__preview-caption {
      color: var(--hq-color-ink-soft);
      font-size: var(--hq-font-label-size);
    }
  `,
})
export class LessonSourcesComponent {
  private readonly api = inject(LessonApiService);
  private readonly band = inject(BandService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  readonly lessonId = input.required<string>();
  readonly files = input.required<readonly SourceFileInfo[]>();
  /**
   * Whether "this is the moment to check" is worth saying: the text exists and the model has
   * not read it yet. Once Analyse is running the sentence would be advice about a door that has
   * already shut, so the page — which knows the step states — decides and this only renders.
   */
  readonly checkNow = input(false);

  /** A retry answered with the whole lesson; the page swaps it in. */
  readonly lessonChanged = output<AdminLesson>();

  protected readonly dialog = signal<OpenDialog>({ kind: 'none' });
  protected readonly markdown = signal('');
  protected readonly markdownLoading = signal(false);
  protected readonly markdownError = signal<string | null>(null);
  protected readonly typed = signal('');
  /** The file a request is in flight for — one at a time, which is what refuses a double submit. */
  protected readonly busyFileId = signal<string | null>(null);

  protected readonly showCheckNow = computed(() => this.checkNow() && this.rows().some((r) => r.view.canPreview));

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

  protected readonly dialogTitle = computed(() => {
    this.lang();
    const open = this.dialog();
    if (open.kind === 'none') return '';
    const key = open.kind === 'preview' ? 'previewTitle' : 'typeTitle';
    return this.t(`lessons.detail.files.convert.${key}`, { file: open.file.fileName });
  });

  protected readonly typeReason = computed(() => {
    this.lang();
    return this.t('lessons.detail.files.convert.typeEmpty');
  });

  // ---- the preview ---------------------------------------------------------------------------

  protected openPreview(file: SourceFileInfo): void {
    this.dialog.set({ kind: 'preview', file });
    this.loadMarkdown(file);
  }

  /** "Edit the text" — the same words, in a box she can change, without a second fetch. */
  protected editFromPreview(): void {
    const open = this.dialog();
    if (open.kind !== 'preview') return;
    this.typed.set(this.markdown());
    this.dialog.set({ kind: 'type', file: open.file });
  }

  protected onDialogOpenChange(open: boolean): void {
    if (!open) this.closeDialog();
  }

  protected closeDialog(): void {
    this.dialog.set({ kind: 'none' });
    this.markdownError.set(null);
  }

  // ---- the two fallbacks ---------------------------------------------------------------------

  /**
   * "Type the text", prefilled with whatever we already have.
   *
   * A file that failed usually has nothing, and the fetch 404s — that is not an error worth a
   * band here, it is simply an empty box, so the failure leaves the textarea as it is.
   */
  protected openTypeText(file: SourceFileInfo): void {
    this.typed.set('');
    this.dialog.set({ kind: 'type', file });
    if (viewOf(file).canPreview) this.loadMarkdown(file, (text) => this.typed.set(text));
  }

  protected readWithOcr(file: SourceFileInfo): void {
    if (this.busyFileId() !== null) return;
    this.busyFileId.set(file.id);
    this.api.retryConversion(this.lessonId(), file.id, 'ocr').subscribe({
      next: (lesson) => this.afterRetry(lesson),
      error: (err: unknown) => this.failed(err),
    });
  }

  protected saveTypedText(): void {
    const open = this.dialog();
    const text = this.typed().trim();
    if (open.kind !== 'type' || text === '' || this.busyFileId() !== null) return;
    this.busyFileId.set(open.file.id);
    this.api.retryConversion(this.lessonId(), open.file.id, 'text', text).subscribe({
      next: (lesson) => this.afterRetry(lesson),
      error: (err: unknown) => this.failed(err),
    });
  }

  private afterRetry(lesson: AdminLesson): void {
    this.busyFileId.set(null);
    this.closeDialog();
    this.lessonChanged.emit(lesson);
  }

  /** §7: a failure is the red band, never a toast — and never the server's own JSON (CR5). */
  private failed(error: unknown): void {
    this.busyFileId.set(null);
    const raw = apiErrorOf(error)?.message ?? '';
    this.band.fail(readableServerText(raw) || this.t('band.unreachable'));
  }

  private loadMarkdown(file: SourceFileInfo, then?: (text: string) => void): void {
    this.markdown.set('');
    this.markdownError.set(null);
    this.markdownLoading.set(true);
    this.api.fileMarkdown(this.lessonId(), file.id).subscribe({
      next: (text) => {
        this.markdownLoading.set(false);
        this.markdown.set(text);
        then?.(text);
      },
      error: () => {
        this.markdownLoading.set(false);
        if (!then) this.markdownError.set(this.t('lessons.detail.files.convert.previewMissing'));
      },
    });
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }

  /** "1,240" in English, "١٬٢٤٠" in Arabic — the count is read, so it is localised. */
  private number(value: number): string {
    return new Intl.NumberFormat(this.lang()).format(value);
  }
}
