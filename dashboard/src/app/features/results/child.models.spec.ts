import { describe, expect, it } from 'vitest';
import type { ChildReport } from '../../api';
import { BAND_LINES, CHART, chartOf, commentsOf, levelsOf, workOf } from './child.models';

const REPORT: ChildReport = {
  childId: 'ch-2',
  name: 'Zain Lutfi',
  classId: 'c-1a',
  className: '1A British',
  levels: [
    { subject: 'math', band: 'secure', levelScore: 64, trend: 'up', lessons: 6 },
    { subject: 'english', band: 'emerging', levelScore: 32, trend: 'flat', lessons: 4 },
  ],
  trend: [
    {
      lessonId: 'l-1',
      title: 'Counting to ten',
      date: '2026-09-07',
      score: 50,
      band: 'developing',
      released: true,
    },
    {
      lessonId: 'l-2',
      title: 'Tens and ones',
      date: '2026-09-14',
      score: 100,
      band: 'exceeding',
      released: true,
    },
    // Never opened: no score, so no bar — a bar of zero would be a different claim.
    { lessonId: 'l-3', title: 'Shapes', date: '2026-09-21', released: false },
  ],
  comments: [
    {
      lessonId: 'l-1',
      lessonTitle: 'Counting to ten',
      stars: 2,
      comment: 'Clear retelling',
      markedAt: 100,
      stopId: 's3',
    },
    { lessonId: 'l-2', lessonTitle: 'Tens and ones', comment: 'Much better this week', markedAt: 200 },
    { lessonId: 'l-2', lessonTitle: 'Tens and ones', comment: '   ', markedAt: 300 },
  ],
  work: [
    { id: 'm-1', url: 'https://api.test/media/child/m-1', kind: 'recording', stopId: 's3', createdAt: 100 },
    { id: 'm-2', url: 'https://api.test/media/child/m-2', kind: 'drawing', stopId: 's4', createdAt: 200 },
    { id: 'm-3', url: '', kind: 'drawing', createdAt: 300 },
  ],
};

describe('the child’s levels', () => {
  it('reads a row per subject, with its band and direction', () => {
    expect(levelsOf(REPORT)).toEqual([
      { subject: 'math', band: 'secure', levelScore: 64, trend: 'up', lessons: 6 },
      { subject: 'english', band: 'emerging', levelScore: 32, trend: 'flat', lessons: 4 },
    ]);
  });

  it('answers an empty report without inventing a level', () => {
    expect(levelsOf({})).toEqual([]);
  });
});

describe('the score chart', () => {
  it('draws one bar per scored lesson and drops the ones never played', () => {
    const chart = chartOf(REPORT.trend ?? []);

    expect(chart.bars.map((bar) => bar.lessonId)).toEqual(['l-1', 'l-2']);
  });

  it('scales a score to the plot, measuring up from the baseline', () => {
    const [half, full] = chartOf(REPORT.trend ?? []).bars;

    expect(full!.height).toBe(CHART.height);
    expect(half!.height).toBe(CHART.height / 2);
    // Both bars sit on the same baseline: y + height is the same line.
    expect(half!.y + half!.height).toBe(full!.y + full!.height);
  });

  it('puts a gridline on each band boundary rather than at round numbers', () => {
    const chart = chartOf(REPORT.trend ?? []);

    expect(chart.lines.map((line) => line.score)).toEqual([...BAND_LINES]);
    // 85 is nearer the top of the plot than 40 is.
    expect(chart.lines[2]!.y).toBeLessThan(chart.lines[0]!.y);
  });

  it('widens with the term and never narrower than the card', () => {
    const many = Array.from({ length: 12 }, (_, index) => ({
      lessonId: `l-${index}`,
      date: '2026-09-01',
      score: 70,
    }));

    expect(chartOf(many).width).toBe(CHART.paddingInline * 2 + 12 * CHART.slot);
    expect(chartOf([]).width).toBe(CHART.minWidth);
    expect(chartOf([]).bars).toEqual([]);
  });

  it('clamps a score the server should never send', () => {
    const chart = chartOf([{ lessonId: 'l-x', date: '2026-09-01', score: 140 }]);

    expect(chart.bars[0]!.height).toBe(CHART.height);
  });
});

describe('the comments and the gallery', () => {
  it('shows the newest first, drops the empty ones, and marks the parent’s', () => {
    const comments = commentsOf(REPORT);

    expect(comments.map((row) => row.comment)).toEqual(['Much better this week', 'Clear retelling']);
    expect(comments[0]!.forParent).toBe(true);
    expect(comments[1]!.forParent).toBe(false);
  });

  it('keeps the work that has a file, and knows which one is listened to', () => {
    const work = workOf(REPORT);

    expect(work.map((item) => item.id)).toEqual(['m-1', 'm-2']);
    expect(work[0]!.audio).toBe(true);
    expect(work[1]!.audio).toBe(false);
  });
});
