import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ResultsAndGradebookApi, apiErrorOf, type LessonResults } from '../../api';
import { BandService } from '../../core/band/band.service';
import { exportName, saveFile } from '../../core/download/download';
import { FLAGS, FlagService } from '../../core/flags/flag.service';
import { FeatureDirective } from '../../core/flags/feature.directive';
import { activeLang } from '../../core/i18n/active-lang';
import { CanDirective } from '../../core/permissions/can.directive';
import { PermissionService } from '../../core/permissions/permission.service';
import { UndoService } from '../../core/undo/undo.service';
import {
  ButtonComponent,
  CardComponent,
  CheckboxComponent,
  EmptyStateComponent,
  InputComponent,
  SkeletonComponent,
} from '../../ui';
import {
  columnsOf,
  defaultRange,
  needingMarking,
  normalise,
  rowsOf,
  trendGlyph,
  type Cell,
  type Row,
} from './gradebook.models';
import { LevelBandComponent } from './level-band.component';
import { marks, parseScore, request, scoreLabel, type MarkDraft } from './results.models';

/**
 * **The Gradebook tab** of the class page (`docs/teacher-flow.md` §4 step 9): children down the
 * side, lessons across the top, one coloured square each.
 *
 * **A square is never only a colour.** Each carries its band's short word and the score itself,
 * with the band's full name in the cell's title — the grid has to survive a projector, a
 * photocopy and colour blindness, and the point of it is comparison at a glance.
 *
 * **An override never hides the automatic score.** The teacher's number is the one in the
 * square; the machine's is struck through beside it, which is the only way a teacher can see
 * *that* she moved a score three weeks later, and by how much.
 *
 * **Why editing a cell reads the lesson's results first.** `PUT /teacher/marks` replaces the
 * whole lesson-level mark, and `GradebookCell` does not carry the parent comment — so writing an
 * override with the comment left out would silently delete a comment a parent may already have
 * read. The editor therefore fetches that one lesson's results, keeps the comment, and sends it
 * back beside the new score. (One request per lesson, cached; the honest fix is a `comment` on
 * `GradebookCell`, which is a server change this package does not make.)
 *
 * Sticky first column, sticky header, and the scroll inside the card — a class of thirty over a
 * half-term is wider than any screen, and a page that scrolls sideways loses its rail.
 */
@Component({
  selector: 'hq-gradebook',
  imports: [
    CardComponent,
    ButtonComponent,
    CheckboxComponent,
    InputComponent,
    SkeletonComponent,
    EmptyStateComponent,
    LevelBandComponent,
    FeatureDirective,
    CanDirective,
    RouterLink,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './gradebook.component.html',
  styleUrl: './gradebook.component.scss',
})
export class GradebookComponent {
  private readonly api = inject(ResultsAndGradebookApi);
  private readonly transloco = inject(TranslocoService);
  private readonly flags = inject(FlagService);
  private readonly permissions = inject(PermissionService);
  private readonly band = inject(BandService);
  private readonly undo = inject(UndoService);
  private readonly lang = activeLang();

  readonly classId = input.required<string>();
  readonly className = input('');

  protected readonly markingFlag = FLAGS.openStopMarking;

  protected readonly canOverride = computed(
    () => this.flags.isOn(FLAGS.openStopMarking) && this.permissions.can('results.write'),
  );

  // ---- the range --------------------------------------------------------------------------

  private readonly initial = defaultRange();
  protected readonly from = signal(this.initial.from);
  protected readonly to = signal(this.initial.to);

  protected readonly book = rxResource({
    params: () => {
      const range = normalise({ from: this.from(), to: this.to() });
      return range ? { classId: this.classId(), ...range } : undefined;
    },
    stream: ({ params }) => this.api.gradebook(params.classId, params.from, params.to),
    defaultValue: {},
  });

  // ---- the grid ---------------------------------------------------------------------------

  protected readonly columns = computed(() => columnsOf(this.book.value()));
  protected readonly rows = computed(() => rowsOf(this.book.value()));
  protected readonly onlyUnmarked = signal(false);

  protected readonly visibleRows = computed(() =>
    this.onlyUnmarked() ? needingMarking(this.rows()) : this.rows(),
  );

  protected readonly needsMarking = computed(() => this.book.value().needsMarking ?? 0);

  protected trendLabel(row: Row): string {
    this.lang();
    return row.trend ? this.t(`results.gradebook.trend.${row.trend}`) : '';
  }

  protected trendGlyph(row: Row): string {
    return trendGlyph(row.trend);
  }

  /** "Counting to ten · 14 Sep · automatic 52" — everything the square cannot fit. */
  protected cellTitle(row: Row, cell: Cell): string {
    this.lang();
    const column = this.columns().find((candidate) => candidate.lessonId === cell.lessonId);
    const parts = [row.name, column?.title ?? '', this.day(column?.date ?? '')];
    if (cell.overridden) parts.push(this.t('results.gradebook.auto', { score: cell.autoScore }));
    return parts.filter((part) => part !== '').join(' · ');
  }

  protected score(value: number | null): string {
    return scoreLabel(value);
  }

  protected columnDate(date: string): string {
    this.lang();
    return this.day(date);
  }

  // ---- one cell ----------------------------------------------------------------------------

  protected readonly editing = signal<{ childId: string; lessonId: string } | null>(null);
  protected readonly draft = signal('');
  protected readonly loadingCell = signal(false);
  protected readonly savingCell = signal(false);
  /** What the lesson's results said when the editor opened: the comment and the score to restore. */
  private before: MarkDraft = { stops: {}, score: null, comment: '' };
  private readonly loaded = new Map<string, LessonResults>();

  protected isEditing(row: Row, cell: Cell): boolean {
    const editing = this.editing();
    return editing?.childId === row.childId && editing.lessonId === cell.lessonId;
  }

  protected edit(row: Row, cell: Cell): void {
    if (!this.canOverride()) return;
    this.editing.set({ childId: row.childId, lessonId: cell.lessonId });
    this.draft.set(cell.teacherScore === null ? '' : String(cell.teacherScore));
    this.before = { stops: {}, score: cell.teacherScore, comment: '' };

    const cached = this.loaded.get(cell.lessonId);
    if (cached) {
      this.before = { ...this.before, comment: commentOf(cached, row.childId) };
      return;
    }
    this.loadingCell.set(true);
    this.api.lessonResults(cell.lessonId).subscribe({
      next: (results) => {
        this.loadingCell.set(false);
        this.loaded.set(cell.lessonId, results);
        if (this.isEditing(row, cell))
          this.before = { ...this.before, comment: commentOf(results, row.childId) };
      },
      error: (error: unknown) => {
        this.loadingCell.set(false);
        this.cancel();
        this.fail(error);
      },
    });
  }

  protected cancel(): void {
    this.editing.set(null);
  }

  protected saveCell(row: Row, cell: Cell): void {
    const next: MarkDraft = { stops: {}, score: parseScore(this.draft()), comment: this.before.comment };
    const payload = marks(cell.lessonId, row.childId, next, this.before);
    this.editing.set(null);
    if (payload.length === 0) return;

    this.savingCell.set(true);
    const before = this.before;
    this.api.saveMarks(request(payload)).subscribe({
      next: () => {
        this.savingCell.set(false);
        this.loaded.delete(cell.lessonId);
        this.book.reload();
        this.undo.offerUndo({
          message: this.t('results.gradebook.saved', { name: row.name, score: this.score(next.score) }),
          undo: () => this.restore(row, cell, next, before),
          commit: () => this.book.reload(),
        });
      },
      error: (error: unknown) => {
        this.savingCell.set(false);
        this.fail(error);
      },
    });
  }

  private restore(row: Row, cell: Cell, applied: MarkDraft, before: MarkDraft): void {
    const payload = marks(cell.lessonId, row.childId, before, applied);
    if (payload.length === 0) return;
    this.api.saveMarks(request(payload)).subscribe({
      next: () => this.book.reload(),
      error: (error: unknown) => this.fail(error),
    });
  }

  // ---- exports -----------------------------------------------------------------------------

  protected readonly exporting = signal(false);

  protected exportCsv(): void {
    const range = normalise({ from: this.from(), to: this.to() });
    if (!range) return;
    this.exporting.set(true);
    this.api.gradebookCsv(this.classId(), range.from, range.to).subscribe({
      next: (csv) => {
        this.exporting.set(false);
        saveFile(csv, this.fileName('csv'), 'text/csv;charset=utf-8');
      },
      error: (error: unknown) => this.failExport(error),
    });
  }

  protected exportXlsx(): void {
    const range = normalise({ from: this.from(), to: this.to() });
    if (!range) return;
    this.exporting.set(true);
    // `application/vnd…sheet` is neither text nor JSON, so the generated client asks for a blob
    // even though its declared type says `string` — which is why `saveFile` takes either.
    this.api.gradebookXlsx(this.classId(), range.from, range.to).subscribe({
      next: (sheet) => {
        this.exporting.set(false);
        saveFile(sheet, this.fileName('xlsx'), XLSX);
      },
      error: (error: unknown) => this.failExport(error),
    });
  }

  private fileName(extension: string): string {
    return exportName([this.className(), this.t('results.gradebook.title')], extension);
  }

  private failExport(error: unknown): void {
    this.exporting.set(false);
    this.fail(error);
  }

  // ---- odds and ends -----------------------------------------------------------------------

  private day(value: string): string {
    if (!value) return '';
    return new Intl.DateTimeFormat(this.lang(), { day: 'numeric', month: 'short' }).format(
      new Date(`${value}T00:00:00Z`),
    );
  }

  private fail(error: unknown): void {
    this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}

const XLSX = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

/** The lesson-level comment this child already has, so an override does not delete it. */
function commentOf(results: LessonResults, childId: string): string {
  return (results.children ?? []).find((child) => child.childId === childId)?.comment ?? '';
}
