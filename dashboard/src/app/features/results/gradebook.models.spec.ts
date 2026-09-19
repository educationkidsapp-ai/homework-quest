import { describe, expect, it } from 'vitest';
import type { Gradebook } from '../../api';
import {
  DEFAULT_WEEKS,
  columnsOf,
  defaultRange,
  needingMarking,
  normalise,
  rowsOf,
  trendGlyph,
} from './gradebook.models';

const BOOK: Gradebook = {
  classId: 'c-1a',
  className: '1A British',
  from: '2026-07-20',
  to: '2026-09-14',
  needsMarking: 1,
  lessons: [
    {
      lessonId: 'l-1',
      title: 'Counting to ten',
      date: '2026-09-07',
      type: 'homework',
      released: true,
      classAverage: 71,
      needsMarking: 0,
    },
    {
      lessonId: 'l-2',
      title: 'Tens and ones',
      date: '2026-09-14',
      type: 'homework',
      released: false,
      classAverage: 64,
      needsMarking: 1,
    },
  ],
  children: [
    {
      childId: 'ch-1',
      name: 'Amina Al Amin',
      average: 85,
      band: 'exceeding',
      trend: 'up',
      cells: [
        { lessonId: 'l-1', attempted: true, autoScore: 90, score: 90, band: 'exceeding' },
        { lessonId: 'l-2', attempted: true, autoScore: 80, score: 80, band: 'secure' },
      ],
    },
    {
      childId: 'ch-2',
      name: 'Zain Lutfi',
      average: 56,
      band: 'developing',
      trend: 'down',
      // Deliberately out of order: the grid lines cells up by lesson, never by position.
      cells: [
        {
          lessonId: 'l-2',
          attempted: true,
          autoScore: 52,
          score: 52,
          band: 'developing',
          needsMarking: true,
        },
        { lessonId: 'l-1', attempted: true, autoScore: 52, teacherScore: 60, score: 60, band: 'secure' },
      ],
    },
    { childId: 'ch-3', name: 'Rasha Hensley', cells: [] },
  ],
};

describe('the gradebook grid', () => {
  it('reads a column per lesson, with the class average the server computed', () => {
    expect(columnsOf(BOOK)).toEqual([
      {
        lessonId: 'l-1',
        title: 'Counting to ten',
        date: '2026-09-07',
        type: 'homework',
        released: true,
        classAverage: 71,
        needsMarking: 0,
      },
      {
        lessonId: 'l-2',
        title: 'Tens and ones',
        date: '2026-09-14',
        type: 'homework',
        released: false,
        classAverage: 64,
        needsMarking: 1,
      },
    ]);
  });

  it('lines every child’s cells up with the columns, by lesson and not by position', () => {
    const [, zain] = rowsOf(BOOK);

    expect(zain!.cells.map((cell) => cell.lessonId)).toEqual(['l-1', 'l-2']);
    expect(zain!.cells[0]!.score).toBe(60);
    expect(zain!.cells[1]!.score).toBe(52);
  });

  it('gives a child with no cells one empty square per column', () => {
    const rasha = rowsOf(BOOK)[2]!;

    expect(rasha.cells).toHaveLength(2);
    expect(rasha.cells.every((cell) => !cell.attempted && cell.score === null)).toBe(true);
    expect(rasha.band).toBeNull();
  });

  it('calls a cell overridden only when the teacher moved it off the automatic score', () => {
    const [amina, zain] = rowsOf(BOOK);

    expect(zain!.cells[0]).toMatchObject({ overridden: true, autoScore: 52, teacherScore: 60 });
    expect(amina!.cells[0]!.overridden).toBe(false);
  });

  it('carries the band, the average and the trend of each row', () => {
    const [amina] = rowsOf(BOOK);

    expect(amina).toMatchObject({ average: 85, band: 'exceeding', trend: 'up' });
    expect(trendGlyph(amina!.trend)).toBe('↑');
    expect(trendGlyph(null)).toBe('');
  });

  it('filters to the rows with a mark still pending', () => {
    expect(needingMarking(rowsOf(BOOK)).map((row) => row.name)).toEqual(['Zain Lutfi']);
  });
});

describe('the range', () => {
  it('opens on the eight weeks behind today', () => {
    const range = defaultRange(new Date('2026-09-14T09:00:00Z'));

    expect(range.to).toBe('2026-09-14');
    expect(range.from).toBe('2026-07-20');
    const days = (Date.parse(range.to) - Date.parse(range.from)) / 86_400_000;
    expect(days).toBe(DEFAULT_WEEKS * 7);
  });

  it('turns a range round rather than asking the server for an empty one', () => {
    expect(normalise({ from: '2026-09-14', to: '2026-07-20' })).toEqual({
      from: '2026-07-20',
      to: '2026-09-14',
    });
    expect(normalise({ from: '', to: '2026-09-14' })).toBeNull();
  });
});
