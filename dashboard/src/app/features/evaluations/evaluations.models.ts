import type { ChildEvaluationRow, EvaluationBoardRow, EvaluationCounts, EvaluationRound } from '../../api';

/**
 * Feature 33's child evaluation, the part with no Angular in it: what a row's status and state
 * are, what a count card counts, which rating a criterion carries, and whether a draft is
 * complete enough to submit.
 *
 * The teacher's page, the leaders' board and the Admin's rounds all read the same rows
 * (`ChildEvaluationRow`) and the same rounds (`EvaluationRound`), so every sentence about one —
 * submitted means read-only, returned means hers again, missing means nothing submitted — is
 * written once here and asserted once in `evaluations.models.spec.ts`.
 */

/** The five statuses the server computes from the deadline and the review. Never stored. */
export const EVALUATION_STATUSES = ['ON_TIME', 'LATE', 'MISSING', 'RETURNED', 'APPROVED'] as const;
export type EvaluationStatus = (typeof EVALUATION_STATUSES)[number];

/** The workflow column: where the row is, as opposed to whether it was on time. */
export const EVALUATION_STATES = ['DRAFT', 'SUBMITTED', 'RETURNED', 'APPROVED'] as const;
export type EvaluationState = (typeof EVALUATION_STATES)[number];

/** The five count cards, in the order every page draws them. */
export const EVALUATION_COUNTS = ['onTime', 'late', 'missing', 'returned', 'approved'] as const;
export type EvaluationCount = (typeof EVALUATION_COUNTS)[number];

/** The four criteria, in the order the server reads them and the editor draws them. */
export const CRITERIA = ['participation', 'effort', 'behaviour', 'understanding'] as const;
export type Criterion = (typeof CRITERIA)[number];

/**
 * The four-point scale, highest first — the value the staff side writes, and the word key the
 * parent's side is given instead of it (`EvaluationDto.EXCELLENT` … `NEEDS_SUPPORT`).
 *
 * Staff see the number *and* the word: a teacher comparing two children needs to know that Good
 * is above Developing, and the order of four adjectives is not self-evident in either language.
 */
export const RATINGS = [4, 3, 2, 1] as const;
export type Rating = (typeof RATINGS)[number];

/** The word key of a rating, which is the only thing a parent is ever told. */
export const RATING_WORDS: Readonly<Record<Rating, string>> = {
  4: 'excellent',
  3: 'good',
  2: 'developing',
  1: 'needsSupport',
};

/** A row's status, with an absent or unknown value read as "nothing submitted". */
export function statusOf(row: ChildEvaluationRow): EvaluationStatus {
  const status = (row.status ?? '').trim().toUpperCase();
  return (EVALUATION_STATUSES as readonly string[]).includes(status)
    ? (status as EvaluationStatus)
    : 'MISSING';
}

/**
 * A row's workflow state.
 *
 * `state` is what the server sends on the rows a teacher or a leader reads; a row that carries
 * only a status — the expected row for a child nobody has written about yet — is read back
 * through its status, so the two can never disagree about whether she may still write.
 */
export function stateOf(row: ChildEvaluationRow): EvaluationState {
  const state = (row.state ?? '').trim().toUpperCase();
  if ((EVALUATION_STATES as readonly string[]).includes(state)) return state as EvaluationState;
  switch (statusOf(row)) {
    case 'APPROVED':
      return 'APPROVED';
    case 'RETURNED':
      return 'RETURNED';
    case 'ON_TIME':
    case 'LATE':
      return 'SUBMITTED';
    default:
      return 'DRAFT';
  }
}

/** Whether the teacher may still write on this row: a draft, or one a leader sent back. */
export function canEdit(row: ChildEvaluationRow): boolean {
  const state = stateOf(row);
  return state === 'DRAFT' || state === 'RETURNED';
}

/** A leader sent this one back with a comment, and it is hers to fix and submit again. */
export function isReturned(row: ChildEvaluationRow): boolean {
  return stateOf(row) === 'RETURNED';
}

/** Whether a leader may still decide on it: submitted, and nobody has decided yet. */
export function canReview(row: ChildEvaluationRow): boolean {
  return stateOf(row) === 'SUBMITTED' && (row.evaluationId ?? '') !== '';
}

/** The card's number as the server counted it. */
export function countOf(counts: EvaluationCounts | undefined, card: EvaluationCount): number {
  return counts?.[card] ?? 0;
}

/** The rating a row carries for one criterion, or `null` while it carries none. */
export function ratingOf(row: ChildEvaluationRow, criterion: Criterion): Rating | null {
  const value = row[criterion];
  return value === 4 || value === 3 || value === 2 || value === 1 ? value : null;
}

/** What a teacher is writing, before any of it has been saved. */
export interface EvaluationDraft {
  readonly participation: Rating | null;
  readonly effort: Rating | null;
  readonly behaviour: Rating | null;
  readonly understanding: Rating | null;
  readonly comment: string;
  readonly goingWell: string;
  readonly nextStep: string;
}

/** The draft a row opens with: whatever is already on it, and blanks for the rest. */
export function draftOf(row: ChildEvaluationRow): EvaluationDraft {
  return {
    participation: ratingOf(row, 'participation'),
    effort: ratingOf(row, 'effort'),
    behaviour: ratingOf(row, 'behaviour'),
    understanding: ratingOf(row, 'understanding'),
    comment: row.comment ?? '',
    goingWell: row.goingWell ?? '',
    nextStep: row.nextStep ?? '',
  };
}

/**
 * The body of a `PUT` or a `POST …/submit`: only the parts she has written.
 *
 * A blank text is left out rather than sent as `""`, because the server reads an absent field
 * as "leave it alone" and a present one as the new value — and `""` for a comment would make a
 * submitted evaluation that says nothing.
 */
export function requestOf(draft: EvaluationDraft): Record<string, unknown> {
  const body: Record<string, unknown> = {};
  for (const criterion of CRITERIA) {
    const rating = draft[criterion];
    if (rating !== null) body[criterion] = rating;
  }
  for (const field of ['comment', 'goingWell', 'nextStep'] as const) {
    const text = draft[field].trim();
    if (text !== '') body[field] = text;
  }
  return body;
}

/**
 * Whether `POST …/submit` would be accepted: all four criteria and a comment.
 *
 * The same rule the server enforces, said before the request rather than after it — an
 * evaluation a parent may one day read is not a half-filled form, and a 400 is a poor way to
 * learn which of the five fields is empty.
 */
export function submitReady(draft: EvaluationDraft): boolean {
  return CRITERIA.every((criterion) => draft[criterion] !== null) && draft.comment.trim() !== '';
}

/** `(roundId, classId, subject, childId)` is what identifies a row; an evaluation id is absent until she writes. */
export function rowKey(row: ChildEvaluationRow): string {
  return [row.roundId ?? '', row.classId ?? '', row.subject ?? '', row.childId ?? ''].join(':');
}

/** The (class, subject) a run of children belongs to — the heading the teacher's page groups by. */
export function groupKey(row: ChildEvaluationRow): string {
  return `${row.classId ?? ''}:${row.subject ?? ''}`;
}

/** One class and subject she teaches, with the children expected in it. */
export interface EvaluationGroup {
  readonly key: string;
  readonly classId: string;
  readonly className: string;
  readonly subject: string;
  readonly rows: readonly ChildEvaluationRow[];
}

/**
 * Her rows as the page draws them: one section per class and subject, in the order the server
 * sent them, with the children who still need writing about first inside each.
 */
export function groupsOf(rows: readonly ChildEvaluationRow[]): readonly EvaluationGroup[] {
  const groups = new Map<string, ChildEvaluationRow[]>();
  for (const row of rows) {
    const key = groupKey(row);
    const run = groups.get(key);
    if (run === undefined) groups.set(key, [row]);
    else run.push(row);
  }
  return [...groups].map(([key, run]) => ({
    key,
    classId: run[0]?.classId ?? '',
    className: run[0]?.className ?? '',
    subject: run[0]?.subject ?? '',
    rows: [...run].sort((a, b) => doneness(a) - doneness(b)),
  }));
}

/** Missing first, then returned (hers again), then submitted, then approved. */
function doneness(row: ChildEvaluationRow): number {
  switch (stateOf(row)) {
    case 'DRAFT':
      return 0;
    case 'RETURNED':
      return 1;
    case 'SUBMITTED':
      return 2;
    default:
      return 3;
  }
}

/** A teacher's row of the board, with the ones who have not finished first. */
export function sortedBoardRows(rows: readonly EvaluationBoardRow[]): readonly EvaluationBoardRow[] {
  return [...rows].sort((a, b) => (b.counts?.missing ?? 0) - (a.counts?.missing ?? 0));
}

/** The `?round=` as a round id, or `''` for "the round running now" — the one ask that needs none. */
export function roundOf(value: string | null | undefined): string {
  return (value ?? '').trim();
}

/** A round's name in the language being read, falling back to the other rather than to blank. */
export function roundName(round: EvaluationRound | undefined, lang: string): string {
  if (round === undefined) return '';
  const arabic = (round.nameAr ?? '').trim();
  const english = (round.nameEn ?? '').trim();
  return lang.startsWith('ar') ? arabic || english : english || arabic;
}

/** A deadline as the Admin's two fields hold it: a date and a time of day, both in the school's zone. */
export interface DueParts {
  readonly date: string;
  readonly time: string;
}

/**
 * The instant the server sent, split into the date and the time **of the school's own zone**.
 *
 * The round's deadline is stored and answered as an instant, and the Admin writes it as a local
 * date and time in the school's zone (`EvaluationRounds.instant`). Formatting it in the browser's
 * zone would move an Asia/Riyadh 9 pm deadline to the previous day for anybody editing from
 * Europe, so the parts come from `Intl` with the zone named rather than from `Date`'s getters.
 */
export function dueParts(iso: string | undefined, timeZone: string): DueParts {
  if (iso === undefined || iso.trim() === '') return { date: '', time: '' };
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return { date: '', time: '' };
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(at);
  const of = (type: string): string => parts.find((part) => part.type === type)?.value ?? '';
  const date = `${of('year')}-${of('month')}-${of('day')}`;
  return { date, time: `${of('hour')}:${of('minute')}` };
}

/**
 * The `dueAt` the server reads: `yyyy-MM-ddTHH:mm`, a local date and time it resolves in the
 * school's zone. `''` while either half is missing, which is what the form's Save waits for.
 */
export function dueAtOf(parts: DueParts): string {
  const date = parts.date.trim();
  const time = parts.time.trim();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^\d{2}:\d{2}/.test(time)) return '';
  return `${date}T${time.slice(0, 5)}`;
}

const BOM = '﻿';

/**
 * The CSV as the server wrote it, with its byte-order mark put back — feature 17's reason: the
 * client reads the body as text, which drops the BOM, and without it Excel reads Arabic names
 * as the local code page.
 */
export function withBom(csv: string): string {
  return csv.startsWith(BOM) ? csv : `${BOM}${csv}`;
}

/** `child-evaluations-Term 1.csv`, or the round's id when it has no name to use. */
export function csvName(round: EvaluationRound | undefined, lang: string): string {
  const name = roundName(round, lang) || (round?.id ?? 'round');
  return `child-evaluations-${name}.csv`;
}
