import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ResultsAndGradebookApi, apiErrorOf } from '../../api';
import { BandService } from '../../core/band/band.service';
import { exportName, saveFile } from '../../core/download/download';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import { UndoService } from '../../core/undo/undo.service';
import {
  BandComponent,
  ButtonComponent,
  CardComponent,
  CheckboxComponent,
  EmptyStateComponent,
  PageComponent,
  SelectComponent,
  SkeletonComponent,
  TableComponent,
  ToggleComponent,
  type Breadcrumb,
  type SelectOption,
  type TableColumn,
  type TableGroup,
} from '../../ui';
import { LevelBandComponent } from './level-band.component';
import { MarkPanelComponent } from './mark-panel.component';
import {
  levelGroups,
  needingMarking,
  resultRows,
  scoreLabel,
  summaryOf,
  weakestStopIds,
  type ResultRow,
  type RowStop,
} from './results.models';

/** A stop column is `stop:{id}`; the name column and the summary columns are their own keys. */
const STOP_PREFIX = 'stop:';

/** §7's `secure` band floor — below it, on a stop the class found hard, is worth the eye. */
const SECURE_FLOOR = 60;

/**
 * **The Results page** (`docs/teacher-flow.md` §4 step 9) — one lesson, every child, every stop.
 *
 * Maya opens it from the lesson she just published, from its card in This week or from its
 * square in the calendar. She sees, in this order: how many played, what the class averaged, how
 * many children are still waiting for her, and whether the parents can see any of it.
 *
 * **Marking is where the work is.** A row opens onto {@link MarkPanelComponent} — the child's own
 * open stops, three stars and a comment each, one score override and one comment for the parent.
 * N4.4 lifted that panel out of this template so the exam results page opens the same editor
 * instead of a second copy of it; this page keeps only which row is open.
 *
 * **Release is explained, not labelled.** "Parents see scores and comments after release" sits
 * under the toggle, and withdrawing — the direction that takes something away from a parent who
 * may already have read it — asks first, in the red band, like every other destructive act.
 *
 * Two flags: `gradebook` gates the screen, on the route, from the one table in `screens.ts`; and
 * `openStopMarking` gates the marking, through the `*hqFeature` inside `hq-mark-panel`, because a
 * school can buy the numbers without the marking. `results.write` gates both marking and release;
 * a `MANAGERIAL` account holds `results.read` alone and gets the page read-only rather than a
 * page of refusals.
 */
@Component({
  selector: 'hq-results-page',
  imports: [
    PageComponent,
    CardComponent,
    TableComponent,
    ButtonComponent,
    CheckboxComponent,
    SelectComponent,
    ToggleComponent,
    BandComponent,
    SkeletonComponent,
    EmptyStateComponent,
    LevelBandComponent,
    MarkPanelComponent,
    CanDirective,
    RouterLink,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './results.page.html',
  styleUrl: './results.page.scss',
})
export class ResultsPage {
  private readonly api = inject(ResultsAndGradebookApi);
  private readonly route = inject(ActivatedRoute);
  private readonly transloco = inject(TranslocoService);
  private readonly band = inject(BandService);
  private readonly undo = inject(UndoService);
  private readonly lang = activeLang();

  private readonly path = toSignal(this.route.paramMap, { initialValue: this.route.snapshot.paramMap });
  protected readonly lessonId = computed(() => this.path().get('id') ?? '');

  protected readonly results = rxResource({
    params: () => this.lessonId(),
    stream: ({ params }) => this.api.lessonResults(params),
    defaultValue: {},
  });

  // ---- the header --------------------------------------------------------------------------

  protected readonly summary = computed(() => summaryOf(this.results.value()));
  protected readonly title = computed(() => this.results.value().title ?? '');
  protected readonly className = computed(() => this.results.value().className ?? '');

  protected readonly subtitle = computed(() => {
    this.lang();
    const value = this.results.value();
    const date = value.date ?? '';
    return date
      ? this.t('results.subtitle', { class: this.className(), date: this.day(date) })
      : this.className();
  });

  protected readonly breadcrumbs = computed<readonly Breadcrumb[]>(() => {
    this.lang();
    const classId = this.results.value().classId ?? '';
    return [
      { label: this.t('classes.title'), link: '/teacher/classes' },
      ...(classId ? [{ label: this.className(), link: `/teacher/classes/${classId}` }] : []),
      { label: this.t('results.title') },
    ];
  });

  /** "18 of 24 played" reads better than a bare number nobody can scale. */
  protected readonly playedLabel = computed(() => {
    this.lang();
    const { played, roster } = this.summary();
    return this.t('results.metric.playedValue', { played, roster });
  });

  protected readonly averageLabel = computed(() => scoreLabel(this.summary().classAverage));

  protected readonly releasedLabel = computed(() => {
    this.lang();
    const { released, releasedAt } = this.summary();
    if (!released) return this.t('results.release.notReleased');
    return releasedAt
      ? this.t('results.release.releasedOn', { date: this.day(new Date(releasedAt)) })
      : this.t('results.release.released');
  });

  // ---- the table ------------------------------------------------------------------------------

  protected readonly rows = computed(() => resultRows(this.results.value()));
  protected readonly weak = computed(() => weakestStopIds(this.rows()));
  protected readonly onlyUnmarked = signal(false);

  protected readonly visibleRows = computed(() =>
    this.onlyUnmarked() ? needingMarking(this.rows()) : this.rows(),
  );

  /**
   * The lesson's stops by level — the table's column groups, and the level filter's options.
   *
   * Publishing a lesson generates Levels 2 and 3, so this is three groups on almost every
   * lesson and one on a hand-written single-level one; the page draws whatever arrives.
   */
  protected readonly levels = computed(() => levelGroups(this.results.value()));

  /**
   * Which level's columns are on screen. `''` is all of them, and is where it starts — a teacher
   * opens the page to see her class, not to choose a level first.
   *
   * A filter rather than a split: three levels of six stops is eighteen columns, and a teacher
   * whose class all played Level 1 wants six of them. It hides columns and never rows — the
   * roster is the one thing on this page that must always be whole.
   */
  protected readonly level = signal('');

  private readonly onlyLevel = computed(() => {
    const value = Number(this.level());
    return Number.isFinite(value) && value > 0 ? value : null;
  });

  protected readonly shownLevels = computed(() => {
    const only = this.onlyLevel();
    return only === null ? this.levels() : this.levels().filter((group) => group.level === only);
  });

  protected readonly levelOptions = computed<readonly SelectOption[]>(() => {
    this.lang();
    return [
      { value: '', label: this.t('results.filter.allLevels') },
      ...this.levels().map((group) => ({
        value: String(group.level),
        label: this.t('results.table.levelGroup', { level: group.level }),
      })),
    ];
  });

  /** The summary columns after the stops — named here so the group row's spacer can count them. */
  private readonly TAIL = ['played', 'answered', 'levelReached', 'score', 'band'] as const;

  protected readonly columns = computed<readonly TableColumn<ResultRow>[]>(() => {
    this.lang();
    return [
      { key: 'name', header: this.t('results.table.child'), width: '22%' },
      ...this.shownLevels().flatMap((group) =>
        group.stops.map((stop) => ({
          key: `${STOP_PREFIX}${stop.stopId}`,
          // A weak column says so in its heading rather than tinting every cell under it: the
          // column is what was hard, and a child who got three stars there did not do badly.
          header: this.isWeak(stop.stopId)
            ? `${this.t('results.table.stop', { number: stop.number })} · ${this.t('results.table.hardest')}`
            : this.t('results.table.stop', { number: stop.number }),
          align: 'center' as const,
        })),
      ),
      { key: 'played', header: this.t('results.table.played'), align: 'center' as const },
      { key: 'answered', header: this.t('results.table.answered'), align: 'end' as const },
      { key: 'levelReached', header: this.t('results.table.level'), align: 'end' as const },
      { key: 'score', header: this.t('results.table.score'), align: 'end' as const },
      { key: 'band', header: this.t('results.table.band') },
    ];
  });

  /**
   * "Level 1 · Level 2 · Level 3" above the stop columns.
   *
   * The grid is the union of every level's stops and each child is scored on one of them, so
   * without this row a teacher reads a column of dashes as twenty children who skipped a stop
   * rather than as twenty children who were playing somewhere else (N4.5 D1). The child column
   * and the summary columns take blank spacers: the groups have to cover the whole width.
   */
  protected readonly groups = computed<readonly TableGroup[]>(() => {
    this.lang();
    const levels = this.shownLevels();
    if (levels.length === 0) return [];
    return [
      { key: 'child', header: '', span: 1 },
      ...levels.map((group) => ({
        key: `level-${group.level}`,
        header: this.t('results.table.levelGroup', { level: group.level }),
        span: group.stops.length,
        align: 'center' as const,
      })),
      { key: 'summary', header: '', span: this.TAIL.length },
    ];
  });

  protected trackRow = (row: ResultRow): string => row.childId;

  /** The stop a `stop:{id}` column stands for, in the row being drawn. */
  protected stopOf(row: ResultRow, key: string): RowStop | null {
    return row.stops.find((stop) => `${STOP_PREFIX}${stop.stopId}` === key) ?? null;
  }

  protected isStopColumn(key: string): boolean {
    return key.startsWith(STOP_PREFIX);
  }

  protected isWeak(stopId: string): boolean {
    return this.weak().has(stopId);
  }

  /**
   * The cells worth tinting: a weak stop the child actually played and did not clear the
   * `secure` floor on. Tinting a whole weak column would paint a three-star answer red.
   */
  protected isWeakCell(stop: RowStop): boolean {
    return this.isWeak(stop.stopId) && stop.attempted && (stop.score ?? 100) < SECURE_FLOOR;
  }

  protected headerTitleOf(key: string): string {
    for (const group of this.levels())
      for (const stop of group.stops)
        if (`${STOP_PREFIX}${stop.stopId}` === key) return stop.title;
    return '';
  }

  // ---- one child's panel -------------------------------------------------------------------------

  protected readonly openChild = signal<string | null>(null);

  protected isOpen = (row: ResultRow): boolean => this.openChild() === row.childId;

  protected toggleChild(row: ResultRow): void {
    this.openChild.set(this.openChild() === row.childId ? null : row.childId);
  }

  /** The panel saved (or undid a save): the numbers on this page have moved. */
  protected onMarked(): void {
    this.openChild.set(null);
    this.results.reload();
  }

  // ---- release -------------------------------------------------------------------------------------

  protected readonly withdrawing = signal(false);
  protected readonly releasing = signal(false);

  /**
   * The toggle both ways. Releasing is done and offered back for ten seconds; **withdrawing asks
   * first**, because it takes a score off a report a parent may already be looking at, and §7's
   * rule is that a destructive act confirms in the red band rather than in a toast afterwards.
   */
  protected onReleaseToggled(next: boolean): void {
    if (next) this.setReleased(true);
    else this.withdrawing.set(true);
  }

  protected confirmWithdraw(): void {
    this.withdrawing.set(false);
    this.setReleased(false);
  }

  protected cancelWithdraw(): void {
    this.withdrawing.set(false);
    // The toggle painted itself off the moment it was clicked; the reload puts it back.
    this.results.reload();
  }

  private setReleased(released: boolean): void {
    this.releasing.set(true);
    this.api.releaseLesson(this.lessonId(), { released }).subscribe({
      next: () => {
        this.releasing.set(false);
        this.results.reload();
        this.undo.offerUndo({
          message: this.t(released ? 'results.release.doneOn' : 'results.release.doneOff'),
          undo: () => this.setReleased(!released),
          commit: () => this.results.reload(),
        });
      },
      error: (error: unknown) => {
        this.releasing.set(false);
        this.results.reload();
        this.fail(error);
      },
    });
  }

  // ---- export ----------------------------------------------------------------------------------------

  protected readonly exporting = signal(false);

  /** The bearer is on the request, so the file is fetched and then saved — never linked to. */
  protected exportCsv(): void {
    this.exporting.set(true);
    // The generated signature already says `string` — `text/csv` makes the client ask for text.
    this.api.lessonResultsCsv(this.lessonId()).subscribe({
      next: (csv) => {
        this.exporting.set(false);
        saveFile(
          csv,
          exportName([this.className(), this.title(), 'results'], 'csv'),
          'text/csv;charset=utf-8',
        );
      },
      error: (error: unknown) => {
        this.exporting.set(false);
        this.fail(error);
      },
    });
  }

  // ---- odds and ends -----------------------------------------------------------------------------------

  protected readonly lessonLink = computed(() => ['/teacher/lessons', this.lessonId()]);

  /** Her own page — band, trend, chart, comments and work (N4.2's third screen). */
  protected childLink(row: ResultRow): readonly string[] {
    return ['/teacher/children', row.childId];
  }

  protected score(value: number | null): string {
    return scoreLabel(value);
  }

  /** `★★` — the stars as a glyph run; a screen reader is given the count in words beside it. */
  protected starsText(stars: number | null): string {
    return stars && stars > 0 ? '\u2605'.repeat(stars) : '\u2013';
  }

  private day(value: string | Date): string {
    const date = typeof value === 'string' ? new Date(`${value}T00:00:00Z`) : value;
    return new Intl.DateTimeFormat(this.lang(), { day: 'numeric', month: 'short' }).format(date);
  }

  private fail(error: unknown): void {
    this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
