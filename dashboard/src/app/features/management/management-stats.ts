import type { GradeStats, ManagementStats, QuietTeacher } from '../../api';

/** One line of the statistics table — a grade, or the department's own total row. */
export interface StatsRow {
  readonly key: string;
  /** `null` on the total row, which carries `grade` 0 and no curriculum on the wire. */
  readonly grade: number | null;
  readonly children: number;
  readonly sections: number;
  /** `null` when nothing was marked in the window: a dash, never a flattering 100 %. */
  readonly attendanceRate: number | null;
  readonly lessonsPublished: number;
  readonly lessonsPlayed: number;
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

/**
 * The teachers who published nothing in the window, once each.
 *
 * `GET /management/stats` names them per grade, and a teacher who takes 1A and 2B appears under
 * both — which on a list whose whole point is "these people have gone quiet" reads as two
 * people. Deduplicated by user id, never by name: two teachers of forty may share one.
 */
export function quietTeachers(stats: ManagementStats | undefined): readonly QuietTeacher[] {
  const seen = new Map<string, QuietTeacher>();
  for (const grade of stats?.grades ?? [])
    for (const teacher of grade.quietTeachers ?? []) seen.set(teacher.userId ?? '', teacher);
  return [...seen.values()].sort((a, b) => (a.displayName ?? '').localeCompare(b.displayName ?? ''));
}

function rowOf(stats: GradeStats, key: string, grade: number | null): StatsRow {
  return {
    key,
    grade,
    children: stats.children ?? 0,
    sections: stats.sections ?? 0,
    attendanceRate: stats.attendanceRate ?? null,
    lessonsPublished: stats.lessonsPublished ?? 0,
    lessonsPlayed: stats.lessonsPlayed ?? 0,
    exams: stats.exams ?? 0,
    examAverage: stats.examAverage ?? null,
    examPassRate: stats.examPassRate ?? null,
  };
}

/** The month ending today, in the school's own timezone — the server's own default, said aloud. */
export function defaultStatsRange(today: string): { readonly from: string; readonly to: string } {
  const from = new Date(`${today}T00:00:00Z`);
  from.setUTCMonth(from.getUTCMonth() - 1);
  return { from: from.toISOString().slice(0, 10), to: today };
}
