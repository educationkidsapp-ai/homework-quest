/* hq-flag: none (shell) — gated by the `lesson.read`/`lesson.write`/`lesson.publish`/
   `lesson.delete`/`play.write`/`stop.write` permissions, not a flag: the lesson pipeline
   ships with the dashboard rather than behind a toggle (see `lessons.page.ts`'s header). */
import { CdkDrag, CdkDragDrop, CdkDragHandle, CdkDropList } from '@angular/cdk/drag-drop';
import { CdkMenu, CdkMenuItem, CdkMenuTrigger } from '@angular/cdk/menu';
import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  viewChildren,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import {
  type AdminLesson,
  type AdminPlay,
  type ParentPanel,
  type PublishedCopy,
  AdminLessonSourceEnum,
  AdminLessonStatusEnum,
  AdminLessonSubjectEnum,
  LessonStepInfoStatusEnum,
  LessonStepInfoStepEnum,
  TeacherApi,
  apiErrorOf,
  readableServerText,
} from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { BandService } from '../../core/band/band.service';
import { activeLang } from '../../core/i18n/active-lang';
import { MediaService } from '../../core/media/media.service';
import { CanDirective } from '../../core/permissions/can.directive';
import {
  BandComponent,
  ButtonComponent,
  CardComponent,
  CheckboxComponent,
  DialogComponent,
  InputComponent,
  type PipelineStep as StripStep,
  PageComponent,
  SelectComponent,
  SkeletonComponent,
  type StepState,
  StepStripComponent,
  TextareaComponent,
  type Tab,
  TabsComponent,
  ToastComponent,
  UndoStripComponent,
} from '../../ui';
import { type Play, type Stop, PhonePreviewComponent } from '../../ui/phone-preview';
import { AddStopComponent } from './add-stop.component';
import { anyConverting, reasonKeyOf } from './file-conversion';
import { LessonApiService } from './lesson-api.service';
import { toPreviewPlay } from './lesson-preview.mapper';
import { LessonSourcesComponent } from './lesson-sources.component';
import {
  type ConfirmedSkillRequest,
  type Subject,
  SUBJECTS,
  confirmSkillsBody,
  createPlayBody,
  errorStepOf,
  generateFromTextBody,
  isRunningStatus,
  jobStatusAsLessonStatus,
  parentPanelBody,
  reorderBody,
  stopBody,
  stopFromTextBody,
} from './lessons.models';
import { ParentPanelEditorComponent } from './parent-panel-editor.component';
import { StopEditorComponent, type StopSaveFailure } from './stop-editor.component';

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
const PLAY_TAB_DEFS: readonly { readonly id: PlayTabId; readonly level: number; readonly variant: number }[] =
  [
    { id: 'L1', level: 1, variant: 0 },
    { id: 'L2', level: 2, variant: 0 },
    { id: 'L3', level: 3, variant: 0 },
    { id: 'Again', level: 1, variant: 1 },
  ];

type PendingAction =
  | { readonly kind: 'publish' }
  | { readonly kind: 'delete' }
  | { readonly kind: 'regeneratePlay'; readonly playId: string }
  | { readonly kind: 'regenerateStop'; readonly stopId: string; readonly title: string }
  | { readonly kind: 'deleteStop'; readonly stopId: string; readonly title: string }
  | { readonly kind: 'leave' };

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
    ToastComponent,
    AddStopComponent,
    StepStripComponent,
    TabsComponent,
    SelectComponent,
    InputComponent,
    CheckboxComponent,
    DialogComponent,
    SkeletonComponent,
    PhonePreviewComponent,
    StopEditorComponent,
    ParentPanelEditorComponent,
    LessonSourcesComponent,
    TextareaComponent,
    CanDirective,
    CdkMenu,
    CdkMenuItem,
    CdkMenuTrigger,
    CdkDropList,
    CdkDrag,
    CdkDragHandle,
    RouterLink,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './lesson.page.html',
  styleUrl: './lesson.page.scss',
})
export class LessonPage {
  private readonly api = inject(LessonApiService);
  private readonly teacherApi = inject(TeacherApi);
  private readonly auth = inject(AuthService);
  private readonly band = inject(BandService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);
  private readonly media = inject(MediaService);
  private readonly lang = activeLang();

  private readonly stopButtons = viewChildren<ElementRef<HTMLButtonElement>>('stopBtn');

  protected readonly isAdmin = computed(() => this.auth.role() === 'ADMIN');
  protected readonly basePath = computed(() => (this.isAdmin() ? '/admin/lessons' : '/teacher/lessons'));
  protected readonly lessonId = this.route.snapshot.paramMap.get('id') ?? '';

  /**
   * The role decides which family of routes this reads — `/admin/**` or `/teacher/**` — so the
   * fetch waits for `/me` rather than guessing and asking the wrong one. `undefined` params keep
   * the resource idle until then, which is why `loading` folds "the role is not known yet" in:
   * without it a hard reload would flash the empty page before the request even started.
   */
  private readonly lessonRes = rxResource<AdminLesson | null, string | undefined>({
    params: () => (this.auth.role() === null ? undefined : this.lessonId),
    stream: ({ params: id }) => this.api.getLesson(id),
    defaultValue: null,
  });
  protected readonly lesson = this.lessonRes.value;
  protected readonly loading = computed(() => this.lessonRes.isLoading() || this.auth.role() === null);
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
    // The version only means something once something was published under it: re-publishing
    // after an edit mints a new one, and a parent looking at yesterday's copy has the old one.
    if (lesson.publishedAt !== undefined && lesson.version > 0) {
      parts.push(this.t('lessons.detail.version', { version: lesson.version }));
    }
    if (this.isAdmin() && lesson.schoolName) parts.push(lesson.schoolName);
    return parts.filter((part) => part.length > 0).join(' · ');
  });

  /**
   * "Analyzed before · 0 tokens" (teacher-flow Step 5).
   *
   * The server hashes the source file, so the same worksheet analyzed by anybody in any school
   * comes back instantly and costs nothing. Saying so is not a boast: it is why the step strip
   * finished before she could read it, and the tooltip carries what it saved.
   */
  protected readonly analyzedBefore = computed(() => this.lesson()?.analyzedBefore === true);

  protected readonly analyzedBeforeTooltip = computed(() => {
    this.lang();
    return this.t('lessons.analyzedBeforeTooltip', { tokens: this.lesson()?.tokensSaved ?? 0 });
  });

  /** Class and subject, as the fixed labels §4 calls for — never editable from the editor. */
  protected readonly classFact = computed(() => {
    this.lang();
    const lesson = this.lesson();
    if (!lesson) return '';
    const subject = this.translateOrEmpty(`subject.${lesson.subject}`) || lesson.subject;
    return lesson.className ? `${lesson.className} · ${subject}` : this.classLabel();
  });

  /** Preview as child opens the web player; N3 builds it, the route is a placeholder until then. */
  protected readonly canPreview = computed(() => {
    const status = this.lesson()?.status;
    return status === AdminLessonStatusEnum.REVIEW || status === AdminLessonStatusEnum.PUBLISHED;
  });

  protected readonly previewQuery = computed(() => ({ lesson: this.lessonId }));

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

  /**
   * The failed step's sentence, with any JSON taken out of it (CR5).
   *
   * `LessonSteps.Messages.of` appends the underlying exception in parentheses, and for a schema
   * or model failure that is the JSON that did not validate, truncated mid-brace. What is left
   * after `readableServerText` is the step's own advice — "Retry, or edit Level 1 by hand" — and
   * when nothing is left, the generic sentence says more than a fragment would.
   */
  protected readonly stepErrorMessage = computed(() => {
    this.lang();
    const step = this.erroredStep();
    // CR4: a failed conversion has a friendly sentence of its own per code, and it is the same
    // one the file's own pill carries. The server's message here names anydoc's exit status and
    // the operator's environment variable, which is true and useless to a teacher.
    if (step?.step === LessonStepInfoStepEnum.CONVERT) return this.t(reasonKeyOf(step.errorCode));
    const raw = step?.errorMessage ?? this.lesson()?.error?.message ?? '';
    return readableServerText(raw) || this.t('lessons.detail.stepFailed');
  });

  protected readonly isErrorStatus = computed(() => this.lesson()?.status === AdminLessonStatusEnum.ERROR);
  protected readonly isPublished = computed(() => this.lesson()?.status === AdminLessonStatusEnum.PUBLISHED);
  protected readonly canReplaceFile = computed(() => this.lesson()?.source !== AdminLessonSourceEnum.MANUAL);

  protected retryContinue(): void {
    const lesson = this.lesson();
    if (!lesson) return;
    const previous = lesson.status;
    this.busy.set(this.t('lessons.detail.busy.retrying'));
    this.lessonRes.update((current) =>
      current ? { ...current, status: AdminLessonStatusEnum.UPLOADING } : current,
    );
    this.api.retry(lesson.id).subscribe({
      next: (job) => {
        this.busy.set(null);
        this.lessonRes.update((current) =>
          current ? { ...current, status: jobStatusAsLessonStatus(job.status) } : current,
        );
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
    this.lessonRes.update((current) =>
      current ? { ...current, status: AdminLessonStatusEnum.UPLOADING } : current,
    );
    this.api.retryStep(lesson.id, step).subscribe({
      next: (job) => {
        this.busy.set(null);
        this.lessonRes.update((current) =>
          current ? { ...current, status: jobStatusAsLessonStatus(job.status) } : current,
        );
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
    this.api.deleteFiles(lesson.id).subscribe({
      next: () => {
        this.busy.set(this.t('lessons.new.busy.uploading'));
        this.api.uploadFiles(lesson.id, files).subscribe({
          next: () => {
            this.busy.set(this.t('lessons.new.busy.analyzing'));
            this.api.analyze(lesson.id).subscribe({
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
    this.api.uploadFiles(lesson.id, files).subscribe({
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
    this.api.deleteFiles(lesson.id).subscribe({
      next: () => {
        this.busy.set(null);
        this.lessonRes.reload();
      },
      error: () => this.busy.set(null),
    });
  }

  // ---- skills confirmation ------------------------------------------------------------------

  protected readonly isNeedsReview = computed(
    () => this.lesson()?.status === AdminLessonStatusEnum.NEEDS_REVIEW,
  );
  protected readonly skillRows = signal<readonly SkillRowView[]>([]);
  protected readonly skillsError = signal<string | null>(null);
  protected readonly subjectOptions = computed(() => {
    this.lang();
    return SUBJECTS.map((subject) => ({
      value: subject,
      label: this.translateOrEmpty(`subject.${subject}`) || subject,
    }));
  });

  /** The status this lesson had the *previous* time this ran — not just its id — so a second
   *  pass through `needs_review` in the same page lifetime (a retry on the skills step that
   *  re-extracts) reseeds instead of posting stale skill ids back to the server. */
  private previousLessonStatus: AdminLessonStatusEnum | null = null;

  private seedSkillRowsIfNeeded(): void {
    const lesson = this.lesson();
    if (!lesson) return;
    const enteringNeedsReview =
      lesson.status === AdminLessonStatusEnum.NEEDS_REVIEW &&
      this.previousLessonStatus !== AdminLessonStatusEnum.NEEDS_REVIEW;
    this.previousLessonStatus = lesson.status;
    if (!enteringNeedsReview) return;
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
    this.api.confirmSkills(lesson.id, confirmSkillsBody(body)).subscribe({
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

  /**
   * A stop that has just been created but whose lesson has not been re-read yet.
   *
   * Without it, `selectFirstStop` would snap the selection back to the first stop for the
   * fraction of a second between "the form saved" and "the re-read landed" — and then leave it
   * there, because by the time the new stop exists the selection no longer points at it.
   */
  private readonly pendingStopId = signal<string | null>(null);

  private selectFirstStop(): void {
    const stops = this.currentPlay()?.stops ?? [];
    const selected = this.selectedStopId();
    if (stops.some((stop) => stop.id === selected)) {
      if (this.pendingStopId() !== null) this.pendingStopId.set(null);
      return;
    }
    if (selected !== null && selected === this.pendingStopId()) return;
    this.selectedStopId.set(stops[0]?.id ?? null);
  }

  /** The listbox pattern (`ui/tabs/tabs.component.ts`'s roving-tabindex arrows, plus Home/End). */
  protected onStopListKeydown(event: KeyboardEvent, index: number): void {
    const stops = this.currentPlay()?.stops ?? [];
    if (stops.length === 0) return;
    const next = this.nextStopIndex(event.key, index, stops.length);
    if (next === null) return;
    event.preventDefault();
    const stop = stops[next];
    if (!stop) return;
    this.selectedStopId.set(stop.id);
    this.stopButtons().at(next)?.nativeElement.focus();
  }

  private nextStopIndex(key: string, current: number, length: number): number | null {
    switch (key) {
      case 'ArrowDown':
        return (current + 1) % length;
      case 'ArrowUp':
        return (current - 1 + length) % length;
      case 'Home':
        return 0;
      case 'End':
        return length - 1;
      default:
        return null;
    }
  }

  protected readonly previewSubject = computed<'math' | 'english'>(() =>
    this.lesson()?.subject === AdminLessonSubjectEnum.MATH ? 'math' : 'english',
  );

  // ---- the stop editor: save, attach a picture, add, delete, reorder ------------------------

  /** True while the JSON draft differs from the saved stop — the leave band asks about it. */
  protected readonly stopDirty = signal(false);

  protected onStopDirtyChange(dirty: boolean): void {
    this.stopDirty.set(dirty);
  }

  /** True while `from-text` is in flight; the editor says what the wait is for. */
  protected readonly savingStopText = signal(false);
  /** The last text save's refusal, in the two shapes the editor can explain. */
  protected readonly stopSaveFailure = signal<StopSaveFailure | null>(null);
  /** The 422's own validator lines — the Raw JSON panel's, and nobody else's. */
  protected readonly stopValidatorErrors = signal<readonly string[]>([]);

  /**
   * CR5: save the stop as the English the teacher wrote, and let the server (and Prompt D) make
   * the JSON.
   *
   * The request is `silentErrors()`, so the two answers this screen can explain — 422
   * `rephrase` and the 400 for a lesson still generating — become a band *under the editor* with
   * her text still in it, rather than a red band at the top of the page carrying the validator's
   * own lines. Anything else (the model being down, a lost session) is still a band, raised here
   * with the same sentence the interceptor would have used.
   */
  protected saveStopText(text: string): void {
    const stop = this.selectedStop();
    if (!stop) return;
    this.savingStopText.set(true);
    this.stopSaveFailure.set(null);
    this.stopValidatorErrors.set([]);
    this.busy.set(this.t('lessons.detail.busy.savingStopText'));
    this.api.stopFromText(stop.id, stopFromTextBody(text)).subscribe({
      next: () => {
        this.savingStopText.set(false);
        this.busy.set(null);
        this.stopDirty.set(false);
        this.lessonRes.reload();
      },
      error: (error: unknown) => {
        this.savingStopText.set(false);
        this.busy.set(null);
        const status = error instanceof HttpErrorResponse ? error.status : 0;
        if (status === 422) {
          this.stopSaveFailure.set('rephrase');
          this.stopValidatorErrors.set(validatorLinesOf(error));
        } else if (status === 400) this.stopSaveFailure.set('generating');
        else this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
      },
    });
  }

  protected saveStop(stop: Stop): void {
    this.stopSaveFailure.set(null);
    this.stopValidatorErrors.set([]);
    this.busy.set(this.t('lessons.detail.busy.savingStop'));
    this.api.updateStop(stop.id, stopBody(stop)).subscribe({
      next: () => {
        this.busy.set(null);
        this.stopDirty.set(false);
        this.lessonRes.reload();
      },
      error: () => this.busy.set(null),
    });
  }

  /**
   * Uploading a picture and attaching it are two steps on the server: the image belongs to the
   * lesson, the `imageId` to the stop. The upload only reloads the lesson so the new id appears
   * in the editor's picker — choosing it is the teacher's, because an upload is not a decision
   * about which stop shows it.
   */
  protected attachImage(file: File): void {
    const lesson = this.lesson();
    if (!lesson) return;
    this.busy.set(this.t('lessons.detail.busy.uploadingImage'));
    // The lesson is re-read rather than patched: `POST …/images` answers with `LessonImage`
    // (`{id, url}`), while `AdminLesson.images` is `PageImage` — same picture, more fields, and
    // the preview needs the ones the upload does not return.
    this.api.uploadImage(lesson.id, file).subscribe({
      next: () => {
        this.busy.set(null);
        this.notice.set(this.t('lessons.detail.editor.attached'));
        this.lessonRes.reload();
      },
      error: () => this.busy.set(null),
    });
  }

  /** CR2: "+ Add stop" opens one form (`hq-add-stop`), which owns the two calls that save it. */
  protected readonly addStopOpen = signal(false);
  /** "Question added", for four seconds. The only toast on this page, and never for a failure. */
  protected readonly stopAddedToast = signal(false);

  protected onStopAdded(stopId: string): void {
    this.pendingStopId.set(stopId);
    this.selectedStopId.set(stopId);
    this.stopAddedToast.set(true);
    this.lessonRes.reload();
  }

  protected requestDeleteStop(): void {
    const stop = this.selectedStop();
    if (stop) this.pendingAction.set({ kind: 'deleteStop', stopId: stop.id, title: stop.title });
  }

  private doDeleteStop(stopId: string): void {
    this.busy.set(this.t('lessons.detail.busy.deletingStop'));
    this.api.deleteStop(stopId).subscribe({
      next: () => {
        this.busy.set(null);
        // No Undo strip here: `POST …/plays/{id}/stops` assigns its own id, so re-adding the
        // deleted JSON would make a *different* stop, and the parent panel's `stopTips` and
        // `modelAnswers` still point at the old one. The red confirm band is the whole guard.
        if (this.selectedStopId() === stopId) this.selectedStopId.set(null);
        this.lessonRes.reload();
      },
      error: () => this.busy.set(null),
    });
  }

  /** Drag settles over 250 ms (`--hq-duration-settle`); the new order posts once it lands. */
  protected dropStop(event: CdkDragDrop<readonly Stop[]>): void {
    const play = this.currentAdminPlay();
    const stops = this.currentPlay()?.stops ?? [];
    if (!play || event.previousIndex === event.currentIndex) return;
    const ids = stops.map((stop) => stop.id);
    const [moved] = ids.splice(event.previousIndex, 1);
    if (moved === undefined) return;
    ids.splice(event.currentIndex, 0, moved);

    const previous = this.lesson();
    this.reorderedIds.set(ids);
    this.busy.set(this.t('lessons.detail.busy.reordering'));
    this.api.reorder(play.id, reorderBody(ids)).subscribe({
      next: () => {
        this.busy.set(null);
        this.reorderedIds.set(null);
        this.lessonRes.reload();
      },
      error: () => {
        // The list snaps back where it was: an optimistic order that the server refused is a
        // lie about what a child will see.
        this.busy.set(null);
        this.reorderedIds.set(null);
        this.lessonRes.update(() => previous);
      },
    });
  }

  /** The order shown while the `PUT` is in flight, so the row does not jump back and forth. */
  private readonly reorderedIds = signal<readonly string[] | null>(null);

  protected readonly orderedStops = computed<readonly Stop[]>(() => {
    const stops = this.currentPlay()?.stops ?? [];
    const ids = this.reorderedIds();
    if (ids === null) return stops;
    return ids
      .map((id) => stops.find((stop) => stop.id === id))
      .filter((stop): stop is Stop => stop !== undefined);
  });

  // ---- manual authoring: create a missing level, generate the rest from a note --------------

  protected readonly isManual = computed(() => this.lesson()?.source === AdminLessonSourceEnum.MANUAL);
  /** A manual lesson never ran the pipeline, so its (empty) step strip says nothing worth space. */
  protected readonly showSteps = computed(() => !this.isManual() && this.stripSteps().length > 0);

  /** The tab that is on screen but has no play behind it yet — what "Create level" would make. */
  protected readonly missingPlay = computed(() => {
    const def = PLAY_TAB_DEFS.find((entry) => entry.id === this.playTab());
    if (!def) return null;
    const plays = this.lesson()?.plays ?? [];
    return plays.some((play) => play.level === def.level && play.variant === def.variant) ? null : def;
  });

  protected createLevel(): void {
    const lesson = this.lesson();
    const def = this.missingPlay();
    if (!lesson || !def) return;
    this.busy.set(this.t('lessons.detail.busy.creatingLevel'));
    this.api.createPlay(lesson.id, createPlayBody(def.level, def.variant)).subscribe({
      next: () => {
        this.busy.set(null);
        this.lessonRes.reload();
      },
      error: () => this.busy.set(null),
    });
  }

  protected readonly generateText = signal('');

  protected readonly canGenerate = computed(
    () => this.busy() === null && this.generateText().trim().length > 0,
  );

  /**
   * "Generate the other levels" hands the pipeline a note instead of a file: the server queues
   * the same generate steps, so the page falls into its usual polling (`isRunningStatus`) and
   * the step strip comes back to life for a manual lesson too.
   */
  protected generateLevels(): void {
    const lesson = this.lesson();
    if (!lesson || !this.canGenerate()) return;
    const text = this.generateText().trim();
    this.busy.set(this.t('lessons.detail.busy.generating'));
    this.api.generateFromText(lesson.id, generateFromTextBody(text)).subscribe({
      next: (job) => {
        this.busy.set(null);
        this.generateText.set('');
        this.lessonRes.update((current) =>
          current ? { ...current, status: jobStatusAsLessonStatus(job.status) } : current,
        );
      },
      error: () => this.busy.set(null),
    });
  }

  // ---- the parent panel --------------------------------------------------------------------

  protected readonly panelDirty = signal(false);

  protected onPanelDirtyChange(dirty: boolean): void {
    this.panelDirty.set(dirty);
  }

  protected savePanel(panel: ParentPanel): void {
    const lesson = this.lesson();
    if (!lesson) return;
    this.busy.set(this.t('lessons.detail.busy.savingPanel'));
    this.api.updatePanel(lesson.id, parentPanelBody(panel)).subscribe({
      next: (saved) => {
        this.busy.set(null);
        this.panelDirty.set(false);
        this.lessonRes.update((current) => (current ? { ...current, parentPanel: saved } : current));
        this.notice.set(this.t('lessons.detail.panel.saved'));
      },
      error: () => this.busy.set(null),
    });
  }

  // ---- leaving with unsaved work -------------------------------------------------------------

  protected readonly hasUnsavedWork = computed(() => this.panelDirty() || this.stopDirty());
  private leaveConfirmed = false;
  private leaveTarget: string | null = null;

  /**
   * `lessonUnsavedGuard` calls this. Unsaved work puts the red band up and refuses the
   * navigation once, remembering where it was headed; confirming the band arms `leaveConfirmed`
   * and repeats that navigation, so "Leave anyway" goes where the teacher clicked rather than
   * to the list. `window.confirm()` is never used — the system's answer is a band.
   */
  confirmLeave(target: string): boolean {
    if (this.leaveConfirmed || !this.hasUnsavedWork()) return true;
    this.leaveTarget = target;
    this.pendingAction.set({ kind: 'leave' });
    return false;
  }

  // ---- move the lesson to another day (teacher, unpublished) ---------------------------------

  /**
   * §8: a published lesson's day is fixed — children have already seen the island on it, and
   * `PATCH /teacher/lessons/{id}` refuses the move server-side too. Unpublish first.
   */
  protected readonly canMoveDate = computed(
    () => this.api.supportsMoveDate() && !this.isPublished() && this.lesson() !== null,
  );

  protected readonly dateValue = computed(() => this.lesson()?.date ?? '');

  /** Optimistic: the date control is the only thing on screen that would lag behind a round trip. */
  protected moveDate(date: string): void {
    const lesson = this.lesson();
    if (!lesson || !date || date === lesson.date) return;
    const previous = lesson.date;
    this.lessonRes.update((current) => (current ? { ...current, date } : current));
    this.busy.set(this.t('lessons.detail.busy.movingDate'));
    this.api.moveDate(lesson.id, date).subscribe({
      next: (updated) => {
        this.busy.set(null);
        this.lessonRes.update(() => updated);
      },
      error: () => {
        this.busy.set(null);
        this.lessonRes.update((current) => (current ? { ...current, date: previous } : current));
      },
    });
  }

  // ---- publish readiness ---------------------------------------------------------------------

  protected readonly publishReady = computed(() => {
    const lesson = this.lesson();
    if (!lesson) return false;
    if (lesson.source === AdminLessonSourceEnum.MANUAL) {
      return lesson.plays.some(
        (play) => play.level === 1 && play.variant === 0 && play.play.stops.length > 0,
      );
    }
    const mains = lesson.plays.filter((play) => play.variant === 0).length;
    const hasAgain = lesson.plays.some((play) => play.variant === 1);
    return mains === 3 && hasAgain && lesson.parentPanel != null;
  });

  protected readonly canPublish = computed(
    () =>
      this.lesson()?.status === AdminLessonStatusEnum.REVIEW && this.publishReady() && this.busy() === null,
  );

  /**
   * §8: "draft and error lessons can be deleted", and the server says the same thing — a delete
   * of anything else answers 409 ("Only a draft or a failed lesson can be deleted"). So a lesson
   * that has been written is kept even after Unpublish takes it off the islands: its plays, its
   * parent panel and its results are what Unpublish was for, and offering a Delete the server
   * would refuse is worse than not offering one.
   */
  protected readonly canDelete = computed(() => {
    const status = this.lesson()?.status;
    return status === AdminLessonStatusEnum.DRAFT || status === AdminLessonStatusEnum.ERROR;
  });

  protected readonly publishReason = computed(() => {
    this.lang();
    if (this.canPublish() || this.busy() !== null) return null;
    const lesson = this.lesson();
    if (!lesson) return null;
    if (lesson.status !== AdminLessonStatusEnum.REVIEW)
      return this.t('lessons.detail.publishReason.notReady');
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
    if (action.kind === 'deleteStop') return this.t('lessons.detail.deleteStopConfirm.title');
    if (action.kind === 'leave') return this.t('lessons.detail.leaveConfirm.title');
    return this.t('lessons.detail.regenerateStopConfirm.title');
  });

  protected readonly confirmMessage = computed(() => {
    this.lang();
    const action = this.pendingAction();
    if (!action) return '';
    if (action.kind === 'publish')
      return this.t('lessons.detail.publishConfirm.message', { title: this.pageTitle() });
    if (action.kind === 'delete') return this.t('lessons.deleteConfirm.message', { title: this.pageTitle() });
    if (action.kind === 'regeneratePlay') return this.t('lessons.detail.regeneratePlayConfirm.message');
    if (action.kind === 'deleteStop')
      return this.t('lessons.detail.deleteStopConfirm.message', { title: action.title });
    if (action.kind === 'leave') return this.t('lessons.detail.leaveConfirm.message');
    return this.t('lessons.detail.regenerateStopConfirm.message', { title: action.title });
  });

  protected readonly confirmLabel = computed(() => {
    this.lang();
    const action = this.pendingAction();
    if (!action) return '';
    if (action.kind === 'publish') return this.t('lessons.detail.publishConfirm.confirm');
    if (action.kind === 'delete') return this.t('lessons.deleteConfirm.confirm');
    if (action.kind === 'regeneratePlay') return this.t('lessons.detail.regeneratePlayConfirm.confirm');
    if (action.kind === 'deleteStop') return this.t('lessons.detail.deleteStopConfirm.confirm');
    if (action.kind === 'leave') return this.t('lessons.detail.leaveConfirm.confirm');
    return this.t('lessons.detail.regenerateStopConfirm.confirm');
  });

  /**
   * An Admin publishes the one lesson and gets the plain confirm band. A teacher gets the sheet
   * (§8): her own class is always in, her sibling sections are the choice.
   */
  protected requestPublish(): void {
    if (this.usesPublishSheet()) this.publishSheetOpen.set(true);
    else this.pendingAction.set({ kind: 'publish' });
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
    else if (action.kind === 'deleteStop') this.doDeleteStop(action.stopId);
    else if (action.kind === 'leave') this.doLeave();
    else this.doRegenerateStop(action.stopId);
  }

  private doRegeneratePlay(playId: string): void {
    this.busy.set(this.t('lessons.detail.busy.regeneratingPlay'));
    this.api.regeneratePlay(playId).subscribe({
      next: () => {
        this.busy.set(null);
        this.lessonRes.reload();
      },
      error: () => this.busy.set(null),
    });
  }

  private doRegenerateStop(stopId: string): void {
    this.busy.set(this.t('lessons.detail.busy.regeneratingStop'));
    this.api.regenerateStop(stopId).subscribe({
      next: () => {
        this.busy.set(null);
        this.lessonRes.reload();
      },
      error: () => this.busy.set(null),
    });
  }

  /** "Leave anyway": the guard is told yes once, and the navigation that was refused is retried. */
  private doLeave(): void {
    this.leaveConfirmed = true;
    void this.router.navigateByUrl(this.leaveTarget ?? this.basePath());
  }

  private doDelete(): void {
    const lesson = this.lesson();
    if (!lesson) return;
    this.api.deleteLesson(lesson.id).subscribe({
      next: () => void this.router.navigate([this.basePath()]),
      error: () => undefined,
    });
  }

  private doPublish(): void {
    const lesson = this.lesson();
    if (!lesson) return;
    const previous = lesson;
    this.busy.set(this.t('lessons.detail.busy.publishing'));
    this.lessonRes.update((current) =>
      current ? { ...current, status: AdminLessonStatusEnum.PUBLISHED } : current,
    );
    this.api.publish(lesson.id, lesson.classId).subscribe({
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

  // ---- the publish sheet: her class, plus the siblings she ticks (§8) -------------------------

  protected readonly usesPublishSheet = computed(() => !this.api.isAdmin());
  protected readonly publishSheetOpen = signal(false);

  /**
   * Her classes, read only while the sheet is open.
   *
   * Every lesson view would otherwise pay for a list only the sheet reads, and the sheet is
   * opened once per lesson at most. The skeleton inside covers the round trip.
   */
  private readonly myClasses = rxResource({
    params: () => (this.publishSheetOpen() && this.usesPublishSheet() ? true : undefined),
    stream: () => this.teacherApi.myClasses(),
    defaultValue: [],
  });

  protected readonly siblingsLoading = this.myClasses.isLoading;

  /**
   * A **sibling** is another of her sections in the same grade and the same subject — the ones
   * this lesson would make sense in. Her own class is excluded because it is never a choice,
   * and 2C · Math is excluded because a Grade 1 lesson is not a Grade 2 lesson.
   */
  protected readonly siblingClasses = computed(() => {
    const lesson = this.lesson();
    if (!lesson) return [];
    return this.myClasses
      .value()
      .filter(
        (card) =>
          card.classId !== undefined &&
          card.classId !== lesson.classId &&
          card.grade === lesson.course.grade &&
          card.subject === lesson.subject,
      )
      .map((card) => ({ id: card.classId!, name: card.className ?? card.classId! }));
  });

  private readonly selectedSiblings = signal<ReadonlySet<string>>(new Set());

  protected isSiblingSelected(id: string): boolean {
    return this.selectedSiblings().has(id);
  }

  protected toggleSibling(id: string, selected: boolean): void {
    this.selectedSiblings.update((current) => {
      const next = new Set(current);
      if (selected) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  protected readonly publishSheetTitle = computed(() => {
    this.lang();
    const lesson = this.lesson();
    if (!lesson) return '';
    return this.t('lessons.detail.publishSheet.title', {
      class: lesson.className ?? this.classFact(),
      date: this.dateLabel(),
    });
  });

  /** The success band: one line per copy, each linking to the lesson that class now has. */
  protected readonly publishedCopies = signal<readonly { id: string; name: string }[]>([]);

  protected dismissPublishedCopies(): void {
    this.publishedCopies.set([]);
  }

  protected confirmPublishSheet(): void {
    const lesson = this.lesson();
    if (!lesson) return;
    // Her own class is never a checkbox and never optional: "Publish to 1A" is the sheet's title.
    const own = lesson.classId ? [lesson.classId] : [];
    const classIds = [...own, ...this.selectedSiblings()];
    const names = new Map(this.siblingClasses().map((sibling) => [sibling.id, sibling.name]));
    if (lesson.classId) names.set(lesson.classId, lesson.className ?? lesson.classId);

    this.publishSheetOpen.set(false);
    this.busy.set(this.t('lessons.detail.busy.publishing'));
    this.api.publishToClasses(lesson.id, classIds).subscribe({
      next: (copies) => {
        this.busy.set(null);
        this.publishedCopies.set(this.toCopyLinks(copies, names));
        this.selectedSiblings.set(new Set());
        this.lessonRes.reload();
      },
      error: () => this.busy.set(null),
    });
  }

  /**
   * The server answers with the copies it made, and a copy for a class she cannot name is still
   * worth linking — `PublishedCopy.classId` is the key, the name is the nicety.
   */
  private toCopyLinks(
    copies: readonly PublishedCopy[],
    names: ReadonlyMap<string, string>,
  ): readonly { id: string; name: string }[] {
    return copies
      .filter((copy) => copy.lessonId !== undefined)
      .map((copy) => ({
        id: copy.lessonId!,
        name: names.get(copy.classId ?? '') ?? copy.classId ?? '',
      }));
  }

  // ---- unpublish: immediate, with a 10 s Undo strip ------------------------------------------

  protected readonly undoOpen = signal(false);

  protected unpublish(): void {
    const lesson = this.lesson();
    if (!lesson) return;
    this.busy.set(this.t('lessons.detail.busy.unpublishing'));
    this.api.unpublish(lesson.id).subscribe({
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
    this.busy.set(this.t('lessons.detail.busy.publishing'));
    // Its own class only: Undo puts back what Unpublish took away, and the siblings it was
    // published to alongside were never unpublished — re-fanning out would republish them.
    this.api.publish(lesson.id, lesson.classId).subscribe({
      next: (updated) => {
        this.busy.set(null);
        this.lessonRes.update(() => updated);
      },
      error: () => this.busy.set(null),
    });
  }

  protected undoExpired(): void {
    this.undoOpen.set(false);
  }

  // ---- wiring ---------------------------------------------------------------------------

  constructor() {
    // Page crops belong to this lesson and are never wanted again once it is closed — they are
    // lossless scans, so a tab that walked through a week of lessons would otherwise be holding
    // all of them. Opening another lesson drops these; so does leaving the editor.
    this.media.scopeTo(this.lessonId);
    inject(DestroyRef).onDestroy(() => this.media.scopeTo(null));

    const key = this.route.snapshot.queryParamMap.get('notice');
    if (key) {
      const text = this.t(key);
      if (text !== key) this.notice.set(text);
    }

    // Poll while a job is running; the interval clears itself on the next run, on a terminal
    // status, and (the `onCleanup` the effect gets for free) when the page is destroyed.
    //
    // CR4 adds the second reason to keep asking: a file may still be `converting` while the
    // lesson's own status has already settled — a retry from a failed conversion puts one file
    // back to work without moving the lesson out of `error`. Both conditions are terminal-only
    // by construction: `converting` is the single non-terminal file state, so the last file to
    // finish stops the timer.
    effect((onCleanup) => {
      const lesson = this.lesson();
      const running = lesson !== null && isRunningStatus(lesson.status);
      if (!running && !anyConverting(lesson?.files ?? [])) return;
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

/**
 * The validator's own lines out of a 422, for the Raw JSON panel.
 *
 * `StopTextService` answers `"Couldn't save, please rephrase. <up to five errors, joined by
 * '; '>"`. The sentence is what the teacher reads, translated; these are what an Admin in debug
 * mode needs to see the shape of, so they are taken from the **raw** body rather than from
 * `apiErrorOf`, which strips exactly this.
 */
function validatorLinesOf(error: unknown): readonly string[] {
  if (!(error instanceof HttpErrorResponse)) return [];
  const body: unknown = error.error;
  const message = typeof body === 'object' && body !== null ? (body as { message?: unknown }).message : null;
  if (typeof message !== 'string') return [];
  const detail = message.replace(/^[^.]*\.\s*/, '');
  return detail
    .split(';')
    .map((line) => line.trim())
    .filter((line) => line !== '');
}
