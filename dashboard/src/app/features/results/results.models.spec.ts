import { describe, expect, it } from 'vitest';
import type { LessonResults } from '../../api';
import {
  draftOf,
  hasChanges,
  marks,
  needingMarking,
  parseScore,
  request,
  resultRows,
  summaryOf,
  weakestStopIds,
  type MarkDraft,
} from './results.models';

/** Two single-answer stops and a retell — the shape `seed/attempts.csv` publishes. */
const RESULTS: LessonResults = {
  lessonId: 'l-1',
  classId: 'c-1a',
  className: '1A British',
  title: 'Counting to ten',
  date: '2026-09-14',
  released: true,
  releasedAt: 1_757_000_000_000,
  classAverage: 71,
  played: 2,
  needsMarking: 1,
  stops: [
    { stopId: 's1', title: 'How many carrots?', type: 'choice', level: 1, open: false },
    { stopId: 's2', title: 'How many peas?', type: 'choice', level: 1, open: false },
    { stopId: 's3', title: 'Tell the story back', type: 'retell', level: 1, open: true },
  ],
  children: [
    {
      childId: 'ch-1',
      name: 'Amina Al Amin',
      attempted: true,
      levelReached: 1,
      autoScore: 90,
      score: 90,
      band: 'exceeding',
      starsEarned: 8,
      starsTotal: 9,
      completion: 100,
      needsMarking: 0,
      stops: [
        { stopId: 's1', attempted: true, stars: 3, score: 100, attempts: 1, needsMarking: false },
        { stopId: 's2', attempted: true, stars: 3, score: 100, attempts: 1, needsMarking: false },
        {
          stopId: 's3',
          attempted: true,
          stars: 2,
          score: 40,
          attempts: 1,
          markStars: 2,
          markComment: 'Clear retelling',
          needsMarking: false,
          workUrl: 'https://api.test/media/child/m-9',
        },
      ],
    },
    {
      childId: 'ch-2',
      name: 'Zain Lutfi',
      attempted: true,
      levelReached: 1,
      autoScore: 52,
      teacherScore: 60,
      score: 60,
      band: 'secure',
      starsEarned: 6,
      starsTotal: 9,
      completion: 100,
      needsMarking: 1,
      comment: 'Good effort',
      stops: [
        { stopId: 's1', attempted: true, stars: 3, score: 100, attempts: 1, needsMarking: false },
        { stopId: 's2', attempted: true, stars: 1, score: 0, attempts: 2, needsMarking: false },
        { stopId: 's3', attempted: true, attempts: 1, needsMarking: true },
      ],
    },
    { childId: 'ch-3', name: 'Rasha Hensley', attempted: false, stops: [] },
  ],
};

describe('result rows', () => {
  it('gives every child a cell for every stop, played or not', () => {
    const rows = resultRows(RESULTS);

    expect(rows).toHaveLength(3);
    expect(rows.map((row) => row.stops.length)).toEqual([3, 3, 3]);
    // The child who never opened the lesson still has three columns, all empty.
    expect(rows[2]!.stops.every((stop) => !stop.attempted)).toBe(true);
    expect(rows[2]!.score).toBeNull();
  });

  it('keeps the automatic score beside the teacher’s override', () => {
    const zain = resultRows(RESULTS)[1]!;

    expect(zain.autoScore).toBe(52);
    expect(zain.teacherScore).toBe(60);
    expect(zain.score).toBe(60);
  });

  it('carries the open stop’s work link and its pending mark', () => {
    const [amina, zain] = resultRows(RESULTS);

    expect(amina!.stops[2]).toMatchObject({ open: true, markStars: 2, needsMarking: false });
    expect(amina!.stops[2]!.workUrl).toBe('https://api.test/media/child/m-9');
    expect(zain!.stops[2]!.needsMarking).toBe(true);
  });
});

describe('the weakest stops', () => {
  it('names the stop this class did worst on, relative to the others', () => {
    // s1 averages 100, s2 50, s3 40 — mean 63, so s2 and s3 are both ten or more below it.
    const weak = weakestStopIds(resultRows(RESULTS));

    expect([...weak].sort()).toEqual(['s2', 's3']);
    expect(weak.has('s1')).toBe(false);
  });

  it('names none when the stops are level with each other', () => {
    const level: LessonResults = {
      stops: RESULTS.stops,
      children: [
        {
          childId: 'ch-1',
          name: 'A',
          attempted: true,
          stops: [
            { stopId: 's1', attempted: true, score: 90 },
            { stopId: 's2', attempted: true, score: 92 },
            { stopId: 's3', attempted: true, score: 88 },
          ],
        },
      ],
    };

    expect(weakestStopIds(resultRows(level)).size).toBe(0);
  });

  it('marks at most two, however many are below the line', () => {
    const wide: LessonResults = {
      stops: [
        ...(RESULTS.stops ?? []),
        { stopId: 's4', title: 'Fourth', type: 'choice', level: 1, open: false },
      ],
      children: [
        {
          childId: 'ch-1',
          name: 'A',
          attempted: true,
          stops: [
            { stopId: 's1', attempted: true, score: 100 },
            { stopId: 's2', attempted: true, score: 10 },
            { stopId: 's3', attempted: true, score: 20 },
            { stopId: 's4', attempted: true, score: 30 },
          ],
        },
      ],
    };

    expect([...weakestStopIds(resultRows(wide))].sort()).toEqual(['s2', 's3']);
  });
});

describe('the marking payload', () => {
  const zain = resultRows(RESULTS)[1]!;

  it('opens the panel on exactly what the server holds', () => {
    expect(draftOf(zain)).toEqual({
      stops: { s3: { stars: null, comment: '' } },
      score: 60,
      comment: 'Good effort',
    });
  });

  it('sends only what changed — a comment does not wipe the stars', () => {
    const before = draftOf(zain);
    const after: MarkDraft = { ...before, comment: 'Much better this week' };

    expect(marks('l-1', 'ch-2', after, before)).toEqual([
      { lessonId: 'l-1', childId: 'ch-2', score: 60, comment: 'Much better this week' },
    ]);
  });

  it('puts a stop mark on the stop and the override on the lesson', () => {
    const before = draftOf(zain);
    const after: MarkDraft = {
      stops: { s3: { stars: 2, comment: 'Retold two of the three parts' } },
      score: null,
      comment: '',
    };

    expect(marks('l-1', 'ch-2', after, before)).toEqual([
      {
        lessonId: 'l-1',
        childId: 'ch-2',
        stopId: 's3',
        stars: 2,
        comment: 'Retold two of the three parts',
      },
      // Everything null: §7's delete, which is what clearing an override has to be.
      { lessonId: 'l-1', childId: 'ch-2', score: undefined, comment: undefined },
    ]);
  });

  it('sends nothing at all when nothing was touched', () => {
    const before = draftOf(zain);

    expect(marks('l-1', 'ch-2', before, { ...before })).toEqual([]);
    expect(hasChanges(before, { ...before })).toBe(false);
  });

  it('undoes by sending the same difference the other way round', () => {
    const before = draftOf(zain);
    const after: MarkDraft = { stops: { s3: { stars: 3, comment: '' } }, score: 80, comment: 'Well done' };

    const forward = marks('l-1', 'ch-2', after, before);
    const back = marks('l-1', 'ch-2', before, after);

    expect(forward).toHaveLength(2);
    expect(back).toEqual([
      { lessonId: 'l-1', childId: 'ch-2', stopId: 's3', stars: undefined, comment: undefined },
      { lessonId: 'l-1', childId: 'ch-2', score: 60, comment: 'Good effort' },
    ]);
    expect(request(back).marks).toHaveLength(2);
  });

  it('ignores whitespace that only looks like a change', () => {
    const before = draftOf(zain);

    expect(marks('l-1', 'ch-2', { ...before, comment: '  Good effort  ' }, before)).toEqual([]);
  });
});

describe('the override field', () => {
  it('takes a number, clamps it to 0–100 and reads an empty box as no override', () => {
    expect(parseScore('72')).toBe(72);
    expect(parseScore(' 101 ')).toBe(100);
    expect(parseScore('-4')).toBe(0);
    expect(parseScore('72.6')).toBe(73);
    expect(parseScore('')).toBeNull();
    expect(parseScore('a lot')).toBeNull();
  });
});

describe('the header and the filter', () => {
  it('reads the four numbers off the response', () => {
    expect(summaryOf(RESULTS)).toEqual({
      played: 2,
      roster: 3,
      classAverage: 71,
      needsMarking: 1,
      released: true,
      releasedAt: 1_757_000_000_000,
    });
  });

  it('leaves standing only the children still waiting for a mark', () => {
    expect(needingMarking(resultRows(RESULTS)).map((row) => row.name)).toEqual(['Zain Lutfi']);
  });
});
