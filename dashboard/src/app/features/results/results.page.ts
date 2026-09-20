import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ResultsAndGradebookApi, apiErrorOf } from '../../api';
import { BandService } from '../../core/band/band.service';
import { exportName, saveFile } from '../../core/download/download';
import { FLAGS, FlagService } from '../../core/flags/flag.service';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import { PermissionService } from '../../core/permissions/permission.service';
import { UndoService } from '../../core/undo/undo.service';
import {
  BandComponent,
  ButtonComponent,
  CardComponent,
  CheckboxComponent,
  EmptyStateComponent,
  InputComponent,
  PageComponent,
  SkeletonComponent,
  TableComponent,
  TextareaComponent,
  ToggleComponent,
  type Breadcrumb,
  type TableColumn,
} from '../../ui';
import { ChildWorkComponent } from './child-work.component';
import { LevelBandComponent } from './level-band.component';
import {
  draftOf,
  marks,
  needingMarking,
  parseScore,
  request,
  resultRows,
  scoreLabel,
  summaryOf,
  weakestStopIds,
  type MarkDraft,
  type ResultRow,
  type RowStop,
} from './results.models';
import { StarsInputComponent } from './stars-input.component';

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
 * **Marking is where the work is.** A row opens onto the child's own open stops — the retell she
 * recorded, the picture she drew — with three stars and a comment each, plus one score override
 * and one comment for the parent. Everything she changes in that panel is sent in a *single*
 * `PUT /teacher/marks`, and the strip that follows offers Undo for ten seconds by sending the
 * previous values back the same way. No dialogs, no per-field saves: the panel is one decision.
 *
 * **Release is explained, not labelled.** "Parents see scores and comments after release" sits
 * under the toggle, and withdrawing — the direction that takes something away from a parent who
 * may already have read it — asks first, in the red band, like every other destructive act.
 *
 * Two flags: `gradebook` gates the screen (the route carries it) and `openStopMarking` gates the
 * marking controls through `*hqFeature="markingFlag"`, because a school can buy the numbers
 * without the marking. `results.write`
 * gates both marking and release; a `MANAGERIAL` account holds `results.read` alone and gets the
 * page read-only rather than a page of refusals.
 */
@Component({
  selector: 'hq-results-page',
  imports: [
    PageComponent,
    CardComponent,
    TableComponent,
    ButtonComponent,
    CheckboxComponent,
    ToggleComponent,
    InputComponent,
    TextareaComponent,
    BandComponent,
    SkeletonComponent,
    EmptyStateComponent,
    LevelBandComponent,
    StarsInputComponent,
    ChildWorkComponent,
    FeatureDirective,
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
  private readonly flags = inject(FlagService);
  private readonly permissions = inject(PermissionService);
  private readonly band = inject(BandService);
  private readonly undo = inject(UndoService);
  private readonly lang = activeLang();

  /** The flag the marking controls carry; the screen's own is on the route. */
  protected readonly markingFlag = FLAGS.openStopMarking;

  /**
   * Whether the fields in a row's panel take input at all. The same two conditions the Save
   * button's `*hqFeature` and `*hqCan` check — a field that accepts a mark and then finds there
   * is no button to send it with is worse than one that is plainly read-only.
   */
  protected readonly canMark = computed(
    () => this.flags.isOn(FLAGS.openStopMarking) && this.permissions.can('results.write'),
  );

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

  protected readonly columns = computed<readonly TableColumn<ResultRow>[]>(() => {
    this.lang();
    const stops = this.rows()[0]?.stops ?? [];
    return [
      { key: 'name', header: this.t('results.table.child'), width: '22%' },
      ...stops.map((stop, index) => ({
        key: `${STOP_PREFIX}${stop.stopId}`,
        // A weak column says so in its heading rather than tinting every cell under it: the
        // column is what was hard, and a child who got three stars there did not do badly.
        header: this.isWeak(stop.stopId)
          ? `${this.t('results.table.stop', { number: index + 1 })} · ${this.t('results.table.hardest')}`
          : this.t('results.table.stop', { number: index + 1 }),
        align: 'center' as const,
      })),
      { key: 'levelReached', header: this.t('results.table.level'), align: 'end' as const },
      { key: 'score', header: this.t('results.table.score'), align: 'end' as const },
      { key: 'band', header: this.t('results.table.band') },
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
    const stop = this.rows()[0]?.stops.find((candidate) => `${STOP_PREFIX}${candidate.stopId}` === key);
    return stop?.title ?? '';
  }

  // ---- one child's panel -------------------------------------------------------------------------

  protected readonly openChild = signal<string | null>(null);
  protected readonly draft = signal<MarkDraft | null>(null);
  /** What the server held when the panel opened — the values Undo puts back. */
  private original: MarkDraft | null = null;
  protected readonly saving = signal(false);

  protected isOpen = (row: ResultRow): boolean => this.openChild() === row.childId;

  protected toggleChild(row: ResultRow): void {
    if (this.openChild() === row.childId) {
      this.openChild.set(null);
      this.draft.set(null);
      return;
    }
    this.original = draftOf(row);
    this.draft.set(this.original);
    this.openChild.set(row.childId);
  }

  protected openStopsOf(row: ResultRow): readonly RowStop[] {
    return row.stops.filter((stop) => stop.open);
  }

  protected starsOf(stopId: string): number | null {
    return this.draft()?.stops[stopId]?.stars ?? null;
  }

  protected commentOf(stopId: string): string {
    return this.draft()?.stops[stopId]?.comment ?? '';
  }

  protected setStars(stopId: string, stars: number | null): void {
    this.editStop(stopId, (mark) => ({ ...mark, stars }));
  }

  protected setStopComment(stopId: string, comment: string): void {
    this.editStop(stopId, (mark) => ({ ...mark, comment }));
  }

  private editStop(
    stopId: string,
    edit: (mark: { stars: number | null; comment: string }) => {
      stars: number | null;
      comment: string;
    },
  ): void {
    const draft = this.draft();
    if (!draft) return;
    const mark = draft.stops[stopId] ?? { stars: null, comment: '' };
    this.draft.set({ ...draft, stops: { ...draft.stops, [stopId]: edit(mark) } });
  }

  protected readonly overrideText = computed(() => {
    const score = this.draft()?.score;
    return score === null || score === undefined ? '' : String(score);
  });

  protected setOverride(text: string): void {
    const draft = this.draft();
    if (draft) this.draft.set({ ...draft, score: parseScore(text) });
  }

  protected setParentComment(comment: string): void {
    const draft = this.draft();
    if (draft) this.draft.set({ ...draft, comment });
  }

  protected readonly dirty = computed(() => {
    const draft = this.draft();
    return draft !== null && this.original !== null && marks('l', 'c', draft, this.original).length > 0;
  });

  /**
   * One request for the whole panel.
   *
   * Only what changed is sent: `PUT /teacher/marks` deletes a mark whose fields are all null, so
   * a full panel would wipe the stop marks of a teacher who only typed a comment. The Undo sends
   * the same diff the other way round, which is why both sides are kept.
   */
  protected save(row: ResultRow): void {
    const draft = this.draft();
    const before = this.original;
    if (!draft || !before) return;
    const payload = marks(this.lessonId(), row.childId, draft, before);
    if (payload.length === 0) return;

    this.saving.set(true);
    this.api.saveMarks(request(payload)).subscribe({
      next: () => {
        this.saving.set(false);
        this.openChild.set(null);
        this.draft.set(null);
        this.results.reload();
        this.undo.offerUndo({
          message: this.t('results.marking.saved', { name: row.name }),
          undo: () => this.restore(row, draft, before),
          commit: () => this.results.reload(),
        });
      },
      error: (error: unknown) => this.fail(error),
    });
  }

  private restore(row: ResultRow, applied: MarkDraft, before: MarkDraft): void {
    const payload = marks(this.lessonId(), row.childId, before, applied);
    if (payload.length === 0) return;
    this.api.saveMarks(request(payload)).subscribe({
      next: () => this.results.reload(),
      error: (error: unknown) => this.fail(error),
    });
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
    this.saving.set(false);
    this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
