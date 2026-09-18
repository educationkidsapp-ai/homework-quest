/**
 * My classes and the class page, as plain data (N2.3, `docs/teacher-flow.md` §4 step 3 and §5).
 *
 * Everything the two screens decide about a card, a calendar cell or a roster row happens here,
 * on values, so it is testable without a DOM and so EN and AR get the same answer.
 *
 * The status words are `features/week`'s: a lesson is `draft`, `ready` or `published` on This
 * week's grid and on the class page's calendar, and the mapping lives in one place
 * (`normaliseStatus`) rather than twice.
 */
import type { ClassCalendar, ClassCalendarDay, ClassStudent, RosterChild, TeacherClassCard } from '../../api';
import { type CellStatus, normaliseStatus } from '../week/week.models';

export type { CellStatus };

/** One card on My classes: an assignment, what today looks like, and how big the class is. */
export interface ClassCardView {
  readonly classId: string;
  readonly className: string;
  readonly curriculum: string;
  readonly grade: number;
  readonly subject: string;
  readonly childrenCount: number;
  readonly playedToday: number;
  readonly status: CellStatus;
  /** The lesson to open, or `null` when today has none — then the card offers to add one. */
  readonly todayLessonId: string | null;
  /**
   * Whether the card says "12 of 18 played".
   *
   * Only a **published** lesson has been played, so a draft's `playedToday` (always zero, but
   * the server is free to change its mind) must not be drawn as "0 of 18 played" — that reads
   * as a lesson the class ignored rather than one she has not sent yet.
   */
  readonly showsPlayed: boolean;
}

export interface GradeGroup<T> {
  readonly grade: number;
  readonly items: readonly T[];
}

export function cardsOf(cards: readonly TeacherClassCard[] | null | undefined): readonly ClassCardView[] {
  return (cards ?? []).map(toCard);
}

function toCard(card: TeacherClassCard): ClassCardView {
  const lessonId = card.todayLessonId ?? null;
  const status = normaliseStatus(card.todayStatus, lessonId !== null);
  return {
    classId: card.classId ?? '',
    className: card.className ?? '',
    curriculum: card.curriculum ?? '',
    grade: card.grade ?? 0,
    subject: card.subject ?? '',
    childrenCount: card.childrenCount ?? 0,
    playedToday: card.playedToday ?? 0,
    status,
    todayLessonId: lessonId,
    showsPlayed: status === 'published',
  };
}

/**
 * Grade first, then class name, then subject — the order This week's rows use, so a teacher
 * reading both screens meets her classes in the same sequence on each.
 */
export function groupCardsByGrade(cards: readonly ClassCardView[]): readonly GradeGroup<ClassCardView>[] {
  const grades = [...new Set(cards.map((card) => card.grade))].sort((a, b) => a - b);
  return grades.map((grade) => ({
    grade,
    items: cards
      .filter((card) => card.grade === grade)
      .sort((a, b) => a.className.localeCompare(b.className) || a.subject.localeCompare(b.subject)),
  }));
}

// ---- the calendar ------------------------------------------------------------------------

/** One square of the month grid. */
export interface CalendarCell {
  readonly iso: string;
  /** Day of the month, 1–31. */
  readonly day: number;
  /** A leading or trailing cell that belongs to the neighbouring month: drawn, never clickable. */
  readonly inMonth: boolean;
  /** The school's own week says so — a weekend cell is dimmed and offers no `+`. */
  readonly schoolDay: boolean;
  readonly status: CellStatus;
  readonly lessonId: string | null;
  /** The lesson's type word (`generated`, `manual`, …). The API carries no title here — N2.3 gap. */
  readonly type: string | null;
  readonly playedCount: number;
  /** A school day that has arrived with nothing planned on it. The server decides, not us. */
  readonly gap: boolean;
}

/**
 * The 42 cells of a month grid, Sunday-first, from the server's days.
 *
 * `schoolDay` and `gap` are **read off the response**, never re-derived: the school week is an
 * Admin setting and the server has already applied it, and a gap is only a gap once the day has
 * arrived. Computing either here would be a second copy of a rule that could disagree — and it
 * did, in the Admin calendar, which hard-codes a Gulf week.
 *
 * Days outside the month are inert: `inMonth` false, no status, no `+`. They exist so the grid
 * is a rectangle and every `role="row"` has seven cells.
 */
export function calendarCells(calendar: ClassCalendar | null | undefined): readonly CalendarCell[] {
  const year = calendar?.year ?? 0;
  const month = calendar?.month ?? 0;
  if (!year || !month) return [];
  const byDate = new Map((calendar?.days ?? []).map((day) => [day.date ?? '', day]));
  const first = Date.UTC(year, month - 1, 1);
  const start = new Date(first);
  start.setUTCDate(start.getUTCDate() - start.getUTCDay());

  return Array.from({ length: 42 }, (_, index) => {
    const date = new Date(start);
    date.setUTCDate(start.getUTCDate() + index);
    const iso = date.toISOString().slice(0, 10);
    const inMonth = date.getUTCMonth() === month - 1 && date.getUTCFullYear() === year;
    return toCell(iso, date.getUTCDate(), inMonth, inMonth ? byDate.get(iso) : undefined);
  });
}

function toCell(
  iso: string,
  day: number,
  inMonth: boolean,
  entry: ClassCalendarDay | undefined,
): CalendarCell {
  const lessonId = entry?.lessonId ?? null;
  return {
    iso,
    day,
    inMonth,
    schoolDay: inMonth && (entry?.schoolDay ?? false),
    status: normaliseStatus(entry?.status, lessonId !== null),
    lessonId,
    type: entry?.type ?? null,
    playedCount: entry?.playedCount ?? 0,
    gap: inMonth && (entry?.gap ?? false),
  };
}

/** `yyyy-MM` for `GET /teacher/classes/{id}/calendar?month=` — never a locale's month name. */
export function monthParam(year: number, month: number): string {
  return `${year}-${String(month).padStart(2, '0')}`;
}

/** The month `delta` months away, as `{ year, month }` with `month` 1–12. */
export function shiftMonth(year: number, month: number, delta: number): { year: number; month: number } {
  const date = new Date(Date.UTC(year, month - 1 + delta, 1));
  return { year: date.getUTCFullYear(), month: date.getUTCMonth() + 1 };
}

// ---- the roster --------------------------------------------------------------------------

/** One row of the Children tab: what she played, plus what the roster knows about her. */
export interface RosterRow {
  readonly childId: string;
  readonly name: string;
  readonly starsThisWeek: number;
  readonly levelReached: number;
  readonly weakSkills: readonly string[];
  /** Epoch millis, or `null` when this child has never played. */
  readonly lastPlayed: number | null;
  /** `null` while the roster is not loaded (the flag is off, or the account may not read it). */
  readonly active: boolean | null;
  readonly parentEmail: string | null;
}

/**
 * `GET /students` joined with `GET /children`.
 *
 * Two endpoints because they answer two questions and carry two permissions: `student.read` is
 * "how is my class doing", `roster.teacher` is "who is in it". The progress list is the spine —
 * a child the roster knows and the progress endpoint does not is a child with no data yet, so
 * she is appended rather than dropped.
 */
export function rosterRows(
  students: readonly ClassStudent[] | null | undefined,
  children: readonly RosterChild[] | null | undefined,
): readonly RosterRow[] {
  const roster = new Map((children ?? []).map((child) => [child.id ?? '', child]));
  const seen = new Set<string>();
  const rows = (students ?? []).map((student) => {
    const id = student.childId ?? '';
    seen.add(id);
    return merge(id, student, roster.get(id));
  });
  const extra = (children ?? [])
    .filter((child) => !seen.has(child.id ?? ''))
    .map((child) => merge(child.id ?? '', undefined, child));
  return [...rows, ...extra].sort((a, b) => a.name.localeCompare(b.name));
}

function merge(id: string, student: ClassStudent | undefined, child: RosterChild | undefined): RosterRow {
  const lastPlayed = student?.lastPlayed ?? 0;
  return {
    childId: id,
    name: child?.name ?? student?.name ?? '',
    starsThisWeek: student?.starsThisWeek ?? 0,
    levelReached: student?.levelReached ?? 0,
    weakSkills: (student?.weakSkills ?? []).map((skill) => skill.name ?? '').filter((name) => name !== ''),
    lastPlayed: lastPlayed > 0 ? lastPlayed : null,
    active: child ? (child.active ?? true) : null,
    parentEmail: child?.parentEmail ?? null,
  };
}
