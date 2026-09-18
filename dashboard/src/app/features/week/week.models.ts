/**
 * This week's grid, as plain data (N2.2, `docs/teacher-flow.md` §4 step 2).
 *
 * Everything the screen does to the grid — reading it out of `GET /teacher/week`, deciding what
 * may be dragged where, moving a card, dropping a copy into a sibling class — happens here, on
 * values, so it can be tested without a DOM and so the optimistic path and the reconciling
 * refetch produce the same shape.
 *
 * Two deliberate choices:
 *
 * 1. **The columns are the server's `days`, in chronological order, always.** The school week is
 *    Admin settings (`GET /platform-settings.schoolWeek`, default Sun–Thu) and the server has
 *    already applied it; re-deriving the columns here would be a second copy of that rule that
 *    could disagree. Arabic does not reverse the array either: the grid is laid out with
 *    logical properties, so `dir="rtl"` puts the first school day at the inline-start by itself.
 *    Reversing the data as well would put it back on the left.
 * 2. **A cell exists for every day**, even when the server sent a shorter `cells` list, because a
 *    missing cell is a hole in a `role="grid"` and an empty one is a place to drop a card.
 */
import type { TeacherWeek, WeekCell, WeekExam, WeekLesson, WeekRow } from '../../api';

/** §4's four words. `none` is the empty cell; the other three come off `WeekLesson.status`. */
export type CellStatus = 'none' | 'draft' | 'ready' | 'published';

const STATUSES: ReadonlySet<string> = new Set(['draft', 'ready', 'published']);

export interface GridCell {
  readonly date: string;
  readonly lesson: WeekLesson | null;
  readonly exam: WeekExam | null;
  readonly status: CellStatus;
  /** A published lesson has been played; moving it would move it under the children. */
  readonly movable: boolean;
}

export interface GridRow {
  readonly classId: string;
  readonly className: string;
  readonly curriculum: string;
  readonly grade: number;
  readonly subject: string;
  readonly cells: readonly GridCell[];
}

/** Rows are grouped by grade, which is how a teacher reads her own timetable. */
export interface GradeGroup {
  readonly grade: number;
  readonly rows: readonly GridRow[];
}

/** What is being dragged: the card, and the row and day it came from. */
export interface DragSource {
  readonly classId: string;
  readonly date: string;
  readonly lesson: WeekLesson;
}

export function statusOf(lesson: WeekLesson | null | undefined): CellStatus {
  const status = lesson?.status ?? '';
  return STATUSES.has(status) ? (status as CellStatus) : lesson ? 'draft' : 'none';
}

export function isMovable(lesson: WeekLesson | null | undefined): boolean {
  return lesson != null && statusOf(lesson) !== 'published';
}

/** The columns: the server's teaching days for the week, chronological. Never reversed — see above. */
export function daysOf(week: TeacherWeek | null | undefined): readonly string[] {
  return [...(week?.days ?? [])].filter((day) => day.length > 0).sort();
}

export function rowsOf(week: TeacherWeek | null | undefined): readonly GridRow[] {
  const days = daysOf(week);
  return (week?.rows ?? []).map((row) => toRow(row, days));
}

function toRow(row: WeekRow, days: readonly string[]): GridRow {
  const byDate = new Map<string, WeekCell>((row.cells ?? []).map((cell) => [cell.date ?? '', cell]));
  return {
    classId: row.classId ?? '',
    className: row.className ?? '',
    curriculum: row.curriculum ?? '',
    grade: row.grade ?? 0,
    subject: row.subject ?? '',
    cells: days.map((date) => toCell(date, byDate.get(date))),
  };
}

function toCell(date: string, cell: WeekCell | undefined): GridCell {
  const lesson = cell?.lesson ?? null;
  return {
    date,
    lesson,
    exam: cell?.exam ?? null,
    status: statusOf(lesson),
    movable: isMovable(lesson),
  };
}

/**
 * Grade first, then the class name — so "1A · Math" and "1B · Math" sit next to each other and
 * the row a card may be copied into is the row underneath it.
 */
export function groupByGrade(rows: readonly GridRow[]): readonly GradeGroup[] {
  const grades = [...new Set(rows.map((row) => row.grade))].sort((a, b) => a - b);
  return grades.map((grade) => ({
    grade,
    rows: rows
      .filter((row) => row.grade === grade)
      .sort((a, b) => a.className.localeCompare(b.className) || a.subject.localeCompare(b.subject)),
  }));
}

/**
 * A sibling is another class of the **same grade, curriculum and subject** — exactly what
 * `TeacherLessonService.requireSibling` lets a copy land in. Matching on grade and subject alone
 * would offer a British card a drop target in an American class and earn a 403 for it.
 */
export function areSiblings(a: GridRow, b: GridRow): boolean {
  return (
    a.classId !== b.classId &&
    a.grade === b.grade &&
    a.subject === b.subject &&
    a.curriculum.toLowerCase() === b.curriculum.toLowerCase()
  );
}

export function siblingsOf(rows: readonly GridRow[], row: GridRow): readonly GridRow[] {
  return rows.filter((candidate) => areSiblings(row, candidate));
}

/** Where a card being dragged may be dropped: its own row (move) and its siblings (copy). */
export function dropTargetsFor(rows: readonly GridRow[], classId: string): ReadonlySet<string> {
  const row = rows.find((candidate) => candidate.classId === classId);
  if (!row) return new Set();
  return new Set([row.classId, ...siblingsOf(rows, row).map((sibling) => sibling.classId)]);
}

/**
 * Moving a card to another day of the same row, optimistically.
 *
 * A no-op when the target day already holds something: the server would keep both lessons (two
 * on one day is legal) but the grid can only draw one, so the screen refuses rather than
 * painting a move that a refetch would undo.
 */
export function withMovedLesson(
  rows: readonly GridRow[],
  classId: string,
  from: string,
  to: string,
): readonly GridRow[] {
  if (from === to) return rows;
  return rows.map((row) => {
    if (row.classId !== classId) return row;
    const source = row.cells.find((cell) => cell.date === from);
    const target = row.cells.find((cell) => cell.date === to);
    if (!source?.lesson || !target || target.lesson) return row;
    return {
      ...row,
      cells: row.cells.map((cell) =>
        cell.date === from
          ? toCell(cell.date, { date: cell.date, exam: cell.exam ?? undefined })
          : cell.date === to
            ? toCell(cell.date, {
                date: cell.date,
                lesson: source.lesson ?? undefined,
                exam: cell.exam ?? undefined,
              })
            : cell,
      ),
    };
  });
}

/**
 * Dropping a copy into a sibling row, optimistically.
 *
 * The copy carries a placeholder id until the refetch replaces it with the server's, and its
 * `playedCount` starts at zero — it is a new lesson in another class, not the same one twice.
 */
export function withCopiedLesson(
  rows: readonly GridRow[],
  targetClassId: string,
  date: string,
  lesson: WeekLesson,
  id: string,
): readonly GridRow[] {
  return rows.map((row) => {
    if (row.classId !== targetClassId) return row;
    const target = row.cells.find((cell) => cell.date === date);
    if (!target || target.lesson) return row;
    const copy: WeekLesson = { ...lesson, id, status: 'ready', playedCount: 0, version: 0 };
    return {
      ...row,
      cells: row.cells.map((cell) =>
        cell.date === date ? toCell(cell.date, { date, lesson: copy, exam: cell.exam ?? undefined }) : cell,
      ),
    };
  });
}

/** The id an optimistic copy carries until the refetch lands. Never sent to the server. */
export function pendingCopyId(targetClassId: string, date: string): string {
  return `pending:${targetClassId}:${date}`;
}

export function isPendingId(id: string | undefined): boolean {
  return (id ?? '').startsWith('pending:');
}
