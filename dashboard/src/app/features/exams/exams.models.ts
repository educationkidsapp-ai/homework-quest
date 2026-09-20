import type { ExamBand, ExamChildResult, ExamQuestion, ExamResults, ExamSettings } from '../../api';
import { type Band, BANDS, bandOf } from '../results/results.models';

/**
 * The vocabulary of §8, and the arithmetic the exam screens do before they draw anything.
 *
 * Everything here is a pure function of a server shape and a clock reading, so the state a
 * teacher sees, the window she typed and the bars under her distribution are all testable
 * without a browser — which is the whole of N4.4's unit suite.
 */

// ---- the five states ----------------------------------------------------------------------

/**
 * What the Exams tab says in its State column.
 *
 * Note what is *not* used: `ExamSettings.open`. The server computes it at request time, so a
 * page left open for ten minutes over a window that has since opened would keep saying
 * "scheduled" — and the tab is exactly the screen a teacher leaves open while she waits. The
 * window is two timestamps and a clock, so it is read from the clock here and `open` is left to
 * the server's own decisions.
 */
export const EXAM_STATES = ['draft', 'scheduled', 'open', 'closed', 'released'] as const;
export type ExamState = (typeof EXAM_STATES)[number];

/** §8's levels. `mixed` is assembled from the three generated ones rather than sat as one. */
export const EXAM_LEVELS = ['1', '2', '3', 'mixed'] as const;
export type ExamLevel = (typeof EXAM_LEVELS)[number];

export const RELEASE_MODES = ['auto_on_close', 'manual'] as const;
export type ReleaseMode = (typeof RELEASE_MODES)[number];

export function isExamLevel(value: string | null | undefined): value is ExamLevel {
  return EXAM_LEVELS.includes((value ?? '') as ExamLevel);
}

export function isReleaseMode(value: string | null | undefined): value is ReleaseMode {
  return RELEASE_MODES.includes((value ?? '') as ReleaseMode);
}

/**
 * Released beats closed, and draft beats everything: an exam nobody can reach is not
 * "scheduled" however promising its window looks, and one whose results the parents already
 * have is not merely "closed".
 */
export function examStateOf(exam: ExamSettings, now: number): ExamState {
  if (exam.status !== 'published') return 'draft';
  if (exam.released === true) return 'released';
  const opensAt = exam.opensAt ?? 0;
  const closesAt = exam.closesAt ?? 0;
  if (now < opensAt) return 'scheduled';
  if (now < closesAt) return 'open';
  return 'closed';
}

/** Settings are hers to change only before the paper is in front of anybody (`409 exam_open`). */
export function settingsFrozen(exam: ExamSettings, now: number): boolean {
  return now >= (exam.opensAt ?? 0);
}

/**
 * Whether Re-open may be offered for this child.
 *
 * §8 gives one extra sitting to a child who was absent or whose exam was cut off — never to one
 * who handed in, and never twice (`409 exam_already_reopened`). The server decides; this keeps
 * the dashboard from offering a button whose only possible answer is a red band.
 */
export function canReopen(child: ExamChildResult): boolean {
  if (child.reopened === true) return false;
  return child.state === 'absent' || child.state === 'started';
}

// ---- the window ----------------------------------------------------------------------------

/** What the two date pickers and the two time pickers hold, before it is an instant. */
export interface WindowDraft {
  readonly opensDate: string;
  readonly opensTime: string;
  readonly closesDate: string;
  readonly closesTime: string;
}

export type WindowError = 'incomplete' | 'order';

/**
 * The window as two instants, or the reason it is not one yet.
 *
 * "Closes after opens" is the server's own rule (`An exam must close after it opens.`) and it is
 * checked here so a teacher learns it while the field is under her cursor rather than after a
 * round trip that also throws away the source she picked.
 */
export function windowErrorOf(draft: WindowDraft, zone: string): WindowError | null {
  const opensAt = zonedEpoch(draft.opensDate, draft.opensTime, zone);
  const closesAt = zonedEpoch(draft.closesDate, draft.closesTime, zone);
  if (opensAt === null || closesAt === null) return 'incomplete';
  return closesAt > opensAt ? null : 'order';
}

export function windowOf(
  draft: WindowDraft,
  zone: string,
): { readonly opensAt: number; readonly closesAt: number } | null {
  if (windowErrorOf(draft, zone) !== null) return null;
  return {
    opensAt: zonedEpoch(draft.opensDate, draft.opensTime, zone)!,
    closesAt: zonedEpoch(draft.closesDate, draft.closesTime, zone)!,
  };
}

/**
 * A wall-clock date and time **in the school's timezone**, as an instant.
 *
 * The teacher types "Tuesday at 09:00" and means nine o'clock where her children are, not nine
 * o'clock where her laptop thinks it is — a distinction with teeth the week a school in
 * Asia/Riyadh is administered from a browser left on UTC. `Intl` is the only timezone database
 * a browser has, so the offset is *measured*: the naive instant is formatted in the zone, read
 * back, and the difference applied. Twice, because applying an offset can cross a DST boundary
 * into a different one, and the second pass lands on the right side of it.
 */
export function zonedEpoch(date: string, time: string, zone: string): number | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^\d{2}:\d{2}$/.test(time)) return null;
  const naive = Date.parse(`${date}T${time}:00Z`);
  if (Number.isNaN(naive)) return null;
  const once = naive - offsetAt(naive, zone);
  return naive - offsetAt(once, zone);
}

/** The zone's offset from UTC at an instant, in milliseconds. */
function offsetAt(instant: number, zone: string): number {
  const parts = formatter(zone).formatToParts(new Date(instant));
  const read = (type: string): number => Number(parts.find((part) => part.type === type)?.value ?? '0');
  const asUtc = Date.UTC(
    read('year'),
    read('month') - 1,
    read('day'),
    read('hour') % 24,
    read('minute'),
    read('second'),
  );
  return asUtc - instant;
}

const formatters = new Map<string, Intl.DateTimeFormat>();

function formatter(zone: string): Intl.DateTimeFormat {
  const cached = formatters.get(zone);
  if (cached) return cached;
  // `en-US` and not the active language: this reads digits back out, and a locale with its own
  // numerals would hand `Number` something it answers `NaN` to.
  const made = new Intl.DateTimeFormat('en-US', {
    timeZone: safeZone(zone),
    hour12: false,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
  formatters.set(zone, made);
  return made;
}

/** A zone the browser does not know throws inside `Intl`; UTC is the server's own default. */
function safeZone(zone: string): string {
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: zone });
    return zone;
  } catch {
    return 'UTC';
  }
}

/** An instant back into the two fields a picker holds, in the school's timezone. */
export function draftOfWindow(opensAt: number, closesAt: number, zone: string): WindowDraft {
  const opens = fields(opensAt, zone);
  const closes = fields(closesAt, zone);
  return {
    opensDate: opens.date,
    opensTime: opens.time,
    closesDate: closes.date,
    closesTime: closes.time,
  };
}

function fields(instant: number, zone: string): { readonly date: string; readonly time: string } {
  const parts = formatter(zone).formatToParts(new Date(instant));
  const read = (type: string): string => parts.find((part) => part.type === type)?.value ?? '00';
  const hour = read('hour') === '24' ? '00' : read('hour');
  return { date: `${read('year')}-${read('month')}-${read('day')}`, time: `${hour}:${read('minute')}` };
}

// ---- the distribution and the questions -------------------------------------------------------

/** One bar of the distribution chart: a band, how many children, and how tall to draw it. */
export interface DistributionBar {
  readonly band: Band;
  readonly children: number;
  /** 0–100, of the **tallest** bar — a chart scaled to the roster is flat in a good class. */
  readonly percent: number;
  /** 0–100, of everyone who sat it: what the label under the bar says. */
  readonly share: number;
}

/**
 * The four bands in §7's order, whatever order the server sent and whichever ones it left out.
 *
 * A missing band is drawn as an empty column rather than skipped: four columns that are always
 * the same four, in the same places, is what makes two exams comparable at a glance, and a
 * chart that silently loses its left-hand column reads as a class with no strugglers.
 */
export function distributionBars(distribution: readonly ExamBand[] | undefined): readonly DistributionBar[] {
  const counts = new Map<Band, number>();
  for (const entry of distribution ?? []) {
    const band = bandOf(entry.band);
    if (band) counts.set(band, (counts.get(band) ?? 0) + (entry.children ?? 0));
  }
  const total = [...counts.values()].reduce((sum, count) => sum + count, 0);
  const tallest = Math.max(0, ...counts.values());
  return BANDS.map((band) => {
    const children = counts.get(band) ?? 0;
    return {
      band,
      children,
      percent: tallest === 0 ? 0 : Math.round((children / tallest) * 100),
      share: total === 0 ? 0 : Math.round((children / total) * 100),
    };
  });
}

/** One row of the per-question table, hardest first. */
export interface QuestionRow {
  readonly stopId: string;
  readonly title: string;
  readonly type: string;
  readonly open: boolean;
  readonly answered: number;
  readonly correct: number;
  /** How many of the children who answered it got it wrong, 0–100. */
  readonly missedPercent: number;
  readonly averageStars: number | null;
}

/**
 * The questions, hardest first.
 *
 * The order is the point of the table: a teacher opens it to find the two questions to go over
 * on Sunday morning, and reading thirty rows in paper order to find them is the work the screen
 * is meant to have done. Ties keep the paper's order, so equally-missed questions still read in
 * the sequence the children met them.
 */
export function questionRows(questions: readonly ExamQuestion[] | undefined): readonly QuestionRow[] {
  return (questions ?? [])
    .map((question, index) => ({
      index,
      row: {
        stopId: question.stopId ?? '',
        title: question.title ?? '',
        type: question.type ?? '',
        open: question.open === true,
        answered: question.answered ?? 0,
        correct: question.correct ?? 0,
        missedPercent: question.missedPercent ?? 0,
        averageStars: question.averageStars ?? null,
      } satisfies QuestionRow,
    }))
    .sort((a, b) => b.row.missedPercent - a.row.missedPercent || a.index - b.index)
    .map((entry) => entry.row);
}

// ---- the per-child table -------------------------------------------------------------------------

export interface ChildRow {
  readonly childId: string;
  readonly name: string;
  readonly state: string;
  readonly score: number | null;
  readonly percent: number | null;
  readonly band: Band | null;
  readonly secondsTaken: number | null;
  readonly submittedAt: number | null;
  readonly needsMarking: number;
  readonly reopened: boolean;
  readonly canReopen: boolean;
}

/**
 * Everyone on the roster, those who sat it first and the absentees after.
 *
 * `children` and `absentees` are two lists on the wire and one table on screen: a teacher
 * looking for "who still owes me this exam" wants them in the same column as everybody else,
 * with the state saying which is which. An absentee already in `children` is not repeated.
 */
export function childRows(results: ExamResults): readonly ChildRow[] {
  const seen = new Set<string>();
  const rows: ChildRow[] = [];
  for (const child of [...(results.children ?? []), ...(results.absentees ?? [])]) {
    const childId = child.childId ?? '';
    if (!childId || seen.has(childId)) continue;
    seen.add(childId);
    rows.push({
      childId,
      name: child.name ?? '',
      state: child.state ?? 'absent',
      score: child.score ?? null,
      percent: child.percent ?? null,
      band: bandOf(child.band),
      secondsTaken: child.secondsTaken ?? null,
      submittedAt: child.submittedAt ?? null,
      needsMarking: child.needsMarking ?? 0,
      reopened: child.reopened === true,
      canReopen: canReopen(child),
    });
  }
  return rows;
}

/**
 * An instant as a teacher reads it: her language, the school's clock.
 *
 * Every date on these screens goes through here rather than through a pipe, because the pipe
 * would render it in the browser's zone and an exam that opens at 09:00 in Riyadh would appear
 * to open at 06:00 to the same teacher on holiday in London.
 */
export function zonedText(
  instant: number | null | undefined,
  zone: string,
  lang: string,
  options: Intl.DateTimeFormatOptions,
): string {
  if (instant === null || instant === undefined || instant <= 0) return '—';
  return new Intl.DateTimeFormat(lang, { ...options, timeZone: safeZone(zone) }).format(new Date(instant));
}

/** `1 542` seconds as `25:42` — a time taken, not a duration anybody should have to divide. */
export function minutesTaken(seconds: number | null): string {
  if (seconds === null || seconds < 0) return '—';
  const minutes = Math.floor(seconds / 60);
  return `${minutes}:${String(seconds % 60).padStart(2, '0')}`;
}
