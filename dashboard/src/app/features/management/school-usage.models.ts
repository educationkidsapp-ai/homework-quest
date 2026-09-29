import type { SchoolUsage } from '../../api';

/** The four tiles above the table: what the school has, and what it did in the window. */
export interface UsageSummary {
  readonly children: number;
  readonly activeFamilies: number;
  readonly lessonsPublished: number;
  readonly plays: number;
}

/** One teacher's line — `TeacherConsistency`, with the ratio the table actually shows. */
export interface UsageTeacherRow {
  readonly key: string;
  readonly displayName: string;
  readonly lessonsPublished: number;
  readonly weeks: number;
  readonly weeksWithALesson: number;
  /** Weeks with a lesson as a percentage of the window, or `null` for a window of no weeks. */
  readonly consistency: number | null;
  /** Epoch millis, or `null` for a teacher who has never published. */
  readonly lastPublishedAt: number | null;
}

/**
 * `GET /school/usage` as the tiles read it.
 *
 * Every field on the wire is optional (`SchoolUsage`), and the two series are summed here rather
 * than in the template: "lessons published" is the window's total, not the last week's, and a
 * school that published nothing has a zero rather than an empty tile.
 */
export function usageSummary(usage: SchoolUsage | undefined): UsageSummary {
  return {
    children: usage?.children ?? 0,
    activeFamilies: usage?.activeFamilies ?? 0,
    lessonsPublished: (usage?.lessonsPublishedPerWeek ?? []).reduce(
      (sum, week) => sum + (week.count ?? 0),
      0,
    ),
    plays: (usage?.playsPerDay ?? []).reduce((sum, day) => sum + (day.count ?? 0), 0),
  };
}

/**
 * The per-teacher table: busiest first, then alphabetical, so the order is stable between two
 * reads of the same window.
 *
 * **`weeks` is the window, `weeksWithALesson` is how many of them she published in** — a teacher
 * with 12 lessons in 2 of 8 weeks is a different fact from one with 8 in 8, and a single count
 * hides it, which is the whole reason the server sends both. A row that arrives with no id keeps
 * its own key rather than collapsing into every other nameless row (`quietTeachers`' lesson).
 */
export function usageTeacherRows(usage: SchoolUsage | undefined): readonly UsageTeacherRow[] {
  return (usage?.teacherConsistency ?? [])
    .map((teacher, index) => {
      const weeks = teacher.weeks ?? 0;
      const withALesson = teacher.weeksWithALesson ?? 0;
      return {
        key: teacher.teacherId ?? `row-${index}`,
        displayName: teacher.displayName ?? '',
        lessonsPublished: teacher.lessonsPublished ?? 0,
        weeks,
        weeksWithALesson: withALesson,
        consistency: weeks === 0 ? null : Math.round((withALesson / weeks) * 100),
        lastPublishedAt: teacher.lastPublishedAt ?? null,
      };
    })
    .sort((a, b) => b.lessonsPublished - a.lessonsPublished || a.displayName.localeCompare(b.displayName));
}
