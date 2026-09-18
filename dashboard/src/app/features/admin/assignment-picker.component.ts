/* hq-flag: none (shell) — part of the Classes/Teachers admin screens, gated by `teacher.manage`
   rather than by a flag (see `classes.page.ts`). */
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  model,
  output,
  signal,
} from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { TeachersApi, apiErrorOf, type TeacherAccount, type TeachingAssignment } from '../../api';
import { BandService } from '../../core/band/band.service';
import { activeLang } from '../../core/i18n/active-lang';
import { CheckboxComponent, DialogComponent } from '../../ui';
import { SUBJECTS, isCurriculum, isSubject, type Subject } from '../lessons/lessons.models';
import { heldPairs, pairKey, teacherLabel, type AdminClass, type AssignmentChoice } from './admin.models';

/**
 * Who teaches what, for one teacher (`docs/teacher-flow.md` §2).
 *
 * Every active section of her curriculum, crossed with the subjects she teaches, one checkbox
 * each. A pair another teacher already holds is **disabled and says whose it is** — the server
 * answers 409 with that name, and a picker that let the box be ticked anyway would turn a
 * knowable fact into a failed save. The 409 is still handled, because two Admins can tick the
 * same box in the same second: the band shows the server's sentence and the boxes go back to
 * what the server last said they were.
 *
 * The whole set is sent on save (`PUT …/assignments` replaces), so the dialog is one decision
 * rather than a row of small ones, and closing it without saving changes nothing.
 */
@Component({
  selector: 'hq-assignment-picker',
  imports: [DialogComponent, CheckboxComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-dialog
      [(open)]="open"
      [title]="title()"
      [confirmLabel]="'admin.teachers.assign.save' | transloco"
      [confirmDisabled]="choices().length === 0"
      [confirmReason]="choices().length === 0 ? ('admin.teachers.assign.none' | transloco) : null"
      [loading]="saving()"
      (confirmed)="save()"
    >
      @if (choices().length === 0) {
        <p class="picker__none">{{ 'admin.teachers.assign.none' | transloco }}</p>
      }
      @for (subject of subjects(); track subject) {
        <fieldset class="picker__group">
          <legend class="picker__legend">{{ 'subject.' + subject | transloco }}</legend>
          @for (choice of choicesOf(subject); track choice.classId) {
            <hq-checkbox
              [label]="choice.className + ' · ' + choice.course"
              [hint]="
                choice.takenBy ? ('admin.teachers.assign.taken' | transloco: { name: choice.takenBy }) : null
              "
              [disabled]="choice.takenBy !== null"
              [checked]="choice.checked"
              (checkedChange)="toggle(choice, $event)"
            />
          }
        </fieldset>
      }
    </hq-dialog>
  `,
  styles: `
    .picker__group {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-8);
      margin: 0;
      padding: var(--hq-space-16);
      border: var(--hq-size-rule) solid var(--hq-color-line);
    }

    .picker__legend {
      padding-inline: var(--hq-space-8);
      font-size: var(--hq-font-label-size);
      font-weight: var(--hq-font-label-weight);
      letter-spacing: var(--hq-font-letter-spacing-label);
      text-transform: uppercase;
    }

    .picker__none {
      margin: 0;
      color: var(--hq-color-ink-soft);
    }
  `,
})
export class AssignmentPickerComponent {
  private readonly teachersApi = inject(TeachersApi);
  private readonly transloco = inject(TranslocoService);
  private readonly band = inject(BandService);
  private readonly lang = activeLang();

  readonly open = model(false);
  readonly teacher = input.required<TeacherAccount>();
  readonly sections = input.required<readonly AdminClass[]>();
  /** `classId|subject` → the name of the teacher who holds it, for every teacher but this one. */
  readonly taken = input.required<ReadonlyMap<string, string>>();

  readonly saved = output<readonly TeachingAssignment[]>();

  protected readonly saving = signal(false);

  /** Ticked pairs, seeded from the teacher's current assignments and reset by every open. */
  private readonly ticked = signal<ReadonlySet<string>>(new Set());
  private seededFor: string | null = null;

  protected readonly title = computed(() => {
    this.lang();
    return this.t('admin.teachers.assign.title', { name: teacherLabel(this.teacher()) });
  });

  protected readonly subjects = computed<readonly Subject[]>(() =>
    SUBJECTS.filter((subject) => (this.teacher().subjects ?? []).includes(subject)),
  );

  protected readonly choices = computed<readonly AssignmentChoice[]>(() => {
    this.lang();
    const teacher = this.teacher();
    const curriculum = teacher.curriculum;
    const held = this.ticked();
    const taken = this.taken();
    return this.sections()
      .filter((section) => section.active !== false && section.curriculum === curriculum)
      .flatMap((section) =>
        this.subjects().map((subject) => {
          const classId = section.id ?? '';
          const key = pairKey(classId, subject);
          return {
            classId,
            subject,
            className: section.name ?? '',
            course: this.courseWord(section.curriculum, section.grade ?? 0),
            checked: held.has(key),
            takenBy: taken.get(key) ?? null,
          };
        }),
      );
  });

  protected choicesOf(subject: Subject): readonly AssignmentChoice[] {
    return this.choices().filter((choice) => choice.subject === subject);
  }

  protected toggle(choice: AssignmentChoice, checked: boolean): void {
    if (choice.takenBy !== null) return;
    const next = new Set(this.ticked());
    const key = pairKey(choice.classId, choice.subject);
    if (checked) next.add(key);
    else next.delete(key);
    this.ticked.set(next);
  }

  protected save(): void {
    const teacher = this.teacher();
    const before = this.ticked();
    const assignments = [...before]
      .map((key) => key.split('|'))
      .filter(([classId, subject]) => classId && isSubject(subject))
      .map(([classId, subject]) => ({ classId: classId ?? '', subject: subject ?? '' }));

    this.saving.set(true);
    this.teachersApi.setAssignments(teacher.userId ?? '', { assignments }).subscribe({
      next: (result) => {
        this.saving.set(false);
        this.open.set(false);
        this.saved.emit(result);
      },
      error: (error: unknown) => {
        this.saving.set(false);
        // Back to what the server last said, so the boxes and the band agree with each other.
        this.ticked.set(heldPairs(teacher.assignments));
        this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
      },
    });
  }

  constructor() {
    // Every fresh open, and every change of teacher, starts from what the server says she holds
    // — so cancelling really is "nothing happened" rather than "nothing happened until next time".
    effect(() => {
      const teacher = this.teacher();
      const stamp = `${teacher.userId ?? ''}|${this.open() ? 'open' : 'closed'}|${(teacher.assignments ?? []).length}`;
      if (stamp === this.seededFor) return;
      this.seededFor = stamp;
      this.ticked.set(heldPairs(teacher.assignments));
    });
  }

  private courseWord(curriculum: string | undefined, grade: number): string {
    const word = isCurriculum(curriculum) ? this.t(`curriculum.${curriculum}`) : (curriculum ?? '');
    return this.t('admin.classes.course', { curriculum: word, grade });
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
