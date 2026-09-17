/* hq-flag: none (shell) — gated by the `lesson.write` permission; the source cards inside are
   individually gated by `lessons.pdf|slides|images|manual` (`*hqFeature`, below). */
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { type AdminLesson, AdminLessonsApi, apiErrorOf, type School, SchoolsApi, TeacherApi } from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { FLAGS } from '../../core/flags/flag.service';
import { activeLang } from '../../core/i18n/active-lang';
import {
  BandComponent,
  ButtonComponent,
  CardComponent,
  EmptyStateComponent,
  InputComponent,
  PageComponent,
  ProgressBarComponent,
  SelectComponent,
  SkeletonComponent,
  type SelectOption,
} from '../../ui';
import {
  type CreateLessonRequest,
  type Curriculum,
  createLessonBody,
  type LessonSource,
  type Subject,
  SUBJECTS,
  isCurriculum,
  isSubject,
} from './lessons.models';

const PRACTICE_LENGTHS = [5, 7, 10] as const;
const SOURCE_DEFS: readonly { readonly id: LessonSource; readonly flag: string }[] = [
  { id: 'pdf', flag: FLAGS.lessonsPdf },
  { id: 'slides', flag: FLAGS.lessonsSlides },
  { id: 'images', flag: FLAGS.lessonsImages },
  { id: 'manual', flag: FLAGS.lessonsManual },
];

interface SourceCardView {
  readonly id: LessonSource;
  readonly flag: string;
  readonly title: string;
  readonly hint: string;
}

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.');
  return dot === -1 ? '' : name.slice(dot + 1).toLowerCase();
}

function acceptsFile(source: LessonSource, name: string): boolean {
  const ext = extensionOf(name);
  if (source === 'pdf') return ext === 'pdf';
  if (source === 'slides') return ext === 'pptx';
  if (source === 'images') return ext === 'png' || ext === 'jpg' || ext === 'jpeg';
  return false;
}

const ACCEPT: Record<Exclude<LessonSource, 'manual'>, string> = {
  pdf: '.pdf,application/pdf',
  slides: '.pptx,application/vnd.openxmlformats-officedocument.presentationml.presentation',
  images: '.png,.jpg,.jpeg,image/png,image/jpeg',
};

/**
 * New lesson (Admin + Teacher, §6 screen 13): course → date/title/length → source → upload.
 *
 * One page, not a paginated wizard — the Lesson and Content cards appear once the course is
 * complete, same as `webAdmin`'s `NewLessonScreen`. The sticky footer carries the one primary
 * action throughout: disabled with a reason while the form is incomplete, and its label
 * changes to name what "Create" will do once a source is picked.
 *
 * PDF, Slides and Images share one upload chain (`createLesson` → `uploadFiles` → `analyze`);
 * Manual only creates the lesson. A failure after the lesson exists rolls it back
 * (`DELETE /admin/lessons/{id}`) rather than leaving an orphan the list would show forever.
 */
@Component({
  selector: 'hq-new-lesson-page',
  imports: [
    PageComponent,
    CardComponent,
    SelectComponent,
    InputComponent,
    ButtonComponent,
    BandComponent,
    ProgressBarComponent,
    SkeletonComponent,
    EmptyStateComponent,
    FeatureDirective,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './new-lesson.page.html',
  styleUrl: './new-lesson.page.scss',
})
export class NewLessonPage {
  private readonly lessonsApi = inject(AdminLessonsApi);
  private readonly teacherApi = inject(TeacherApi);
  private readonly schoolsApi = inject(SchoolsApi);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  protected readonly isAdmin = computed(() => this.auth.role() === 'ADMIN');
  protected readonly basePath = computed(() => (this.isAdmin() ? '/admin/lessons' : '/teacher/lessons'));
  protected readonly pickSchool = computed(() => this.isAdmin() && !this.auth.effectiveSchoolId());

  // ---- where the course options come from -------------------------------------------------

  private readonly teacherOptions = rxResource({
    params: () => (this.isAdmin() ? undefined : true),
    stream: () => this.teacherApi.teacherOptions(),
  });

  private readonly adminSchool = rxResource<School | null, string | undefined>({
    params: () => (this.isAdmin() ? (this.auth.effectiveSchoolId() ?? undefined) : undefined),
    stream: ({ params: id }) => this.schoolsApi.school(id),
    defaultValue: null,
  });

  protected readonly optionsLoading = computed(() =>
    this.isAdmin() ? this.adminSchool.isLoading() : this.teacherOptions.isLoading(),
  );

  protected readonly availableCurricula = computed<readonly Curriculum[]>(() => {
    if (this.isAdmin()) return (this.adminSchool.value()?.curriculumOptions ?? []).filter(isCurriculum);
    const curriculum = this.teacherOptions.value()?.curriculum;
    return isCurriculum(curriculum) ? [curriculum] : [];
  });

  protected readonly availableGrades = computed<readonly number[]>(() => {
    const grades = this.isAdmin() ? this.adminSchool.value()?.gradeOptions : this.teacherOptions.value()?.grades;
    return [...(grades ?? [])].sort((a, b) => a - b);
  });

  protected readonly availableSubjects = computed<readonly Subject[]>(() => {
    if (this.isAdmin()) return SUBJECTS;
    return (this.teacherOptions.value()?.subjects ?? []).filter(isSubject);
  });

  protected readonly curriculumOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return this.availableCurricula().map((c) => ({ value: c, label: this.translateOrEmpty(`curriculum.${c}`) || c }));
  });

  protected readonly gradeOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return this.availableGrades().map((g) => ({
      value: String(g),
      label: this.t('lessons.chooser.gradeOption', { grade: g }),
    }));
  });

  protected readonly subjectOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return this.availableSubjects().map((s) => ({ value: s, label: this.translateOrEmpty(`subject.${s}`) || s }));
  });

  protected readonly practiceLengthOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return PRACTICE_LENGTHS.map((n) => ({ value: String(n), label: this.t('lessons.new.practiceLengthOption', { count: n }) }));
  });

  // ---- the form ------------------------------------------------------------------------

  protected readonly curriculum = signal<Curriculum | null>(null);
  protected readonly grade = signal<number | null>(null);
  protected readonly subject = signal<Subject | null>(null);
  protected readonly date = signal<string>(todayIso());
  protected readonly title = signal<string>('');
  protected readonly practiceLength = signal<number>(7);
  protected readonly source = signal<LessonSource | null>(null);
  protected readonly files = signal<readonly File[]>([]);
  protected readonly dragging = signal(false);
  protected readonly busy = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  protected readonly curriculumValue = computed(() => this.curriculum() ?? '');
  protected readonly gradeValue = computed(() => (this.grade() === null ? '' : String(this.grade())));
  protected readonly subjectValue = computed(() => this.subject() ?? '');
  protected readonly practiceLengthValue = computed(() => String(this.practiceLength()));

  protected onCurriculumChange(value: string): void {
    this.curriculum.set(isCurriculum(value) ? value : null);
  }

  protected onGradeChange(value: string): void {
    this.grade.set(value ? Number(value) : null);
  }

  protected onSubjectChange(value: string): void {
    this.subject.set(isSubject(value) ? value : null);
  }

  protected onPracticeLengthChange(value: string): void {
    const parsed = Number(value);
    if ((PRACTICE_LENGTHS as readonly number[]).includes(parsed)) this.practiceLength.set(parsed);
  }

  protected readonly step1Valid = computed(
    () => this.curriculum() !== null && this.grade() !== null && this.subject() !== null,
  );
  protected readonly dateValid = computed(() => this.date() !== '');
  protected readonly ready = computed(
    () =>
      this.step1Valid() &&
      this.dateValid() &&
      this.busy() === null &&
      this.source() !== null &&
      (this.source() === 'manual' || this.files().length > 0),
  );

  protected readonly primaryReason = computed(() => {
    this.lang();
    if (!this.step1Valid()) return this.t('lessons.new.reason.chooseCourse');
    if (!this.dateValid()) return this.t('lessons.new.reason.fixDate');
    if (this.source() === null) return this.t('lessons.new.reason.chooseSource');
    if (this.source() !== 'manual' && this.files().length === 0) return this.t('lessons.new.reason.addFile');
    return null;
  });

  protected readonly primaryLabel = computed(() => {
    this.lang();
    const source = this.source();
    return source ? this.t(`lessons.new.create.${source}`) : this.t('lessons.new.createDefault');
  });

  // ---- source + files -------------------------------------------------------------------

  protected readonly sourceCards = computed<readonly SourceCardView[]>(() => {
    this.lang();
    return SOURCE_DEFS.map((def) => ({
      id: def.id,
      flag: def.flag,
      title: this.t(`lessons.new.source.${def.id}.title`),
      hint: this.t(`lessons.new.source.${def.id}.hint`),
    }));
  });

  protected readonly acceptFor = computed(() => {
    const source = this.source();
    return source && source !== 'manual' ? ACCEPT[source] : '';
  });

  protected readonly dropzoneTitle = computed(() => {
    this.lang();
    const source = this.source();
    return source && source !== 'manual' ? this.t(`lessons.new.dropzone.${source}`) : '';
  });

  protected selectSource(id: LessonSource): void {
    if (this.busy() !== null) return;
    this.source.set(id);
    this.files.set(id === 'manual' ? [] : this.files().filter((file) => acceptsFile(id, file.name)));
    this.error.set(null);
  }

  protected onDragOver(event: DragEvent): void {
    event.preventDefault();
    if (this.busy() === null) this.dragging.set(true);
  }

  protected onDragLeave(): void {
    this.dragging.set(false);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    if (this.busy() !== null) return;
    this.addFiles(Array.from(event.dataTransfer?.files ?? []));
  }

  protected onFileInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.addFiles(Array.from(input.files ?? []));
    input.value = '';
  }

  protected removeFile(index: number): void {
    this.files.update((files) => files.filter((_, i) => i !== index));
  }

  protected formatFileSize(bytes: number): string {
    if (bytes >= 1_000_000) return `${(bytes / 1_000_000).toFixed(1)} MB`;
    if (bytes >= 1_000) return `${Math.round(bytes / 1000)} KB`;
    return `${bytes} B`;
  }

  private addFiles(selected: readonly File[]): void {
    const source = this.source();
    if (!source || source === 'manual' || selected.length === 0) return;
    const accepted = selected.filter((file) => acceptsFile(source, file.name));
    const rejected = selected.filter((file) => !acceptsFile(source, file.name));

    if (accepted.length > 0) {
      this.files.update((files) => {
        const merged = source === 'images' ? [...files, ...accepted] : accepted;
        return merged.slice(0, 10);
      });
    }
    this.error.set(
      rejected.length > 0
        ? this.t('lessons.new.fileRejected', { name: rejected.map((f) => f.name).join(', '), reason: this.reasonFor(source) })
        : null,
    );
  }

  private reasonFor(source: Exclude<LessonSource, 'manual'>): string {
    return this.t(`lessons.new.reason${source === 'pdf' ? 'Pdf' : source === 'slides' ? 'Slides' : 'Images'}`);
  }

  // ---- create → upload → analyze, with rollback on failure --------------------------------

  protected create(): void {
    const source = this.source();
    if (!this.ready() || !source) return;

    const request: CreateLessonRequest = {
      curriculum: this.curriculum()!,
      grade: this.grade()!,
      subject: this.subject()!,
      date: this.date(),
      practiceLength: this.practiceLength(),
      source: source === 'manual' ? 'manual' : undefined,
      title: this.title().trim() || undefined,
    };

    this.error.set(null);
    this.busy.set(this.t('lessons.new.busy.creating'));
    this.lessonsApi.createLesson(createLessonBody(request)).subscribe({
      next: (lesson) => this.afterCreate(lesson, source),
      error: (err: unknown) => {
        this.busy.set(null);
        this.error.set(apiErrorOf(err)?.message ?? this.t('band.unreachable'));
      },
    });
  }

  private afterCreate(lesson: AdminLesson, source: LessonSource): void {
    if (source === 'manual') {
      this.busy.set(null);
      void this.router.navigate([this.basePath(), lesson.id], { queryParams: { notice: 'lessons.new.createdManual' } });
      return;
    }
    this.busy.set(this.t('lessons.new.busy.uploading'));
    this.lessonsApi.uploadFiles(lesson.id, [...this.files()]).subscribe({
      next: () => this.startAnalyze(lesson.id),
      error: (err: unknown) => this.rollback(lesson.id, err),
    });
  }

  private startAnalyze(lessonId: string): void {
    this.busy.set(this.t('lessons.new.busy.analyzing'));
    this.lessonsApi.analyze(lessonId).subscribe({
      next: () => {
        this.busy.set(null);
        void this.router.navigate([this.basePath(), lessonId], { queryParams: { notice: 'lessons.new.created' } });
      },
      error: (err: unknown) => this.rollback(lessonId, err),
    });
  }

  /** No orphan lesson: an upload or analyze failure removes the draft the create step made. */
  private rollback(lessonId: string, cause: unknown): void {
    const message = apiErrorOf(cause)?.message ?? this.t('band.unreachable');
    this.lessonsApi.deleteLesson(lessonId).subscribe({
      next: () => {
        this.busy.set(null);
        this.error.set(this.t('lessons.new.rollback', { message }));
      },
      error: () => {
        this.busy.set(null);
        this.error.set(this.t('lessons.new.rollbackFailed', { message }));
      },
    });
  }

  // ---- wiring: query-param preselect, single-option auto-select (teacher only) -----------

  private preselected = false;

  constructor() {
    effect(() => {
      if (this.preselected || this.pickSchool()) return;
      if (this.isAdmin() ? this.adminSchool.isLoading() : this.teacherOptions.isLoading()) return;
      this.preselected = true;
      this.applyPreselect();
    });
  }

  private applyPreselect(): void {
    const params = this.route.snapshot.queryParamMap;
    const curricula = this.availableCurricula();
    const grades = this.availableGrades();
    const subjects = this.availableSubjects();
    const teacherOnly = !this.isAdmin();

    const qCurriculum = params.get('curriculum');
    const curriculum =
      isCurriculum(qCurriculum) && curricula.includes(qCurriculum)
        ? qCurriculum
        : teacherOnly && curricula.length === 1
          ? curricula[0]
          : null;
    if (curriculum) this.curriculum.set(curriculum);

    const qGrade = params.get('grade');
    const grade =
      qGrade && grades.includes(Number(qGrade))
        ? Number(qGrade)
        : teacherOnly && grades.length === 1
          ? (grades[0] ?? null)
          : null;
    if (grade !== null) this.grade.set(grade);

    const qSubject = params.get('subject');
    const subject =
      isSubject(qSubject) && subjects.includes(qSubject) ? qSubject : teacherOnly && subjects.length === 1 ? subjects[0] : null;
    if (subject) this.subject.set(subject);

    const qDate = params.get('date');
    if (qDate && /^\d{4}-\d{2}-\d{2}$/.test(qDate)) this.date.set(qDate);
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }

  private translateOrEmpty(key: string): string {
    const text = this.t(key);
    return text === key ? '' : text;
  }
}
