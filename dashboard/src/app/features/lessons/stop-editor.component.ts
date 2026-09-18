import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import { ButtonComponent, InputComponent, SelectComponent, type SelectOption, TextareaComponent } from '../../ui';
import { STOP_TYPES, type Stop, type StopType } from '../../ui/phone-preview';
import { declaredStopType, validateStop } from './stop-validator';

/** A picture already attached to the lesson — `LessonImage` from the contract. */
export interface EditorImage {
  readonly id: string;
  readonly url: string;
}

/** Ajv and the schema are both lazy, so validation settles a tick late; this debounces typing. */
const VALIDATE_DEBOUNCE_MS = 250;

/**
 * The stop editor, to the right of the stop list (dev prompt §4.4; teacher flow §4 step 6).
 *
 * Five quick fields sit above the full JSON document because those five are what a teacher
 * actually changes — the wording the pot says, the tip the parent reads, the picture — and
 * making her find them inside forty lines of JSON would be the whole reason the old admin panel
 * was unusable. Editing either half writes the same document: a quick field re-serializes the
 * parsed JSON, so the textarea is always the truth and there is no second copy to reconcile.
 *
 * **Save is disabled until the document validates** against the declared type's branch
 * (`stop-validator.ts`), and the id is immutable: the server addresses the stop by it, so a
 * changed id would silently create nothing and update nothing. Both refusals say why.
 */
@Component({
  selector: 'hq-stop-editor',
  imports: [ButtonComponent, InputComponent, SelectComponent, TextareaComponent, CanDirective, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="editor">
      <div class="editor__quick">
        <hq-input
          [label]="'lessons.detail.editor.title' | transloco"
          [required]="true"
          [value]="field('title')"
          (valueChange)="patchString('title', $event)"
          [disabled]="disabled()"
        />
        <hq-input
          [label]="'lessons.detail.editor.speak' | transloco"
          [required]="true"
          [value]="field('speak')"
          (valueChange)="patchString('speak', $event)"
          [disabled]="disabled()"
        />
        <hq-textarea
          [label]="'lessons.detail.editor.parentTipEn' | transloco"
          [rows]="2"
          dir="ltr"
          [required]="true"
          [value]="tip('en')"
          (valueChange)="patchTip('en', $event)"
          [disabled]="disabled()"
        />
        <hq-textarea
          [label]="'lessons.detail.editor.parentTipAr' | transloco"
          [rows]="2"
          dir="rtl"
          [required]="true"
          [value]="tip('ar')"
          (valueChange)="patchTip('ar', $event)"
          [disabled]="disabled()"
        />
        <div class="editor__image">
          <hq-select
            [label]="'lessons.detail.editor.image' | transloco"
            [options]="imageOptions()"
            [placeholder]="'lessons.detail.editor.noImage' | transloco"
            [value]="imageId()"
            (valueChange)="patchImage($event)"
            [disabled]="disabled() || images().length === 0"
          />
          <div class="editor__image-actions">
            <label class="editor__attach" *hqCan="'stop.write'">
              <input
                class="editor__file"
                type="file"
                accept="image/png,image/jpeg,image/webp"
                [disabled]="disabled()"
                (change)="onAttachInput($event)"
              />
              <span class="editor__attach-label">{{ 'lessons.detail.editor.attach' | transloco }}</span>
            </label>
            @if (imageId() !== '') {
              <hq-button
                *hqCan="'stop.write'"
                variant="quiet"
                [disabled]="disabled()"
                (pressed)="patchImage('')"
              >
                {{ 'lessons.detail.editor.detach' | transloco }}
              </hq-button>
            }
          </div>
        </div>
      </div>

      <hq-textarea
        [label]="'lessons.detail.editor.json' | transloco"
        [rows]="16"
        [mono]="true"
        [required]="true"
        dir="ltr"
        [value]="text()"
        (valueChange)="text.set($event)"
        [hint]="'lessons.detail.editor.jsonHint' | transloco"
        [error]="errorText()"
        [disabled]="disabled()"
      />

      <div class="editor__actions" *hqCan="'stop.write'">
        <hq-button variant="primary" [disabled]="!canSave()" [reason]="saveReason()" (pressed)="save()">
          {{ 'lessons.detail.editor.save' | transloco }}
        </hq-button>
        <hq-button variant="quiet" [disabled]="disabled()" (pressed)="regenerated.emit()">
          {{ 'lessons.detail.plays.regenerateStop' | transloco }}
        </hq-button>
        <hq-button variant="quiet" [disabled]="disabled()" (pressed)="deleted.emit()">
          {{ 'lessons.detail.editor.delete' | transloco }}
        </hq-button>
      </div>
    </div>
  `,
  styles: `
    .editor {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-16);
    }

    .editor__quick {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--hq-space-12) var(--hq-space-16);
    }

    .editor__image {
      grid-column: 1 / -1;
      display: flex;
      align-items: flex-end;
      gap: var(--hq-space-16);
    }

    .editor__image hq-select {
      flex: 1 1 auto;
      min-inline-size: 0;
    }

    .editor__image-actions {
      display: flex;
      align-items: center;
      gap: var(--hq-space-8);
      block-size: var(--hq-size-input-height);
    }

    .editor__attach {
      display: inline-flex;
      align-items: center;
    }

    .editor__file {
      position: absolute;
      inline-size: 1px;
      block-size: 1px;
      opacity: 0;
    }

    .editor__attach-label {
      display: inline-flex;
      align-items: center;
      block-size: var(--hq-size-button-height);
      padding-inline: var(--hq-space-16);
      border: var(--hq-size-rule) solid var(--hq-color-ink);
      font-weight: var(--hq-font-label-weight);
      cursor: pointer;
    }

    .editor__file:focus-visible + .editor__attach-label {
      outline: var(--hq-size-rule) solid var(--hq-color-focus);
      outline-offset: var(--hq-space-4);
    }

    .editor__actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--hq-space-8);
    }

    @media (width <= 60rem) {
      .editor__quick {
        grid-template-columns: minmax(0, 1fr);
      }
    }
  `,
})
export class StopEditorComponent {
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  readonly stop = input.required<Stop | null>();
  readonly images = input<readonly EditorImage[]>([]);
  /** The page is mid-request: every control locks rather than racing the server. */
  readonly disabled = input(false);

  /** The stop document to `PUT`, already valid — the page turns it into the request body. */
  readonly saved = output<Stop>();
  readonly regenerated = output<void>();
  readonly deleted = output<void>();
  readonly imageAttached = output<File>();
  /** True while the draft differs from the stop the server last gave us. */
  readonly dirtyChange = output<boolean>();

  /** The document being edited — the single source of truth for both halves of the editor. */
  protected readonly text = signal('');
  private readonly validation = signal<{ readonly valid: boolean; readonly errors: readonly string[] } | null>(null);

  private readonly serverText = computed(() => {
    const stop = this.stop();
    return stop ? `${JSON.stringify(stop, null, 2)}\n` : '';
  });

  protected readonly dirty = computed(() => this.text() !== this.serverText());

  private readonly parsed = computed<Record<string, unknown> | null>(() => {
    try {
      const value: unknown = JSON.parse(this.text());
      return typeof value === 'object' && value !== null && !Array.isArray(value)
        ? (value as Record<string, unknown>)
        : null;
    } catch {
      return null;
    }
  });

  protected readonly declaredType = computed<StopType | null>(() => declaredStopType(this.text(), STOP_TYPES));

  /** The id the server addresses this stop by. Changing it would update nothing — so it cannot. */
  private readonly idChanged = computed(() => {
    const parsed = this.parsed();
    const stop = this.stop();
    return parsed !== null && stop !== null && parsed['id'] !== stop.id;
  });

  protected readonly errorText = computed(() => {
    this.lang();
    if (this.idChanged()) return this.t('lessons.detail.editor.idImmutable');
    if (this.parsed() !== null && this.declaredType() === null) return this.t('lessons.detail.editor.unknownType');
    const errors = this.validation()?.errors ?? [];
    return errors.length > 0 ? errors.join('\n') : null;
  });

  protected readonly canSave = computed(
    () =>
      !this.disabled() &&
      this.dirty() &&
      !this.idChanged() &&
      this.declaredType() !== null &&
      this.validation()?.valid === true,
  );

  protected readonly saveReason = computed(() => {
    this.lang();
    if (this.canSave() || this.disabled()) return null;
    if (!this.dirty()) return this.t('lessons.detail.editor.noChanges');
    return this.t('lessons.detail.editor.fixFirst');
  });

  protected readonly imageOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return this.images().map((image, index) => ({
      value: image.id,
      label: this.t('lessons.detail.editor.imageOption', { index: index + 1 }),
    }));
  });

  protected readonly imageId = computed(() => {
    const value = this.parsed()?.['imageId'];
    return typeof value === 'string' ? value : '';
  });

  constructor() {
    // A different stop (or the same one saved and re-read) reseeds the draft. Selecting a stop,
    // typing, then selecting it again is meant to lose the edit — the confirm lives on the page.
    effect(() => this.text.set(this.serverText()));

    effect((onCleanup) => {
      const text = this.text();
      const type = declaredStopType(text, STOP_TYPES);
      if (type === null) {
        this.validation.set(null);
        return;
      }
      const timer = setTimeout(() => {
        void validateStop(type, text).then((result) => {
          // A later keystroke has already replaced this run's input — drop the stale answer.
          if (this.text() === text) this.validation.set(result);
        });
      }, VALIDATE_DEBOUNCE_MS);
      onCleanup(() => clearTimeout(timer));
    });

    effect(() => this.dirtyChange.emit(this.dirty()));
  }

  // ---- quick fields, which write through to the same document --------------------------------

  protected field(key: 'title' | 'speak'): string {
    const value = this.parsed()?.[key];
    return typeof value === 'string' ? value : '';
  }

  protected tip(lang: 'en' | 'ar'): string {
    const tip = this.parsed()?.['parentTip'];
    if (typeof tip !== 'object' || tip === null) return '';
    const value = (tip as Record<string, unknown>)[lang];
    return typeof value === 'string' ? value : '';
  }

  protected patchString(key: 'title' | 'speak', value: string): void {
    this.patch((draft) => ({ ...draft, [key]: value }));
  }

  protected patchTip(lang: 'en' | 'ar', value: string): void {
    this.patch((draft) => {
      const tip = draft['parentTip'];
      const current = typeof tip === 'object' && tip !== null ? (tip as Record<string, unknown>) : {};
      return { ...draft, parentTip: { ...current, [lang]: value } };
    });
  }

  /** `''` detaches: the key is removed rather than set to null, which the schema would refuse. */
  protected patchImage(id: string): void {
    this.patch((draft) => {
      if (id === '') {
        const { imageId, ...rest } = draft;
        void imageId;
        return rest;
      }
      return { ...draft, imageId: id };
    });
  }

  private patch(change: (draft: Record<string, unknown>) => Record<string, unknown>): void {
    const parsed = this.parsed();
    // Hand-edited JSON that no longer parses has no fields to patch; the textarea owns it then.
    if (parsed === null) return;
    this.text.set(`${JSON.stringify(change(parsed), null, 2)}\n`);
  }

  protected onAttachInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (file) this.imageAttached.emit(file);
  }

  protected save(): void {
    const parsed = this.parsed();
    if (!this.canSave() || parsed === null) return;
    this.saved.emit(parsed as unknown as Stop);
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
