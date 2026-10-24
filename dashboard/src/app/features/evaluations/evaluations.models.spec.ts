import { describe, expect, it } from 'vitest';
import type { ChildEvaluationRow } from '../../api';
import {
  CRITERIA,
  EVALUATION_COUNTS,
  RATING_WORDS,
  canEdit,
  canReview,
  countOf,
  csvName,
  draftOf,
  dueAtOf,
  dueParts,
  groupsOf,
  isReturned,
  ratingOf,
  requestOf,
  roundName,
  roundOf,
  rowKey,
  sortedBoardRows,
  stateOf,
  statusOf,
  submitReady,
  withBom,
} from './evaluations.models';

function row(extra: Partial<ChildEvaluationRow> = {}): ChildEvaluationRow {
  return { roundId: 'r-1', classId: 'c-1', subject: 'math', childId: 'ch-1', status: 'MISSING', ...extra };
}

describe('feature 33 — the evaluation rows', () => {
  it('reads an absent or unknown status as nothing submitted', () => {
    expect(statusOf(row({ status: undefined }))).toBe('MISSING');
    expect(statusOf(row({ status: 'something-else' }))).toBe('MISSING');
    expect(statusOf(row({ status: 'on_time' }))).toBe('ON_TIME');
  });

  /** A row that carries only a status still has to say whether she may write on it. */
  it('derives the state from the status when the server sent none', () => {
    expect(stateOf(row({ status: 'MISSING' }))).toBe('DRAFT');
    expect(stateOf(row({ status: 'ON_TIME' }))).toBe('SUBMITTED');
    expect(stateOf(row({ status: 'LATE' }))).toBe('SUBMITTED');
    expect(stateOf(row({ status: 'RETURNED' }))).toBe('RETURNED');
    expect(stateOf(row({ status: 'APPROVED' }))).toBe('APPROVED');
    // The column wins where there is one: a late draft is still a draft.
    expect(stateOf(row({ status: 'LATE', state: 'DRAFT' }))).toBe('DRAFT');
  });

  it('lets her write on a draft and on a returned row, and on nothing else', () => {
    expect(canEdit(row({ state: 'DRAFT' }))).toBe(true);
    expect(canEdit(row({ state: 'RETURNED' }))).toBe(true);
    expect(canEdit(row({ state: 'SUBMITTED' }))).toBe(false);
    expect(canEdit(row({ state: 'APPROVED' }))).toBe(false);
    expect(isReturned(row({ state: 'RETURNED' }))).toBe(true);
  });

  /** A leader decides on a submitted row once, and there is nothing to decide without an id. */
  it('offers a decision only on a submitted row that has an id', () => {
    expect(canReview(row({ state: 'SUBMITTED', evaluationId: 'e-1' }))).toBe(true);
    expect(canReview(row({ state: 'SUBMITTED' }))).toBe(false);
    expect(canReview(row({ state: 'DRAFT', evaluationId: 'e-1' }))).toBe(false);
    expect(canReview(row({ state: 'APPROVED', evaluationId: 'e-1' }))).toBe(false);
  });

  it('reads a rating only inside the four-point scale', () => {
    expect(ratingOf(row({ participation: 4 }), 'participation')).toBe(4);
    expect(ratingOf(row({ effort: 1 }), 'effort')).toBe(1);
    expect(ratingOf(row({ behaviour: 0 }), 'behaviour')).toBeNull();
    expect(ratingOf(row({ understanding: 5 }), 'understanding')).toBeNull();
    expect(ratingOf(row(), 'participation')).toBeNull();
  });

  it('names the four points of the scale, highest first', () => {
    expect([RATING_WORDS[4], RATING_WORDS[3], RATING_WORDS[2], RATING_WORDS[1]]).toEqual([
      'excellent',
      'good',
      'developing',
      'needsSupport',
    ]);
  });

  it('counts a card as the server counted it, and nothing as zero', () => {
    expect(EVALUATION_COUNTS).toEqual(['onTime', 'late', 'missing', 'returned', 'approved']);
    expect(countOf({ missing: 3 }, 'missing')).toBe(3);
    expect(countOf(undefined, 'approved')).toBe(0);
  });

  /** The same rule the server enforces, said before the request instead of after the 400. */
  it('submits only with all four criteria and a comment', () => {
    const full = draftOf(
      row({ participation: 4, effort: 3, behaviour: 2, understanding: 1, comment: 'Reads well' }),
    );
    expect(submitReady(full)).toBe(true);
    expect(submitReady({ ...full, comment: '   ' })).toBe(false);
    expect(submitReady({ ...full, behaviour: null })).toBe(false);
  });

  /** A blank text is left out, not sent as `""`: the server reads an absent field as "leave it". */
  it('sends only what she has written', () => {
    const draft = draftOf(row({ participation: 3, comment: '  Settling in  ' }));
    expect(requestOf(draft)).toEqual({ participation: 3, comment: 'Settling in' });
  });

  it('keys a row by its round, class, subject and child', () => {
    expect(rowKey(row())).toBe('r-1:c-1:math:ch-1');
  });

  /** One section per class and subject, the children who still need writing about first. */
  it('groups her rows by class and subject, unfinished children first', () => {
    const groups = groupsOf([
      row({ childId: 'ch-1', childName: 'Amal', className: '1A', state: 'APPROVED' }),
      row({ childId: 'ch-2', childName: 'Bader', className: '1A', state: 'DRAFT' }),
      row({ childId: 'ch-3', childName: 'Carla', className: '2A', classId: 'c-2', subject: 'english' }),
    ]);

    expect(groups.map((group) => group.key)).toEqual(['c-1:math', 'c-2:english']);
    expect(groups[0]!.rows.map((child) => child.childName)).toEqual(['Bader', 'Amal']);
    expect(groups[1]!.className).toBe('2A');
  });

  it('puts the teacher with the most missing evaluations first', () => {
    const rows = sortedBoardRows([
      { teacherId: 't-1', counts: { missing: 0 } },
      { teacherId: 't-2', counts: { missing: 4 } },
    ]);
    expect(rows.map((board) => board.teacherId)).toEqual(['t-2', 't-1']);
  });

  it('reads a blank round param as "the round running now"', () => {
    expect(roundOf(null)).toBe('');
    expect(roundOf('  ')).toBe('');
    expect(roundOf(' r-7 ')).toBe('r-7');
  });

  /** A round is named in both languages, and a missing one falls back rather than reading blank. */
  it('names a round in the language being read', () => {
    const round = { id: 'r-1', nameEn: 'Term 1 — mid-term', nameAr: 'الفصل الأول — منتصف' };
    expect(roundName(round, 'en')).toBe('Term 1 — mid-term');
    expect(roundName(round, 'ar')).toBe('الفصل الأول — منتصف');
    expect(roundName({ id: 'r-1', nameEn: 'Term 1' }, 'ar')).toBe('Term 1');
    expect(roundName(undefined, 'en')).toBe('');
  });

  /**
   * The deadline is an instant and the Admin writes it as a local date and time **in the
   * school's zone** — formatting it in the browser's would move a 9 pm Riyadh deadline a day.
   */
  it('splits the deadline into the school zone’s own date and time, and puts it back', () => {
    const parts = dueParts('2026-11-05T18:00:00Z', 'Asia/Riyadh');
    expect(parts).toEqual({ date: '2026-11-05', time: '21:00' });
    expect(dueAtOf(parts)).toBe('2026-11-05T21:00');
    // Half a deadline is not a deadline: the form's Save waits for both halves.
    expect(dueAtOf({ date: '2026-11-05', time: '' })).toBe('');
    expect(dueAtOf({ date: '', time: '21:00' })).toBe('');
    expect(dueParts(undefined, 'Asia/Riyadh')).toEqual({ date: '', time: '' });
  });

  it('puts the byte-order mark back on the CSV, once', () => {
    expect(withBom('a,b\n')).toBe('﻿a,b\n');
    expect(withBom('﻿a,b\n')).toBe('﻿a,b\n');
    expect(csvName({ id: 'r-1', nameEn: 'Term 1' }, 'en')).toBe('child-evaluations-Term 1.csv');
    expect(csvName({ id: 'r-1' }, 'en')).toBe('child-evaluations-r-1.csv');
  });

  it('reads the four criteria in the order the server reads them', () => {
    expect(CRITERIA).toEqual(['participation', 'effort', 'behaviour', 'understanding']);
  });
});
