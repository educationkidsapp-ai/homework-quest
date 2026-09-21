import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { type LessonResults, ExamsApi, ResultsAndGradebookApi, apiErrorOf } from '../../api';
import { BandService } from '../../core/band/band.service';
import { exportName, saveFile } from '../../core/download/download';
import { FLAGS } from '../../core/flags/flag.service';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import { PlatformService } from '../../core/platform/platform.service';
import { UndoService } from '../../core/undo/undo.service';
import {
  BandComponent,
  ButtonComponent,
  CardComponent,
  EmptyStateComponent,
  PageComponent,
  SkeletonComponent,
  TableComponent,
  type Breadcrumb,
  type TableColumn,
} from '../../ui';
import { LevelBandComponent } from '../results/level-band.component';
import { MarkPanelComponent } from '../results/mark-panel.component';
import {
  formatAnswer,
  isFault,
  resultRows,
  scoreLabel,
  type ResultRow,
  type RowStop,
} from '../results/results.models';
import { ExamDistributionComponent } from './exam-distribution.component';
import { type ChildRow, childRows, minutesTaken, questionRows, zonedText } from './exams.models';

const XLSX = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const MOMENT: Intl.DateTimeFormatOptions = {
  day: 'numeric',
  month: 'short',
  hour: '2-digit',
  minute: '2-digit',
};

/**
 * **The exam results page** (`docs/teacher-flow.md` §4 step 10) — one exam, the whole class.
 *
 * Five numbers at the top, in the order Maya asks for them: who sat it, who handed in, who was
 * absent, what the class averaged, and how much marking is still hers. Then the shape of the
 * class over the four bands, then the questions hardest-first — the two questions to go over on
 * Sunday morning — and then every child, with the way into her work.
 *
 * **Three things she can do here, and each says what it costs.**
 *
 * * *Re-open* gives one absent or interrupted child another sitting. It is once per child
 *   (`409 exam_already_reopened`), so it asks in the red band first and the button is simply
 *   absent for a child the server would refuse.
 * * *Release* hands the scores to the parents, under §7's sentence rather than a label. Taking
 *   them back takes something off a report a parent may already have read, so that direction
 *   confirms; releasing is offered back for ten seconds instead.
 * * *Export* — CSV, the Excel sheet, and the printable per-child PDF. All three are behind the
 *   bearer, so the bytes come through the generated client and are saved; an `<a href>` would
 *   navigate the tab to a 401.
 *
 * **Marking reuses N4.2's panel.** An exam is a lesson, so its open answers arrive through
 * `GET /teacher/lessons/{id}/results` and are marked with the same `PUT /teacher/marks`. That
 * second request is made only when the exam actually has marking outstanding — a page with
 * nothing to mark does not pay for the retells it will not show.
 *
 * **The gates.** The route carries `exams` and `results.read` from the one table in
 * `core/nav/screens.ts`; every control on the page repeats the flag with `*hqFeature="examsFlag"`
 * and names the key it needs with `*hqCan`, so a MANAGERIAL account reads the numbers and
 * releases nothing.
 */
@Component({
  selector: 'hq-exam-results-page',
  imports: [
    PageComponent,
    CardComponent,
    TableComponent,
    ButtonComponent,
    BandComponent,
    SkeletonComponent,
    EmptyStateComponent,
    LevelBandComponent,
    MarkPanelComponent,
    ExamDistributionComponent,
    FeatureDirective,
    CanDirective,
    RouterLink,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './exam-results.page.html',
  styleUrl: './exam-results.page.scss',
})
export class ExamResultsPage {
  private readonly api = inject(ExamsApi);
  private readonly grading = inject(ResultsAndGradebookApi);
  private readonly route = inject(ActivatedRoute);
  private readonly transloco = inject(TranslocoService);
  private readonly platform = inject(PlatformService);
  private readonly band = inject(BandService);
  private readonly undo = inject(UndoService);
  private readonly lang = activeLang();

  protected readonly examsFlag = FLAGS.exams;

  private readonly path = toSignal(this.route.paramMap, { initialValue: this.route.snapshot.paramMap });
  protected readonly examId = computed(() => this.path().get('id') ?? '');

  protected readonly results = rxResource({
    params: () => this.examId(),
    stream: ({ params }) => this.api.examResults(params),
    defaultValue: {},
  });

  protected readonly zone = computed(() => this.platform.timezone());

  // ---- the header and the five numbers -----------------------------------------------------

  protected readonly title = computed(() => this.results.value().title ?? '');
  protected readonly className = computed(() => this.results.value().className ?? '');
  protected readonly settings = computed(() => this.results.value().settings ?? null);

  protected readonly subtitle = computed(() => {
    this.lang();
    const settings = this.settings();
    if (!settings) return this.className();
    return this.t('exams.results.subtitle', {
      class: this.className(),
      window: `${this.moment(settings.opensAt)} — ${this.moment(settings.closesAt)}`,
    });
  });

  protected readonly breadcrumbs = computed<readonly Breadcrumb[]>(() => {
    this.lang();
    const classId = this.results.value().classId ?? '';
    return [
      { label: this.t('classes.title'), link: '/teacher/classes' },
      ...(classId ? [{ label: this.className(), link: `/teacher/classes/${classId}` }] : []),
      { label: this.t('exams.results.title') },
    ];
  });

  protected readonly satLabel = computed(() => {
    this.lang();
    const value = this.results.value();
    return this.t('exams.results.metric.satValue', { sat: value.sat ?? 0, roster: value.roster ?? 0 });
  });

  protected readonly submitted = computed(() => this.results.value().submitted ?? 0);
  protected readonly absent = computed(() => this.results.value().absent ?? 0);
  protected readonly needsMarking = computed(() => this.results.value().needsMarking ?? 0);
  protected readonly average = computed(() => scoreLabel(this.results.value().classAverage ?? null));

  // ---- the two charts ------------------------------------------------------------------------

  protected readonly distribution = computed(() => this.results.value().distribution ?? []);
  protected readonly questions = computed(() => questionRows(this.results.value().questions));

  protected readonly questionColumns = computed<readonly TableColumn<{ stopId: string }>[]>(() => {
    this.lang();
    return [
      { key: 'title', header: this.t('exams.results.questions.question'), width: '44%' },
      { key: 'answered', header: this.t('exams.results.questions.answered'), align: 'end' as const },
      { key: 'correct', header: this.t('exams.results.questions.correct'), align: 'end' as const },
      { key: 'missedPercent', header: this.t('exams.results.questions.missed'), align: 'end' as const },
    ];
  });

  protected trackQuestion = (row: { stopId: string }): string => row.stopId;

  // ---- the children --------------------------------------------------------------------------

  protected readonly rows = computed(() => childRows(this.results.value()));

  protected readonly columns = computed<readonly TableColumn<ChildRow>[]>(() => {
    this.lang();
    return [
      { key: 'name', header: this.t('results.table.child'), width: '24%' },
      { key: 'state', header: this.t('exams.results.table.state') },
      { key: 'score', header: this.t('results.table.score'), align: 'end' as const },
      { key: 'answered', header: this.t('exams.results.table.answered'), align: 'end' as const },
      { key: 'percent', header: this.t('exams.results.table.percent'), align: 'end' as const },
      { key: 'band', header: this.t('results.table.band') },
      { key: 'secondsTaken', header: this.t('exams.results.table.taken'), align: 'end' as const },
      { key: 'submittedAt', header: this.t('exams.results.table.submitted') },
      { key: 'actions', header: this.t('exams.results.table.marking'), width: '14%', align: 'end' as const },
    ];
  });

  protected trackRow = (row: ChildRow): string => row.childId;

  protected readonly openChild = signal<string | null>(null);

  protected isOpen = (row: ChildRow): boolean => this.openChild() === row.childId;

  /**
   * A row opens onto the marking panel — but only for a child who has work to mark. Expanding an
   * absent child onto an empty panel is a click that answers nothing.
   */
  protected toggleChild(row: ChildRow): void {
    if (row.answered === 0 && row.needsMarking === 0 && this.openChild() !== row.childId) return;
    this.openChild.set(this.openChild() === row.childId ? null : row.childId);
  }

  /**
   * The lesson-shaped results behind this exam, for marking and class answers inspection.
   */
  protected readonly lessonResults = rxResource({
    params: () => this.examId(),
    stream: ({ params }) =>
      this.grading.lessonResults(params).pipe(catchError(() => of({} as LessonResults))),
    defaultValue: {},
  });

  protected readonly markRows = computed(() => resultRows(this.lessonResults.value()));

  protected readonly activeView = signal<'overview' | 'matrix'>('overview');
  protected readonly matrixFaultsOnly = signal(false);

  protected setView(view: 'overview' | 'matrix'): void {
    this.activeView.set(view);
  }

  protected toggleMatrixFaultsOnly(): void {
    this.matrixFaultsOnly.update((v) => !v);
  }

  protected readonly examStops = computed(() => this.lessonResults.value().stops ?? []);

  protected readonly matrixRows = computed(() => {
    const rows = this.markRows().filter((r) => r.answered > 0);
    if (!this.matrixFaultsOnly()) return rows;
    return rows.filter((r) => r.stops.some((s) => s.attempted && isFault(s)));
  });

  protected isFaultStop(stop: RowStop): boolean {
    return isFault(stop);
  }

  protected displayAnswer(raw: string | undefined): string {
    return formatAnswer(raw);
  }

  protected childStopOf(row: ResultRow, stopId: string): RowStop | undefined {
    return row.stops.find((s) => s.stopId === stopId);
  }

  protected markRowOf(row: ChildRow): ResultRow | null {
    return this.markRows().find((candidate) => candidate.childId === row.childId) ?? null;
  }

  protected onMarked(): void {
    this.openChild.set(null);
    this.results.reload();
    this.lessonResults.reload();
  }

  protected childLink(row: ChildRow): readonly string[] {
    return ['/teacher/children', row.childId];
  }

  protected score(value: number | null): string {
    return scoreLabel(value);
  }

  /**
   * Whether this child has handed anything in.
   *
   * The server sends 0 for a child who never sat the paper, and a 0 in a score column reads as
   * a child who failed it rather than one who was not there. Everything a score implies — the
   * percent, the band — is held back to the same moment.
   */
  protected handed(row: ChildRow): boolean {
    return row.state === 'submitted';
  }

  protected taken(row: ChildRow): string {
    return minutesTaken(row.secondsTaken);
  }

  protected handedIn(row: ChildRow): string {
    return this.moment(row.submittedAt ?? undefined);
  }

  // ---- re-open -----------------------------------------------------------------------------------

  protected readonly reopening = signal<ChildRow | null>(null);
  /** What the last re-opening did, as a notice band she dismisses when she has read it. */
  protected readonly reopened = signal<string | null>(null);
  protected readonly busy = signal(false);

  protected askReopen(row: ChildRow): void {
    this.reopening.set(row);
  }

  protected cancelReopen(): void {
    this.reopening.set(null);
  }

  protected readonly reopenTitle = computed(() => {
    this.lang();
    const row = this.reopening();
    return row ? this.t('exams.results.reopen.title', { name: row.name }) : '';
  });

  protected readonly reopenMessage = computed(() => {
    this.lang();
    const row = this.reopening();
    return row ? this.t('exams.results.reopen.message', { name: row.name, title: this.title() }) : '';
  });

  /**
   * One more sitting, and only one. No Undo strip: the server has no un-re-open, and offering
   * one that quietly does nothing would be worse than the confirmation this already asked for.
   */
  protected confirmReopen(): void {
    const row = this.reopening();
    if (!row) return;
    this.reopening.set(null);
    this.busy.set(true);
    this.api.reopenExam(this.examId(), row.childId).subscribe({
      next: (reopened) => {
        this.busy.set(false);
        this.results.reload();
        this.reopened.set(
          this.t('exams.results.reopen.done', {
            name: row.name,
            closes: this.moment(reopened.closesAt),
          }),
        );
      },
      error: (cause: unknown) => this.fail(cause),
    });
  }

  // ---- release ------------------------------------------------------------------------------------

  protected readonly released = computed(() => this.results.value().released === true);

  protected readonly manualRelease = computed(() => this.settings()?.releaseMode === 'manual');

  protected readonly releaseLine = computed(() => {
    this.lang();
    if (this.released()) {
      const at = this.results.value().releasedAt;
      return at
        ? this.t('exams.results.release.releasedOn', { date: this.moment(at) })
        : this.t('exams.results.release.released');
    }
    return this.manualRelease()
      ? this.t('exams.results.release.sentence')
      : this.t('exams.results.release.auto');
  });

  protected readonly withdrawing = signal(false);

  protected release(): void {
    this.setReleased(true);
  }

  protected askWithdraw(): void {
    this.withdrawing.set(true);
  }

  protected cancelWithdraw(): void {
    this.withdrawing.set(false);
  }

  protected confirmWithdraw(): void {
    this.withdrawing.set(false);
    this.setReleased(false);
  }

  private setReleased(released: boolean): void {
    this.busy.set(true);
    this.api.releaseExam(this.examId(), { released }).subscribe({
      next: () => {
        this.busy.set(false);
        this.results.reload();
        this.undo.offerUndo({
          message: this.t(released ? 'exams.results.release.done' : 'exams.results.release.doneOff'),
          undo: () => this.setReleased(!released),
          commit: () => this.results.reload(),
        });
      },
      error: (cause: unknown) => this.fail(cause),
    });
  }

  // ---- the three exports ------------------------------------------------------------------------

  protected readonly exporting = signal(false);

  protected exportCsv(): void {
    this.exporting.set(true);
    this.api.examResultsCsv(this.examId()).subscribe({
      next: (csv) => this.savedAs(csv, 'csv', 'text/csv;charset=utf-8'),
      error: (cause: unknown) => this.failExport(cause),
    });
  }

  protected exportXlsx(): void {
    this.exporting.set(true);
    // Neither text nor JSON, so the generated client asks for a blob although its declared type
    // says `string` — which is why `saveFile` takes either.
    this.api.examResultsXlsx(this.examId()).subscribe({
      next: (sheet) => this.savedAs(sheet, 'xlsx', XLSX),
      error: (cause: unknown) => this.failExport(cause),
    });
  }

  /** §8's printable sheet, one child at a time; the server sends it `inline`, we save it. */
  protected exportSheet(row: ChildRow): void {
    this.exporting.set(true);
    this.api.examSheet(this.examId(), row.childId).subscribe({
      next: (pdf) => {
        this.exporting.set(false);
        saveFile(pdf, exportName([this.className(), this.title(), row.name], 'pdf'), 'application/pdf');
      },
      error: (cause: unknown) => this.failExport(cause),
    });
  }

  private savedAs(body: Blob | string, extension: string, type: string): void {
    this.exporting.set(false);
    saveFile(body, exportName([this.className(), this.title(), 'exam'], extension), type);
  }

  // ---- odds and ends ---------------------------------------------------------------------------------

  protected readonly examLink = computed(() => ['/teacher/lessons', this.examId()]);

  protected readonly empty = computed(() => !this.results.isLoading() && this.rows().length === 0);

  private moment(instant: number | null | undefined): string {
    return zonedText(instant, this.zone(), this.lang(), MOMENT);
  }

  private failExport(cause: unknown): void {
    this.exporting.set(false);
    this.fail(cause);
  }

  private fail(cause: unknown): void {
    this.busy.set(false);
    this.band.fail(apiErrorOf(cause)?.message ?? this.t('band.unreachable'));
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
