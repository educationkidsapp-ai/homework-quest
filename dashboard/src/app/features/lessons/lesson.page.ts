/* hq-flag: none (shell) — gated by the `lesson.read`/`lesson.write`/`lesson.publish`/
   `lesson.delete`/`play.write`/`stop.write` permissions, not a flag: the lesson pipeline
   ships with the dashboard rather than behind a toggle (see `lessons.page.ts`'s header). */
import { CdkMenu, CdkMenuItem, CdkMenuTrigger } from '@angular/cdk/menu';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, ElementRef, computed, effect, inject, signal, viewChildren } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import {
  type AdminLesson,
  type AdminPlay,
  AdminLessonSourceEnum,
  AdminLessonStatusEnum,
  AdminLessonSubjectEnum,
  AdminLessonsApi,
  LessonStepInfoStatusEnum,
} from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import {
  BandComponent,
  ButtonComponent,
  CardComponent,
  CheckboxComponent,
  InputComponent,
  type PipelineStep as StripStep,
  PageComponent,
  SelectComponent,
  SkeletonComponent,
  type StepState,
  StepStripComponent,
  type Tab,
  TabsComponent,
  UndoStripComponent,
} from '../../ui';
import { type Play, type Stop, PhonePreviewComponent } from '../../ui/phone-preview';
import { toPreviewPlay } from './lesson-preview.mapper';
import {
  type ConfirmedSkillRequest,
  type Subject,
  SUBJECTS,
  confirmSkillsBody,
  errorStepOf,
  isRunningStatus,
  jobStatusAsLessonStatus,
} from './lessons.models';

const POLL_MS = 2500;

interface SkillRowView {
  id: string | null;
  name: string;
  subject: Subject;
  method: string;
  keep: boolean;
  readonly candidates: readonly string[];
  readonly question: string | null;
}

type PlayTabId = 'L1' | 'L2' | 'L3' | 'Again';
const PLAY_TAB_DEFS: readonly { readonly id: PlayTabId; readonly level: number; readonly variant: number }[] = [
  { id: 'L1', level: 1, variant: 0 },
  { id: 'L2', level: 2, variant: 0 },
  { id: 'L3', level: 3, variant: 0 },
  { id: 'Again', level: 1, variant: 1 },
];

type PendingAction =
  | { readonly kind: 'publish' }
  | { readonly kind: 'delete' }
  | { readonly kind: 'regeneratePlay'; readonly playId: string }
  | { readonly kind: 'regenerateStop'; readonly stopId: string; readonly title: string };

/**
 * One lesson through its pipeline (Admin + Teacher, §6 screens 8/13): the step strip, its
 * files, the skills it asked to confirm, the three levels + Again as tabs with a live phone
 * preview, the parent panel (read-only — editing is P3.2e) and publish.
 *
 * The stop editor and manual authoring (`AddStop`, `PatchStop`, `CreateLevel`, …) stay out of
 * this slice on purpose — see `LessonContract.kt`'s `Intent` for the full set this ports from.
 * MANAGERIAL never reaches this route today (no `management/lessons/:id` screen exists yet),
 * so `*hqCan` on every write is what will make a future read-only viewer safe, not a role check.
 */
@Component({
  selector: 'hq-lesson-page',
  imports: [
    PageComponent,
    CardComponent,
    ButtonComponent,
    BandComponent,
    UndoStripComponent,
    StepStripComponent,
    TabsComponent,
    SelectComponent,
    InputComponent,
    CheckboxComponent,
    SkeletonComponent,
    PhonePreviewComponent,
    CanDirective,
    CdkMenu,
    CdkMenuItem,
    CdkMenuTrigger,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './lesson.page.html',
  styleUrl: './lesson.page.scss',
})
export class LessonPage {
  private readonly lessonsApi = inject(AdminLessonsApi);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  private readonly stopButtons = viewChildren<ElementRef<HTMLButtonElement>>('stopBtn');

  protected readonly isAdmin = computed(() => this.auth.role() === 'ADMIN');
  protected readonly basePath = computed(() => (this.isAdmin() ? '/admin/lessons' : '/teacher/lessons'));
  protected readonly lessonId = this.route.snapshot.paramMap.get('id') ?? '';

  private readonly lessonRes = rxResource<AdminLesson | null, string>({
    params: () => this.lessonId,
    stream: ({ params: id }) => this.lessonsApi.getLesson(id),
    defaultValue: null,
  });
  protected readonly lesson = this.lessonRes.value;
  protected readonly loading = this.lessonRes.isLoading;
  /** A teacher's `GET` for someone else's lesson answers 404 (never 403 — see `error.interceptor.ts`). */
  protected readonly notFound = computed(() => {
    const error = this.lessonRes.error();
    return error instanceof HttpErrorResponse && error.status === 404;
  });

  protected readonly busy = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  // ---- header -----------------------------------------------------------------------------

  protected readonly pageTitle = computed(() => {
    this.lang();
    return this.lesson()?.title || this.t('lessons.untitled');
  });

  protected readonly classLabel = computed(() => {
    this.lang();
    const lesson = this.lesson();
    if (!lesson) return '';
    return this.t('lessons.classLabel', {
      curriculum: this.translateOrEmpty(`curriculum.${lesson.course.curriculum}`) || lesson.course.curriculum,
      grade: lesson.course.grade,
      subject: this.translateOrEmpty(`subject.${lesson.subject}`) || lesson.subject,
    });
  });

  protected readonly dateLabel = computed(() => {
    const iso = this.lesson()?.date;
    if (!iso) return '';
    const date = new Date(`${iso}T00:00:00Z`);
    if (Number.isNaN(date.getTime())) return iso;
    return new Intl.DateTimeFormat(this.transloco.getActiveLang(), {
      day: 'numeric',
      month: 'short',
      year: 'numeric',
      timeZone: 'UTC',
    }).format(date);
  });

  protected readonly statusWord = computed(() => {
    this.lang();
    const status = this.lesson()?.status;
    return status ? this.t(`lessons.status.${status}`) : '';
  });

  protected readonly breadcrumbs = computed(() => {
    this.lang();
    return [
      { label: this.t(this.isAdmin() ? 'nav.allLessons' : 'nav.myLessons'), link: this.basePath() },
      { label: this.pageTitle() },
    ];
  });

  protected readonly subtitleLine = computed(() => {
    this.lang();
    const lesson = this.lesson();
    if (!lesson) return null;
    const parts = [this.classLabel(), this.dateLabel(), this.statusWord()];
    if (this.isAdmin() && lesson.schoolName) parts.push(lesson.schoolName);
    return parts.filter((part) => part.length > 0).join(' · ');
  });

  // ---- step strip ---------------------------------------------------------------------------

  protected readonly stripSteps = computed<readonly StripStep[]>(() => {
    this.lang();
    return (this.lesson()?.steps ?? []).map((step) => ({
      id: step.step,
      label: this.t(`lessons.step.${step.step}`),
      state: step.status,
    }));
  });

  protected readonly stepStateLabels = computed<Record<StepState, string>>(() => {
    this.lang();
    return {
      pending: this.t('lessons.detail.stepState.pending'),
      running: this.t('lessons.detail.stepState.running'),
      done: this.t('lessons.detail.stepState.done'),
      error: this.t('lessons.detail.stepState.error'),
    };
  });

  protected readonly erroredStep = computed(() => {
    const lesson = this.lesson();
    return lesson?.steps.find((step) => step.status === LessonStepInfoStatusEnum.ERROR) ?? null;
  });

  protected readonly stepErrorMessage = computed(
    () => this.erroredStep()?.errorMessage ?? this.lesson()?.error?.message ?? '',
  );

  protected readonly isErrorStatus = computed(() => this.lesson()?.status === AdminLessonStatusEnum.ERROR);
  protected readonly isPublished = computed(() => this.lesson()?.status === AdminLessonStatusEnum.PUBLISHED);
  protected readonly canReplaceFile = computed(() => this.lesson()?.source !== AdminLessonSourceEnum.MANUAL);

  protected retryContinue(): void {
    const lesson = this.lesson();
    if (!lesson) return;
    const previous = lesson.status;
    this.busy.set(this.t('lessons.detail.busy.retrying'));
    this.lessonRes.update((current) => (current ? { ...current, status: AdminLessonStatusEnum.UPLOADING } : current));
    this.lessonsApi.retry(lesson.id).subscribe({
      next: (job) => {
        this.busy.set(null);
        this.lessonRes.update((current) => (current ? { ...current, status: jobStatusAsLessonStatus(job.status) } : current));
      },
      error: () => {
        this.busy.set(null);
        this.lessonRes.update((current) => (current ? { ...current, status: previous } : current));
      },
    });
  }

  protected retryFailedStep(): void {
    const lesson = this.lesson();
    if (!lesson) return;
    const step = errorStepOf(lesson);
    if (!step) return;
    const previous = lesson.status;
    this.busy.set(this.t('lessons.detail.busy.retryingStep', { step: this.t(`lessons.step.${step}`) }));
    this.lessonRes.update((current) => (current ? { ...current, status: AdminLessonStatusEnum.UPLOADING } : current));
    this.lessonsApi.retryStep(lesson.id, step).subscribe({
      next: (job) => {
        this.busy.set(null);
        this.lessonRes.update((current) => (current ? { ...current, status: jobStatusAsLessonStatus(job.status) } : current));
      },
      error: () => {
        this.busy.set(null);
        this.lessonRes.update((current) => (current ? { ...current, status: previous } : current));
      },
    });
  }

  // ---- replace file: delete → upload → analyze, one chain ---------------------------------

  protected readonly replacing = signal(false);

  protected startReplace(): void {
    this.replacing.set(true);
  }

  protected cancelReplace(): void {
    this.replacing.set(false);
  }

  protected onReplaceDragOver(event: DragEvent): void {
    event.preventDefault();
  }

  protected onReplaceDrop(event: DragEvent): void {
    event.preventDefault();
    this.replaceFiles(Array.from(event.dataTransfer?.files ?? []));
  }

  protected onReplaceInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.replaceFiles(Array.from(input.files ?? []));
    input.value = '';
  }

  private replaceFiles(files: readonly File[]): void {
    const lesson = this.lesson();
    if (!lesson || files.length === 0) return;
    this.busy.set(this.t('lessons.detail.busy.removingFiles'));
    this.lessonsApi.deleteFiles(lesson.id).subscribe({
      next: () => {
        this.busy.set(this.t('lessons.new.busy.uploading'));
        this.lessonsApi.uploadFiles(lesson.id, [...files]).subscribe({
          next: () => {
            this.busy.set(this.t('lessons.new.busy.analyzing'));
            this.lessonsApi.analyze(lesson.id).subscribe({
              next: () => {
                this.busy.set(null);
                this.replacing.set(false);
                this.lessonRes.reload();
              },
              error: () => this.busy.set(null),
            });
          },
          error: () => this.busy.set(null),
        });
      },
      error: () => this.busy.set(null),
    });
  }

  // ---- files section ------------------------------------------------------------------------

  protected readonly files = computed(() => this.lesson()?.files ?? []);
  protected readonly hasUndeletedFiles = computed(() => this.files().some((file) => !file.deleted));

  protected onUploadMoreInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    input.value = '';
    const lesson = this.lesson();
    if (!lesson || files.length === 0) return;
    this.busy.set(this.t('lessons.new.busy.uploading'));
    this.lessonsApi.uploadFiles(lesson.id, files).subscribe({
      next: () => {
        this.busy.set(null);
        this.lessonRes.reload();
      },
      error: () => this.busy.set(null),
    });
  }

  protected deleteAllFiles(): void {
    const lesson = this.lesson();
    if (!lesson) return;
    this.busy.set(this.t('lessons.detail.busy.removingFiles'));
    this.lessonsApi.deleteFiles(lesson.id).subscribe({
      next: () => {
        this.busy.set(null);
        this.lessonRes.reload();
      },
      error: () => this.busy.set(null),
    });
  }

  // ---- skills confirmation ------------------------------------------------------------------

  protected readonly isNeedsReview = computed(() => this.lesson()?.status === AdminLessonStatusEnum.NEEDS_REVIEW);
  protected readonly skillRows = signal<readonly SkillRowView[]>([]);
  protected readonly skillsError = signal<string | null>(null);
  protected readonly subjectOptions = computed(() => {
    this.lang();
    return SUBJECTS.map((subject) => ({ value: subject, label: this.translateOrEmpty(`subject.${subject}`) || subject }));
  });

  private skillRowsForLessonId: string | null = null;

  private seedSkillRowsIfNeeded(): void {
    const lesson = this.lesson();
    if (!lesson || !this.isNeedsReview() || this.skillRowsForLessonId === lesson.id) return;
    this.skillRowsForLessonId = lesson.id;
    this.skillRows.set(
      lesson.skills.map((skill) => ({
        id: skill.id,
        name: skill.name,
        subject: skill.subject,
        method: skill.method,
        keep: true,
        candidates: skill.unsure?.candidates ?? [],
        question: skill.unsure?.question ?? null,
      })),
    );
  }

  protected setSkillName(index: number, name: string): void {
    this.skillRows.update((rows) => rows.map((row, i) => (i === index ? { ...row, name } : row)));
  }

  protected setSkillMethod(index: number, method: string): void {
    this.skillRows.update((rows) => rows.map((row, i) => (i === index ? { ...row, method } : row)));
  }

  protected setSkillSubject(index: number, subject: string): void {
    if (subject !== 'math' && subject !== 'english') return;
    this.skillRows.update((rows) => rows.map((row, i) => (i === index ? { ...row, subject } : row)));
  }

  protected setSkillKeep(index: number, keep: boolean): void {
    this.skillRows.update((rows) => rows.map((row, i) => (i === index ? { ...row, keep } : row)));
  }

  protected addSkillRow(): void {
    const subject: Subject = this.lesson()?.subject === AdminLessonSubjectEnum.MATH ? 'math' : 'english';
    this.skillRows.update((rows) => [
      ...rows,
      { id: null, name: '', subject, method: '', keep: true, candidates: [], question: null },
    ]);
  }

  protected confirmSkills(): void {
    const lesson = this.lesson();
    if (!lesson) return;
    const kept = this.skillRows().filter((row) => row.keep && row.name.trim().length > 0);
    if (kept.length === 0) {
      this.skillsError.set(this.t('lessons.detail.skills.keepAtLeastOne'));
      return;
    }
    this.skillsError.set(null);
    this.busy.set(this.t('lessons.detail.busy.confirmingSkills'));
    const body: readonly ConfirmedSkillRequest[] = kept.map((row) => ({
      id: row.id ?? undefined,
      name: row.name.trim(),
      subject: row.subject,
      method: row.method.trim() || undefined,
    }));
    this.lessonsApi.confirmSkills(lesson.id, confirmSkillsBody(body)).subscribe({
      next: () => {
        this.busy.set(null);
        this.lessonRes.reload();
      },
      error: () => this.busy.set(null),
    });
  }

  // ---- plays: L1 / L2 / L3 / Again, stop list + pinned preview -----------------------------

  protected readonly playTab = signal<PlayTabId>('L1');
  protected readonly playTabs = computed<readonly Tab<PlayTabId>[]>(() => {
    this.lang();
    const plays = this.lesson()?.plays ?? [];
    return PLAY_TAB_DEFS.map((def) => ({
      id: def.id,
      label: this.t(`lessons.detail.playTab.${def.id}`),
      disabled: !plays.some((play) => play.level === def.level && play.variant === def.variant),
    }));
  });

  protected readonly currentAdminPlay = computed<AdminPlay | null>(() => {
    const def = PLAY_TAB_DEFS.find((d) => d.id === this.playTab());
    const plays = this.lesson()?.plays ?? [];
    return (def && plays.find((play) => play.level === def.level && play.variant === def.variant)) ?? null;
  });

  protected readonly currentPlay = computed<Play | null>(() => {
    const adminPlay = this.currentAdminPlay();
    return adminPlay ? toPreviewPlay(adminPlay) : null;
  });

  protected readonly selectedStopId = signal<string | null>(null);
  protected readonly selectedStop = computed<Stop | null>(
    () => this.currentPlay()?.stops.find((stop) => stop.id === this.selectedStopId()) ?? null,
  );

  protected selectPlayTab(id: PlayTabId): void {
    this.playTab.set(id);
  }

  protected selectStop(id: string): void {
    this.selectedStopId.set(id);
  }

  private selectFirstStop(): void {
    const stops = this.currentPlay()?.stops ?? [];
    const first = stops[0];
    this.selectedStopId.set(stops.some((stop) => stop.id === this.selectedStopId()) ? this.selectedStopId() : (first?.id ?? null));
  }

  protected onStopListKeydown(event: KeyboardEvent, index: number): void {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    const stops = this.currentPlay()?.stops ?? [];
    if (stops.length === 0) return;
    event.preventDefault();
    const next = event.key === 'ArrowDown' ? (index + 1) % stops.length : (index - 1 + stops.length) % stops.length;
    const stop = stops[next];
    if (!stop) return;
    this.selectedStopId.set(stop.id);
    this.stopButtons().at(next)?.nativeElement.focus();
  }

  protected readonly previewSubject = computed<'math' | 'english'>(() =>
    this.lesson()?.subject === AdminLessonSubjectEnum.MATH ? 'math' : 'english',
  );

  // ---- publish readiness ---------------------------------------------------------------------

  protected readonly publishReady = computed(() => {
    const lesson = this.lesson();
    if (!lesson) return false;
    if (lesson.source === AdminLessonSourceEnum.MANUAL) {
      return lesson.plays.some((play) => play.level === 1 && play.variant === 0 && play.play.stops.length > 0);
    }
    const mains = lesson.plays.filter((play) => play.variant === 0).length;
    const hasAgain = lesson.plays.some((play) => play.variant === 1);
    return mains === 3 && hasAgain && lesson.parentPanel != null;
  });

  protected readonly canPublish = computed(
    () => this.lesson()?.status === AdminLessonStatusEnum.REVIEW && this.publishReady() && this.busy() === null,
  );

  protected readonly publishReason = computed(() => {
    this.lang();
    if (this.canPublish() || this.busy() !== null) return null;
    const lesson = this.lesson();
    if (!lesson) return null;
    if (lesson.status !== AdminLessonStatusEnum.REVIEW) return this.t('lessons.detail.publishReason.notReady');
    return this.t('lessons.detail.publishReason.incomplete');
  });

  // ---- one confirm band for every destructive/expensive action -----------------------------

  protected readonly pendingAction = signal<PendingAction | null>(null);

  protected readonly confirmTitle = computed(() => {
    this.lang();
    const action = this.pendingAction();
    if (!action) return '';
    if (action.kind === 'publish') return this.t('lessons.detail.publishConfirm.title');
    if (action.kind === 'delete') return this.t('lessons.deleteConfirm.title');
    if (action.kind === 'regeneratePlay') return this.t('lessons.detail.regeneratePlayConfirm.title');
    return this.t('lessons.detail.regenerateStopConfirm.title');
  });

  protected readonly confirmMessage = computed(() => {
    this.lang();
    const action = this.pendingAction();
    if (!action) return '';
    if (action.kind === 'publish') return this.t('lessons.detail.publishConfirm.message', { title: this.pageTitle() });
    if (action.kind === 'delete') return this.t('lessons.deleteConfirm.message', { title: this.pageTitle() });
    if (action.kind === 'regeneratePlay') return this.t('lessons.detail.regeneratePlayConfirm.message');
    return this.t('lessons.detail.regenerateStopConfirm.message', { title: action.title });
  });

  protected readonly confirmLabel = computed(() => {
    this.lang();
    const action = this.pendingAction();
    if (!action) return '';
    if (action.kind === 'publish') return this.t('lessons.detail.publishConfirm.confirm');
    if (action.kind === 'delete') return this.t('lessons.deleteConfirm.confirm');
    if (action.kind === 'regeneratePlay') return this.t('lessons.detail.regeneratePlayConfirm.confirm');
    return this.t('lessons.detail.regenerateStopConfirm.confirm');
  });

  protected requestPublish(): void {
    this.pendingAction.set({ kind: 'publish' });
  }

  protected requestDelete(): void {
    this.pendingAction.set({ kind: 'delete' });
  }

  protected requestRegeneratePlay(): void {
    const play = this.currentAdminPlay();
    if (play) this.pendingAction.set({ kind: 'regeneratePlay', playId: play.id });
  }

  protected requestRegenerateStop(): void {
    const stop = this.selectedStop();
    if (stop) this.pendingAction.set({ kind: 'regenerateStop', stopId: stop.id, title: stop.title });
  }

  protected cancelPendingAction(): void {
    this.pendingAction.set(null);
  }

  protected confirmPendingAction(): void {
    const action = this.pendingAction();
    this.pendingAction.set(null);
    if (!action) return;
    if (action.kind === 'publish') this.doPublish();
    else if (action.kind === 'delete') this.doDelete();
    else if (action.kind === 'regeneratePlay') this.doRegeneratePlay(action.playId);
    else this.doRegenerateStop(action.stopId);
  }

  private doRegeneratePlay(playId: string): void {
    this.busy.set(this.t('lessons.detail.busy.regeneratingPlay'));
    this.lessonsApi.regeneratePlay(playId).subscribe({
      next: () => {
        this.busy.set(null);
        this.lessonRes.reload();
      },
      error: () => this.busy.set(null),
    });
  }

  private doRegenerateStop(stopId: string): void {
    this.busy.set(this.t('lessons.detail.busy.regeneratingStop'));
    this.lessonsApi.regenerateStop(stopId).subscribe({
      next: () => {
        this.busy.set(null);
        this.lessonRes.reload();
      },
      error: () => this.busy.set(null),
    });
  }

  private doDelete(): void {
    const lesson = this.lesson();
    if (!lesson) return;
    this.lessonsApi.deleteLesson(lesson.id).subscribe({
      next: () => void this.router.navigate([this.basePath()]),
      error: () => undefined,
    });
  }

  private doPublish(): void {
    const lesson = this.lesson();
    if (!lesson) return;
    const previous = lesson;
    this.busy.set(this.t('lessons.detail.busy.publishing'));
    this.lessonRes.update((current) => (current ? { ...current, status: AdminLessonStatusEnum.PUBLISHED } : current));
    this.lessonsApi.publish(lesson.id).subscribe({
      next: (updated) => {
        this.busy.set(null);
        this.lessonRes.update(() => updated);
        this.notice.set(this.t('lessons.detail.publishedNotice'));
      },
      error: () => {
        this.busy.set(null);
        this.lessonRes.update(() => previous);
      },
    });
  }

  // ---- unpublish: immediate, with a 10 s Undo strip ------------------------------------------

  protected readonly undoOpen = signal(false);

  protected unpublish(): void {
    const lesson = this.lesson();
    if (!lesson) return;
    this.busy.set(this.t('lessons.detail.busy.unpublishing'));
    this.lessonsApi.unpublish(lesson.id).subscribe({
      next: (updated) => {
        this.busy.set(null);
        this.lessonRes.update(() => updated);
        this.undoOpen.set(true);
      },
      error: () => this.busy.set(null),
    });
  }

  protected undoUnpublish(): void {
    this.undoOpen.set(false);
    const lesson = this.lesson();
    if (!lesson) return;
    this.lessonsApi.publish(lesson.id).subscribe({ next: (updated) => this.lessonRes.update(() => updated) });
  }

  protected undoExpired(): void {
    this.undoOpen.set(false);
  }

  // ---- wiring ---------------------------------------------------------------------------

  constructor() {
    const key = this.route.snapshot.queryParamMap.get('notice');
    if (key) {
      const text = this.t(key);
      if (text !== key) this.notice.set(text);
    }

    // Poll while a job is running; the interval clears itself on the next run, on a terminal
    // status, and (the `onCleanup` the effect gets for free) when the page is destroyed.
    effect((onCleanup) => {
      const status = this.lesson()?.status;
      if (!status || !isRunningStatus(status)) return;
      const timer = setInterval(() => this.lessonRes.reload(), POLL_MS);
      onCleanup(() => clearInterval(timer));
    });

    // The skills rows are seeded once from the server and then edited locally — see
    // `seedSkillRowsIfNeeded`'s guard, which is what keeps a poll mid-review from clobbering
    // an admin's in-progress edits.
    effect(() => this.seedSkillRowsIfNeeded());

    // The stop list's selection follows the play tab: the first stop by default, or the
    // previously selected one if the new play still has it (regenerating a play, say).
    effect(() => this.selectFirstStop());

    // A lesson that is not this teacher's (or is simply gone) reads as "not found", not as a
    // band over a blank page — see `error.interceptor.ts`'s comment on why 404 gets no
    // navigation of its own there; this screen is the one place that has to supply it.
    effect(() => {
      if (this.notFound()) void this.router.navigate(['/not-found']);
    });
  }

  protected t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }

  private translateOrEmpty(key: string): string {
    const text = this.t(key);
    return text === key ? '' : text;
  }
}
