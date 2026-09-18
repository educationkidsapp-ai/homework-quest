/* hq-flag: none (shell) — gated by `lesson.read`/`lesson.write`/`lesson.delete` permissions,
   not a flag: the lesson pipeline ships with the dashboard rather than behind a toggle. */
import { CdkMenu, CdkMenuItem, CdkMenuTrigger } from '@angular/cdk/menu';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import {
  type AdminLesson,
  AdminLessonStatusEnum,
  AdminReportsApi,
  type CalendarResponse,
  TeacherApi,
} from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import {
  BandComponent,
  ButtonComponent,
  EmptyStateComponent,
  InputComponent,
  PageComponent,
  SelectComponent,
  SkeletonComponent,
  TableComponent,
  TabsComponent,
  type SelectOption,
  type Tab,
  type TableColumn,
} from '../../ui';
import { LessonApiService } from './lesson-api.service';
import { LessonsCalendarComponent } from './lessons-calendar.component';
import {
  type Curriculum,
  CURRICULA,
  GRADES,
  type LessonStatus,
  errorStepOf,
  isCurriculum,
  isRunningStatus,
  jobStatusAsLessonStatus,
  readStoredCourse,
  writeStoredCourse,
} from './lessons.models';

interface LessonRowView {
  readonly id: string;
  readonly title: string;
  readonly classLabel: string;
  readonly date: string;
  readonly status: LessonStatus;
  readonly errorLabel: string | null;
  readonly schoolName: string;
  readonly source: AdminLesson;
}

type ConfirmMode = 'row' | 'all-failed' | null;
type ViewMode = 'list' | 'calendar';

const POLL_MS = 2500;

/**
 * All lessons (Admin, §6 screen 8) and My lessons (Teacher, §6 screen 12) — one component,
 * because `GET /admin/lessons` is one endpoint scoped by the caller's tenant filter. The role
 * only decides which columns show (Admin's school column) and where the curriculum/grade
 * chooser's options come from (every course for Admin, `GET /teacher/options` for a teacher).
 *
 * The lesson detail page and the New lesson wizard stay the P3.1 stub until P3.2c/d; a row
 * here only links to `/…/lessons/:id`, and the primary action only links to `/…/lessons/new`.
 */
@Component({
  selector: 'hq-lessons-page',
  imports: [
    PageComponent,
    SelectComponent,
    InputComponent,
    ButtonComponent,
    TabsComponent,
    TableComponent,
    EmptyStateComponent,
    SkeletonComponent,
    BandComponent,
    LessonsCalendarComponent,
    RouterLink,
    CanDirective,
    CdkMenu,
    CdkMenuItem,
    CdkMenuTrigger,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './lessons.page.html',
  styleUrl: './lessons.page.scss',
})
export class LessonsPage {
  private readonly lessonsApi = inject(LessonApiService);
  private readonly reportsApi = inject(AdminReportsApi);
  private readonly teacherApi = inject(TeacherApi);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  private readonly params = toSignal(this.route.queryParamMap, {
    initialValue: this.route.snapshot.queryParamMap,
  });

  private restoredForUser: string | null = null;

  protected readonly isAdmin = computed(() => this.auth.role() === 'ADMIN');
  protected readonly basePath = computed(() => (this.isAdmin() ? '/admin/lessons' : '/teacher/lessons'));
  /** "Delete all failed" is a tenant-wide sweep with no teacher alias — see `LessonApiService`. */
  protected readonly canDeleteFailed = computed(() => this.lessonsApi.supportsDeleteFailed());
  protected readonly pageTitle = computed(() => {
    this.lang();
    return this.t(this.isAdmin() ? 'nav.allLessons' : 'nav.myLessons');
  });

  // ---- the curriculum → grade chooser -----------------------------------------------------

  private readonly teacherOptions = rxResource({
    params: () => (this.isAdmin() ? undefined : true),
    stream: () => this.teacherApi.teacherOptions(),
  });

  protected readonly availableCurricula = computed<readonly Curriculum[]>(() => {
    if (this.isAdmin()) return CURRICULA;
    const curriculum = this.teacherOptions.value()?.curriculum;
    return curriculum === 'american' || curriculum === 'british' ? [curriculum] : [];
  });

  /**
   * The grades she may choose from.
   *
   * `GET /teacher/options.grades` comes off her **teacher profile**, which the office fills in
   * and the one-school seed leaves empty — so it is unioned with the grades of the classes she
   * is actually assigned to, which the same response already carries. Without that a seeded
   * teacher gets a Grade select with nothing in it, and "All lessons of this class" (N2.3) lands
   * on a chooser that cannot be set to the class she came from.
   */
  protected readonly availableGrades = computed<readonly number[]>(() => {
    if (this.isAdmin()) return GRADES;
    const options = this.teacherOptions.value();
    const assigned = (options?.classes ?? []).map((section) => section.grade ?? 0);
    return [...new Set([...(options?.grades ?? []), ...assigned])]
      .filter((grade) => grade > 0)
      .sort((a, b) => a - b);
  });

  protected readonly curriculumOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return this.availableCurricula().map((curriculum) => ({
      value: curriculum,
      label: this.translateOrEmpty(`curriculum.${curriculum}`) || curriculum,
    }));
  });

  protected readonly gradeOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return this.availableGrades().map((grade) => ({
      value: String(grade),
      label: this.t('lessons.chooser.gradeOption', { grade }),
    }));
  });

  protected readonly curriculum = signal<Curriculum | null>(null);
  protected readonly grade = signal<number | null>(null);
  protected readonly gradeValue = computed(() => {
    const grade = this.grade();
    return grade === null ? '' : String(grade);
  });

  protected readonly courseSubtitle = computed(() => {
    this.lang();
    const curriculum = this.curriculum();
    const grade = this.grade();
    if (!curriculum || !grade) return this.t('lessons.chooser.prompt');
    return this.t('lessons.chooser.summary', {
      curriculum: this.translateOrEmpty(`curriculum.${curriculum}`) || curriculum,
      grade,
    });
  });

  protected onCurriculumChange(value: string): void {
    this.curriculum.set(value === 'american' || value === 'british' ? value : null);
  }

  protected onGradeChange(value: string): void {
    this.grade.set(value ? Number(value) : null);
  }

  // ---- the list ------------------------------------------------------------------------

  protected readonly courseKey = computed(() => {
    const curriculum = this.curriculum();
    const grade = this.grade();
    return curriculum && grade ? `${curriculum}/${grade}` : null;
  });

  private readonly listParams = computed(() => {
    const course = this.courseKey();
    if (!course) return undefined;
    const schoolId = this.isAdmin() ? (this.auth.effectiveSchoolId() ?? '') : '';
    return `${course}|${schoolId}`;
  });

  protected readonly lessons = rxResource<readonly AdminLesson[], string | undefined>({
    params: () => this.listParams(),
    stream: ({ params }) => {
      const [course, schoolId] = params.split('|');
      const [curriculum, gradeText] = (course ?? '').split('/');
      return this.lessonsApi.list({
        curriculum: curriculum ?? '',
        grade: Number(gradeText ?? ''),
        schoolId: schoolId || undefined,
      });
    },
    defaultValue: [],
  });

  protected readonly hasRunning = computed(() =>
    this.lessons.value().some((row) => isRunningStatus(row.status)),
  );
  protected readonly hasFailed = computed(() =>
    this.lessons.value().some((row) => row.status === AdminLessonStatusEnum.ERROR),
  );

  protected readonly filterText = signal('');

  /**
   * `?classId=` — "All lessons of this class", the link N2.3's class page opens.
   *
   * Filtered here rather than at the endpoint because `GET /admin/lessons` takes a course, not a
   * class, and the course is already narrowed to the one the chooser is on; adding a server
   * parameter for a subset of a page we have in hand would be a round trip for nothing. The
   * link carries `curriculum` and `grade` too, so the chooser lands on the right course first.
   */
  protected readonly classFilter = computed(() => this.params().get('classId'));

  /**
   * Her classes, fetched **only** while a class filter is on, to put a name on it.
   *
   * The lessons in hand would name it too — but a class with no lessons yet is exactly the one
   * a teacher follows this link from, and "Showing only ." is not a sentence.
   */
  private readonly myClasses = rxResource({
    params: () => (!this.isAdmin() && this.classFilter() ? true : undefined),
    stream: () => this.teacherApi.myClasses(),
    defaultValue: [],
  });

  protected readonly classFilterLabel = computed(() => {
    this.lang();
    const classId = this.classFilter();
    if (!classId) return null;
    const name =
      this.myClasses.value().find((card) => card.classId === classId)?.className ??
      this.lessons.value().find((row) => row.classId === classId)?.className ??
      '';
    return name ? this.t('lessons.filter.onlyClass', { class: name }) : null;
  });

  protected readonly rows = computed<readonly LessonRowView[]>(() => {
    this.lang();
    const query = this.filterText().trim().toLowerCase();
    const classId = this.classFilter();
    return this.lessons
      .value()
      .filter((row) => !classId || row.classId === classId)
      .filter((row) => !query || (row.title ?? '').toLowerCase().includes(query))
      .map((row) => this.toRowView(row));
  });

  protected readonly columns = computed<readonly TableColumn<LessonRowView>[]>(() => {
    this.lang();
    const base: TableColumn<LessonRowView>[] = [
      { key: 'title', header: this.t('lessons.table.title'), width: '32%' },
      { key: 'classLabel', header: this.t('lessons.table.class') },
      { key: 'date', header: this.t('lessons.table.date') },
      { key: 'status', header: this.t('lessons.table.status') },
    ];
    if (this.isAdmin()) base.push({ key: 'schoolName', header: this.t('lessons.table.school') });
    return base;
  });

  // ---- view toggle -----------------------------------------------------------------------

  protected readonly view = signal<ViewMode>('list');
  protected readonly viewTabs = computed<readonly Tab<ViewMode>[]>(() => [
    { id: 'list', label: this.t('lessons.view.list') },
    { id: 'calendar', label: this.t('lessons.view.calendar') },
  ]);

  // ---- calendar ------------------------------------------------------------------------

  protected readonly calendarYear = signal(new Date().getUTCFullYear());
  protected readonly calendarMonth = signal(new Date().getUTCMonth() + 1);

  private readonly calendarParams = computed(() => {
    if (this.view() !== 'calendar') return undefined;
    const course = this.courseKey();
    return course ? `${course}|${this.calendarYear()}-${this.calendarMonth()}` : undefined;
  });

  protected readonly calendar = rxResource<CalendarResponse | null, string | undefined>({
    params: () => this.calendarParams(),
    stream: ({ params }) => {
      const [course, yearMonth] = params.split('|');
      const [curriculum, gradeText] = (course ?? '').split('/');
      const [year, month] = (yearMonth ?? '').split('-').map(Number);
      return this.reportsApi.calendar(curriculum ?? '', Number(gradeText ?? ''), year ?? 0, month ?? 0);
    },
    defaultValue: null,
  });

  protected onMonthChange(next: { year: number; month: number }): void {
    this.calendarYear.set(next.year);
    this.calendarMonth.set(next.month);
  }

  // ---- retry (optimistic) ---------------------------------------------------------------

  protected retry(row: AdminLesson): void {
    const previousStatus = row.status;
    this.lessons.update((rows) =>
      rows.map((r) => (r.id === row.id ? { ...r, status: AdminLessonStatusEnum.UPLOADING } : r)),
    );
    this.lessonsApi.retry(row.id).subscribe({
      next: (job) => {
        const status = jobStatusAsLessonStatus(job.status);
        this.lessons.update((rows) => rows.map((r) => (r.id === row.id ? { ...r, status } : r)));
      },
      error: () => {
        this.lessons.update((rows) =>
          rows.map((r) => (r.id === row.id ? { ...r, status: previousStatus } : r)),
        );
      },
    });
  }

  // ---- delete, one row or every failed one — both confirm with a red band ----------------

  protected readonly menuRow = signal<AdminLesson | null>(null);
  protected readonly pendingDelete = signal<AdminLesson | null>(null);
  protected readonly pendingDeleteAllFailed = signal(false);

  protected readonly confirmMode = computed<ConfirmMode>(() => {
    if (this.pendingDelete()) return 'row';
    if (this.pendingDeleteAllFailed()) return 'all-failed';
    return null;
  });

  // Empty, not just hidden, while nothing is pending — a screen reader must not find a
  // "Delete this lesson?" band that happens to carry `hidden`, and neither should a test.
  protected readonly confirmTitle = computed(() => {
    this.lang();
    const mode = this.confirmMode();
    if (mode === null) return '';
    return this.t(
      mode === 'all-failed' ? 'lessons.deleteAllFailedConfirm.title' : 'lessons.deleteConfirm.title',
    );
  });

  protected readonly confirmMessage = computed(() => {
    this.lang();
    const mode = this.confirmMode();
    if (mode === null) return '';
    if (mode === 'all-failed') return this.t('lessons.deleteAllFailedConfirm.message');
    const row = this.pendingDelete();
    return this.t('lessons.deleteConfirm.message', { title: row?.title || this.t('lessons.untitled') });
  });

  protected readonly confirmLabel = computed(() => {
    this.lang();
    const mode = this.confirmMode();
    if (mode === null) return '';
    return this.t(
      mode === 'all-failed' ? 'lessons.deleteAllFailedConfirm.confirm' : 'lessons.deleteConfirm.confirm',
    );
  });

  protected requestDelete(row: AdminLesson | null): void {
    if (row) this.pendingDelete.set(row);
  }

  protected requestDeleteAllFailed(): void {
    this.pendingDeleteAllFailed.set(true);
  }

  protected confirmPending(): void {
    if (this.confirmMode() === 'all-failed') {
      this.lessonsApi.deleteFailed().subscribe({
        next: (result) => {
          if ((result.deleted ?? 0) > 0) this.lessons.reload();
          this.pendingDeleteAllFailed.set(false);
        },
        error: () => this.pendingDeleteAllFailed.set(false),
      });
      return;
    }
    const row = this.pendingDelete();
    if (!row) return;
    this.lessonsApi.deleteLesson(row.id).subscribe({
      next: () => {
        this.lessons.update((rows) => rows.filter((r) => r.id !== row.id));
        this.pendingDelete.set(null);
      },
      error: () => this.pendingDelete.set(null),
    });
  }

  protected cancelPending(): void {
    this.pendingDelete.set(null);
    this.pendingDeleteAllFailed.set(false);
  }

  protected openNewLesson(): void {
    void this.router.navigate([this.basePath(), 'new']);
  }

  // ---- wiring ----------------------------------------------------------------------------

  constructor() {
    // Restore the chooser from `hq.course.<userId>` once this user's options are known.
    effect(() => {
      const userId = this.auth.user()?.id;
      if (!userId || userId === this.restoredForUser) return;
      if (!this.isAdmin() && this.teacherOptions.isLoading()) return;
      this.restoredForUser = userId;
      // A link that names the course wins over what this browser last looked at: arriving from
      // the class page on the wrong grade would show an empty list and look like a bug.
      const linked = this.linkedCourse();
      const stored = linked ?? readStoredCourse(userId);
      if (!stored) return;
      if (
        this.availableCurricula().includes(stored.curriculum) &&
        this.availableGrades().includes(stored.grade)
      ) {
        this.curriculum.set(stored.curriculum);
        this.grade.set(stored.grade);
      }
    });

    // Remember a completed choice.
    effect(() => {
      const userId = this.auth.user()?.id;
      const curriculum = this.curriculum();
      const grade = this.grade();
      if (userId && curriculum && grade) writeStoredCourse(userId, { curriculum, grade });
    });

    // Poll while anything is mid-pipeline.
    effect((onCleanup) => {
      if (!this.hasRunning()) return;
      const timer = setInterval(() => this.lessons.reload(), POLL_MS);
      onCleanup(() => clearInterval(timer));
    });
  }

  /** `?curriculum=&grade=` off the class page's link, when both are usable. */
  private linkedCourse(): { curriculum: Curriculum; grade: number } | null {
    const params = this.params();
    const curriculum = params.get('curriculum');
    const grade = Number(params.get('grade'));
    if (!isCurriculum(curriculum) || !Number.isInteger(grade) || grade <= 0) return null;
    return { curriculum, grade };
  }

  protected clearClassFilter(): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { classId: null },
      queryParamsHandling: 'merge',
    });
  }

  protected trackRow = (row: LessonRowView): string => row.id;

  private toRowView(row: AdminLesson): LessonRowView {
    const step = row.status === AdminLessonStatusEnum.ERROR ? errorStepOf(row) : null;
    return {
      id: row.id,
      title: row.title || this.t('lessons.untitled'),
      classLabel: this.classLabel(row),
      date: this.formatDate(row.date),
      status: row.status,
      errorLabel: step ? this.t('lessons.errorAt', { step: this.t(`lessons.step.${step}`) }) : null,
      schoolName: row.schoolName ?? '',
      source: row,
    };
  }

  private classLabel(row: AdminLesson): string {
    return this.t('lessons.classLabel', {
      curriculum: this.translateOrEmpty(`curriculum.${row.course.curriculum}`) || row.course.curriculum,
      grade: row.course.grade,
      subject: this.translateOrEmpty(`subject.${row.subject}`) || row.subject,
    });
  }

  private formatDate(iso: string): string {
    const date = new Date(`${iso}T00:00:00Z`);
    if (Number.isNaN(date.getTime())) return iso;
    return new Intl.DateTimeFormat(this.transloco.getActiveLang(), {
      day: 'numeric',
      month: 'short',
      year: 'numeric',
      timeZone: 'UTC',
    }).format(date);
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }

  private translateOrEmpty(key: string): string {
    const text = this.t(key);
    return text === key ? '' : text;
  }
}
