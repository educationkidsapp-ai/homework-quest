import { describe, expect, it } from 'vitest';
import {
  calendarCells,
  cardsOf,
  groupCardsByGrade,
  monthParam,
  rosterRows,
  shiftMonth,
} from './classes.models';

const CARD = {
  classId: 'c-1a',
  className: '1A',
  curriculum: 'british',
  grade: 1,
  subject: 'math',
  childrenCount: 18,
};

describe('My classes cards', () => {
  it("reads today's four states off the card", () => {
    const [none, draft, ready, published] = cardsOf([
      { ...CARD },
      { ...CARD, classId: 'c-2', todayLessonId: 'l-1', todayStatus: 'draft' },
      { ...CARD, classId: 'c-3', todayLessonId: 'l-2', todayStatus: 'ready' },
      { ...CARD, classId: 'c-4', todayLessonId: 'l-3', todayStatus: 'published', playedToday: 12 },
    ]);

    expect(none?.status).toBe('none');
    expect(none?.todayLessonId).toBeNull();
    expect(draft?.status).toBe('draft');
    expect(ready?.status).toBe('ready');
    expect(published?.status).toBe('published');
    expect(published?.playedToday).toBe(12);
  });

  /**
   * Only a published lesson has been played, so only a published card offers "12 of 18 played".
   * A draft drawn as "0 of 18 played" reads as a class that ignored her, not as one she has not
   * sent yet — the difference between a teacher chasing children and a teacher pressing Publish.
   */
  it('offers the played count only once the lesson is published', () => {
    const [draft, published] = cardsOf([
      { ...CARD, todayLessonId: 'l-1', todayStatus: 'draft', playedToday: 0 },
      { ...CARD, classId: 'c-2', todayLessonId: 'l-2', todayStatus: 'published', playedToday: 3 },
    ]);

    expect(draft?.showsPlayed).toBe(false);
    expect(published?.showsPlayed).toBe(true);
  });

  /** A status word this build has never heard of is a draft, never a published lesson. */
  it('reads an unknown status as a draft rather than as published', () => {
    const [card] = cardsOf([{ ...CARD, todayLessonId: 'l-1', todayStatus: 'archived' }]);

    expect(card?.status).toBe('draft');
    expect(card?.showsPlayed).toBe(false);
  });

  it('survives a card with nothing on it', () => {
    const [card] = cardsOf([{}]);

    expect(card).toMatchObject({ classId: '', className: '', childrenCount: 0, status: 'none' });
  });

  it('groups by grade, then class name, then subject', () => {
    const groups = groupCardsByGrade(
      cardsOf([
        { ...CARD, classId: 'c-3', className: '3A', grade: 3 },
        { ...CARD, classId: 'c-1b', className: '1B' },
        { ...CARD, classId: 'c-1a-en', className: '1A', subject: 'english' },
        { ...CARD, classId: 'c-1a' },
      ]),
    );

    expect(groups.map((group) => group.grade)).toEqual([1, 3]);
    expect(groups[0]?.items.map((card) => card.classId)).toEqual(['c-1a-en', 'c-1a', 'c-1b']);
  });
});

describe('the class calendar', () => {
  const MARCH = {
    classId: 'c-1a',
    year: 2026,
    month: 3,
    gaps: 1,
    days: [
      // 1 March 2026 is a Sunday: the first school day of the Gulf week.
      {
        date: '2026-03-01',
        schoolDay: true,
        lessonId: 'l-1',
        status: 'published',
        title: 'Sorting shapes',
        type: 'homework',
        playedCount: 7,
      },
      { date: '2026-03-02', schoolDay: true, gap: true },
      { date: '2026-03-06', schoolDay: false },
    ],
  };

  it('lays the month out as six full weeks, Sunday first', () => {
    const cells = calendarCells(MARCH);

    expect(cells).toHaveLength(42);
    expect(cells[0]?.iso).toBe('2026-03-01');
    expect(cells.filter((cell) => cell.inMonth)).toHaveLength(31);
  });

  it('carries the lesson, its status and its played count onto the day', () => {
    const cell = calendarCells(MARCH)[0];

    expect(cell).toMatchObject({
      lessonId: 'l-1',
      status: 'published',
      title: 'Sorting shapes',
      type: 'homework',
      playedCount: 7,
      schoolDay: true,
      gap: false,
    });
  });

  /**
   * `schoolDay` and `gap` are the server's answers, not ours: the school week is an Admin
   * setting, and a gap is only a gap once the day has arrived. Re-deriving either here would
   * put a `+` on a Friday in a school that teaches Monday to Friday.
   */
  it('takes school days and gaps from the response rather than deriving them', () => {
    const cells = calendarCells(MARCH);
    const friday = cells.find((cell) => cell.iso === '2026-03-06');
    const gap = cells.find((cell) => cell.iso === '2026-03-02');

    expect(friday?.schoolDay).toBe(false);
    expect(gap).toMatchObject({ schoolDay: true, gap: true, lessonId: null, status: 'none' });
  });

  it('draws the neighbouring months inert', () => {
    const outside = calendarCells(MARCH).filter((cell) => !cell.inMonth);

    expect(outside.length).toBeGreaterThan(0);
    expect(outside.every((cell) => !cell.schoolDay && !cell.gap && cell.lessonId === null)).toBe(true);
  });

  it('draws nothing at all until the month is known', () => {
    expect(calendarCells({})).toEqual([]);
    expect(calendarCells(null)).toEqual([]);
  });

  it('asks for the month as yyyy-MM and walks across the year boundary', () => {
    expect(monthParam(2026, 3)).toBe('2026-03');
    expect(monthParam(2026, 12)).toBe('2026-12');
    expect(shiftMonth(2026, 12, 1)).toEqual({ year: 2027, month: 1 });
    expect(shiftMonth(2026, 1, -1)).toEqual({ year: 2025, month: 12 });
  });
});

describe('the roster', () => {
  const STUDENT = {
    childId: 'ch-1',
    classId: 'c-1a',
    name: 'Amina',
    starsThisWeek: 9,
    levelReached: 2,
    lastPlayed: 1_772_000_000_000,
    weakSkills: [{ skillId: 's-1', name: 'Place value' }, { name: '' }],
  };

  it('joins progress with the roster and drops skills with no name', () => {
    const [row] = rosterRows(
      [STUDENT],
      [{ id: 'ch-1', classId: 'c-1a', name: 'Amina K.', parentEmail: 'p@x.test', active: true }],
      'c-1a',
    );

    expect(row).toMatchObject({
      childId: 'ch-1',
      name: 'Amina K.',
      starsThisWeek: 9,
      levelReached: 2,
      weakSkills: ['Place value'],
      active: true,
      parentEmail: 'p@x.test',
    });
  });

  /** Without the roster (flag off) the tab still lists the class — it only loses the columns. */
  it('leaves active and parentEmail unknown when the roster was not fetched', () => {
    const [row] = rosterRows([STUDENT], [], 'c-1a');

    expect(row?.active).toBeNull();
    expect(row?.parentEmail).toBeNull();
  });

  it('keeps a child the roster knows and the progress endpoint does not', () => {
    const rows = rosterRows(
      [STUDENT],
      [{ id: 'ch-2', classId: 'c-1a', name: 'Bilal', active: false }],
      'c-1a',
    );

    expect(rows.map((row) => row.name)).toEqual(['Amina', 'Bilal']);
    expect(rows[1]).toMatchObject({ starsThisWeek: 0, lastPlayed: null, active: false });
  });

  it('reads a never-played child as never, not as 1970', () => {
    const [row] = rosterRows([{ ...STUDENT, lastPlayed: 0 }], [], 'c-1a');

    expect(row?.lastPlayed).toBeNull();
  });

  /**
   * The review that sent N2.3 back: 1A's Children tab listed all 96 children of Grade 1 British,
   * because the endpoint was scoped to the course rather than the section. The server is fixed
   * (#70) and this keeps the screen honest whatever it is handed.
   */
  it('keeps only the children of this class', () => {
    const rows = rosterRows(
      [STUDENT, { ...STUDENT, childId: 'ch-9', classId: 'c-1b', name: 'Zayn' }],
      [
        { id: 'ch-1', classId: 'c-1a', name: 'Amina', active: true },
        { id: 'ch-8', classId: 'c-1b', name: 'Yara', active: true },
      ],
      'c-1a',
    );

    expect(rows.map((row) => row.name)).toEqual(['Amina']);
  });

  /** An older server sends no `classId` at all; dropping every row would be the worse failure. */
  it('keeps rows a server sent without a class of their own', () => {
    const rows = rosterRows([{ ...STUDENT, classId: undefined }], [], 'c-1a');

    expect(rows.map((row) => row.name)).toEqual(['Amina']);
  });
});
