/* hq-flag: none (shell) — This week is the teacher's landing route, gated by `teacher.week`
   rather than by a flag: a teacher with no way in has no dashboard at all. The screens it links
   to carry their own gates (`lesson.write` for the editor, `teacher.lesson.copy` for a copy). */
import { CdkDrag, CdkDragDrop, CdkDropList, CdkDropListGroup } from '@angular/cdk/drag-drop';
import { CdkMenu, CdkMenuItem, CdkMenuTrigger } from '@angular/cdk/menu';
import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { TeacherApi, TeacherLessonsApi, type TeacherWeek, type WeekGap, apiErrorOf } from '../../api';
import { BandService } from '../../core/band/band.service';
import { activeLang } from '../../core/i18n/active-lang';
import { PlatformService } from '../../core/platform/platform.service';
import { UndoService } from '../../core/undo/undo.service';
import {
  BandComponent,
  ButtonComponent,
  EmptyStateComponent,
  ListStaggerDirective,
  PageComponent,
  SkeletonComponent,
} from '../../ui';
import {
  type DragSource,
  type GridCell,
  type GridRow,
  areSiblings,
  daysOf,
  dropTargetsFor,
  groupByGrade,
  pendingCopyId,
  rowsOf,
  siblingsOf,
  withCopiedLesson,
  withMovedLesson,
} from './week.models';

/** What a drop list carries: which assignment and which day the cell under the cursor is. */
export interface CellRef {
  readonly row: GridRow;
  readonly cell: GridCell;
}

interface CopyRequest {
  readonly source: DragSource;
  readonly target: GridRow;
}

/**
 * **This week** (`docs/teacher-flow.md` §4 step 2) — one grid across every class she teaches.
 *
 * Columns are the school week the server already applied (`GET /teacher/week` returns the
 * teaching days); rows are her assignments, grouped by grade. Each cell is the day's item: a
 * lesson card with a status square and a played count, an exam with its window, or a faint `+`
 * that opens the editor pre-set to that class, subject and day.
 *
 * Two gestures do the work, and both have a keyboard twin on the card's overflow menu, because a
 * grid whose only affordance is dragging is a grid a keyboard cannot use:
 *
 * * **Move** — another day of the same row, unpublished only. `PATCH /teacher/lessons/{id}`.
 *   Optimistic, undoable for ten seconds, and rolled back under the red band if the server says
 *   no (it will, for a lesson somebody has already published from another tab).
 * * **Copy** — a sibling row, meaning another class of the same grade, curriculum and subject.
 *   `POST /teacher/lessons/{id}/copy`. It confirms first, because it creates a second lesson.
 *   The server keeps the source's date, so the copy lands on the day the card is *on*, not the
 *   day it was dropped — the confirm strip names that day rather than implying otherwise.
 */
@Component({
  selector: 'hq-week-page',
  imports: [
    PageComponent,
    ButtonComponent,
    BandComponent,
    EmptyStateComponent,
    SkeletonComponent,
    ListStaggerDirective,
    CdkDropListGroup,
    CdkDropList,
    CdkDrag,
    CdkMenu,
    CdkMenuItem,
    CdkMenuTrigger,
    RouterLink,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './week.page.html',
  styleUrl: './week.page.scss',
})
export class WeekPage {
  private readonly teacherApi = inject(TeacherApi);
  private readonly lessonsApi = inject(TeacherLessonsApi);
  private readonly platform = inject(PlatformService);
  private readonly band = inject(BandService);
  private readonly undo = inject(UndoService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  // ---- which week ---------------------------------------------------------------------------

  private readonly params = toSignal(this.route.queryParamMap, {
    initialValue: this.route.snapshot.queryParamMap,
  });

  /** `?start=` — the URL is the state, so Back walks the weeks and a link opens on one. */
  protected readonly start = computed(() => this.params().get('start'));

  protected readonly week = rxResource<TeacherWeek, string | null>({
    params: () => this.start(),
    stream: ({ params }) => this.teacherApi.myWeek(params ?? undefined),
    defaultValue: {},
  });

  protected readonly days = computed(() => daysOf(this.week.value()));

  /**
   * The grid, writable.
   *
   * `linkedSignal` rather than `computed`: a move or a copy paints before the server answers,
   * and the refetch that follows has to be able to take the grid back — which is exactly what
   * re-running the computation on a new response does.
   */
  protected readonly rows = linkedSignal<TeacherWeek, readonly GridRow[]>({
    source: this.week.value,
    computation: (week) => rowsOf(week),
  });

  protected readonly groups = computed(() => groupByGrade(this.rows()));
  protected readonly hasRows = computed(() => this.rows().length > 0);

  // ---- the school's clock --------------------------------------------------------------------

  protected readonly timezone = computed(() => this.platform.settings().timezone ?? 'UTC');

  /** Today **in the school's timezone** — `en-CA` is the one locale that formats as `YYYY-MM-DD`. */
  protected readonly today = computed(() =>
    new Intl.DateTimeFormat('en-CA', { timeZone: this.timezone() }).format(new Date()),
  );

  protected readonly rangeLabel = computed(() => {
    this.lang();
    const days = this.days();
    const first = days[0];
    const last = days.at(-1);
    if (!first || !last) return '';
    return this.t('week.range', { from: this.dayLong(first), to: this.dayLong(last) });
  });

  protected dayLabel(iso: string): string {
    this.lang();
    return this.format(iso, { weekday: 'short', day: 'numeric' });
  }

  protected dayName(iso: string): string {
    this.lang();
    return this.format(iso, { weekday: 'long' });
  }

  private dayLong(iso: string): string {
    return this.format(iso, { day: 'numeric', month: 'short', year: 'numeric' });
  }

  /**
   * An ISO date is a calendar day, not an instant: formatting it at UTC midnight and asking for
   * UTC back is what keeps "2026-09-20" from becoming the 19th in a browser west of Greenwich.
   */
  private format(iso: string, options: Intl.DateTimeFormatOptions): string {
    const date = new Date(`${iso}T00:00:00Z`);
    if (Number.isNaN(date.getTime())) return iso;
    return new Intl.DateTimeFormat(this.lang(), { ...options, timeZone: 'UTC' }).format(date);
  }

  protected shift(weeks: number): void {
    const first = this.days()[0] ?? this.today();
    const date = new Date(`${first}T00:00:00Z`);
    date.setUTCDate(date.getUTCDate() + weeks * 7);
    this.goTo(date.toISOString().slice(0, 10));
  }

  protected goToday(): void {
    this.goTo(this.today());
  }

  private goTo(start: string): void {
    void this.router.navigate([], { relativeTo: this.route, queryParams: { start } });
  }

  // ---- the summary strip ----------------------------------------------------------------------

  protected readonly gaps = computed<readonly string[]>(() => {
    this.lang();
    return (this.week.value().summary?.gaps ?? []).map((gap: WeekGap) =>
      this.t('week.summary.gap', { class: gap.className ?? '', day: this.dayName(gap.date ?? '') }),
    );
  });

  protected readonly examsClosing = computed(() => this.week.value().summary?.examsClosing?.length ?? 0);
  protected readonly marksWaiting = computed(() => this.week.value().summary?.marksWaiting ?? 0);

  // ---- links out --------------------------------------------------------------------------------

  protected readonly newLessonLink = ['/teacher/lessons/new'];

  /** §4: the `+` opens the editor already knowing the class, the subject and the day. */
  protected newLessonParams(row: GridRow, cell: GridCell): Record<string, string> {
    return {
      classId: row.classId,
      curriculum: row.curriculum,
      grade: String(row.grade),
      subject: row.subject,
      date: cell.date,
    };
  }

  protected lessonLink(cell: GridCell): readonly unknown[] {
    return ['/teacher/lessons', cell.lesson?.id ?? ''];
  }

  // ---- drag ---------------------------------------------------------------------------------------

  protected readonly dragging = signal<DragSource | null>(null);

  /** While a card is in the air: the classes it may land in. `null` when nothing is being dragged. */
  private readonly validTargets = computed<ReadonlySet<string> | null>(() => {
    const source = this.dragging();
    return source ? dropTargetsFor(this.rows(), source.classId) : null;
  });

  /** A row that is not a valid target is drawn disabled rather than simply refusing the drop. */
  protected rowDisabled(row: GridRow): boolean {
    const targets = this.validTargets();
    return targets !== null && !targets.has(row.classId);
  }

  protected dragStarted(row: GridRow, cell: GridCell): void {
    if (cell.lesson) this.dragging.set({ classId: row.classId, date: cell.date, lesson: cell.lesson });
  }

  protected dragEnded(): void {
    this.dragging.set(null);
  }

  /**
   * Same row: the day must be free. Sibling row: anywhere in it, because the copy takes the
   * source's date whatever cell the cursor is over. Anything else is not a target at all.
   */
  protected readonly allowDrop = (drag: CdkDrag<CellRef>, drop: CdkDropList<CellRef>): boolean => {
    const source = drag.data;
    const target = drop.data;
    if (!source?.cell.lesson || !target) return false;
    if (source.row.classId === target.row.classId) return source.cell.movable && !target.cell.lesson;
    return areSiblings(source.row, target.row);
  };

  protected dropped(event: CdkDragDrop<CellRef, CellRef, CellRef>): void {
    this.dragging.set(null);
    const source = event.item.data;
    const target = event.container.data;
    if (!source.cell.lesson) return;
    const from: DragSource = {
      classId: source.row.classId,
      date: source.cell.date,
      lesson: source.cell.lesson,
    };
    if (source.row.classId === target.row.classId) this.move(from, target.cell.date);
    else if (areSiblings(source.row, target.row)) this.askCopy(from, target.row);
  }

  // ---- the keyboard twin: the card's overflow menu ---------------------------------------------

  protected readonly menuCell = signal<CellRef | null>(null);

  protected readonly menuSource = computed<DragSource | null>(() => {
    const ref = this.menuCell();
    return ref?.cell.lesson
      ? { classId: ref.row.classId, date: ref.cell.date, lesson: ref.cell.lesson }
      : null;
  });

  /** The days this card could move to, each with whether that day is already taken. */
  protected readonly moveTargets = computed<readonly { date: string; taken: boolean }[]>(() => {
    const ref = this.menuCell();
    if (!ref?.cell.movable) return [];
    return ref.row.cells
      .filter((cell) => cell.date !== ref.cell.date)
      .map((cell) => ({ date: cell.date, taken: cell.lesson !== null }));
  });

  protected readonly copyTargets = computed<readonly GridRow[]>(() => {
    const ref = this.menuCell();
    return ref ? siblingsOf(this.rows(), ref.row) : [];
  });

  protected moveFromMenu(date: string): void {
    const source = this.menuSource();
    if (source) this.move(source, date);
  }

  protected copyFromMenu(target: GridRow): void {
    const source = this.menuSource();
    if (source) this.askCopy(source, target);
  }

  // ---- move ---------------------------------------------------------------------------------------

  private move(source: DragSource, toDate: string): void {
    if (source.date === toDate) return;
    const before = this.rows();
    const target = this.cellAt(before, source.classId, toDate);
    if (!target || target.lesson) {
      if (target?.lesson) this.refuse(source.classId, toDate);
      return;
    }
    this.rows.set(withMovedLesson(before, source.classId, source.date, toDate));

    this.lessonsApi.moveTeacherLesson(source.lesson.id ?? '', { date: toDate }).subscribe({
      next: () =>
        this.undo.offerUndo({
          message: this.t('week.undo.moved', {
            title: source.lesson.title ?? '',
            day: this.dayName(toDate),
          }),
          undo: () => this.move({ ...source, date: toDate }, source.date),
          commit: () => this.week.reload(),
        }),
      error: (error: unknown) => {
        this.rows.set(before);
        this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
      },
    });
  }

  // ---- copy ----------------------------------------------------------------------------------------

  protected readonly copyRequest = signal<CopyRequest | null>(null);

  // Empty rather than absent while nothing is pending: a screen reader must not find a live
  // "Copy this lesson?" band that merely carries `hidden` (the P3.2b note).
  protected readonly copyMessage = computed(() => {
    this.lang();
    const request = this.copyRequest();
    if (!request) return '';
    return this.t('week.copyConfirm.message', {
      title: request.source.lesson.title ?? '',
      class: request.target.className,
      day: this.dayName(request.source.date),
    });
  });

  private askCopy(source: DragSource, target: GridRow): void {
    this.copyRequest.set({ source, target });
  }

  protected cancelCopy(): void {
    this.copyRequest.set(null);
  }

  protected confirmCopy(): void {
    const request = this.copyRequest();
    this.copyRequest.set(null);
    if (!request) return;

    const { source, target } = request;
    const before = this.rows();
    // The server copies onto the source's own date, so that is the cell that has to be free.
    const landing = this.cellAt(before, target.classId, source.date);
    if (!landing || landing.lesson) {
      this.refuse(target.classId, source.date);
      return;
    }
    this.rows.set(
      withCopiedLesson(
        before,
        target.classId,
        source.date,
        source.lesson,
        pendingCopyId(target.classId, source.date),
      ),
    );

    this.lessonsApi.copyTeacherLesson(source.lesson.id ?? '', { classId: target.classId }).subscribe({
      next: () => {
        this.undo.offerUndo({
          message: this.t('week.undo.copied', {
            title: source.lesson.title ?? '',
            class: target.className,
          }),
          // A copy is a real second lesson; taking it back is a delete she does from the lesson
          // page, so the strip only reports it and the refetch replaces the placeholder id.
          undo: () => this.week.reload(),
          commit: () => this.week.reload(),
        });
      },
      error: (error: unknown) => {
        this.rows.set(before);
        this.band.fail(apiErrorOf(error)?.message ?? this.t('band.unreachable'));
      },
    });
  }

  // ---- keyboard: arrows walk the grid, Enter opens what is in the cell -----------------------------

  /**
   * Roving focus without a roving tabindex: every cell holds exactly one focusable control (the
   * card's link, or the empty cell's `+`), so the arrow keys are index arithmetic over them and
   * Tab still reaches the `+` on its own.
   */
  protected onGridKeydown(event: KeyboardEvent): void {
    const step = { ArrowRight: 1, ArrowLeft: -1, ArrowDown: 0, ArrowUp: 0 }[event.key];
    if (step === undefined) return;
    const grid = event.currentTarget as HTMLElement;
    const cells = Array.from(grid.querySelectorAll<HTMLElement>('[data-hq-cell]'));
    const current = cells.indexOf(document.activeElement as HTMLElement);
    if (current < 0) return;

    const columns = Math.max(this.days().length, 1);
    // `dir="rtl"` mirrors the columns, so Right must mean "the next column on the screen".
    const rtl = (grid.closest('[dir]')?.getAttribute('dir') ?? grid.ownerDocument.dir) === 'rtl';
    const inline = rtl ? -step : step;
    const delta = event.key === 'ArrowDown' ? columns : event.key === 'ArrowUp' ? -columns : inline;
    const next = cells[current + delta];
    if (!next) return;
    event.preventDefault();
    next.focus();
  }

  // ---- wiring ---------------------------------------------------------------------------------------

  private cellAt(rows: readonly GridRow[], classId: string, date: string): GridCell | null {
    return rows.find((row) => row.classId === classId)?.cells.find((cell) => cell.date === date) ?? null;
  }

  private refuse(classId: string, date: string): void {
    const name = this.rows().find((row) => row.classId === classId)?.className ?? '';
    this.band.fail(this.t('week.occupied', { class: name, day: this.dayName(date) }));
  }

  protected rowLabel(row: GridRow): string {
    this.lang();
    return this.t('week.rowLabel', {
      class: row.className,
      subject: this.word(`subject.${row.subject}`, row.subject),
      curriculum: this.word(`curriculum.${row.curriculum}`, row.curriculum),
    });
  }

  protected cellLabel(row: GridRow, cell: GridCell): string {
    this.lang();
    return this.t('week.cellLabel', { class: row.className, day: this.dayName(cell.date) });
  }

  protected trackRow = (_index: number, row: GridRow): string => `${row.classId}:${row.subject}`;

  private word(key: string, fallback: string): string {
    const text = this.t(key);
    return text === key ? fallback : text;
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate<string>(key, params);
  }
}
