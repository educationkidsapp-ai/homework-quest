/* hq-flag: none (shell) — gated by the `lesson.write` permission; the source cards inside are
   individually gated by `lessons.pdf|slides|images|manual` (`*hqFeature`, below). */
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import {
  type School,
  SchoolsApi,
  TeacherApi,
  type TeacherClassCard,
} from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { FLAGS } from '../../core/flags/flag.service';
import { activeLang } from '../../core/i18n/active-lang';
import {
  BandComponent,
  ButtonComponent,
  CardComponent,
  DayPickerComponent,
  EmptyStateComponent,
  InputComponent,
  PageComponent,
  ProgressBarComponent,
  SelectComponent,
  SkeletonComponent,
  type SelectOption,
} from '../../ui';
import { LessonCreationService } from './lesson-creation.service';
import {
  type Curriculum,
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

/** One teaching assignment: a class *and* the subject she teaches in it. */
function assignmentKeyOf(card: TeacherClassCard): string {
  return `${card.classId ?? ''}::${card.subject ?? ''}`;
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
  if (source === 'pdf') return ext === 'pdf' || ext === 'docx' || ext === 'doc';
  if (source === 'slides') return ext === 'pptx' || ext === 'pptm' || ext === 'ppsx';
  if (source === 'images') return ext === 'png' || ext === 'jpg' || ext === 'jpeg';
  return false;
}

const ACCEPT: Record<Exclude<LessonSource, 'manual'>, string> = {
  pdf: '.pdf,.docx,.doc,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  slides: '.pptx,.pptm,.ppsx,application/vnd.openxmlformats-officedocument.presentationml.presentation,application/vnd.ms-powerpoint.presentation.macroEnabled.12,application/vnd.openxmlformats-officedocument.presentationml.slideshow',
  images: '.png,.jpg,.jpeg,image/png,image/jpeg',
};

const MAX_FILES = 10;
/** `multipart.max-file-size: 25MB` in `server/src/main/resources/application.yml`. */
const MAX_FILE_BYTES = 25 * 1024 * 1024;
/** Headroom under `multipart.max-request-size: 120MB` for the rest of the multipart body. */
const MAX_TOTAL_BYTES = 100 * 1024 * 1024;

/**
 * New lesson (Admin + Teacher, §6 screen 13): course → date/title/length → source → upload.
 *
 * One page, not a paginated wizard — the Lesson and Content cards appear once the course is
 * complete, same as `webAdmin`'s `NewLessonScreen`. The sticky footer carries the one primary
 * action throughout: disabled with a reason while the form is incomplete, and its label
 * changes to name what "Create" will do once a source is picked.
 *
 * PDF, Slides and Images share one upload chain (create → upload → analyze); Manual only
 * creates the lesson. E3 moved that chain into {@link LessonCreationService}: this page starts
 * it, draws its progress, and offers "Work in background" — the teacher who leaves is not
 * pulled back later, and the failure path (rollback, or the upload job's own `error`) is
 * handled whether or not anybody is still watching.
 */
@Component({
  selector: 'hq-new-lesson-page',
  imports: [
    PageComponent,
    CardComponent,
    SelectComponent,
    InputComponent,
    DayPickerComponent,
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
  private readonly creation = inject(LessonCreationService);
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

  /**
   * N2.2: `?classId=` — This week's `+` knows which class's cell it came from, and a lesson
   * created from there has to land in **that section**, not merely in the same course. The card
   * is read back from `GET /teacher/classes` (curriculum, grade and subject all come off it, and
   * disagreeing query params lose to it). N2.4b made that the teacher's only path: she always
   * authors into a section, with or without the link, so the id is a preselect rather than the
   * one way in.
   */
  private readonly classIdParam = signal<string | null>(this.route.snapshot.queryParamMap.get('classId'));

  /**
   * N2.4b: read for **every** teacher, not only the ones arriving with a `?classId=`.
   *
   * `POST /teacher/lessons` is now the only create a teacher makes, and it takes a section. So
   * the section is the first thing she picks (§4: "Class and subject are fixed") and the course
   * pickers are the Admin's alone — a teacher never sees a curriculum/grade pair that could
   * describe two of her sections at once.
   */
  private readonly myClasses = rxResource<readonly TeacherClassCard[], boolean | undefined>({
    params: () => (this.isAdmin() ? undefined : true),
    stream: () => this.teacherApi.myClasses(),
    defaultValue: [],
  });

  /**
   * The **assignment** she is authoring into, keyed by class *and* subject.
   *
   * `GET /teacher/classes` answers one card per assignment, so `1A · Math` and `1A · English`
   * are two cards sharing a `classId`. Keying the select on the id alone would make them the
   * same option and silently author English into the Math lesson.
   */
  protected readonly assignmentKey = signal<string | null>(null);

  protected readonly fixedClass = computed<TeacherClassCard | null>(() => {
    const cards = this.myClasses.value();
    const key = this.assignmentKey();
    if (key !== null) return cards.find((card) => assignmentKeyOf(card) === key) ?? null;
    const id = this.classIdParam();
    if (id === null) return null;
    const subject = this.route.snapshot.queryParamMap.get('subject');
    return (
      cards.find((card) => card.classId === id && (!subject || card.subject === subject)) ??
      cards.find((card) => card.classId === id) ??
      null
    );
  });

  /** Her assignments, as the Class select's options — `1A · Math · British`. */
  protected readonly classOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return this.myClasses.value().map((card) => {
      const cName = card.className ?? '';
      const curr = this.translateOrEmpty(`curriculum.${card.curriculum}`) || (card.curriculum ?? '');
      const subject = this.translateOrEmpty(`subject.${card.subject}`) || (card.subject ?? '');
      const hasCurriculumInName = curr && cName.toLowerCase().includes(curr.toLowerCase());
      const label = hasCurriculumInName
        ? this.t('lessons.new.classOptionShort', { class: cName, subject })
        : this.t('lessons.new.classOption', { class: cName, subject, curriculum: curr });
      return {
        value: assignmentKeyOf(card),
        label: label || `${cName} · ${subject}`,
      };
    });
  });

  /** The `+` fixed the section; she may not move the lesson to another one from here. */
  protected readonly classFixed = computed(() => this.classIdParam() !== null);

  protected readonly classValue = computed(() => {
    const card = this.fixedClass();
    return card ? assignmentKeyOf(card) : '';
  });

  protected onClassChange(value: string): void {
    this.assignmentKey.set(value || null);
  }

  private readonly adminSchool = rxResource<School | null, string | undefined>({
    params: () => (this.isAdmin() ? (this.auth.effectiveSchoolId() ?? undefined) : undefined),
    stream: ({ params: id }) => this.schoolsApi.school(id),
    defaultValue: null,
  });

  protected readonly optionsLoading = computed(() =>
    this.isAdmin() ? this.adminSchool.isLoading() : this.myClasses.isLoading(),
  );

  /** A teacher with no assignment at all cannot author anything — say so instead of an empty select. */
  protected readonly noClasses = computed(() => !this.isAdmin() && this.myClasses.value().length === 0);

  /**
   * The Admin's course pickers. A teacher has none: her course is the section she picked, and
   * `GET /teacher/options` is not consulted at all any more — it answers `grades: []` for a
   * seeded teacher, so a picker built from it could never be pre-set however many grades the
   * link named, and it cannot tell 1A apart from 1B in the first place.
   */
  protected readonly availableCurricula = computed<readonly Curriculum[]>(() => {
    const fixed = this.fixedClass()?.curriculum;
    if (fixed !== undefined) return isCurriculum(fixed) ? [fixed] : [];
    return (this.adminSchool.value()?.curriculumOptions ?? []).filter(isCurriculum);
  });

  protected readonly availableGrades = computed<readonly number[]>(() => {
    const fixed = this.fixedClass()?.grade;
    if (fixed !== undefined) return [fixed];
    return [...(this.adminSchool.value()?.gradeOptions ?? [])].sort((a, b) => a - b);
  });

  protected readonly availableSubjects = computed<readonly Subject[]>(() => {
    const fixed = this.fixedClass()?.subject;
    if (fixed !== undefined) return isSubject(fixed) ? [fixed] : [];
    return SUBJECTS;
  });

  protected readonly curriculumOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return this.availableCurricula().map((c) => ({
      value: c,
      label: this.translateOrEmpty(`curriculum.${c}`) || c,
    }));
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
    return this.availableSubjects().map((s) => ({
      value: s,
      label: this.translateOrEmpty(`subject.${s}`) || s,
    }));
  });

  protected readonly practiceLengthOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return PRACTICE_LENGTHS.map((n) => ({
      value: String(n),
      label: this.t('lessons.new.practiceLengthOption', { count: n }),
    }));
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
  /** This form's own complaints (a rejected file). The chain's failures live on the service. */
  protected readonly error = signal<string | null>(null);

  protected readonly step = this.creation.step;
  protected readonly uploadFailed = this.creation.uploadFailed;
  /** The progress card's caption, and what everything on the form is disabled by. */
  protected readonly busy = computed<string | null>(() => {
    this.lang();
    const step = this.creation.step();
    return step === null ? null : this.t(`lessons.new.busy.${step}`);
  });
  protected readonly bandError = computed(() => this.error() ?? this.creation.error());

  protected dismissError(): void {
    this.error.set(null);
    if (!this.uploadFailed()) this.creation.error.set(null);
  }

  protected retryUpload(): void {
    this.creation.retryUpload();
  }

  protected deleteDraft(): void {
    this.creation.deleteDraft();
  }

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

  /**
   * A teacher's course is her class; an Admin's is the trio. Both end with the same three
   * signals set, so everything downstream — the date card, the source cards, `create()` — is
   * one code path.
   */
  protected readonly step1Valid = computed(() =>
    this.isAdmin()
      ? this.curriculum() !== null && this.grade() !== null && this.subject() !== null
      : this.fixedClass() !== null && this.subject() !== null,
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
    if (!this.step1Valid()) {
      return this.t(this.isAdmin() ? 'lessons.new.reason.chooseCourse' : 'lessons.new.reason.chooseClass');
    }
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

  /**
   * The source cards on screen.
   *
   * Once "Write it yourself" is chosen the three Upload cards go (owner, 2026-09-26): she has
   * said there is no file, and three invitations to add one are the only thing left on the screen
   * that mentions them. "Pick another source" clears the choice and brings all four back.
   */
  protected readonly visibleSourceCards = computed<readonly SourceCardView[]>(() =>
    this.source() === 'manual' ? this.sourceCards().filter((card) => card.id === 'manual') : this.sourceCards(),
  );

  protected readonly acceptFor = computed(() => {
    const source = this.source();
    return source && source !== 'manual' ? ACCEPT[source] : '';
  });

  protected readonly dropzoneTitle = computed(() => {
    this.lang();
    const source = this.source();
    return source && source !== 'manual' ? this.t(`lessons.new.dropzone.${source}`) : '';
  });

  /** `null` un-picks, which is what "Pick another source" does. */
  protected selectSource(id: LessonSource | null): void {
    if (this.busy() !== null) return;
    this.source.set(id);
    this.files.set(id === null || id === 'manual' ? [] : this.files().filter((f) => acceptsFile(id, f.name)));
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

  /**
   * Validates by type, then per-file size, then the 10-file and 100 MB ceilings — each stage
   * only sees what the one before it let through, and each rejection is named in the band
   * rather than dropped without a word. Nothing here is a server-side guarantee: it mirrors
   * `application.yml`'s `multipart.max-file-size`/`max-request-size` so the failure shows up
   * here, in place, instead of as a 413 after the upload has already started.
   */
  private addFiles(selected: readonly File[]): void {
    const source = this.source();
    if (!source || source === 'manual' || selected.length === 0) return;

    const wrongType = selected.filter((file) => !acceptsFile(source, file.name));
    const rightType = selected.filter((file) => acceptsFile(source, file.name));
    const tooLarge = rightType.filter((file) => file.size > MAX_FILE_BYTES);
    const sized = rightType.filter((file) => file.size <= MAX_FILE_BYTES);

    const kept = source === 'images' ? this.files() : [];
    const room = Math.max(0, MAX_FILES - kept.length);
    const withinCount = sized.slice(0, room);
    const overCount = sized.slice(room);

    let runningTotal = kept.reduce((sum, file) => sum + file.size, 0);
    const withinTotal: File[] = [];
    const overTotal: File[] = [];
    for (const file of withinCount) {
      if (runningTotal + file.size > MAX_TOTAL_BYTES) {
        overTotal.push(file);
        continue;
      }
      runningTotal += file.size;
      withinTotal.push(file);
    }

    if (withinTotal.length > 0) this.files.set([...kept, ...withinTotal]);

    const messages: string[] = [];
    if (wrongType.length > 0) {
      messages.push(
        this.t('lessons.new.fileRejected', {
          name: wrongType.map((f) => f.name).join(', '),
          reason: this.reasonFor(source),
        }),
      );
    }
    if (tooLarge.length > 0) {
      messages.push(this.t('lessons.new.fileTooLarge', { name: tooLarge.map((f) => f.name).join(', ') }));
    }
    if (overCount.length > 0) messages.push(this.t('lessons.new.tooManyFiles'));
    if (overTotal.length > 0) {
      messages.push(this.t('lessons.new.totalTooLarge', { name: overTotal.map((f) => f.name).join(', ') }));
    }
    this.error.set(messages.length > 0 ? messages.join(' ') : null);
  }

  private reasonFor(source: Exclude<LessonSource, 'manual'>): string {
    return this.t(
      `lessons.new.reason${source === 'pdf' ? 'Pdf' : source === 'slides' ? 'Slides' : 'Images'}`,
    );
  }

  // ---- create → upload → analyze, run by the service --------------------------------------

  /**
   * The screen starts the chain and then only watches it. {@link LessonCreationService} owns
   * the three calls, the rollback and the upload job's verdict, so closing this page stops
   * nothing and pulls nobody back (E3).
   */
  protected create(): void {
    const source = this.source();
    if (!this.ready() || !source) return;
    this.error.set(null);
    this.creation.start(
      {
        classId: this.fixedClass()?.classId,
        curriculum: this.curriculum() ?? undefined,
        grade: this.grade() ?? undefined,
        subject: this.subject()!,
        date: this.date(),
        source,
        practiceLength: this.practiceLength(),
        title: this.title().trim() || undefined,
      },
      source,
      this.files(),
    );
  }

  /** "Work in background": to the list, and the chain carries on without an audience. */
  protected workInBackground(): void {
    this.creation.workInBackground();
    void this.router.navigate([this.basePath()]);
  }

  // ---- wiring: query-param preselect, single-option auto-select (teacher only) -----------

  private preselected = false;

  constructor() {
    // Only a chain that finishes while *this* page instance is open navigates. A chain that
    // finished while nobody was here has nothing left to say — its outcome reached her through
    // the bell — and that is true however she left, not only via "Work in background": a
    // leftover `done` would otherwise bounce her straight to that old lesson the next time she
    // opens New lesson. This page starts clean.
    if (!this.creation.busy() && (this.creation.done() || this.creation.background())) {
      this.creation.reset();
    }

    // The one navigation this page still makes, and only for the teacher who stayed to watch.
    effect(() => {
      if (!this.creation.done() || this.creation.background()) return;
      const id = this.creation.lessonId();
      const notice = this.creation.noticeKey();
      if (!id) return;
      this.creation.reset();
      // E4a: a hand-written lesson opens with the Add question sheet already up. The lesson page
      // consumes `compose` once and drops it from the URL; every other source lands on the strip.
      const compose = notice === 'lessons.new.createdManual' ? { compose: '1' } : {};
      void this.router.navigate([this.basePath(), id], {
        queryParams: notice ? { notice, ...compose } : {},
      });
    });

    effect(() => {
      if (this.preselected || this.pickSchool()) return;
      if (this.isAdmin() ? this.adminSchool.isLoading() : this.myClasses.isLoading()) return;
      // A `?classId=` is the whole point of the preselect when it is there; applying the rest
      // first would flash a different class into the pickers and then correct itself.
      this.preselected = true;
      this.applyPreselect();
    });

    // Picking another class re-fills the course the rest of the form reads off it.
    effect(() => {
      const card = this.fixedClass();
      if (!card) return;
      if (isCurriculum(card.curriculum)) this.curriculum.set(card.curriculum);
      if (card.grade !== undefined) this.grade.set(card.grade);
      if (isSubject(card.subject)) this.subject.set(card.subject);
    });
  }

  /**
   * The link's query params, and then the section, which wins over the three of them that
   * merely describe it: they are a convenience for the link, the class is the fact.
   */
  private applyPreselect(): void {
    const params = this.route.snapshot.queryParamMap;

    if (this.isAdmin()) {
      const qCurriculum = params.get('curriculum');
      if (isCurriculum(qCurriculum) && this.availableCurricula().includes(qCurriculum)) {
        this.curriculum.set(qCurriculum);
      }
      const qGrade = Number(params.get('grade'));
      if (this.availableGrades().includes(qGrade)) this.grade.set(qGrade);
      const qSubject = params.get('subject');
      if (isSubject(qSubject)) this.subject.set(qSubject);
    } else if (this.classIdParam() === null) {
      // No link: one assignment is chosen for her, several are a choice worth making.
      const cards = this.myClasses.value();
      if (cards.length === 1) this.assignmentKey.set(assignmentKeyOf(cards[0]!));
    }

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
