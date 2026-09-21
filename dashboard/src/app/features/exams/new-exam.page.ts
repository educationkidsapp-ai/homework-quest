import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { type ExamSettings, ExamsApi, TeacherApi, apiErrorOf } from '../../api';
import { FLAGS } from '../../core/flags/flag.service';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { activeLang } from '../../core/i18n/active-lang';
import { PlatformService } from '../../core/platform/platform.service';
import {
  BandComponent,
  ButtonComponent,
  CardComponent,
  EmptyStateComponent,
  InputComponent,
  PageComponent,
  ProgressBarComponent,
  SelectComponent,
  type Breadcrumb,
  type SelectOption,
} from '../../ui';
import { LessonApiService } from '../lessons/lesson-api.service';
import type { LessonSource } from '../lessons/lessons.models';
import {
  EXAM_LEVELS,
  RELEASE_MODES,
  type ExamLevel,
  type ReleaseMode,
  type WindowDraft,
  isReleaseMode,
  windowErrorOf,
  windowOf,
} from './exams.models';

const SOURCE_DEFS: readonly { readonly id: LessonSource; readonly flag: string }[] = [
  { id: 'pdf', flag: FLAGS.lessonsPdf },
  { id: 'slides', flag: FLAGS.lessonsSlides },
  { id: 'images', flag: FLAGS.lessonsImages },
  { id: 'manual', flag: FLAGS.lessonsManual },
];

const ACCEPT: Record<Exclude<LessonSource, 'manual'>, string> = {
  pdf: '.pdf,.docx,.doc,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  slides: '.pptx,.pptm,.ppsx,application/vnd.openxmlformats-officedocument.presentationml.presentation,application/vnd.ms-powerpoint.presentation.macroEnabled.12,application/vnd.openxmlformats-officedocument.presentationml.slideshow',
  images: '.png,.jpg,.jpeg,image/png,image/jpeg',
};

/** `multipart.max-file-size: 25MB`, and ten files, exactly as New lesson holds them. */
const MAX_FILES = 10;
const MAX_FILE_BYTES = 25 * 1024 * 1024;

function accepts(source: LessonSource, name: string): boolean {
  const ext = name.slice(name.lastIndexOf('.') + 1).toLowerCase();
  if (source === 'pdf') return ext === 'pdf' || ext === 'docx' || ext === 'doc';
  if (source === 'slides') return ext === 'pptx' || ext === 'pptm' || ext === 'ppsx';
  if (source === 'images') return ext === 'png' || ext === 'jpg' || ext === 'jpeg';
  return false;
}

/** An hour from now, rounded up to the next quarter — a window a teacher can accept as it is. */
function defaultWindow(zone: string): WindowDraft {
  const start = new Date(Date.now() + 60 * 60 * 1000);
  start.setMinutes(Math.ceil(start.getMinutes() / 15) * 15, 0, 0);
  const end = new Date(start.getTime() + 60 * 60 * 1000);
  const text = (date: Date, part: 'date' | 'time'): string => {
    const parts = new Intl.DateTimeFormat('en-CA', {
      timeZone: zone,
      hour12: false,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    }).formatToParts(date);
    const read = (type: string): string => parts.find((entry) => entry.type === type)?.value ?? '00';
    const hour = read('hour') === '24' ? '00' : read('hour');
    return part === 'date' ? `${read('year')}-${read('month')}-${read('day')}` : `${hour}:${read('minute')}`;
  };
  return {
    opensDate: text(start, 'date'),
    opensTime: text(start, 'time'),
    closesDate: text(end, 'date'),
    closesTime: text(end, 'time'),
  };
}

/**
 * **New exam** (`docs/teacher-flow.md` §4 step 10) — the settings, then the same editor as a lesson.
 *
 * One page, one primary action, the same shape as New lesson: the exam, its window, its level,
 * how it releases, and where the questions come from. On create the server makes a lesson
 * stamped `type = exam` and runs the ordinary pipeline over the source, so the editor she lands
 * in is the one she already knows — with a settings card on top of it.
 *
 * **The window is a wall clock in the school's timezone.** Four fields and not a `datetime-local`
 * pair: the native control is the browser's zone, silently, and an exam that opens "at nine"
 * means nine where the children are. The zone is named under the fields so nobody has to guess
 * whose nine it is, and "closes after opens" is checked here as well as by the server, because
 * learning it from a 400 costs her the file she just picked.
 *
 * A failure after the exam row exists rolls it back, exactly as New lesson does: an orphan exam
 * would sit in the tab forever with a window nobody set.
 *
 * **The gates.** The route carries `exams` and `lesson.write` from the one table in
 * `core/nav/screens.ts`; the source cards inside carry `lessons.pdf|slides|images|manual` on
 * `*hqFeature`, so a school without slides is not offered a slide deck it cannot convert.
 */
@Component({
  selector: 'hq-new-exam-page',
  imports: [
    PageComponent,
    CardComponent,
    InputComponent,
    SelectComponent,
    ButtonComponent,
    BandComponent,
    ProgressBarComponent,
    EmptyStateComponent,
    FeatureDirective,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './new-exam.page.html',
  styleUrl: './new-exam.page.scss',
})
export class NewExamPage {
  private readonly exams = inject(ExamsApi);
  private readonly lessons = inject(LessonApiService);
  private readonly teacherApi = inject(TeacherApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);
  private readonly platform = inject(PlatformService);
  private readonly lang = activeLang();

  protected readonly examsFlag = FLAGS.exams;

  private readonly query = toSignal(this.route.queryParamMap, {
    initialValue: this.route.snapshot.queryParamMap,
  });

  protected readonly classId = computed(() => this.query().get('classId') ?? '');

  private readonly myClasses = rxResource({
    params: () => this.classId(),
    stream: () => this.teacherApi.myClasses(),
    defaultValue: [],
  });

  protected readonly section = computed(() =>
    this.myClasses.value().find((card) => card.classId === this.classId()),
  );

  protected readonly className = computed(() => this.section()?.className ?? '');

  protected readonly zone = computed(() => this.platform.timezone());

  // ---- the fields ----------------------------------------------------------------------------

  protected readonly title = signal('');
  protected readonly window = signal<WindowDraft>(defaultWindow(this.platform.timezone()));
  protected readonly level = signal<ExamLevel>('mixed');
  protected readonly releaseMode = signal<ReleaseMode>('auto_on_close');
  protected readonly duration = signal('');
  protected readonly source = signal<LessonSource | null>(null);
  protected readonly files = signal<readonly File[]>([]);

  protected readonly busy = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  protected setWindow(part: keyof WindowDraft, value: string): void {
    this.window.set({ ...this.window(), [part]: value });
  }

  protected readonly windowError = computed(() => windowErrorOf(this.window(), this.zone()));

  /** Shown on the field rather than at the top: the mistake is in the pair, and so is the sentence. */
  protected readonly windowMessage = computed(() => {
    this.lang();
    const error = this.windowError();
    return error === null ? null : this.t(`exams.create.error.${error}`);
  });

  /**
   * §8's "one level — 1, 2, 3, or mixed", as four radios rather than a dropdown.
   *
   * Every child sits the same paper, so this is the one decision on the form that changes what
   * the exam *is*; a `<select>` hides three of the four choices behind a click, and the fourth
   * — mixed — is the one a teacher has to read a sentence about before she picks it.
   */
  protected readonly levelChoices = computed(() => {
    this.lang();
    return EXAM_LEVELS.map((value) => ({ value, label: this.t(`exams.level.${value}`) }));
  });

  protected isLevel(value: ExamLevel): boolean {
    return this.level() === value;
  }

  /** `hq-select` emits `T | ''` for its placeholder row; these two never show one. */
  protected setReleaseMode(value: ReleaseMode | ''): void {
    if (isReleaseMode(value)) this.releaseMode.set(value);
  }

  protected readonly releaseOptions = computed<readonly SelectOption<ReleaseMode>[]>(() => {
    this.lang();
    return RELEASE_MODES.map((value) => ({ value, label: this.t(`exams.release.${value}`) }));
  });

  protected readonly sourceCards = computed(() => {
    this.lang();
    return SOURCE_DEFS.map((def) => ({
      ...def,
      title: this.t(`lessons.new.source.${def.id}.title`),
      hint: this.t(`lessons.new.source.${def.id}.hint`),
    }));
  });

  protected selectSource(source: LessonSource): void {
    if (this.source() === source) return;
    this.source.set(source);
    this.files.set([]);
    this.error.set(null);
  }

  protected acceptFor(): string | null {
    const source = this.source();
    return source && source !== 'manual' ? ACCEPT[source] : null;
  }

  protected onFileInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.addFiles(Array.from(input.files ?? []));
    input.value = '';
  }

  protected removeFile(index: number): void {
    this.files.set(this.files().filter((_, at) => at !== index));
  }

  protected formatFileSize(bytes: number): string {
    if (bytes >= 1_000_000) return `${(bytes / 1_000_000).toFixed(1)} MB`;
    if (bytes >= 1_000) return `${Math.round(bytes / 1000)} KB`;
    return `${bytes} B`;
  }

  private addFiles(selected: readonly File[]): void {
    const source = this.source();
    if (!source || source === 'manual' || selected.length === 0) return;
    const usable = selected.filter((file) => accepts(source, file.name) && file.size <= MAX_FILE_BYTES);
    const rejected = selected.length - usable.length;
    const kept = source === 'images' ? this.files() : [];
    this.files.set([...kept, ...usable].slice(0, MAX_FILES));
    this.error.set(rejected > 0 ? this.t('exams.create.error.files', { count: rejected }) : null);
  }

  // ---- the one primary action ------------------------------------------------------------------

  /** What is still missing, in the order a teacher fills the form — one sentence, never a list. */
  protected readonly reason = computed<string | null>(() => {
    this.lang();
    if (!this.classId()) return this.t('exams.create.noClass');
    if (this.title().trim() === '') return this.t('exams.create.reason.title');
    if (this.windowError() !== null) return this.t('exams.create.reason.window');
    const source = this.source();
    if (source === null) return this.t('exams.create.reason.source');
    if (source !== 'manual' && this.files().length === 0) return this.t('exams.create.reason.files');
    return null;
  });

  protected readonly ready = computed(() => this.reason() === null && this.busy() === null);

  protected create(): void {
    const source = this.source();
    const window = windowOf(this.window(), this.zone());
    if (!this.ready() || !source || !window) return;

    const minutes = Number.parseInt(this.duration(), 10);
    this.error.set(null);
    this.busy.set(this.t('exams.create.busy.creating'));
    this.exams
      .createExam(this.classId(), {
        title: this.title().trim(),
        opensAt: window.opensAt,
        closesAt: window.closesAt,
        level: this.level(),
        releaseMode: this.releaseMode(),
        durationMinutes: Number.isFinite(minutes) && minutes > 0 ? minutes : undefined,
        source,
      })
      .subscribe({
        next: (exam) => this.afterCreate(exam, source),
        error: (cause: unknown) => this.fail(cause),
      });
  }

  private afterCreate(exam: ExamSettings, source: LessonSource): void {
    const examId = exam.examId ?? '';
    if (source === 'manual') return this.openEditor(examId);
    this.busy.set(this.t('exams.create.busy.uploading'));
    this.lessons.uploadFiles(examId, this.files()).subscribe({
      next: () => this.startAnalyze(examId),
      error: (cause: unknown) => this.rollback(examId, cause),
    });
  }

  private startAnalyze(examId: string): void {
    this.busy.set(this.t('exams.create.busy.analyzing'));
    this.lessons.analyze(examId).subscribe({
      next: () => this.openEditor(examId),
      error: (cause: unknown) => this.rollback(examId, cause),
    });
  }

  private openEditor(examId: string): void {
    this.busy.set(null);
    void this.router.navigate(['/teacher/lessons', examId], {
      queryParams: { notice: 'exams.create.created' },
    });
  }

  /** An exam nobody asked for is worse than a form she has to fill in again. */
  private rollback(examId: string, cause: unknown): void {
    const message = apiErrorOf(cause)?.message ?? this.t('band.unreachable');
    this.lessons.deleteLesson(examId).subscribe({
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

  private fail(cause: unknown): void {
    this.busy.set(null);
    this.error.set(apiErrorOf(cause)?.message ?? this.t('band.unreachable'));
  }

  // ---- the frame ---------------------------------------------------------------------------------

  protected readonly breadcrumbs = computed<readonly Breadcrumb[]>(() => {
    this.lang();
    const classId = this.classId();
    return [
      { label: this.t('classes.title'), link: '/teacher/classes' },
      ...(classId ? [{ label: this.className(), link: `/teacher/classes/${classId}` }] : []),
      { label: this.t('exams.create.title') },
    ];
  });

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
