import type { Gradebook, GradebookLesson } from '../../api';
import { bandOf, type Band } from './results.models';

/**
 * The gradebook grid (`docs/teacher-flow.md` §4 step 9), as pure data.
 *
 * The component draws a table and owns the requests; everything that decides *what a square
 * says* is here, because "colour plus a letter, never colour alone" and "the automatic score
 * stays visible under an override" are rules worth a test rather than a stylesheet.
 */

/** One square: a child's score on one lesson, and how it came to be that number. */
export interface Cell {
  readonly lessonId: string;
  readonly attempted: boolean;
  readonly score: number | null;
  readonly autoScore: number | null;
  readonly teacherScore: number | null;
  readonly band: Band | null;
  readonly needsMarking: boolean;
  /** The teacher has moved this score: the automatic one is shown struck through beside it. */
  readonly overridden: boolean;
}

/** §7's three directions. `null` is "not enough scores yet to say". */
export type Trend = 'up' | 'flat' | 'down' | null;

export interface Row {
  readonly childId: string;
  readonly name: string;
  /** The plain mean of the row, as the server computed it over the range on screen. */
  readonly average: number | null;
  readonly band: Band | null;
  readonly trend: Trend;
  readonly needsMarking: boolean;
  /** One per column, in the columns' own order, whether she played that lesson or not. */
  readonly cells: readonly Cell[];
}

export interface Column {
  readonly lessonId: string;
  readonly title: string;
  readonly date: string;
  readonly type: string;
  readonly released: boolean;
  readonly classAverage: number | null;
  readonly needsMarking: number;
}

export function columnsOf(book: Gradebook): readonly Column[] {
  return (book.lessons ?? []).map((lesson: GradebookLesson) => ({
    lessonId: lesson.lessonId ?? '',
    title: lesson.title ?? '',
    date: lesson.date ?? '',
    type: lesson.type ?? 'homework',
    released: lesson.released === true,
    classAverage: lesson.classAverage ?? null,
    needsMarking: lesson.needsMarking ?? 0,
  }));
}

/**
 * The rows, with every child's cells lined up with the columns.
 *
 * The server answers `cells` 1:1 with `lessons`, and this keeps it that way by looking each
 * cell up by lesson rather than trusting the order: a grid that slips by one column puts a
 * child's Tuesday score under Monday, which is the kind of mistake nobody spots and everybody
 * acts on.
 */
export function rowsOf(book: Gradebook): readonly Row[] {
  const columns = columnsOf(book);
  return (book.children ?? []).map((child) => {
    const byLesson = new Map((child.cells ?? []).map((cell) => [cell.lessonId ?? '', cell]));
    const cells = columns.map<Cell>((column) => {
      const cell = byLesson.get(column.lessonId);
      const teacherScore = cell?.teacherScore ?? null;
      const autoScore = cell?.autoScore ?? null;
      return {
        lessonId: column.lessonId,
        attempted: cell?.attempted === true,
        score: cell?.score ?? null,
        autoScore,
        teacherScore,
        band: bandOf(cell?.band),
        needsMarking: cell?.needsMarking === true,
        overridden: teacherScore !== null && teacherScore !== autoScore,
      };
    });
    return {
      childId: child.childId ?? '',
      name: child.name ?? '',
      average: child.average ?? null,
      band: bandOf(child.band),
      trend: trendOf(child.trend),
      needsMarking: cells.some((cell) => cell.needsMarking),
      cells,
    };
  });
}

function trendOf(value: string | null | undefined): Trend {
  return value === 'up' || value === 'flat' || value === 'down' ? value : null;
}

/** An arrow a teacher reads at a glance; the word beside it is what a screen reader gets. */
export function trendGlyph(trend: Trend): string {
  if (trend === 'up') return '↑';
  if (trend === 'down') return '↓';
  if (trend === 'flat') return '→';
  return '';
}

export function needingMarking(rows: readonly Row[]): readonly Row[] {
  return rows.filter((row) => row.needsMarking);
}

// ---------------------------------------------------------------- the range

/** §4 step 9's default window: the eight weeks behind today, which is about a half-term. */
export const DEFAULT_WEEKS = 8;

export interface Range {
  readonly from: string;
  readonly to: string;
}

export function isoDate(date: Date): string {
  return date.toISOString().slice(0, 10);
}

/**
 * The range the tab opens on.
 *
 * Eight weeks rather than a term, because the server has no term calendar: §4 asks for "the
 * current term or eight weeks", and inventing term boundaries the school has not told anyone
 * about would give two teachers of the same class two different grids.
 */
export function defaultRange(today = new Date()): Range {
  const from = new Date(today);
  from.setUTCDate(from.getUTCDate() - DEFAULT_WEEKS * 7);
  return { from: isoDate(from), to: isoDate(today) };
}

/** A range the server will take: `from` never after `to`, both `YYYY-MM-DD` or both dropped. */
export function normalise(range: Range): Range | null {
  if (!range.from || !range.to) return null;
  return range.from <= range.to ? range : { from: range.to, to: range.from };
}
