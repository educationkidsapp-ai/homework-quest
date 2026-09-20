import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { forkJoin, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { type ExamResults, type ExamSettings, ExamsApi } from '../../api';
import { FLAGS } from '../../core/flags/flag.service';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import { PlatformService } from '../../core/platform/platform.service';
import {
  CardComponent,
  EmptyStateComponent,
  SkeletonComponent,
  TableComponent,
  type TableColumn,
} from '../../ui';
import { type ExamState, examStateOf, zonedText } from './exams.models';

/** One row of the tab: an exam, where it is in its life, and how far through the marking. */
export interface ExamRow {
  readonly examId: string;
  readonly title: string;
  readonly window: string;
  readonly state: ExamState;
  readonly sat: number | null;
  readonly roster: number | null;
  readonly needsMarking: number | null;
}

/**
 * How many exams' results the tab fetches to fill its **Sat** and **Needs marking** columns.
 *
 * The list endpoint answers `ExamSettings[]` and nothing else (see the class comment), so those
 * two numbers cost one request each. A class has a handful of exams a year and only the ones
 * that have opened have anybody sitting them, so the cost is bounded twice over — by the state
 * filter and by this cap, newest first. Older rows show a dash and their own Results link,
 * which is where the numbers live anyway.
 */
const RESULTS_FETCH_LIMIT = 6;

/**
 * **The Exams tab** of the class page (`docs/teacher-flow.md` §4 step 10) — the N2.3 stub, filled.
 *
 * One table and one button. The table is the answer to "where is each of my exams": a draft she
 * has not published, one scheduled for Sunday, one open right now, one closed with marking still
 * to do, one released to the parents.
 *
 * **The server gap this works around, plainly.** `GET /teacher/classes/{id}/exams` returns
 * `ExamSettings[]`, which carries the window, the level and the status but neither the roster
 * nor how many children sat it — those live in `GET /teacher/exams/{id}/results`. Rather than
 * leave two columns of §8 off the screen, the tab fetches the results of the exams that have
 * actually opened, newest first and at most {@link RESULTS_FETCH_LIMIT} of them. A `sat` column
 * on the list endpoint would replace this with one request; that is reported, not hidden.
 */
@Component({
  selector: 'hq-exams-tab',
  imports: [
    CardComponent,
    TableComponent,
    SkeletonComponent,
    EmptyStateComponent,
    FeatureDirective,
    CanDirective,
    RouterLink,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './exams-tab.component.html',
  styleUrl: './exams-tab.component.scss',
})
export class ExamsTabComponent {
  private readonly api = inject(ExamsApi);
  private readonly transloco = inject(TranslocoService);
  private readonly platform = inject(PlatformService);
  private readonly lang = activeLang();

  readonly classId = input.required<string>();
  readonly className = input('');

  protected readonly examsFlag = FLAGS.exams;

  /**
   * A clock the whole tab reads, ticked once a minute.
   *
   * A state derived from `Date.now()` inside a `computed` is computed once and never again: the
   * exam that opens at nine would still read "scheduled" at half past to a teacher who has had
   * the tab open since eight. One signal, one interval, every row.
   */
  private readonly now = signal(Date.now());

  protected readonly exams = rxResource({
    params: () => this.classId(),
    stream: ({ params }) => this.api.classExams(params),
    defaultValue: [],
  });

  /** The results of the exams that have opened — the only ones with anybody to count. */
  private readonly opened = computed(() =>
    this.exams
      .value()
      .filter(
        (exam) => examStateOf(exam, this.now()) !== 'draft' && examStateOf(exam, this.now()) !== 'scheduled',
      )
      .slice(0, RESULTS_FETCH_LIMIT)
      .map((exam) => exam.examId ?? '')
      .filter((id) => id !== ''),
  );

  private readonly counts = rxResource({
    params: () => this.opened().join(','),
    stream: ({ params }) => {
      const ids = params.split(',').filter((id) => id !== '');
      if (ids.length === 0) return of({} as Record<string, ExamResults>);
      return forkJoin(
        ids.map((id) =>
          // One exam's results failing must not blank the other rows' numbers, so each is
          // caught on its own and that row simply keeps its dash.
          this.api.examResults(id).pipe(catchError(() => of(null))),
        ),
      ).pipe(
        map((all) => {
          const byId: Record<string, ExamResults> = {};
          all.forEach((results, index) => {
            if (results) byId[ids[index]!] = results;
          });
          return byId;
        }),
      );
    },
    defaultValue: {},
  });

  protected readonly rows = computed<readonly ExamRow[]>(() => {
    this.lang();
    const zone = this.platform.timezone();
    const counts = this.counts.value();
    return this.exams.value().map((exam) => {
      const examId = exam.examId ?? '';
      const results = counts[examId];
      return {
        examId,
        title: exam.title?.trim() || this.t('exams.untitled'),
        window: this.windowText(exam, zone),
        state: examStateOf(exam, this.now()),
        sat: results?.sat ?? null,
        roster: results?.roster ?? null,
        needsMarking: results?.needsMarking ?? null,
      };
    });
  });

  protected readonly loading = computed(() => this.exams.isLoading());

  protected readonly columns = computed<readonly TableColumn<ExamRow>[]>(() => {
    this.lang();
    return [
      { key: 'title', header: this.t('exams.table.title'), width: '32%' },
      { key: 'window', header: this.t('exams.table.window'), width: '28%' },
      { key: 'state', header: this.t('exams.table.state') },
      { key: 'sat', header: this.t('exams.table.sat'), align: 'end' as const },
      { key: 'needsMarking', header: this.t('exams.table.needsMarking'), align: 'end' as const },
    ];
  });

  protected trackRow = (row: ExamRow): string => row.examId;

  protected readonly newExamLink = ['/teacher/exams/new'];

  protected readonly newExamParams = computed<Record<string, string>>(() => ({
    classId: this.classId(),
  }));

  protected resultsLink(row: ExamRow): readonly string[] {
    return ['/teacher/exams', row.examId, 'results'];
  }

  protected editorLink(row: ExamRow): readonly string[] {
    return ['/teacher/lessons', row.examId];
  }

  /** "18 of 24", or a dash where the tab has not paid for the number. */
  protected satText(row: ExamRow): string {
    this.lang();
    if (row.sat === null || row.roster === null) return '—';
    return this.t('exams.table.satValue', { sat: row.sat, roster: row.roster });
  }

  private windowText(exam: ExamSettings, zone: string): string {
    const opens = zonedText(exam.opensAt, zone, this.lang(), {
      day: 'numeric',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit',
    });
    const closes = zonedText(exam.closesAt, zone, this.lang(), { hour: '2-digit', minute: '2-digit' });
    return this.t('exams.window', { opens, closes });
  }

  constructor() {
    const tick = setInterval(() => this.now.set(Date.now()), 60_000);
    inject(DestroyRef).onDestroy(() => clearInterval(tick));
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
