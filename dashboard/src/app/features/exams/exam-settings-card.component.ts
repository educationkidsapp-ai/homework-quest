import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { type ExamSettings, ExamsApi, apiErrorOf } from '../../api';
import { BandService } from '../../core/band/band.service';
import { FLAGS } from '../../core/flags/flag.service';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import { PlatformService } from '../../core/platform/platform.service';
import { UndoService } from '../../core/undo/undo.service';
import {
  ButtonComponent,
  CardComponent,
  InputComponent,
  SelectComponent,
  SkeletonComponent,
  type SelectOption,
} from '../../ui';
import { ExamContextService } from './exam-context.service';
import {
  EXAM_LEVELS,
  RELEASE_MODES,
  type ExamLevel,
  type ReleaseMode,
  type WindowDraft,
  draftOfWindow,
  isExamLevel,
  isReleaseMode,
  settingsFrozen,
  windowErrorOf,
  windowOf,
  zonedText,
} from './exams.models';

/**
 * **The settings card** the lesson editor grows when the lesson it is showing is an exam (§8).
 *
 * The editor itself is unchanged — an exam is a lesson, its stops are edited the same way, its
 * parent panel is the same panel — and everything that makes it an exam lives here: the window,
 * the level every child sits, the optional time limit and how the results reach the parents.
 *
 * **Editable until it opens, read-only after.** `PATCH /teacher/exams/{id}` answers `409
 * exam_open` once the window has started, because the paper is in front of children and a level
 * that moved under them would change what they are being marked on. The card says so in words
 * — "Open — settings frozen" — rather than disabling four fields and leaving her to work out
 * why, and it stops offering Save at the same moment the server stops accepting it.
 *
 * **The gap it works around.** There is no `GET /teacher/exams/{id}`; the only read is the
 * class's whole list, so the card fetches that and finds itself in it. One request either way
 * for a card on one screen, but it is a list request where a row request would do.
 */
@Component({
  selector: 'hq-exam-settings-card',
  imports: [
    CardComponent,
    InputComponent,
    SelectComponent,
    ButtonComponent,
    SkeletonComponent,
    FeatureDirective,
    CanDirective,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './exam-settings-card.component.html',
  styleUrl: './exam-settings-card.component.scss',
})
export class ExamSettingsCardComponent {
  private readonly api = inject(ExamsApi);
  private readonly transloco = inject(TranslocoService);
  private readonly platform = inject(PlatformService);
  private readonly context = inject(ExamContextService);
  private readonly band = inject(BandService);
  private readonly undo = inject(UndoService);
  private readonly lang = activeLang();

  readonly examId = input.required<string>();
  readonly classId = input.required<string>();

  protected readonly examsFlag = FLAGS.exams;

  private readonly list = rxResource({
    params: () => this.classId(),
    stream: ({ params }) => this.api.classExams(params),
    defaultValue: [] as ExamSettings[],
  });

  protected readonly exam = computed(
    () => this.list.value().find((row) => row.examId === this.examId()) ?? null,
  );

  protected readonly loading = computed(() => this.list.isLoading());

  protected readonly zone = computed(() => this.platform.timezone());

  /**
   * A clock, ticked once a minute, so a card left open across the opening minute freezes itself
   * rather than offering a Save the server will refuse.
   */
  private readonly now = signal(Date.now());

  protected readonly frozen = computed(() => {
    const exam = this.exam();
    return exam !== null && settingsFrozen(exam, this.now());
  });

  // ---- the draft ------------------------------------------------------------------------------

  protected readonly title = signal('');
  protected readonly window = signal<WindowDraft>({
    opensDate: '',
    opensTime: '',
    closesDate: '',
    closesTime: '',
  });
  protected readonly level = signal<ExamLevel>('mixed');
  protected readonly releaseMode = signal<ReleaseMode>('auto_on_close');
  protected readonly duration = signal('');
  protected readonly saving = signal(false);

  /** `hq-select` emits `T | ''` for its placeholder row; these never show one. */
  protected setReleaseMode(value: ReleaseMode | ''): void {
    if (isReleaseMode(value)) this.releaseMode.set(value);
  }

  protected setLevel(value: ExamLevel | ''): void {
    if (isExamLevel(value)) this.level.set(value);
  }

  protected setWindow(part: keyof WindowDraft, value: string): void {
    this.window.set({ ...this.window(), [part]: value });
  }

  protected readonly windowError = computed(() => windowErrorOf(this.window(), this.zone()));

  protected readonly windowMessage = computed(() => {
    this.lang();
    const error = this.windowError();
    return error === null ? null : this.t(`exams.create.error.${error}`);
  });

  protected readonly levelOptions = computed<readonly SelectOption<ExamLevel>[]>(() => {
    this.lang();
    return EXAM_LEVELS.map((value) => ({ value, label: this.t(`exams.level.${value}`) }));
  });

  protected readonly releaseOptions = computed<readonly SelectOption<ReleaseMode>[]>(() => {
    this.lang();
    return RELEASE_MODES.map((value) => ({ value, label: this.t(`exams.release.${value}`) }));
  });

  /** §8's sentence, in the card and — through {@link ExamContextService} — on the publish band. */
  protected readonly sentence = computed(() => {
    this.lang();
    const exam = this.exam();
    if (!exam) return '';
    return this.t('exams.settings.publishSentence', {
      opens: this.moment(exam.opensAt),
      closes: this.moment(exam.closesAt),
    });
  });

  protected readonly canSave = computed(
    () => !this.frozen() && this.windowError() === null && this.title().trim() !== '' && !this.saving(),
  );

  protected save(): void {
    const exam = this.exam();
    const window = windowOf(this.window(), this.zone());
    if (!exam || !window || !this.canSave()) return;
    const minutes = Number.parseInt(this.duration(), 10);

    this.saving.set(true);
    this.api
      .updateExam(this.examId(), {
        title: this.title().trim(),
        opensAt: window.opensAt,
        closesAt: window.closesAt,
        level: this.level(),
        releaseMode: this.releaseMode(),
        durationMinutes: Number.isFinite(minutes) && minutes > 0 ? minutes : undefined,
      })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.list.reload();
          this.undo.offerUndo({
            message: this.t('exams.settings.saved'),
            // Undo puts the row back as the server last described it, through the same PATCH.
            undo: () => this.restore(exam),
            commit: () => this.list.reload(),
          });
        },
        error: (cause: unknown) => {
          this.saving.set(false);
          this.list.reload();
          this.band.fail(apiErrorOf(cause)?.message ?? this.t('band.unreachable'));
        },
      });
  }

  private restore(previous: ExamSettings): void {
    this.api
      .updateExam(this.examId(), {
        title: previous.title ?? undefined,
        opensAt: previous.opensAt,
        closesAt: previous.closesAt,
        level: previous.level,
        releaseMode: previous.releaseMode,
        durationMinutes: previous.durationMinutes,
      })
      .subscribe({
        next: () => this.list.reload(),
        error: (cause: unknown) => this.band.fail(apiErrorOf(cause)?.message ?? this.t('band.unreachable')),
      });
  }

  // ---- read-only lines, for after it has opened -------------------------------------------------

  protected readonly windowText = computed(() => {
    this.lang();
    const exam = this.exam();
    return exam ? `${this.moment(exam.opensAt)} — ${this.moment(exam.closesAt)}` : '';
  });

  protected readonly levelText = computed(() => {
    this.lang();
    return this.t(`exams.level.${this.exam()?.level ?? 'mixed'}`);
  });

  protected readonly releaseText = computed(() => {
    this.lang();
    return this.t(`exams.release.${this.exam()?.releaseMode ?? 'auto_on_close'}`);
  });

  protected readonly durationText = computed(() => {
    this.lang();
    const minutes = this.exam()?.durationMinutes;
    return minutes ? this.t('exams.settings.minutes', { count: minutes }) : this.t('exams.settings.noLimit');
  });

  private moment(instant: number | undefined): string {
    return zonedText(instant, this.zone(), this.lang(), {
      day: 'numeric',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit',
    });
  }

  constructor() {
    const destroyRef = inject(DestroyRef);
    const tick = setInterval(() => this.now.set(Date.now()), 60_000);
    destroyRef.onDestroy(() => clearInterval(tick));

    // The draft follows the server's row until she starts typing into it — `untracked` is not
    // needed because nothing here reads the draft signals, only writes them.
    effect(() => {
      const exam = this.exam();
      if (!exam) return;
      this.context.set(this.sentence());
      this.title.set(exam.title ?? '');
      this.window.set(draftOfWindow(exam.opensAt ?? 0, exam.closesAt ?? 0, this.zone()));
      this.level.set(isExamLevel(exam.level) ? exam.level : 'mixed');
      this.releaseMode.set(isReleaseMode(exam.releaseMode) ? exam.releaseMode : 'auto_on_close');
      this.duration.set(exam.durationMinutes ? String(exam.durationMinutes) : '');
    });
    destroyRef.onDestroy(() => this.context.clear());
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
