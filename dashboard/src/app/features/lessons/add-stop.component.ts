import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  input,
  model,
  output,
  type Signal,
  signal,
  viewChild,
} from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { activeLang } from '../../core/i18n/active-lang';
import {
  ButtonComponent,
  DialogComponent,
  InputComponent,
  SelectComponent,
  type SelectOption,
  type SelectOptionGroup,
  TextareaComponent,
} from '../../ui';
import type { StopType } from '../../ui/phone-preview';
import { type Subject, stopBody } from './lessons.models';
import { StopDraftService } from './stop-draft.service';
import { type EditorImage } from './stop-editor.component';
import { STOP_TEMPLATES, STOP_TEMPLATE_GROUPS, templatesByGroup } from './stop-templates';

/**
 * `Play.schema.json`'s `maxLength` on a stop's `title`, refused here rather than by the server.
 *
 * It said 80 until 2026-09-26, which no branch of the schema has ever allowed: a 41-character
 * title passed this form and `POST …/plays/{id}/stops` answered 400 on the template document,
 * before the assistant was ever asked. The quick-field title in `stop-editor.component.ts` holds
 * the same 40 (`QUICK_FIELDS`).
 */
const MAX_TITLE = 40;
/** Well under `StopTextService.MAX_TEXT` (8000): one stop's question, not a worksheet. */
const MAX_QUESTION = 2000;

/** Which field a validation message belongs under. */
type FieldName = 'title' | 'question' | 'type';

/**
 * CR2: "Add stop" is one form, not a menu of twenty-two shapes.
 *
 * **What the owner asked for.** One card, one column, five fields, labels above and helper text
 * under each, one primary Save and one Cancel — no tabs, no wizard, no advanced section. The
 * form opens as a dialog on a desktop and as a full-screen sheet under 768 px, because a phone
 * has no page left to float a panel over.
 *
 * **Why it still needs a type.** The twenty-two templates did not go away — a stop *is* one of
 * twenty-two documents, and the app cannot play "some question". What changed is who does the
 * work: the teacher picks the shape from one labelled list with a line saying what each is for,
 * writes the question in her own words, and CR5's Prompt D turns the pair into the exact JSON.
 * The old grouped CDK menu made that choice *before* she had written anything, out of a popup
 * of twenty-two lines with no explanation on any of them.
 *
 * **Why saving is two calls.** `POST …/stops` takes a schema-valid document, and
 * `POST …/stops/{id}/from-text` needs a stop to already exist (`StopTextService.fromText` looks
 * it up by id). So there is no "convert first, create after" order available: this creates the
 * chosen type's template with her title, picture and parent tip on it, then converts.
 *
 * **E4a: neither call is awaited here.** Both belong to {@link StopDraftService}, so Save closes
 * the sheet — or, for "Save and add another", empties it and puts the caret back in Title — while
 * the assistant writes. The new stop is already in the level's list with a row that says so, and
 * a refusal is reported on that row rather than in a form she has moved on from.
 *
 * No publish toggle: a stop has no published state of its own — it goes out with the lesson,
 * from the lesson page's own Publish button.
 */
@Component({
  selector: 'hq-add-stop',
  imports: [
    ButtonComponent,
    DialogComponent,
    InputComponent,
    TextareaComponent,
    SelectComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-dialog
      [(open)]="open"
      [sheet]="true"
      [guarded]="true"
      [title]="'lessons.detail.addStop.title' | transloco"
      [confirmLabel]="'lessons.detail.addStop.save' | transloco"
      [cancelLabel]="'lessons.detail.addStop.cancel' | transloco"
      (closeRequested)="requestCancel()"
      (confirmed)="save()"
    >
      <!-- The second way out of this sheet: ten questions in a row, without closing and
           re-opening it nine times. Secondary, because a screen has one primary action. -->
      <hq-button hqDialogAction variant="secondary" (pressed)="saveAndAddAnother()">
        {{ 'lessons.detail.addStop.saveAnother' | transloco }}
      </hq-button>

      <!-- Rendered only while open: a closed dialog's fields are still in the document, and a
           second "Title" in the page is a second thing a label, a shortcut and a screen reader
           can land on. -->
      @if (open()) {
        <div class="add-stop" data-hq-add-stop>
          <p class="add-stop__lede">{{ 'lessons.detail.addStop.lede' | transloco }}</p>

          <hq-input
            #titleField
            [label]="'lessons.detail.addStop.titleLabel' | transloco"
            [hint]="'lessons.detail.addStop.titleHint' | transloco"
            [required]="true"
            [maxLength]="MAX_TITLE"
            [error]="errorFor('title')"
            [value]="titleValue()"
            (valueChange)="titleValue.set($event)"
            (blurred)="touch('title')"
          />

          <hq-textarea
            [label]="'lessons.detail.addStop.questionLabel' | transloco"
            [hint]="'lessons.detail.addStop.questionHint' | transloco"
            [rows]="5"
            [required]="true"
            [maxLength]="MAX_QUESTION"
            [error]="errorFor('question')"
            [value]="questionValue()"
            (valueChange)="onQuestionChange($event)"
            (blurred)="touch('question')"
          />

          <hq-select
            [label]="'lessons.detail.addStop.typeLabel' | transloco"
            [placeholder]="'lessons.detail.addStop.typePlaceholder' | transloco"
            [groups]="typeGroups()"
            [hint]="typeHint()"
            [required]="true"
            [error]="errorFor('type')"
            [value]="typeValue()"
            (valueChange)="typeValue.set($event)"
            (blurred)="touch('type')"
          />

          <div class="add-stop__picture">
            <hq-select
              [label]="'lessons.detail.addStop.pictureLabel' | transloco"
              [placeholder]="'lessons.detail.editor.noImage' | transloco"
              [options]="imageOptions()"
              [hint]="'lessons.detail.addStop.pictureHint' | transloco"
              [markOptional]="true"
              [disabled]="images().length === 0"
              [value]="imageValue()"
              (valueChange)="imageValue.set($event)"
            />
            <label class="add-stop__attach">
              <input
                class="add-stop__file"
                type="file"
                accept="image/png,image/jpeg,image/webp"
                (change)="onAttachInput($event)"
              />
              <span class="add-stop__attach-label">{{ 'lessons.detail.editor.attach' | transloco }}</span>
            </label>
          </div>

          <fieldset class="add-stop__tip">
            <legend class="add-stop__tip-legend">{{ 'lessons.detail.addStop.tipLabel' | transloco }}</legend>
            <p class="add-stop__tip-hint" id="add-stop-tip-hint">
              {{ 'lessons.detail.addStop.tipHint' | transloco }}
            </p>
            <div class="add-stop__tip-pair" aria-describedby="add-stop-tip-hint">
              <hq-textarea
                [label]="'lessons.detail.editor.parentTipEn' | transloco"
                [rows]="2"
                [value]="tipEn()"
                (valueChange)="tipEn.set($event)"
              />
              <hq-textarea
                [label]="'lessons.detail.editor.parentTipAr' | transloco"
                [rows]="2"
                dir="rtl"
                [value]="tipAr()"
                (valueChange)="tipAr.set($event)"
              />
            </div>
          </fieldset>
        </div>
      }
    </hq-dialog>

    <!-- The dirty-cancel confirm. Its own dialog rather than a red band, because the form it is
         asking about is itself a modal, and a band behind it could not be read. -->
    <hq-dialog
      [(open)]="discardOpen"
      [title]="'lessons.detail.addStop.discardTitle' | transloco"
      [confirmLabel]="'lessons.detail.addStop.discardConfirm' | transloco"
      (confirmed)="discard()"
    >
      <p>{{ 'lessons.detail.addStop.discardBody' | transloco }}</p>
    </hq-dialog>
  `,
  styles: `
    @use 'mixins' as m;

    // One column, always: the owner's brief names it, and it is also the only layout that
    // survives the 375 px sheet without a second set of rules.
    .add-stop {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-24);
    }

    .add-stop__lede {
      color: var(--hq-color-ink-soft);
      font-size: var(--hq-text-theme-sm);
    }

    .add-stop__picture {
      display: flex;
      align-items: flex-end;
      gap: var(--hq-space-12);
    }

    .add-stop__picture hq-select {
      flex: 1;
    }

    // The file input is made invisible rather than removed with display:none: hidden that way it is
    // still focusable and still announced, so the label below is a real keyboard control.
    .add-stop__file {
      position: absolute;
      inline-size: var(--hq-size-rule-thin);
      block-size: var(--hq-size-rule-thin);
      opacity: 0;
    }

    .add-stop__attach {
      position: relative;
      display: inline-flex;
    }

    .add-stop__attach-label {
      @include m.control;
      display: inline-flex;
      align-items: center;
      block-size: var(--hq-size-control-height);
      padding-inline: var(--hq-space-16);
      background: var(--hq-color-surface);
      cursor: pointer;
    }

    .add-stop__file:focus-visible + .add-stop__attach-label {
      outline: var(--hq-size-focus-ring) solid var(--hq-color-focus);
      outline-offset: var(--hq-size-focus-offset);
    }

    .add-stop__tip {
      border: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-4);
    }

    .add-stop__tip-legend {
      font-size: var(--hq-text-theme-sm);
      line-height: calc(var(--hq-text-theme-sm-line) / var(--hq-text-theme-sm));
      font-weight: var(--hq-text-weight-medium);
      color: var(--hq-color-ink-strong);
    }

    .add-stop__tip-hint {
      font-size: var(--hq-font-label-size);
      color: var(--hq-color-ink-soft);
    }

    .add-stop__tip-pair {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: var(--hq-space-12);
      margin-block-start: var(--hq-space-8);
    }

    @include m.below(m.$sheet-breakpoint) {
      .add-stop__picture,
      .add-stop__tip-pair {
        grid-template-columns: 1fr;
        flex-direction: column;
        align-items: stretch;
      }
    }
  `,
})
export class AddStopComponent {
  private readonly drafts = inject(StopDraftService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  /** `hq-input` is a component, so the element carrying the real `<input>` is read by hand. */
  private readonly titleField: Signal<ElementRef<HTMLElement> | undefined> = viewChild('titleField', {
    read: ElementRef,
  });

  protected readonly MAX_TITLE = MAX_TITLE;
  protected readonly MAX_QUESTION = MAX_QUESTION;

  readonly open = model(false);
  /** The lesson the play belongs to — what the draft's row state is keyed to. */
  readonly lessonId = input.required<string>();
  /** The play the new stop joins; nothing can be saved without it. */
  readonly playId = input.required<string>();
  readonly subject = input.required<Subject>();
  readonly images = input<readonly EditorImage[]>([]);

  /** A question was handed to the assistant — the page says so and selects the new row. */
  readonly added = output<void>();
  readonly imageAttached = output<File>();

  protected readonly titleValue = signal('');
  protected readonly questionValue = signal('');
  protected readonly typeValue = signal<StopType | ''>('');
  protected readonly imageValue = signal('');
  protected readonly tipEn = signal('');
  protected readonly tipAr = signal('');

  protected readonly discardOpen = signal(false);
  private readonly touched = signal<ReadonlySet<FieldName>>(new Set());
  private readonly submitted = signal(false);

  protected readonly typeGroups = computed<readonly SelectOptionGroup<StopType>[]>(() => {
    this.lang();
    return STOP_TEMPLATE_GROUPS.map((group) => ({
      label: this.t(`lessons.detail.editor.group.${group}`),
      options: templatesByGroup(group).map((entry) => ({
        value: entry.type,
        label: this.t(`lessons.detail.stopType.${entry.type}`),
      })),
    }));
  });

  /** What the chosen type is for — the line the old menu never had room for. */
  protected readonly typeHint = computed(() => {
    this.lang();
    const type = this.typeValue();
    if (type === '') return this.t('lessons.detail.addStop.typeHint');
    return this.t(`lessons.detail.stopTypeFor.${type}`);
  });

  protected readonly imageOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return this.images().map((image, index) => ({
      value: image.id,
      label: this.t('lessons.detail.editor.imageOption', { index: index + 1 }),
    }));
  });

  /** Every problem the form can state, whether or not it is being shown yet. */
  private readonly problems = computed<ReadonlyMap<FieldName, string>>(() => {
    this.lang();
    const problems = new Map<FieldName, string>();
    const title = this.titleValue().trim();
    if (title === '') problems.set('title', this.t('lessons.detail.addStop.titleRequired'));
    else if (title.length > MAX_TITLE) problems.set('title', this.t('lessons.detail.addStop.titleTooLong'));

    const question = this.questionValue().trim();
    if (question === '') problems.set('question', this.t('lessons.detail.addStop.questionRequired'));
    else if (question.length > MAX_QUESTION)
      problems.set('question', this.t('lessons.detail.addStop.questionTooLong'));

    if (this.typeValue() === '') problems.set('type', this.t('lessons.detail.addStop.typeRequired'));
    return problems;
  });

  protected readonly valid = computed(() => this.problems().size === 0);

  /**
   * A field says what is wrong once the teacher has left it, or once she has pressed Save —
   * never while she is still typing the first letter of it.
   */
  protected errorFor(field: FieldName): string | null {
    if (!this.submitted() && !this.touched().has(field)) return null;
    return this.problems().get(field) ?? null;
  }

  protected touch(field: FieldName): void {
    this.touched.update((set) => new Set(set).add(field));
  }

  protected onQuestionChange(text: string): void {
    this.questionValue.set(text);
  }

  protected onAttachInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (file) this.imageAttached.emit(file);
  }

  /** Dirty means she wrote something. An untouched form closes without a question. */
  protected readonly dirty = computed(
    () =>
      this.titleValue() !== '' ||
      this.questionValue() !== '' ||
      this.typeValue() !== '' ||
      this.imageValue() !== '' ||
      this.tipEn() !== '' ||
      this.tipAr() !== '',
  );

  protected requestCancel(): void {
    if (this.dirty()) this.discardOpen.set(true);
    else this.close();
  }

  protected discard(): void {
    this.discardOpen.set(false);
    this.close();
  }

  private close(): void {
    this.open.set(false);
    this.reset();
  }

  private reset(): void {
    this.titleValue.set('');
    this.questionValue.set('');
    this.typeValue.set('');
    this.imageValue.set('');
    this.tipEn.set('');
    this.tipAr.set('');
    this.touched.set(new Set());
    this.submitted.set(false);
  }

  /**
   * Hand the question over and get out of the way.
   *
   * Everything after the validation is {@link StopDraftService}'s: the template stop, the
   * conversion, the 90 s timeout and whatever the server says about any of them. This sheet's
   * whole job ends at a valid form, so it can close on the click — which is the point of E4a.
   */
  protected save(): void {
    if (this.submit()) this.close();
  }

  /**
   * The same save, with the sheet kept open for the next one.
   *
   * The type stays — a teacher adding five questions is usually adding five of a kind — and so
   * does the picture and the parent tip; the title and the question are hers alone and are
   * emptied. Focus goes back to Title, because that is where she would put it herself.
   */
  protected saveAndAddAnother(): void {
    if (!this.submit()) return;
    this.titleValue.set('');
    this.questionValue.set('');
    this.touched.set(new Set());
    this.submitted.set(false);
    this.titleField()?.nativeElement.querySelector('input')?.focus();
  }

  /** The shared half: validate, build the template document, start the draft. */
  private submit(): boolean {
    this.submitted.set(true);
    if (!this.valid()) return false;

    const template = STOP_TEMPLATES.find((entry) => entry.type === this.typeValue());
    if (!template) return false;

    const title = this.titleValue().trim();
    const skeleton = template.make(this.subject()) as unknown as Record<string, unknown>;
    const draft: Record<string, unknown> = { ...skeleton, title };
    if (this.imageValue() !== '') draft['imageId'] = this.imageValue();
    const tip = skeleton['parentTip'] as { en: string; ar: string } | undefined;
    if (tip && (this.tipEn().trim() !== '' || this.tipAr().trim() !== '')) {
      draft['parentTip'] = { en: this.tipEn().trim() || tip.en, ar: this.tipAr().trim() || tip.ar };
    }

    // The title leads the text because that is the shape `StopText.describe` gives a stop back
    // in, so Prompt D is asked to keep the heading she typed rather than invent one.
    const text = `${title}\n\n${this.questionValue().trim()}`;
    this.drafts.add(this.lessonId(), this.playId(), stopBody(draft), text);
    this.added.emit();
    return true;
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate(key, params);
  }
}
