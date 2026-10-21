/* hq-flag: none (shell) — routed through `areaRoutes`, whose `evaluation-board` rows carry
   `FLAGS.childEvaluations`; the server's `EvaluationBoardController` is behind the same key. */
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import {
  type ChildEvaluationRow,
  type EvaluationBoard,
  type EvaluationBoardRow,
  ChildEvaluationBoardApi,
  apiErrorOf,
} from '../../api';
import { saveFile } from '../../core/download/download';
import { silentErrors } from '../../core/http/error.interceptor';
import { activeLang } from '../../core/i18n/active-lang';
import {
  type SelectOption,
  type TableColumn,
  BandComponent,
  ButtonComponent,
  CardComponent,
  DialogComponent,
  EmptyStateComponent,
  PageComponent,
  SelectComponent,
  SkeletonComponent,
  TableComponent,
  TextareaComponent,
} from '../../ui';
import { translateOr } from '../coordinator/coordinator.labels';
import {
  type Criterion,
  type EvaluationCount,
  type EvaluationStatus,
  CRITERIA,
  EVALUATION_COUNTS,
  canReview,
  countOf,
  csvName,
  isReturned,
  roundName,
  roundOf,
  rowKey,
  sortedBoardRows,
  statusOf,
  withBom,
} from './evaluations.models';

const EMPTY: EvaluationBoard = { rows: [] };

type Decision = 'APPROVE' | 'RETURN';

/**
 * **Evaluation tracking / متابعة التقييم** (feature 33) — who has written her children's
 * evaluations for this round, who is late, who has not written at all, and what she wrote.
 *
 * **The rows are the attendance board's own scope and nothing else** (`GET /evaluations/board`):
 * the Admin sees the school, a department manager her academy and her direct reports, a floor
 * supervisor the teachers of her floors, and a coordinator or a teacher her direct reports only.
 * The page asks for no scope of its own — it has no way to ask for one — so there is exactly one
 * answer to "whose evaluations do I see", and it is the same one as on Staff attendance.
 *
 * **Counts on the row, evaluations one tap away.** The board answers counts only; opening a
 * teacher asks `GET /evaluations/board/{teacherId}?round=` for her rows, because a leader is
 * looking for the three people who have not written, not for four hundred children.
 *
 * **Approve or return, once.** `POST /evaluations/{id}/review` answers 409 the second time, and
 * the dialog says so in her language rather than leaving a button that looks as if it did
 * nothing. A return carries a comment the teacher reads on her own page — "fix it" without
 * saying what is not a review — so the dialog will not send one without it.
 */
@Component({
  selector: 'hq-evaluation-board-page',
  imports: [
    BandComponent,
    ButtonComponent,
    CardComponent,
    DialogComponent,
    EmptyStateComponent,
    PageComponent,
    SelectComponent,
    SkeletonComponent,
    TableComponent,
    TextareaComponent,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './evaluation-board.page.html',
  styleUrl: './evaluations.scss',
})
export class EvaluationBoardPage {
  private readonly api = inject(ChildEvaluationBoardApi);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  protected readonly cards = EVALUATION_COUNTS;
  protected readonly criteria = CRITERIA;

  /** `''` is the round running now — the one ask that needs no id. */
  protected readonly round = signal('');

  protected readonly board = rxResource<EvaluationBoard, string>({
    params: () => this.round(),
    stream: ({ params: round }) => this.api.evaluationBoard(round === '' ? undefined : round),
    defaultValue: EMPTY,
  });

  protected readonly rows = computed(() => sortedBoardRows(this.board.value().rows ?? []));
  protected readonly counts = computed(() => this.board.value().counts);
  protected readonly resolved = computed(() => this.board.value().round);
  protected readonly timeZone = computed(() => this.resolved()?.timeZone ?? 'UTC');
  /** A teacher or coordinator nobody reports to: say so rather than "no rows". */
  protected readonly leadsNobody = computed(
    () => this.board.value().scope === 'REPORTS' && this.rows().length === 0,
  );
  protected readonly noRounds = computed(() => (this.board.value().rounds ?? []).length === 0);

  protected readonly roundOptions = computed<readonly SelectOption[]>(() => {
    const lang = this.lang();
    return (this.board.value().rounds ?? []).map((row) => ({
      value: row.id ?? '',
      label: roundName(row, lang),
    }));
  });

  protected readonly pickedRound = computed(() => this.round() || (this.resolved()?.id ?? ''));

  protected pickRound(id: string): void {
    this.round.set(roundOf(id));
    this.opened.set(null);
    this.loaded.set({});
  }

  protected count(card: EvaluationCount): number {
    return countOf(this.counts(), card);
  }

  protected readonly columns = computed<readonly TableColumn<EvaluationBoardRow>[]>(() => {
    this.lang();
    const number = (key: EvaluationCount): TableColumn<EvaluationBoardRow> => ({
      key,
      header: this.t(`evaluations.card.${key}`),
      align: 'end',
    });
    return [
      { key: 'fullName', header: this.t('evaluations.column.teacher') },
      number('onTime'),
      number('late'),
      number('missing'),
      number('returned'),
      number('approved'),
    ];
  });

  protected readonly rowColumns = computed<readonly TableColumn<ChildEvaluationRow>[]>(() => {
    this.lang();
    return [
      { key: 'childName', header: this.t('evaluations.column.child') },
      { key: 'className', header: this.t('evaluations.column.class') },
      { key: 'subject', header: this.t('evaluations.column.subject') },
      { key: 'status', header: this.t('evaluations.column.status') },
      { key: 'ratings', header: this.t('evaluations.column.ratings') },
      { key: 'review', header: this.t('evaluations.column.review') },
      { key: 'act', header: '' },
    ];
  });

  protected readonly trackTeacher = (row: EvaluationBoardRow): string => row.teacherId ?? '';
  protected readonly trackRow = (row: ChildEvaluationRow): string => rowKey(row);

  // ---- opening one teacher ---------------------------------------------------------------------

  private readonly opened = signal<string | null>(null);
  /** One teacher's rows, read once per round and kept while the round stands. */
  private readonly loaded = signal<Readonly<Record<string, readonly ChildEvaluationRow[]>>>({});
  protected readonly loading = signal<string | null>(null);

  protected readonly isOpen = (row: EvaluationBoardRow): boolean => this.opened() === row.teacherId;

  protected rowsOf(row: EvaluationBoardRow): readonly ChildEvaluationRow[] {
    return this.loaded()[row.teacherId ?? ''] ?? [];
  }

  protected toggle(row: EvaluationBoardRow): void {
    const id = row.teacherId ?? '';
    if (id === '') return;
    if (this.opened() === id) {
      this.opened.set(null);
      return;
    }
    this.opened.set(id);
    if (this.loaded()[id] === undefined) this.fetch(id);
  }

  /** `GET /evaluations/board/{teacherId}` answers the whole board again, with her rows filled. */
  private fetch(teacherId: string): void {
    const round = this.round();
    this.loading.set(teacherId);
    this.api.teacherEvaluationBoard(teacherId, round === '' ? undefined : round).subscribe({
      next: (board) => {
        this.loading.set(null);
        const rows = (board.rows ?? []).find((row) => row.teacherId === teacherId)?.rows ?? [];
        this.loaded.update((current) => ({ ...current, [teacherId]: rows }));
      },
      error: () => this.loading.set(null),
    });
  }

  protected countsOf(row: EvaluationBoardRow, card: EvaluationCount): number {
    return countOf(row.counts, card);
  }

  // ---- approving one evaluation, or sending it back ---------------------------------------------

  protected readonly reviewing = signal<ChildEvaluationRow | null>(null);
  protected readonly decision = signal<Decision>('APPROVE');
  protected readonly dialogOpen = signal(false);
  protected readonly comment = signal('');
  protected readonly failure = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected readonly canReview = canReview;
  protected readonly isReturned = isReturned;
  protected readonly statusOf = statusOf;

  protected readonly returning = computed(() => this.decision() === 'RETURN');
  /** A return without a comment is "fix it" with nothing to fix, so the primary action waits. */
  protected readonly valid = computed(() => !this.returning() || this.comment().trim() !== '');

  protected review(row: ChildEvaluationRow, decision: Decision): void {
    this.reviewing.set(row);
    this.decision.set(decision);
    this.comment.set('');
    this.failure.set(null);
    this.dialogOpen.set(true);
  }

  protected onDialog(open: boolean): void {
    this.dialogOpen.set(open);
    if (!open) this.reviewing.set(null);
  }

  protected confirmReview(): void {
    const row = this.reviewing();
    const id = row?.evaluationId ?? '';
    if (id === '' || this.saving() || !this.valid()) return;
    const comment = this.comment().trim();
    const teacherId = this.opened() ?? '';
    this.saving.set(true);
    this.failure.set(null);
    this.api
      .reviewEvaluation(
        id,
        { decision: this.decision(), ...(comment === '' ? {} : { comment }) },
        'body',
        false,
        { context: silentErrors() },
      )
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.dialogOpen.set(false);
          this.reviewing.set(null);
          // The counts and the statuses are the server's to compute, so both are read again.
          if (teacherId !== '') this.fetch(teacherId);
          this.board.reload();
        },
        error: (error: unknown) => {
          this.saving.set(false);
          const failed = apiErrorOf(error);
          this.failure.set(
            failed?.code === 'already_reviewed' || failed?.code === 'not_submitted'
              ? this.t(`evaluations.${failed.code}`)
              : (failed?.message ?? this.t('band.unreachable')),
          );
        },
      });
  }

  // ---- the same counts as a file ----------------------------------------------------------------

  protected readonly exporting = signal(false);

  protected exportCsv(): void {
    const round = this.round();
    this.exporting.set(true);
    this.api.evaluationBoardCsv(round === '' ? undefined : round).subscribe({
      next: (csv) => {
        this.exporting.set(false);
        saveFile(
          withBom(csv),
          csvName(this.resolved(), this.transloco.getActiveLang()),
          'text/csv;charset=utf-8',
        );
      },
      error: () => this.exporting.set(false),
    });
  }

  // ---- labels -----------------------------------------------------------------------------------

  protected readonly roundLabel = computed(() => roundName(this.resolved(), this.lang()));

  protected readonly dueLabel = computed(() => {
    this.lang();
    const dueAt = this.resolved()?.dueAt ?? '';
    return dueAt === '' ? '' : this.t('evaluations.dueAt', { at: this.at(dueAt), zone: this.timeZone() });
  });

  protected readonly reviewTitle = computed(() =>
    this.t(this.returning() ? 'evaluations.returnTitle' : 'evaluations.approveTitle'),
  );

  protected readonly confirmLabel = computed(() =>
    this.t(this.returning() ? 'evaluations.return' : 'evaluations.approve'),
  );

  protected statusLabel(status: EvaluationStatus): string {
    this.lang();
    return this.t(`evaluations.status.${status}`);
  }

  protected criterionLabel(criterion: Criterion): string {
    this.lang();
    return this.t(`evaluations.criterion.${criterion}`);
  }

  protected subjectLabel(subject: string | undefined): string {
    this.lang();
    return subject === undefined || subject === ''
      ? '—'
      : translateOr(this.transloco, `subject.${subject}`, subject);
  }

  protected ratingsLabel(row: ChildEvaluationRow): string {
    this.lang();
    if (CRITERIA.every((criterion) => row[criterion] === undefined)) return '—';
    return CRITERIA.map((criterion) => `${this.criterionLabel(criterion)} ${row[criterion] ?? '—'}`).join(
      ' · ',
    );
  }

  protected reviewLabel(row: ChildEvaluationRow): string {
    this.lang();
    const comment = (row.reviewComment ?? '').trim();
    const name = row.reviewedByName ?? this.t('evaluations.aLeader');
    const when = row.reviewedAt ? this.at(row.reviewedAt) : '';
    const status = statusOf(row);
    if (status !== 'RETURNED' && status !== 'APPROVED') return '—';
    const head = this.t(status === 'RETURNED' ? 'evaluations.returnedBy' : 'evaluations.approvedBy', {
      name,
      at: when,
    });
    return comment === '' ? head : `${head} — ${comment}`;
  }

  protected at(iso: string): string {
    return new Intl.DateTimeFormat(this.transloco.getActiveLang(), {
      day: 'numeric',
      month: 'short',
      hour: 'numeric',
      minute: '2-digit',
      timeZone: this.timeZone(),
    }).format(new Date(iso));
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
