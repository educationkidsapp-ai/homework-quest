import { describe, expect, it } from 'vitest';
import { DAYS, WEEK } from './week.fixture';
import {
  areSiblings,
  daysOf,
  dropTargetsFor,
  groupByGrade,
  isMovable,
  rowsOf,
  siblingsOf,
  statusOf,
  withCopiedLesson,
  withMovedLesson,
} from './week.models';

const rows = rowsOf(WEEK);
const oneA = rows[0]!;
const oneB = rows[1]!;
const threeA = rows[2]!;

describe('the week grid', () => {
  it('takes its columns from the server, chronologically — Arabic does not reverse them', () => {
    // The layout is logical (`dir="rtl"` mirrors the grid's own columns), so reversing the data
    // as well would put the first school day back on the left.
    expect(daysOf(WEEK)).toEqual([...DAYS]);
    expect(daysOf({ ...WEEK, days: [...DAYS].reverse() })).toEqual([...DAYS]);
  });

  it('gives every row one cell per day, in day order, even when the response is short', () => {
    for (const row of rows) expect(row.cells.map((cell) => cell.date)).toEqual([...DAYS]);

    const short = rowsOf({ ...WEEK, rows: [{ ...WEEK.rows![1]!, cells: [{ date: DAYS[0] }] }] });
    expect(short[0]!.cells).toHaveLength(DAYS.length);
    expect(short[0]!.cells.every((cell) => cell.lesson === null)).toBe(true);
  });

  it('reads the four statuses off the cell, and only lets the unpublished ones move', () => {
    expect(oneA.cells[0]!.status).toBe('draft');
    expect(oneA.cells[1]!.status).toBe('none');
    expect(threeA.cells[2]!.status).toBe('published');

    expect(oneA.cells[0]!.movable).toBe(true);
    expect(threeA.cells[2]!.movable).toBe(false);
    expect(isMovable(null)).toBe(false);
    // A status the server grows later is a lesson that exists, so it is drawn rather than dropped.
    expect(statusOf({ id: 'l-9', status: 'paused' })).toBe('draft');
  });

  it('groups the rows by grade, lowest first, classes in name order', () => {
    const groups = groupByGrade(rows);
    expect(groups.map((group) => group.grade)).toEqual([1, 3]);
    expect(groups[0]!.rows.map((row) => row.className)).toEqual(['1A', '1B']);
  });
});

describe('siblings', () => {
  it('is another class of the same grade, curriculum and subject', () => {
    expect(areSiblings(oneA, oneB)).toBe(true);
    expect(areSiblings(oneA, threeA)).toBe(false);
    expect(areSiblings(oneA, oneA)).toBe(false);
  });

  it('is not a class of the same grade on another curriculum — the server refuses that copy', () => {
    expect(areSiblings(oneA, { ...oneB, curriculum: 'american' })).toBe(false);
    expect(areSiblings(oneA, { ...oneB, subject: 'english' })).toBe(false);
  });

  it('offers a dragged card its own row and its siblings, and nothing else', () => {
    expect(siblingsOf(rows, oneA).map((row) => row.classId)).toEqual(['c-1b']);
    expect([...dropTargetsFor(rows, 'c-1a')]).toEqual(['c-1a', 'c-1b']);
    expect([...dropTargetsFor(rows, 'c-3a')]).toEqual(['c-3a']);
    expect([...dropTargetsFor(rows, 'nobody')]).toEqual([]);
  });
});

describe('the optimistic move', () => {
  it('takes the card out of one day and puts it in another of the same row', () => {
    const moved = withMovedLesson(rows, 'c-1a', DAYS[0], DAYS[3]);
    expect(moved[0]!.cells[0]!.lesson).toBeNull();
    expect(moved[0]!.cells[0]!.status).toBe('none');
    expect(moved[0]!.cells[3]!.lesson?.title).toBe('Fractions');
    // Nothing else moved.
    expect(moved[1]).toEqual(rows[1]);
    expect(moved[2]!.cells[2]!.lesson?.id).toBe('l-2');
  });

  it('is its own inverse, which is what the Undo strip offers', () => {
    const there = withMovedLesson(rows, 'c-1a', DAYS[0], DAYS[3]);
    const back = withMovedLesson(there, 'c-1a', DAYS[3], DAYS[0]);
    expect(back).toEqual(rows);
  });

  it('refuses a day that is already taken, and a move to the day it is on', () => {
    const occupied = withMovedLesson(rows, 'c-3a', DAYS[2], DAYS[2]);
    expect(occupied).toBe(rows);

    const taken = withMovedLesson(withMovedLesson(rows, 'c-1a', DAYS[0], DAYS[1]), 'c-1a', DAYS[1], DAYS[1]);
    expect(taken[0]!.cells[1]!.lesson?.id).toBe('l-1');
  });
});

describe('the optimistic copy', () => {
  it('drops a ready copy into the sibling row on the source lesson’s own day', () => {
    const copied = withCopiedLesson(rows, 'c-1b', DAYS[0], oneA.cells[0]!.lesson!, 'pending:c-1b:x');

    const cell = copied[1]!.cells[0]!;
    expect(cell.lesson?.title).toBe('Fractions');
    expect(cell.lesson?.id).toBe('pending:c-1b:x');
    // A copy is a second lesson in another class: nobody has played it, and it is not published.
    expect(cell.lesson?.playedCount).toBe(0);
    expect(cell.status).toBe('ready');
    // The source is untouched — a copy is not a move.
    expect(copied[0]!.cells[0]!.lesson?.id).toBe('l-1');
  });

  it('leaves an occupied cell alone', () => {
    const copied = withCopiedLesson(rows, 'c-3a', DAYS[2], oneA.cells[0]!.lesson!, 'pending:x');
    expect(copied[2]!.cells[2]!.lesson?.id).toBe('l-2');
  });
});
