import type { TeacherWeek } from '../../api';

/**
 * One teacher's week, shaped exactly as `GET /teacher/week` answers it (N2.1).
 *
 * Sunday 20 September 2026 to Thursday the 24th — the default Gulf school week — with three
 * assignments: 1A and 1B are siblings (Grade 1, British, Math), 3A is not. 1A has a draft on the
 * Sunday and 3A a published lesson on the Tuesday, so both the movable and the immovable case
 * are in the fixture, and 1B's Tuesday is the gap the summary strip names.
 */
export const DAYS = ['2026-09-20', '2026-09-21', '2026-09-22', '2026-09-23', '2026-09-24'] as const;

export const WEEK: TeacherWeek = {
  start: DAYS[0],
  days: [...DAYS],
  rows: [
    {
      classId: 'c-1a',
      className: '1A',
      curriculum: 'british',
      grade: 1,
      subject: 'math',
      cells: [
        {
          date: DAYS[0],
          lesson: {
            id: 'l-1',
            title: 'Fractions',
            status: 'draft',
            type: 'homework',
            playedCount: 0,
            childrenCount: 18,
            version: 0,
          },
        },
        { date: DAYS[1] },
        { date: DAYS[2] },
        { date: DAYS[3] },
        { date: DAYS[4] },
      ],
    },
    {
      classId: 'c-1b',
      className: '1B',
      curriculum: 'british',
      grade: 1,
      subject: 'math',
      cells: DAYS.map((date) => ({ date })),
    },
    {
      classId: 'c-3a',
      className: '3A',
      curriculum: 'british',
      grade: 3,
      subject: 'math',
      cells: [
        { date: DAYS[0] },
        { date: DAYS[1] },
        {
          date: DAYS[2],
          lesson: {
            id: 'l-2',
            title: 'Decimals',
            status: 'published',
            type: 'homework',
            playedCount: 12,
            childrenCount: 18,
            version: 1,
          },
        },
        { date: DAYS[3] },
        { date: DAYS[4] },
      ],
    },
  ],
  summary: {
    gaps: [{ classId: 'c-1b', className: '1B', date: DAYS[2] }],
    examsClosing: [],
    marksWaiting: 0,
  },
};
