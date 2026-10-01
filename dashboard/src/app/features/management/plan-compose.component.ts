import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  model,
  output,
  signal,
} from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import {
  type PlanContext,
  type PlanDraft,
  EMPTY_PLAN_DRAFT,
  PLAN_FILE_TYPES,
  PLAN_PDF_TYPE,
  canPostPlan,
  planBlocked,
  planErrors,
  planFileError,
} from '../../core/broadcasts/plan-rules';
import { weekOptions } from '../../core/broadcasts/broadcast.rules';
import {
  BandComponent,
  DialogComponent,
  ProgressBarComponent,
  type SelectOption,
  SelectComponent,
} from '../../ui';

/**
 * **Compose a weekly plan** (MH2 item 4): a grade, a week, and a picture of the plan.
 *
 * MH1 made a plan an image, which is what the department actually produces — a printed sheet per
 * grade, photographed or exported. So the sheet asks for the three things the server requires and
 * nothing else: no title, no body, no audience (the server gives a plan its own), no class
 * checkboxes (they would make the grade a 400).
 *
 * **The file is validated before it leaves the browser.** Type and size are checked here against
 * `PLAN_FILE_TYPES` and the two size caps — the same rules `MediaController` enforces — because a
 * 6 MB scan that uploads for twenty seconds and then answers 413 has wasted the twenty seconds and
 * told her nothing she could not have been told at once.
 *
 * **A PDF is accepted as well as a picture** (D2, list 3): the sheet the department exports, up to
 * 10 MB. Whatever she picked is named under the drop target — a picture also gets its preview, and
 * a PDF gets its name and size, which is all there is to show of a document before it is opened.
 *
 * **Replacing is confirmed with a red band**, not with a second dialog: posting for a grade and week
 * that already have a plan deletes the previous row, its read marks and its bell rows
 * (`BroadcastService.replacePlan`), and that is destructive in the sense the design language means.
 * The primary action says "Replace plan" rather than "Post" while the band is up, so the words on
 * the button are the words for what it does.
 */
@Component({
  selector: 'hq-plan-compose',
  imports: [BandComponent, DialogComponent, ProgressBarComponent, SelectComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-dialog
      [sheet]="true"
      [(open)]="open"
      [title]="'plans.add' | transloco"
      [confirmLabel]="(replacing() ? 'plans.replace' : 'broadcasts.post') | transloco"
      [cancelLabel]="'ui.cancel' | transloco"
      [confirmDisabled]="!valid()"
      [loading]="posting()"
      (confirmed)="submit()"
    >
      @if (blocked()) {
        <hq-band variant="notice" [open]="true" [dismissible]="false" [title]="'plans.blocked' | transloco">
          {{ 'plans.blockedHint' | transloco }}
        </hq-band>
      } @else {
        <hq-select
          [label]="'broadcasts.grade' | transloco"
          [required]="true"
          [placeholder]="'plans.gradePick' | transloco"
          [options]="gradeChoices()"
          [value]="gradeValue()"
          [error]="errorFor('grade')"
          (valueChange)="patch({ grade: $event === '' ? null : +$event })"
        />
        <hq-select
          [label]="'broadcasts.week' | transloco"
          [required]="true"
          [placeholder]="'broadcasts.weekPick' | transloco"
          [options]="weekChoices()"
          [value]="draft().weekStart"
          [error]="errorFor('weekStart')"
          (valueChange)="patch({ weekStart: $event })"
        />

        <!-- A drop target that is also a label for the input inside it: dragging a photo in and
             pressing Enter on the keyboard are the same control, not two. -->
        <label
          class="pc__drop"
          [class.is-over]="dragging()"
          (dragover)="onDragOver($event)"
          (dragleave)="dragging.set(false)"
          (drop)="onDrop($event)"
        >
          <span class="pc__label">{{ 'plans.image' | transloco }}</span>
          <span class="hq-muted">{{ 'plans.imageHint' | transloco }}</span>
          <input
            class="pc__file"
            type="file"
            [accept]="accept"
            [attr.aria-label]="'plans.image' | transloco"
            (change)="onPick($event)"
          />
          @if (preview(); as source) {
            <img class="pc__preview" [src]="source" [alt]="previewAlt()" />
          }
          @if (chosen(); as file) {
            <span class="pc__chosen" aria-live="polite">
              @if (file.pdf) {
                <span class="hq-badge" aria-hidden="true">PDF</span>
              }
              <span dir="auto">{{ file.name }}</span>
              <span class="hq-muted">{{ 'plans.fileSize' | transloco: { size: file.size } }}</span>
            </span>
          }
        </label>
        @if (errorFor('file'); as message) {
          <p class="pc__error">{{ message }}</p>
        }

        @if (replacing()) {
          <hq-band
            variant="error"
            [open]="true"
            [dismissible]="false"
            [title]="'plans.replacesTitle' | transloco"
          >
            {{ 'plans.replacesBody' | transloco }}
          </hq-band>
        }

        @if (posting()) {
          <hq-progress-bar [value]="progress()" [label]="'plans.uploading' | transloco" />
        }
      }
    </hq-dialog>
  `,
  styles: `
    .pc__drop {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-2);
      padding: var(--hq-space-3);
      border: var(--hq-rule) dashed var(--hq-ink);
      cursor: pointer;
    }

    .pc__drop.is-over {
      border-style: solid;
      border-color: var(--hq-accent);
    }

    .pc__label {
      font-weight: var(--hq-font-label-weight);
    }

    .pc__preview {
      max-width: 100%;
      max-height: 320px;
      object-fit: contain;
    }

    .pc__chosen {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--hq-space-8);
      overflow-wrap: anywhere;
    }

    .pc__error {
      color: var(--hq-accent);
    }
  `,
})
export class PlanComposeComponent {
  private readonly transloco = inject(TranslocoService);

  protected readonly accept = PLAN_FILE_TYPES.join(',');

  readonly open = model<boolean>(false);
  readonly ctx = input.required<PlanContext>();
  /** Today in the school's zone, `YYYY-MM-DD` — what the week list is counted from. */
  readonly today = input.required<string>();
  /** The grade and week the card she pressed was for. */
  readonly initial = input<PlanDraft>(EMPTY_PLAN_DRAFT);
  readonly posting = input<boolean>(false);
  /** 0–100 while the image is going up; the page reports it from the upload's own events. */
  readonly progress = input<number>(0);
  /** The grade/week pairs that already have a plan, as `grade|weekStart`. */
  readonly taken = input<readonly string[]>([]);

  readonly submitted = output<PlanDraft>();

  protected readonly draft = linkedSignal<PlanDraft, PlanDraft>({
    source: () => this.initial(),
    computation: (source) => source,
  });

  protected readonly dragging = signal(false);
  /**
   * The picked file as a `data:` URL.
   *
   * **Not `URL.createObjectURL`**, although it would be the cheaper carrier: the shipped CSP is
   * `img-src 'self' https: data:` (`DashboardController`), which has no `blob:` — so the browser
   * would refuse to paint an object URL in QA and in production, which is the same trap
   * `core/media/media.service.ts` documents for the protected routes.
   */
  protected readonly preview = signal<string | null>(null);

  /** The file she picked: its name, its size in megabytes (the unit is the translation's), its kind. */
  protected readonly chosen = computed(() => {
    const file = this.draft().file;
    if (file === null) return null;
    return {
      name: file.name,
      size: (file.size / (1024 * 1024)).toFixed(1),
      pdf: file.type === PLAN_PDF_TYPE,
    };
  });

  protected readonly blocked = computed(() => planBlocked(this.ctx()));
  protected readonly valid = computed(() => canPostPlan(this.draft(), this.ctx()));

  protected readonly gradeChoices = computed<readonly SelectOption[]>(() =>
    this.ctx().grades.map((grade) => ({
      value: String(grade),
      label: this.transloco.translate<string>('broadcasts.gradeN', { grade }),
    })),
  );

  protected readonly gradeValue = computed(() => {
    const grade = this.draft().grade;
    return grade === null ? '' : String(grade);
  });

  protected readonly weekChoices = computed<readonly SelectOption[]>(() =>
    weekOptions(this.today()).map((week) => ({ value: week, label: weekLabel(this.transloco, week) })),
  );

  /** This grade already has this week's plan, so posting replaces it. */
  protected readonly replacing = computed(() => {
    const draft = this.draft();
    if (draft.grade === null || draft.weekStart === '') return false;
    return this.taken().includes(`${draft.grade}|${draft.weekStart}`);
  });

  protected readonly previewAlt = computed(() =>
    this.transloco.translate<string>('plans.imageAlt', {
      grade: this.draft().grade ?? '',
      week: weekLabel(this.transloco, this.draft().weekStart || this.today()),
    }),
  );

  /**
   * A field's message, but **only once she has touched it**: `planErrors` reports a missing grade
   * and a missing image on a sheet she has just opened, and three red lines on an empty form is the
   * screen telling her off for not having filled it in yet.
   */
  protected errorFor(field: keyof ReturnType<typeof planErrors>): string | null {
    const draft = this.draft();
    const untouched =
      (field === 'grade' && draft.grade === null) ||
      (field === 'weekStart' && draft.weekStart === '') ||
      (field === 'file' && draft.file === null);
    if (untouched) return null;
    const key = planErrors(draft, this.ctx())[field];
    return key === null ? null : this.transloco.translate<string>(key);
  }

  protected patch(part: Partial<PlanDraft>): void {
    this.draft.update((draft) => ({ ...draft, ...part }));
  }

  protected onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(true);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    this.take(event.dataTransfer?.files?.[0] ?? null);
  }

  protected onPick(event: Event): void {
    this.take((event.target as HTMLInputElement).files?.[0] ?? null);
  }

  /**
   * A refused file is **not** kept as the draft's, but it is still what the message is about: the
   * draft holds it so `planErrors` can say which of the two rules it broke, and `valid()` stays
   * false because that is exactly what the error means.
   */
  private take(file: File | null): void {
    if (file === null) return;
    this.patch({ file });
    this.preview.set(null);
    // Nothing to paint for a refused file, or for a PDF — which is named, not previewed.
    if (planFileError(file) !== null || file.type === PLAN_PDF_TYPE) return;
    const reader = new FileReader();
    reader.onload = () => {
      if (typeof reader.result === 'string' && this.draft().file === file) this.preview.set(reader.result);
    };
    reader.readAsDataURL(file);
  }

  protected submit(): void {
    if (!this.valid()) return;
    this.submitted.emit(this.draft());
  }
}

/** "Week of 12 Oct 2026", in the reader's own locale. UTC, because a week start is a calendar day. */
export function weekLabel(transloco: TranslocoService, week: string): string {
  return transloco.translate<string>('broadcasts.weekOf', {
    date: new Date(`${week}T00:00:00Z`).toLocaleDateString(undefined, {
      timeZone: 'UTC',
      day: 'numeric',
      month: 'short',
      year: 'numeric',
    }),
  });
}
