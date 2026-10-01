import type { GradeStats, ManagementStats } from '../../api';

/** One line of the statistics table — a grade, or the department's own total row. */
export interface StatsRow {
  readonly key: string;
  /** `null` on the total row, which carries `grade` 0 and no curriculum on the wire. */
  readonly grade: number | null;
  readonly children: number;
  readonly sections: number;
  /** `null` when nothing was marked in the window: a dash, never a flattering 100 %. */
  readonly attendanceRate: number | null;
  readonly exams: number;
  readonly examAverage: number | null;
  readonly examPassRate: number | null;
}

/**
 * `ManagementStats` as the table draws it: a row per grade, then the department's total.
 *
 * Every number on the wire is optional and every rate may be genuinely absent — `attendanceRate`
 * is *null* rather than 100 when nothing was marked (`AttendanceService`'s own rule), and an
 * empty grade has no exam average. The two cases are kept apart here rather than in the
 * template: a missing count is a zero, a missing rate is a dash.
 */
export function statsRows(stats: ManagementStats | undefined): readonly StatsRow[] {
  const grades = [...(stats?.grades ?? [])]
    .map((grade) => rowOf(grade, `grade-${grade.grade ?? 0}`, grade.grade ?? 0))
    .sort((a, b) => (a.grade ?? 0) - (b.grade ?? 0));
  const total = stats?.total;
  return total ? [...grades, rowOf(total, 'total', null)] : grades;
}

function rowOf(stats: GradeStats, key: string, grade: number | null): StatsRow {
  return {
    key,
    grade,
    children: stats.children ?? 0,
    sections: stats.sections ?? 0,
    attendanceRate: stats.attendanceRate ?? null,
    exams: stats.exams ?? 0,
    examAverage: stats.examAverage ?? null,
    examPassRate: stats.examPassRate ?? null,
  };
}

/**
 * `GET /management/stats` refuses a window longer than a term (`ManagementStatsService`), so the
 * screen refuses to ask for one: a 400 in the red band is a worse answer than a control that
 * cannot make the mistake.
 */
export const MAX_WINDOW_DAYS = 186;

/**
 * The month ending today, in the school's own timezone — the server's own default, said aloud.
 *
 * **Thirty days back, not `setUTCMonth(-1)`.** Opened on the 31st of March that call lands on
 * the 3rd of March (February has no 31st), so the default window was ~28 days on some days of
 * the year and 31 on others — on a screen whose numbers are compared month to month. `today` is
 * already resolved in the school's zone by the caller, and every step here is UTC arithmetic on
 * a UTC-anchored instant, so there is no second clock to disagree with the first.
 */
export function defaultStatsRange(today: string): { readonly from: string; readonly to: string } {
  const from = new Date(`${today}T00:00:00Z`);
  from.setUTCDate(from.getUTCDate() - 30);
  return { from: from.toISOString().slice(0, 10), to: today };
}

/** How many days the chosen window covers, both bounds counted; 0 when it is not a window. */
export function windowDays(from: string, to: string): number {
  if (from === '' || to === '' || from > to) return 0;
  const span = Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`);
  return Number.isNaN(span) ? 0 : span / 86_400_000 + 1;
}
