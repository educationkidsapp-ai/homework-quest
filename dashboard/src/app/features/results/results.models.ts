import type { ChildResult, LessonResults, MarkInput, SaveMarksRequest } from '../../api';

/**
 * The shapes the Results page draws, and the arithmetic behind them
 * (`docs/teacher-flow.md` §4 step 9).
 *
 * Everything here is a pure function of what the server answered. That is the point: the page
 * itself then has no arithmetic in it, and the three things a mistake here would break — the
 * payload `PUT /teacher/marks` receives, what Undo sends back, and which stops are called weak —
 * are all testable without a DOM.
 */

/** §7's four level bands, weakest first. The server's `band` strings, named once. */
export const BANDS = ['emerging', 'developing', 'secure', 'exceeding'] as const;
export type Band = (typeof BANDS)[number];

export function isBand(value: string | null | undefined): value is Band {
  return value !== null && value !== undefined && (BANDS as readonly string[]).includes(value);
}

export function bandOf(value: string | null | undefined): Band | null {
  return isBand(value) ? value : null;
}

/** One stop of one child: the lesson's stop, plus whatever she did with it. */
export interface RowStop {
  readonly stopId: string;
  readonly title: string;
  readonly type: string;
  readonly level: number;
  /** A retell, a drawing or an open answer — the stops a teacher marks by hand. */
  readonly open: boolean;
  /**
   * Whether this stop belongs to the level the child was scored on.
   *
   * `stops[]` is the union over every level of the lesson, so most of a row's cells are stops of
   * levels she never saw. Those are not "not attempted" — they were never hers — and the
   * difference is the whole of defect D1: the page drew them as holes and the marking panel
   * offered them for marking.
   */
  readonly inLevel: boolean;
  readonly attempted: boolean;
  readonly stars: number | null;
  readonly attempts: number;
  readonly accuracy: number | null;
  readonly score: number | null;
  readonly markStars: number | null;
  readonly markComment: string;
  readonly needsMarking: boolean;
  /** `/media/child/{id}` — the saved recording, drawing or photograph, behind the bearer. */
  readonly workUrl: string | null;
}

/** One child's row: her stops in the lesson's own order, whether she played them or not. */
export interface ResultRow {
  readonly childId: string;
  readonly name: string;
  readonly attempted: boolean;
  readonly levelReached: number | null;
  /** The level she was actually scored on — `0` when she has played nothing. */
  readonly scoredLevel: number;
  /** How many stops of that level she answered, out of how many it has. */
  readonly answered: number;
  readonly total: number;
  readonly starsEarned: number;
  readonly starsTotal: number;
  readonly completion: number;
  readonly autoScore: number | null;
  readonly teacherScore: number | null;
  readonly score: number | null;
  readonly band: Band | null;
  readonly needsMarking: number;
  /** The teacher's lesson-level comment — the one a parent reads after release. */
  readonly comment: string;
  readonly stops: readonly RowStop[];
}

/**
 * The table's rows.
 *
 * Every child gets a cell for every stop of the lesson, including the ones she never reached:
 * a grid with holes in it cannot be read down a column, and "she did not get there" is the
 * answer a teacher is looking for as often as a score is.
 *
 * **The union is over all three levels, and a child is scored on one of them** (N4.5 D1).
 * `results.stops` is level-major and `child.stops` are exactly her `scoredLevel`'s, so the join
 * by stop id below can only ever match inside her own group. Every other cell is marked
 * {@link RowStop.inLevel} `false` — drawn as "not this level", never as a hole she left — and
 * it is that flag, not the absence of a match, that keeps a mark off a stop she never played.
 */
export function resultRows(results: LessonResults): readonly ResultRow[] {
  const stops = results.stops ?? [];
  return (results.children ?? []).map((child) => {
    const byStop = new Map((child.stops ?? []).map((stop) => [stop.stopId ?? '', stop]));
    const scoredLevel = child.scoredLevel ?? 0;
    return {
      childId: child.childId ?? '',
      name: child.name ?? '',
      attempted: child.attempted === true,
      levelReached: child.levelReached ?? null,
      scoredLevel,
      answered: child.answered ?? 0,
      total: child.total ?? 0,
      starsEarned: child.starsEarned ?? 0,
      starsTotal: child.starsTotal ?? 0,
      completion: child.completion ?? 0,
      autoScore: child.autoScore ?? null,
      teacherScore: child.teacherScore ?? null,
      score: child.score ?? null,
      band: bandOf(child.band),
      needsMarking: child.needsMarking ?? 0,
      comment: child.comment ?? '',
      stops: stops.map((stop) => {
        const level = stop.level ?? 1;
        const inLevel = level === scoredLevel;
        const played = inLevel ? byStop.get(stop.stopId ?? '') : undefined;
        return {
          stopId: stop.stopId ?? '',
          title: stop.title ?? '',
          type: stop.type ?? '',
          level,
          open: stop.open === true,
          inLevel,
          attempted: played?.attempted === true,
          stars: played?.stars ?? null,
          attempts: played?.attempts ?? 0,
          accuracy: played?.accuracy ?? null,
          score: played?.score ?? null,
          markStars: played?.markStars ?? null,
          markComment: played?.markComment ?? '',
          needsMarking: played?.needsMarking === true,
          workUrl: played?.workUrl ?? null,
        };
      }),
    };
  });
}

// ---------------------------------------------------------------- the levels

/** One level's column group: its stops, in the order the lesson plays them. */
export interface LevelGroup {
  readonly level: number;
  readonly stops: readonly LevelStop[];
}

export interface LevelStop {
  readonly stopId: string;
  readonly title: string;
  readonly open: boolean;
  /** Its place inside its own level — Level 3's first stop is "Stop 1", not "Stop 11". */
  readonly number: number;
}

/**
 * The lesson's stops as the table's column groups: Level 1, then Level 2, then Level 3.
 *
 * Read from `results.stops` rather than from a row, so the headings stand even on a lesson no
 * child has opened yet. The server sends the union level-major and in play order inside a level;
 * this preserves both and only groups what arrives.
 */
export function levelGroups(results: LessonResults): readonly LevelGroup[] {
  const groups = new Map<number, LevelStop[]>();
  for (const stop of results.stops ?? []) {
    const level = stop.level ?? 1;
    let stops = groups.get(level);
    if (!stops) {
      stops = [];
      groups.set(level, stops);
    }
    stops.push({
      stopId: stop.stopId ?? '',
      title: stop.title ?? '',
      open: stop.open === true,
      number: stops.length + 1,
    });
  }
  return [...groups.entries()].map(([level, stops]) => ({ level, stops }));
}

/** How far below the class's own stop average a stop has to sit before it is called weak. */
export const WEAK_MARGIN = 10;

/**
 * The stops this class found hardest — the ones §4 step 9 asks the page to highlight.
 *
 * Relative, not a fixed threshold: a lesson every child scored 90 on has no weak stop, and one
 * where nobody cleared 50 has more than one. A stop is weak when its own average is
 * {@link WEAK_MARGIN} points or more below the average of **its own level's** stops, and only the
 * two weakest are marked — highlighting five of six columns highlights nothing.
 *
 * Per level, because the union spans three of them: Level 3 is meant to be harder than Level 1,
 * and comparing the two would call the whole of Level 3 hardest on every lesson in the school.
 */
export function weakestStopIds(rows: readonly ResultRow[], take = 2): ReadonlySet<string> {
  const first = rows[0];
  if (!first) return new Set();

  const averages = new Map<string, number>();
  const byLevel = new Map<number, string[]>();
  for (const [index, stop] of first.stops.entries()) {
    const scores = rows
      .map((row) => row.stops[index]?.score ?? null)
      .filter((score): score is number => score !== null);
    if (scores.length === 0) continue;
    averages.set(stop.stopId, mean(scores));
    byLevel.set(stop.level, [...(byLevel.get(stop.level) ?? []), stop.stopId]);
  }

  const weak: [string, number][] = [];
  for (const stopIds of byLevel.values()) {
    if (stopIds.length < 2) continue;
    const overall = mean(stopIds.map((stopId) => averages.get(stopId) ?? 0));
    for (const stopId of stopIds) {
      const average = averages.get(stopId) ?? 0;
      if (overall - average >= WEAK_MARGIN) weak.push([stopId, average]);
    }
  }

  return new Set(
    weak
      .sort((a, b) => a[1] - b[1])
      .slice(0, take)
      .map(([stopId]) => stopId),
  );
}

function mean(values: readonly number[]): number {
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

// ---------------------------------------------------------------- marking

/** What the teacher has typed into one child's panel, before it is sent. */
export interface MarkDraft {
  /** Per open stop: 1–3 stars and a note to herself. `null` stars means "not marked". */
  readonly stops: Readonly<Record<string, StopMark>>;
  /** The lesson-level override, 0–100, or `null` to let the automatic score stand. */
  readonly score: number | null;
  /** The comment the parent reads after release. */
  readonly comment: string;
}

export interface StopMark {
  readonly stars: number | null;
  readonly comment: string;
}

export const EMPTY_STOP_MARK: StopMark = { stars: null, comment: '' };

/**
 * The draft a panel opens with: exactly what the server currently holds.
 *
 * Her own level's open stops and no others (N4.5 D1) — {@link markableStops} is the same rule,
 * and the two must agree or the panel would show a field whose edits are never sent.
 */
export function draftOf(row: ResultRow): MarkDraft {
  const stops: Record<string, StopMark> = {};
  for (const stop of markableStops(row))
    stops[stop.stopId] = { stars: stop.markStars, comment: stop.markComment };
  return { stops, score: row.teacherScore, comment: row.comment };
}

/**
 * The stops a teacher may mark for this child: open, and of the level she was scored on.
 *
 * Before N4.5 this was `stops.filter(open)` over a column list built from the lesson's *top*
 * level, so on every multi-level lesson the panel offered Level 3's retell to a child who had
 * played Level 1 and `PUT /teacher/marks` stored the stars against a stop she never answered.
 */
export function markableStops(row: ResultRow): readonly RowStop[] {
  return row.stops.filter((stop) => stop.open && stop.inLevel);
}

/**
 * What changed, as `PUT /teacher/marks` wants it.
 *
 * **Only what changed.** The endpoint deletes a mark whose every field is null, so sending the
 * whole panel every time would delete the stop marks of a teacher who only typed a comment.
 *
 * **`stopId` absent is the lesson.** §7: a mark with no stop is the score override and the
 * parent's comment together; a mark on a stop carries stars and the teacher's own note, and the
 * server drops a `score` sent on one.
 *
 * Undo is this function with its arguments the other way round — `marks(lesson, child, before,
 * after)` — which is why neither side is treated as authoritative here.
 */
export function marks(
  lessonId: string,
  childId: string,
  next: MarkDraft,
  previous: MarkDraft,
): readonly MarkInput[] {
  const out: MarkInput[] = [];
  const stopIds = new Set([...Object.keys(next.stops), ...Object.keys(previous.stops)]);
  for (const stopId of stopIds) {
    const after = next.stops[stopId] ?? EMPTY_STOP_MARK;
    const before = previous.stops[stopId] ?? EMPTY_STOP_MARK;
    if (after.stars === before.stars && after.comment.trim() === before.comment.trim()) continue;
    out.push({
      lessonId,
      childId,
      stopId,
      stars: after.stars ?? undefined,
      comment: after.comment.trim() || undefined,
    });
  }
  if (next.score !== previous.score || next.comment.trim() !== previous.comment.trim())
    out.push({
      lessonId,
      childId,
      score: next.score ?? undefined,
      comment: next.comment.trim() || undefined,
    });
  return out;
}

export function request(inputs: readonly MarkInput[]): SaveMarksRequest {
  return { marks: [...inputs] };
}

export function hasChanges(next: MarkDraft, previous: MarkDraft): boolean {
  return marks('l', 'c', next, previous).length > 0;
}

// ---------------------------------------------------------------- the header

/** The four numbers above the table, in the order §3's metric row puts them. */
export interface ResultsSummary {
  readonly played: number;
  readonly roster: number;
  readonly classAverage: number | null;
  readonly needsMarking: number;
  readonly released: boolean;
  readonly releasedAt: number | null;
}

export function summaryOf(results: LessonResults): ResultsSummary {
  return {
    played: results.played ?? 0,
    roster: (results.children ?? []).length,
    classAverage: results.classAverage ?? null,
    needsMarking: results.needsMarking ?? 0,
    released: results.released === true,
    releasedAt: results.releasedAt ?? null,
  };
}

/** A child who has not played has no score, and "0" would be a lie about her, not a number. */
export function scoreLabel(value: number | null, dash = '—'): string {
  return value === null ? dash : String(value);
}

/** The override the teacher typed, as a number the server will take, or `null`. */
export function parseScore(text: string): number | null {
  const trimmed = text.trim();
  if (trimmed === '') return null;
  const value = Number(trimmed);
  if (!Number.isFinite(value)) return null;
  return Math.max(0, Math.min(100, Math.round(value)));
}

/** Rows a "needs marking" filter leaves standing. */
export function needingMarking(rows: readonly ResultRow[]): readonly ResultRow[] {
  return rows.filter((row) => row.needsMarking > 0);
}

/** The children of a lesson whose open stops are all marked — for the toggle's own label. */
export function markedCount(children: readonly ChildResult[]): number {
  return children.filter((child) => (child.needsMarking ?? 0) === 0).length;
}
