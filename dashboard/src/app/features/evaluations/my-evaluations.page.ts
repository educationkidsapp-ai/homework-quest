/* hq-flag: none (shell) — routed through `areaRoutes`, whose `evaluations` row carries
   `FLAGS.childEvaluations`; the server's `TeacherEvaluationsController` is behind the same key. */
import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { map } from 'rxjs/operators';
import {
  type ChildEvaluationRow,
  type TeacherEvaluations,
  ChildEvaluationsApi,
  apiErrorOf,
} from '../../api';
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
  type EvaluationDraft,
  type EvaluationStatus,
  type Rating,
  CRITERIA,
  EVALUATION_COUNTS,
  RATINGS,
  RATING_WORDS,
  canEdit,
  countOf,
  draftOf,
  groupsOf,
  isReturned,
  requestOf,
  roundName,
  roundOf,
  rowKey,
  statusOf,
  submitReady,
} from './evaluations.models';

const EMPTY: TeacherEvaluations = { rows: [] };

const BLANK: EvaluationDraft = {
  participation: null,
  effort: null,
  behaviour: null,
  understanding: null,
  comment: '',
  goingWell: '',
  nextStep: '',
};

/**
 * **Evaluations / تقييم الطلاب** (feature 33) — what each teacher writes about each child she
 * teaches, a few times a year, for the child's parent to read in plain words.
 *
 * **The rows are her assignments**, never a list she keeps: `GET /teacher/evaluations?round=`
 * answers one row per (class, subject, child) her `teaching_assignments` and those classes'
 * children give her, so a child who joins her class appears here without anybody adding him.
 *
 * **One round at a time.** The picker is the rounds the Admin created, and `?round=` is what the
 * bell links to — the reminder and the return both name the round whose evaluations are waiting
 * (`core/notifications/notification-target.ts`), so the link has to land on that round and not on
 * whichever one is current by the time she taps it.
 *
 * **Draft until she submits.** She saves as often as she likes; `Submit` needs all four criteria
 * and a comment, which the editor's primary action says before the server has to. A submitted or
 * approved row is read-only (409), and a **returned** one is hers again — with the leader's
 * comment in a band above the form, because "fix it" without saying what is not a review.
 *
 * **Numbers here, words there.** Staff see "Good (3)": a teacher comparing two children needs
 * the order of the four adjectives, which is not self-evident in either language. The parent's
 * side is given the word alone, by the server, and never the number.
 */
@Component({
  selector: 'hq-my-evaluations-page',
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
  templateUrl: './my-evaluations.page.html',
  styleUrl: './evaluations.scss',
})
export class MyEvaluationsPage {
  private readonly api = inject(ChildEvaluationsApi);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  protected readonly cards = EVALUATION_COUNTS;
  protected readonly criteria = CRITERIA;

  /**
   * The bell's `?round=`: `evaluation.reminder` and `evaluation.returned` both carry the round
   * whose evaluations are waiting, and both land here.
   */
  private readonly roundParam = toSignal(
    inject(ActivatedRoute).queryParamMap.pipe(map((params) => roundOf(params.get('round')))),
    { initialValue: '' },
  );

  /** `''` is "the round running now" — the one ask that needs no id at all. */
  protected readonly round = linkedSignal<string, string>({
    source: () => this.roundParam(),
    computation: (param) => param,
  });

  protected readonly evaluations = rxResource<TeacherEvaluations, string>({
    params: () => this.round(),
    stream: ({ params: round }) => this.api.myEvaluations(round === '' ? undefined : round),
    defaultValue: EMPTY,
  });

  protected readonly groups = computed(() => groupsOf(this.evaluations.value().rows ?? []));
  protected readonly counts = computed(() => this.evaluations.value().counts);
  protected readonly resolved = computed(() => this.evaluations.value().round);
  protected readonly timeZone = computed(() => this.resolved()?.timeZone ?? 'UTC');
  /** A school whose Admin has created no round at all: say so rather than "nothing expected". */
  protected readonly noRounds = computed(() => (this.evaluations.value().rounds ?? []).length === 0);

  protected readonly roundOptions = computed<readonly SelectOption[]>(() => {
    const lang = this.lang();
    return (this.evaluations.value().rounds ?? []).map((row) => ({
      value: row.id ?? '',
      label: roundName(row, lang),
    }));
  });

  protected readonly pickedRound = computed(() => this.round() || (this.resolved()?.id ?? ''));

  protected pickRound(id: string): void {
    this.round.set(roundOf(id));
  }

  protected count(card: EvaluationCount): number {
    return countOf(this.counts(), card);
  }

  protected readonly columns = computed<readonly TableColumn<ChildEvaluationRow>[]>(() => {
    this.lang();
    return [
      { key: 'childName', header: this.t('evaluations.column.child') },
      { key: 'status', header: this.t('evaluations.column.status') },
      { key: 'ratings', header: this.t('evaluations.column.ratings') },
      { key: 'review', header: this.t('evaluations.column.review') },
      { key: 'act', header: '' },
    ];
  });

  protected readonly trackRow = (row: ChildEvaluationRow): string => rowKey(row);

  // ---- the editor ------------------------------------------------------------------------------

  protected readonly editing = signal<ChildEvaluationRow | null>(null);
  protected readonly dialogOpen = signal(false);
  protected readonly draft = signal<EvaluationDraft>(BLANK);
  protected readonly failure = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected readonly canEdit = canEdit;
  protected readonly isReturned = isReturned;
  protected readonly statusOf = statusOf;

  protected readonly ratings = RATINGS;

  protected open(row: ChildEvaluationRow): void {
    this.editing.set(row);
    this.draft.set(draftOf(row));
    this.failure.set(null);
    this.dialogOpen.set(true);
  }

  protected onDialog(open: boolean): void {
    this.dialogOpen.set(open);
    if (!open) this.editing.set(null);
  }

  protected rateOptions(): readonly SelectOption[] {
    this.lang();
    return RATINGS.map((rating) => ({ value: String(rating), label: this.ratingLabel(rating) }));
  }

  protected rated(criterion: Criterion): string {
    const rating = this.draft()[criterion];
    return rating === null ? '' : String(rating);
  }

  protected rate(criterion: Criterion, value: string): void {
    const rating = Number(value);
    const picked = RATINGS.find((candidate) => candidate === rating) ?? null;
    const patch: Partial<EvaluationDraft> = { [criterion]: picked };
    this.draft.update((draft) => ({ ...draft, ...patch }));
  }

  protected setText(field: 'comment' | 'goingWell' | 'nextStep', value: string): void {
    const patch: Partial<EvaluationDraft> = { [field]: value };
    this.draft.update((draft) => ({ ...draft, ...patch }));
  }

  /** Said on the primary action rather than after a 400: the server wants all five of them. */
  protected readonly ready = computed(() => submitReady(this.draft()));

  protected saveDraft(): void {
    this.send(false);
  }

  protected submit(): void {
    if (!this.ready()) return;
    this.send(true);
  }

  /**
   * One request either way: `PUT` keeps it a draft, `POST …/submit` saves the same body and
   * submits it, so nothing she typed is lost when the submit is refused.
   */
  private send(submitting: boolean): void {
    const row = this.editing();
    if (row === null || this.saving()) return;
    const roundId = row.roundId ?? '';
    const classId = row.classId ?? '';
    const subject = row.subject ?? '';
    const childId = row.childId ?? '';
    const body = requestOf(this.draft());
    this.saving.set(true);
    this.failure.set(null);

    const call = submitting
      ? this.api.submitEvaluation(roundId, classId, subject, childId, body, 'body', false, {
          context: silentErrors(),
        })
      : this.api.saveEvaluation(roundId, classId, subject, childId, body, 'body', false, {
          context: silentErrors(),
        });

    call.subscribe({
      next: () => {
        this.saving.set(false);
        this.dialogOpen.set(false);
        this.editing.set(null);
        // Read again rather than patch in place: the status and the counts are the server's.
        this.evaluations.reload();
      },
      error: (error: unknown) => {
        this.saving.set(false);
        const failed = apiErrorOf(error);
        this.failure.set(
          failed?.code === 'already_submitted' || failed?.code === 'already_approved'
            ? this.t(`evaluations.${failed.code}`)
            : (failed?.message ?? this.t('band.unreachable')),
        );
      },
    });
  }

  // ---- labels ----------------------------------------------------------------------------------

  protected readonly roundLabel = computed(() => roundName(this.resolved(), this.lang()));

  /** The deadline in the school's own zone, which is the only zone it means anything in. */
  protected readonly dueLabel = computed(() => {
    this.lang();
    const dueAt = this.resolved()?.dueAt ?? '';
    return dueAt === '' ? '' : this.t('evaluations.dueAt', { at: this.at(dueAt), zone: this.timeZone() });
  });

  protected statusLabel(status: EvaluationStatus): string {
    this.lang();
    return this.t(`evaluations.status.${status}`);
  }

  protected ratingLabel(rating: Rating): string {
    this.lang();
    return this.t('evaluations.rating', { word: this.t(`evaluations.word.${RATING_WORDS[rating]}`), rating });
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

  /** The four ratings on one line, so the table says what she wrote without opening it. */
  protected ratingsLabel(row: ChildEvaluationRow): string {
    this.lang();
    const written = CRITERIA.map((criterion) => row[criterion]).filter(
      (value): value is number => value !== undefined,
    );
    if (written.length === 0) return '—';
    return CRITERIA.map((criterion) => {
      const value = row[criterion];
      return `${this.criterionLabel(criterion)} ${value ?? '—'}`;
    }).join(' · ');
  }

  protected reviewLabel(row: ChildEvaluationRow): string {
    this.lang();
    const comment = (row.reviewComment ?? '').trim();
    const name = row.reviewedByName ?? this.t('evaluations.aLeader');
    const when = row.reviewedAt ? this.at(row.reviewedAt) : '';
    if (isReturned(row)) {
      const head = this.t('evaluations.returnedBy', { name, at: when });
      return comment === '' ? head : `${head} — ${comment}`;
    }
    if (statusOf(row) !== 'APPROVED') return '—';
    const head = this.t('evaluations.approvedBy', { name, at: when });
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
