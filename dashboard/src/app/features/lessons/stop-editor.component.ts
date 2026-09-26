import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import { ViewModeService } from '../../core/view-mode/view-mode.service';
import { BandComponent, ButtonComponent, InputComponent, SelectComponent, type SelectOption, TextareaComponent } from '../../ui';
import { STOP_TYPES, type Stop, type StopType } from '../../ui/phone-preview';
import { stopJson } from './lessons.models';
import { StopProseComponent } from './stop-prose.component';
import { declaredStopType, validateStop } from './stop-validator';

/** A picture already attached to the lesson — `LessonImage` from the contract. */
export interface EditorImage {
  readonly id: string;
  readonly url: string;
}

/**
 * Why the last save did not land, in the two shapes a teacher can act on.
 *
 * `rephrase` is the server's 422: the model could not turn this wording into a stop the schema
 * accepts, twice. `generating` is its 400 for a lesson whose pipeline is still running.
 * Everything else is a red band from the error interceptor and never reaches here.
 */
export type StopSaveFailure = 'rephrase' | 'generating';

/** Ajv and the schema are both lazy, so validation settles a tick late; this debounces typing. */
const VALIDATE_DEBOUNCE_MS = 250;

/** `StopTextService.MAX_TEXT` — the server's 400 for a longer text, refused here instead. */
const MAX_TEXT = 8000;

/**
 * The five quick fields, by the `instancePath` Ajv reports them under, with the bound
 * `Play.schema.json` puts on each — `max: null` for the picture, which is an id and not prose.
 *
 * E4a: the bounds are here because the generated Ajv module is 605 kB and now loads only for the
 * Raw JSON panel (see the class comment), so the teacher path checks the five fields it can
 * actually edit itself. Everything else about the document is the server's validator's, which
 * these five cannot break — they are `minLength: 1` and a maximum on all twenty-two branches.
 */
const QUICK_FIELDS = [
  { path: '/title', labelKey: 'lessons.detail.editor.title', max: 40 },
  { path: '/speak', labelKey: 'lessons.detail.editor.speak', max: 90 },
  { path: '/parentTip/en', labelKey: 'lessons.detail.editor.parentTipEn', max: 200 },
  { path: '/parentTip/ar', labelKey: 'lessons.detail.editor.parentTipAr', max: 200 },
  { path: '/imageId', labelKey: 'lessons.detail.editor.image', max: null },
] as const;

/**
 * The stop editor, to the right of the stop list (dev prompt §4.4; teacher flow §4 step 6).
 *
 * **CR5 turned this inside out.** It used to be a JSON document with five quick fields above it,
 * which asked a teacher to read a data format to find out what her own question says. Now the
 * surface is the stop in English — `Stop.teacherText`, which the server either saved when she
 * last wrote it or described from the stored JSON — rendered above as headings, paragraphs and
 * lists, and editable below in a plain textarea. Saving posts it to
 * `POST /{teacher,admin}/stops/{id}/from-text`, and turning it back into schema-valid JSON is the
 * server's problem (and the model's). JSON appears on this screen only in the Raw panel, which
 * `ViewModeService` opens for an Admin in debug mode and for nobody else.
 *
 * **The quick fields stay** (title, what the pot says, the parent tip in both languages, the
 * picture). They are the five things a teacher changes without rewriting anything, they are
 * exact rather than interpreted, and they still go through the raw-JSON `PUT` — no model call, no
 * wait. The cost is that the `PUT` clears the saved prose, so the description she reads
 * afterwards is the server's re-description of the new JSON; the hint under the fields says so.
 *
 * **The schema validator is the Raw panel's alone.** `stop-validators.generated.js` is 605 kB of
 * precompiled Ajv, and it used to load the moment a stop was selected — which the lesson page does
 * by itself — so every teacher downloaded it for a panel only an Admin in debug mode can see. It
 * is now behind the same `viewMode.debug()` the panel is; the quick fields are bounded by
 * {@link QUICK_FIELDS} instead, and the server validates every write regardless.
 *
 * **Only one half is live at a time.** Editing the prose disables the quick fields and editing a
 * quick field disables the prose, because the two saves are different requests with different
 * inputs: Prompt D is handed the *stored* JSON as context, so an unsaved quick-field edit would
 * be silently dropped by a text save, and a `PUT` would silently drop the typed prose. Refusing
 * to be in both states at once is the only version of this that cannot lose work.
 */
@Component({
  selector: 'hq-stop-editor',
  imports: [
    BandComponent,
    ButtonComponent,
    InputComponent,
    SelectComponent,
    TextareaComponent,
    StopProseComponent,
    CanDirective,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="editor">
      <hq-stop-prose [text]="prose()" />

      <hq-textarea
        [label]="'lessons.detail.editor.text.label' | transloco"
        [rows]="8"
        [autoGrow]="true"
        [required]="true"
        [value]="prose()"
        (valueChange)="prose.set($event)"
        [hint]="proseHint()"
        [error]="proseError()"
        [disabled]="disabled() || jsonDirty()"
      />

      @if (saving()) {
        <p class="editor__saving" role="status">{{ 'lessons.detail.editor.text.saving' | transloco }}</p>
      }

      @if (failure(); as why) {
        <hq-band variant="error" [open]="true" [dismissible]="false" [title]="'band.failed' | transloco">
          {{ 'lessons.detail.editor.text.' + why | transloco }}
        </hq-band>
      }

      <div class="editor__quick">
        <hq-input
          [label]="'lessons.detail.editor.title' | transloco"
          [required]="true"
          [value]="field('title')"
          (valueChange)="patchString('title', $event)"
          [disabled]="fieldsDisabled()"
        />
        <hq-input
          [label]="'lessons.detail.editor.speak' | transloco"
          [required]="true"
          [value]="field('speak')"
          (valueChange)="patchString('speak', $event)"
          [disabled]="fieldsDisabled()"
        />
        <hq-textarea
          [label]="'lessons.detail.editor.parentTipEn' | transloco"
          [rows]="2"
          dir="ltr"
          [required]="true"
          [value]="tip('en')"
          (valueChange)="patchTip('en', $event)"
          [disabled]="fieldsDisabled()"
        />
        <hq-textarea
          [label]="'lessons.detail.editor.parentTipAr' | transloco"
          [rows]="2"
          dir="rtl"
          [required]="true"
          [value]="tip('ar')"
          (valueChange)="patchTip('ar', $event)"
          [disabled]="fieldsDisabled()"
        />
        <div class="editor__image">
          <hq-select
            [label]="'lessons.detail.editor.image' | transloco"
            [options]="imageOptions()"
            [placeholder]="'lessons.detail.editor.noImage' | transloco"
            [value]="imageId()"
            (valueChange)="patchImage($event)"
            [disabled]="fieldsDisabled() || images().length === 0"
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
                [disabled]="fieldsDisabled()"
                (pressed)="patchImage('')"
              >
                {{ 'lessons.detail.editor.detach' | transloco }}
              </hq-button>
            }
          </div>
        </div>
        <p class="editor__note">{{ 'lessons.detail.editor.text.fieldsNote' | transloco }}</p>
      </div>

      @if (fieldProblem(); as problem) {
        <hq-band variant="error" [open]="true" [dismissible]="false" [title]="'band.failed' | transloco">
          {{ problem }}
        </hq-band>
      }

      @if (viewMode.debug()) {
        <details class="editor__raw" data-hq-raw-json>
          <summary class="editor__raw-summary">{{ 'lessons.detail.editor.raw' | transloco }}</summary>
          <div class="editor__raw-body">
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
              [disabled]="fieldsDisabled()"
            />
            @if (validatorErrors().length > 0) {
              <div>
                <p class="editor__raw-title">{{ 'lessons.detail.editor.rawErrors' | transloco }}</p>
                <ul class="editor__raw-list">
                  @for (line of validatorErrors(); track $index) {
                    <li>{{ line }}</li>
                  }
                </ul>
              </div>
            }
          </div>
        </details>
      }

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

    .editor__saving {
      font-size: var(--hq-font-label-size);
      font-weight: var(--hq-font-label-weight);
      color: var(--hq-color-ink-soft);
    }

    .editor__quick {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--hq-space-12) var(--hq-space-16);
    }

    .editor__note {
      grid-column: 1 / -1;
      font-size: var(--hq-font-label-size);
      color: var(--hq-color-ink-soft);
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

    .editor__raw {
      border: var(--hq-size-rule-thin) solid var(--hq-color-rule);
      padding: var(--hq-space-12) var(--hq-space-16);
    }

    .editor__raw-summary {
      font-size: var(--hq-font-label-size);
      font-weight: var(--hq-font-label-weight);
      color: var(--hq-color-ink-soft);
      cursor: pointer;
    }

    .editor__raw-body {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-12);
      padding-block-start: var(--hq-space-12);
    }

    .editor__raw-title {
      font-size: var(--hq-font-label-size);
      font-weight: var(--hq-font-label-weight);
      color: var(--hq-color-error-ink);
    }

    .editor__raw-list {
      margin: 0;
      padding-inline-start: var(--hq-space-24);
      font-family: var(--hq-font-family-mono);
      font-size: var(--hq-text-theme-xs);
      color: var(--hq-color-error-ink);
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
  /** `debug` is an Admin's, and opens the Raw JSON panel below the fields. */
  protected readonly viewMode = inject(ViewModeService);

  readonly stop = input.required<Stop | null>();
  readonly images = input<readonly EditorImage[]>([]);
  /** The page is mid-request: every control locks rather than racing the server. */
  readonly disabled = input(false);
  /** True while `from-text` is in flight — the model is writing, and it takes seconds. */
  readonly saving = input(false);
  /** Why the last text save did not land; cleared by the page when the next one starts. */
  readonly failure = input<StopSaveFailure | null>(null);
  /** The validator's own lines from the last 422 — the Raw panel's, never a teacher's. */
  readonly validatorErrors = input<readonly string[]>([]);

  /** The quick fields / raw JSON path: the stop document to `PUT`, already schema-valid. */
  readonly saved = output<Stop>();
  /** The CR5 path: the teacher's English, for `POST …/stops/{id}/from-text`. */
  readonly textSaved = output<string>();
  readonly regenerated = output<void>();
  readonly deleted = output<void>();
  readonly imageAttached = output<File>();
  /** True while either draft differs from the stop the server last gave us. */
  readonly dirtyChange = output<boolean>();

  /** What the teacher reads and writes — the main surface. */
  protected readonly prose = signal('');
  /** The JSON document behind the quick fields and the raw panel. */
  protected readonly text = signal('');
  private readonly validation = signal<{ readonly valid: boolean; readonly errors: readonly string[] } | null>(null);

  private readonly serverProse = computed(() => this.stop()?.teacherText ?? '');

  private readonly serverText = computed(() => {
    const stop = this.stop();
    return stop ? `${JSON.stringify(stopJson(stop), null, 2)}\n` : '';
  });

  protected readonly proseDirty = computed(() => this.prose() !== this.serverProse());
  protected readonly jsonDirty = computed(() => this.text() !== this.serverText());
  private readonly dirty = computed(() => this.proseDirty() || this.jsonDirty());

  /** One half at a time — see the class comment on why this is a refusal and not a merge. */
  protected readonly fieldsDisabled = computed(() => this.disabled() || this.proseDirty());

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

  protected readonly proseError = computed(() => {
    this.lang();
    return this.prose().length > MAX_TEXT ? this.t('lessons.detail.editor.text.tooLong') : null;
  });

  protected readonly proseHint = computed(() => {
    this.lang();
    if (this.jsonDirty()) return this.t('lessons.detail.editor.text.fieldsFirst');
    return this.t('lessons.detail.editor.text.hint');
  });

  /** Valid JSON is what the `PUT` needs; the text path needs only something non-empty. */
  private readonly jsonSavable = computed(() => {
    if (!this.jsonDirty() || this.idChanged() || this.declaredType() === null) return false;
    return this.viewMode.debug() ? this.validation()?.valid === true : this.quickProblem() === null;
  });

  /** `undefined` for a path the document does not carry — which for these five is a problem. */
  private valueAt(path: string): unknown {
    return path
      .split('/')
      .filter((key) => key !== '')
      .reduce<unknown>(
        (node, key) =>
          typeof node === 'object' && node !== null ? (node as Record<string, unknown>)[key] : undefined,
        this.parsed(),
      );
  }

  /** The first quick field outside its bounds, without Ajv — see {@link QUICK_FIELDS}. */
  private readonly quickProblem = computed<(typeof QUICK_FIELDS)[number] | null>(() => {
    if (this.parsed() === null) return null;
    return (
      QUICK_FIELDS.find((field) => {
        if (field.max === null) return false;
        const value = this.valueAt(field.path);
        return typeof value !== 'string' || value.trim() === '' || value.length > field.max;
      }) ?? null
    );
  });

  private readonly proseSavable = computed(
    () => this.proseDirty() && this.prose().trim() !== '' && this.proseError() === null,
  );

  protected readonly canSave = computed(
    () => !this.disabled() && !this.saving() && (this.proseSavable() || this.jsonSavable()),
  );

  /**
   * A quick field the schema will not take, named — because in teacher view the JSON that
   * carries the reason is not on screen.
   *
   * `Play.schema.json` holds every one of the five to `minLength: 1` and a maximum (40 for a
   * title, 90 for what the pot says, 200 for a tip), and emptying one is the ordinary way to
   * make the document invalid. The Raw panel shows Ajv's own lines; this says which field,
   * which is the whole of what a teacher can act on.
   */
  protected readonly fieldProblem = computed(() => {
    this.lang();
    if (this.viewMode.debug() || !this.jsonDirty()) return null;
    if (this.idChanged()) return this.t('lessons.detail.editor.idImmutable');
    const field = this.quickProblem();
    if (field === null) return this.parsed() === null ? this.t('lessons.detail.editor.text.documentProblem') : null;
    return this.t('lessons.detail.editor.text.fieldProblem', { field: this.t(field.labelKey) });
  });

  protected readonly saveReason = computed(() => {
    this.lang();
    if (this.canSave() || this.disabled() || this.saving()) return null;
    if (!this.dirty()) return this.t('lessons.detail.editor.noChanges');
    if (this.proseDirty()) return this.t('lessons.detail.editor.text.writeSomething');
    return this.fieldProblem() ?? this.t('lessons.detail.editor.fixFirst');
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
    // A different stop (or the same one saved and re-read) reseeds both drafts. Selecting a stop,
    // typing, then selecting it again is meant to lose the edit — the confirm lives on the page.
    effect(() => this.prose.set(this.serverProse()));
    effect(() => this.text.set(this.serverText()));

    effect((onCleanup) => {
      const text = this.text();
      // The 605 kB chunk is asked for here and nowhere else, so this is the one gate it needs.
      const type = this.viewMode.debug() ? declaredStopType(text, STOP_TYPES) : null;
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

  /** The prose is the surface, so it wins: the other half is disabled while it is dirty. */
  protected save(): void {
    if (!this.canSave()) return;
    if (this.proseSavable()) {
      this.textSaved.emit(this.prose());
      return;
    }
    const parsed = this.parsed();
    if (parsed !== null) this.saved.emit(parsed as unknown as Stop);
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}

