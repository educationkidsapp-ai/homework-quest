import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  model,
  output,
} from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import type { CreateBroadcastRequest } from '../../api';
import {
  AUDIENCE_ROLES,
  type AudienceRole,
  type BroadcastDraft,
  type BroadcastKind,
  type ComposableSection,
  type ComposeContext,
  EMPTY_DRAFT,
  MAX_BODY,
  MAX_TITLE,
  canChooseGrade,
  canPost,
  composeErrors,
  gradeOptions,
  kindsFor,
  requestOf,
  weekOptions,
} from '../../core/broadcasts/broadcast.rules';
import {
  CheckboxComponent,
  DialogComponent,
  InputComponent,
  type SelectOption,
  SelectComponent,
  TextareaComponent,
} from '../../ui';

/** A section the writer may name, with the label the checkbox carries. */
export interface ComposeSection extends ComposableSection {
  readonly className: string;
}

/**
 * **The one compose sheet** for everything a supervisor broadcasts (MG2b).
 *
 * RM3b built it inside the Broadcasts screen. MG2b gave the manager a second screen that writes
 * the same thing — Weekly plans, where the kind is fixed and the week and the grade arrive from
 * the card she pressed "Add plan" on — so the sheet moved here rather than being copied. The rules
 * it validates against are still `core/broadcasts/broadcast.rules.ts`; this component is the
 * fields, and `submitted` hands the caller the body those rules built. Who posts it stays with the
 * caller, because `POST /management/broadcasts` and `POST /coordinator/broadcasts` are two routes
 * and only the screen knows which one it holds.
 */
@Component({
  selector: 'hq-broadcast-compose',
  imports: [
    CheckboxComponent,
    DialogComponent,
    InputComponent,
    SelectComponent,
    TextareaComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-dialog
      [sheet]="true"
      [(open)]="open"
      [title]="title() | transloco"
      [confirmLabel]="'broadcasts.post' | transloco"
      [cancelLabel]="'ui.cancel' | transloco"
      [confirmDisabled]="!valid()"
      [loading]="posting()"
      (confirmed)="submit()"
    >
      @if (kindOptions().length > 1) {
        <hq-select
          [label]="'broadcasts.kind' | transloco"
          [options]="kindOptions()"
          [value]="draft().kind"
          (valueChange)="setKind($event)"
        />
      }

      @if (draft().kind === 'weekly_plan') {
        <hq-select
          [label]="'broadcasts.week' | transloco"
          [hint]="replacesHint() | transloco"
          [placeholder]="'broadcasts.weekPick' | transloco"
          [options]="weekChoices()"
          [value]="draft().weekStart"
          [error]="errorFor('weekStart')"
          (valueChange)="patch({ weekStart: $event })"
        />
      }

      <hq-input
        [label]="'broadcasts.titleLabel' | transloco"
        [required]="true"
        [maxLength]="MAX_TITLE"
        [value]="draft().title"
        [error]="errorFor('title')"
        (valueChange)="patch({ title: $event })"
      />
      <hq-textarea
        [label]="'broadcasts.bodyEn' | transloco"
        [required]="true"
        [rows]="4"
        [maxLength]="MAX_BODY"
        [value]="draft().bodyEn"
        [error]="errorFor('bodyEn')"
        (valueChange)="patch({ bodyEn: $event })"
      />
      <hq-textarea
        dir="rtl"
        [label]="'broadcasts.bodyAr' | transloco"
        [rows]="4"
        [maxLength]="MAX_BODY"
        [hint]="'broadcasts.bodyArHint' | transloco"
        [value]="draft().bodyAr"
        [error]="errorFor('bodyAr')"
        (valueChange)="patch({ bodyAr: $event })"
      />

      @if (isManager()) {
        <fieldset class="bc__set">
          <legend>{{ 'broadcasts.audience' | transloco }}</legend>
          @if (errorFor('audience'); as message) {
            <p class="bc__error">{{ message }}</p>
          }
          @for (who of audienceRoles; track who) {
            <hq-checkbox
              [label]="'broadcasts.audienceRole.' + who | transloco"
              [checked]="draft().audience.includes(who)"
              (checkedChange)="toggleAudience(who, $event)"
            />
          }
        </fieldset>

        @if (ctx().departments.length > 1) {
          <hq-select
            [label]="'broadcasts.department' | transloco"
            [hint]="'broadcasts.departmentHint' | transloco"
            [placeholder]="'broadcasts.departmentPick' | transloco"
            [required]="true"
            [options]="departmentOptions()"
            [value]="draft().department"
            [error]="errorFor('department')"
            (valueChange)="patch({ department: $event, sectionIds: [], grade: null })"
          />
          <!-- The server's rule, said once: a grade may not travel with named sections, and a row
               that names none cannot say which of her two departments it is for. -->
          <p class="hq-muted">{{ 'broadcasts.gradeTwoDepartments' | transloco }}</p>
        } @else {
          <hq-select
            [label]="'broadcasts.grade' | transloco"
            [hint]="'broadcasts.gradeHint' | transloco"
            [options]="gradeChoices()"
            [value]="gradeValue()"
            [error]="errorFor('grade')"
            (valueChange)="setGrade($event)"
          />
        }
      } @else {
        <p class="hq-muted">{{ 'broadcasts.audienceFixed' | transloco }}</p>
      }

      @if (draft().grade === null) {
        <fieldset class="bc__set">
          <legend>{{ 'broadcasts.classes' | transloco }}</legend>
          <p class="hq-muted">{{ 'broadcasts.classesHint' | transloco }}</p>
          @for (row of sectionChoices(); track row.classId) {
            <hq-checkbox
              [label]="row.className"
              [checked]="draft().sectionIds.includes(row.classId)"
              (checkedChange)="toggleSection(row.classId, $event)"
            />
          }
        </fieldset>
      }

      <hq-input
        type="date"
        [label]="'broadcasts.expires' | transloco"
        [hint]="'broadcasts.expiresHint' | transloco"
        [min]="ctx().today"
        [value]="draft().expires"
        [error]="errorFor('expires')"
        (valueChange)="patch({ expires: $event })"
      />
    </hq-dialog>
  `,
  styles: `
    .bc__set {
      margin: 0;
      padding: 0;
      border: 0;
    }

    .bc__set legend {
      padding: 0;
      font-weight: var(--hq-font-label-weight);
    }

    .bc__error {
      color: var(--hq-accent);
    }
  `,
})
export class BroadcastComposeComponent {
  private readonly transloco = inject(TranslocoService);

  protected readonly MAX_TITLE = MAX_TITLE;
  protected readonly MAX_BODY = MAX_BODY;
  protected readonly audienceRoles = AUDIENCE_ROLES;

  readonly open = model<boolean>(false);
  readonly ctx = input.required<ComposeContext>();
  readonly sections = input<readonly ComposeSection[]>([]);
  readonly posting = input<boolean>(false);
  /** Narrower than the role's own kinds — the Weekly plans screen writes plans and nothing else. */
  readonly kinds = input<readonly BroadcastKind[] | null>(null);
  /** The draft the sheet opens on: a blank one, or the week and grade of the card she pressed. */
  readonly initial = input<BroadcastDraft>(EMPTY_DRAFT);
  readonly title = input<string>('broadcasts.compose');

  readonly submitted = output<CreateBroadcastRequest>();

  /**
   * The draft, reset by the prefill the caller hands in.
   *
   * `linkedSignal` rather than an effect: "Add plan" on the grade-3 card and "Add plan" on the
   * all-grades card are two different `initial()`s, and the sheet has to be looking at the second
   * one the moment it reopens — while everything she types between them survives.
   */
  protected readonly draft = linkedSignal<BroadcastDraft, BroadcastDraft>({
    source: () => this.initial(),
    computation: (source) => source,
  });

  protected readonly isManager = computed(() => this.ctx().role === 'manager');

  protected readonly valid = computed(() => canPost(this.draft(), this.ctx()));

  protected readonly kindOptions = computed<readonly SelectOption[]>(() =>
    (this.kinds() ?? kindsFor(this.ctx().role)).map((kind) => ({
      value: kind,
      label: this.transloco.translate<string>(`broadcasts.kinds.${kind}`),
    })),
  );

  protected readonly weekChoices = computed<readonly SelectOption[]>(() =>
    weekOptions(this.ctx().today).map((week) => ({ value: week, label: this.weekLabel(week) })),
  );

  protected readonly departmentOptions = computed<readonly SelectOption[]>(() =>
    this.ctx().departments.map((curriculum) => ({
      value: curriculum,
      label: this.transloco.translate<string>(`curriculum.${curriculum}`),
    })),
  );

  /** "All grades" first — the department's own plan, which is what `grade` absent means. */
  protected readonly gradeChoices = computed<readonly SelectOption[]>(() => [
    { value: '', label: this.transloco.translate<string>('broadcasts.gradeAll') },
    ...(canChooseGrade(this.ctx()) ? gradeOptions(this.draft(), this.ctx()) : []).map((grade) => ({
      value: String(grade),
      label: this.transloco.translate<string>('broadcasts.gradeN', { grade }),
    })),
  ]);

  protected readonly gradeValue = computed(() => {
    const grade = this.draft().grade;
    return grade === null ? '' : String(grade);
  });

  /** The sections of the department she chose; one department has nothing to narrow by. */
  protected readonly sectionChoices = computed<readonly ComposeSection[]>(() => {
    const chosen = this.draft().department;
    return this.sections().filter((row) => chosen === '' || row.curriculum === chosen);
  });

  /** A plan replaces this week's for this grade; the other two kinds replace nothing. */
  protected readonly replacesHint = computed(() =>
    this.draft().grade === null ? 'broadcasts.weekReplaces' : 'broadcasts.weekReplacesGrade',
  );

  protected errorFor(field: keyof ReturnType<typeof composeErrors>): string | null {
    const key = composeErrors(this.draft(), this.ctx())[field];
    return key === null ? null : this.transloco.translate<string>(key);
  }

  /** A `<select>` answers a string; the kinds it was built from are the only ones it can answer. */
  protected setKind(kind: string): void {
    this.patch({ kind: kind as BroadcastKind, ...(kind === 'weekly_plan' ? {} : { weekStart: '' }) });
  }

  protected setGrade(grade: string): void {
    this.patch({ grade: grade === '' ? null : Number(grade), ...(grade === '' ? {} : { sectionIds: [] }) });
  }

  protected patch(part: Partial<BroadcastDraft>): void {
    this.draft.update((draft) => ({ ...draft, ...part }));
  }

  protected toggleAudience(who: AudienceRole, on: boolean): void {
    this.patch({
      audience: on ? [...this.draft().audience, who] : this.draft().audience.filter((role) => role !== who),
    });
  }

  protected toggleSection(classId: string, on: boolean): void {
    this.patch({
      sectionIds: on
        ? [...this.draft().sectionIds, classId]
        : this.draft().sectionIds.filter((id) => id !== classId),
    });
  }

  protected submit(): void {
    if (!this.valid()) return;
    this.submitted.emit(requestOf(this.draft(), this.ctx()));
  }

  private weekLabel(week: string): string {
    return this.transloco.translate<string>('broadcasts.weekOf', {
      date: new Date(`${week}T00:00:00Z`).toLocaleDateString(undefined, {
        timeZone: 'UTC',
        day: 'numeric',
        month: 'short',
      }),
    });
  }
}
