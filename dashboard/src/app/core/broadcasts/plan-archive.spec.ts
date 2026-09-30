import { describe, expect, it } from 'vitest';
import type { BroadcastView, WeeklyPlanArchive } from '../../api';
import { NO_PLAN_FILTER, filterWeeks, planCsv, planRows, planWeeks, readerBodies } from './plan-archive';

/**
 * MG2b, owner's item 4 ("see all weekly plans"), reworked by MH2 item 4 — a plan is a grade, a week
 * and an image. The archive is the one screen that must keep what every feed drops (a past week, an
 * expired plan), so these tests are about the mapping being lossless, the filter being the only
 * thing that takes a row away, and the attachment surviving onto the flat row the screens draw.
 */
describe('the weekly-plan archive', () => {
  const plan = (over: Partial<BroadcastView>): BroadcastView => ({
    id: 'b-1',
    kind: 'weekly_plan',
    curriculum: 'british',
    authorName: 'Nada Fahad',
    authorRole: 'MANAGERIAL',
    attachment: { id: 'att-1', name: 'week.png', type: 'image/png', url: 'https://api.example/x' },
    ...over,
  });

  const archive: WeeklyPlanArchive = {
    from: '2026-09-13',
    to: '2026-09-27',
    weeks: [
      {
        weekStart: '2026-09-27',
        items: [
          // The server's own order: by grade. A row with no grade at all is one written before MH1
          // made it required, and the mapping still has to carry it.
          { plan: plan({ id: 'b-all', attachment: { id: 'att-all', name: 'department.png' } }), readBy: 12 },
          { plan: plan({ id: 'b-g1', grade: 1, attachment: { id: 'att-g1', name: 'g1.png' } }), readBy: 4 },
          { plan: plan({ id: 'b-g3', grade: 3, attachment: { id: 'att-g3', name: 'g3.png' } }), readBy: 0 },
        ],
      },
      {
        weekStart: '2026-09-13',
        items: [{ plan: plan({ id: 'b-old', grade: 1 }), readBy: 9 }],
      },
    ],
  };

  it('maps weeks newest first, carrying the attachment onto the flat row', () => {
    const weeks = planWeeks(archive);
    expect(weeks.map((week) => week.weekStart)).toEqual(['2026-09-27', '2026-09-13']);
    expect(weeks[0]?.rows.map((row) => row.grade)).toEqual([null, 1, 3]);
    // The id, not the url: the url is the server's own absolute host and answers 401 in an `<img>`.
    expect(weeks[0]?.rows.map((row) => row.attachmentId)).toEqual(['att-all', 'att-g1', 'att-g3']);
    expect(weeks[0]?.rows[1]?.attachmentName).toBe('g1.png');
    // The week is stamped on every row, because the CSV has no weeks to nest inside.
    expect(planRows(weeks).map((row) => row.weekStart)).toEqual([
      '2026-09-27',
      '2026-09-27',
      '2026-09-27',
      '2026-09-13',
    ]);
    // `readBy: 0` is a number and not "unknown": nobody has opened it yet, which is the point.
    expect(weeks[0]?.rows[2]?.readBy).toBe(0);
  });

  it('answers an empty list rather than throwing on an empty or absent archive', () => {
    expect(planWeeks(undefined)).toEqual([]);
    expect(planWeeks({ weeks: [] })).toEqual([]);
    // A week whose only item carries no plan is a week with nothing to draw, so it goes too.
    expect(planWeeks({ weeks: [{ weekStart: '2026-09-27', items: [{}] }] })).toEqual([]);
  });

  it('filters by grade and by window, and drops a week it empties', () => {
    const weeks = planWeeks(archive);
    expect(filterWeeks(weeks, NO_PLAN_FILTER)).toHaveLength(2);

    // One grade: the two weeks that have a grade-1 plan, and neither week's other rows.
    const grade1 = filterWeeks(weeks, { ...NO_PLAN_FILTER, grade: 1 });
    expect(planRows(grade1).map((row) => row.id)).toEqual(['b-g1', 'b-old']);

    const window = filterWeeks(weeks, { ...NO_PLAN_FILTER, from: '2026-09-20', to: '2026-09-27' });
    expect(window.map((week) => week.weekStart)).toEqual(['2026-09-27']);
    // Both together, and a window that holds nothing is an empty list rather than an empty week.
    expect(filterWeeks(weeks, { grade: 3, from: '2026-09-13', to: '2026-09-13' })).toEqual([]);
  });

  it('exports the list she is looking at, naming the image rather than a title', () => {
    const csv = planCsv(filterWeeks(planWeeks(archive), { ...NO_PLAN_FILTER, grade: 3 }), {
      week: 'Week',
      grade: 'Grade',
      image: 'Image',
      readBy: 'Read by',
    });
    const lines = csv.split('\r\n');
    expect(lines[0]).toBe('﻿"Week","Grade","Image","Read by"');
    // MH1 took the title off a plan, so the file name is the only words a row has left.
    expect(lines[1]).toBe('"2026-09-27","3","g3.png","0"');
    // One row, because the filter said so: the export never re-reads past the screen.
    expect(lines[2]).toBe('');
  });

  it('puts the reader’s own language first', () => {
    const row = plan({ bodyEn: 'English', bodyAr: 'عربي' });
    expect(readerBodies(row, 'en').map((body) => body.dir)).toEqual(['ltr', 'rtl']);
    expect(readerBodies(row, 'ar').map((body) => body.dir)).toEqual(['rtl', 'ltr']);
    // An empty half is not an empty paragraph.
    expect(readerBodies(plan({ bodyEn: 'English', bodyAr: '' }), 'ar')).toHaveLength(1);
  });
});
