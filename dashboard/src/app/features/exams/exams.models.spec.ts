import { describe, expect, it } from 'vitest';
import type { ExamResults, ExamSettings } from '../../api';
import {
  canReopen,
  childRows,
  distributionBars,
  draftOfWindow,
  examStateOf,
  minutesTaken,
  questionRows,
  settingsFrozen,
  windowErrorOf,
  windowOf,
  zonedEpoch,
  zonedText,
} from './exams.models';

/** 18 Sep 2026, 09:00 and 10:00 UTC — the window every test below moves around. */
const OPENS = Date.UTC(2026, 8, 18, 9, 0);
const CLOSES = Date.UTC(2026, 8, 18, 10, 0);

function exam(overrides: Partial<ExamSettings> = {}): ExamSettings {
  return {
    examId: 'e-1',
    title: 'Mid-term',
    classId: 'c-1a',
    className: '1A British',
    subject: 'math',
    date: '2026-09-18',
    opensAt: OPENS,
    closesAt: CLOSES,
    level: 'mixed',
    singleAttempt: true,
    hintsOff: true,
    numbersOff: true,
    releaseMode: 'auto_on_close',
    status: 'published',
    released: false,
    open: false,
    ...overrides,
  };
}

describe('the five states of an exam', () => {
  it('is a draft until it is published, however good its window looks', () => {
    expect(examStateOf(exam({ status: 'review' }), OPENS - 1000)).toBe('draft');
    // Even mid-window: nobody can reach a lesson that was never published.
    expect(examStateOf(exam({ status: 'review' }), OPENS + 1000)).toBe('draft');
  });

  it('walks scheduled → open → closed with the clock', () => {
    expect(examStateOf(exam(), OPENS - 1)).toBe('scheduled');
    expect(examStateOf(exam(), OPENS)).toBe('open');
    expect(examStateOf(exam(), CLOSES - 1)).toBe('open');
    expect(examStateOf(exam(), CLOSES)).toBe('closed');
  });

  it('says released once the parents have the scores, whatever the window says', () => {
    expect(examStateOf(exam({ released: true }), CLOSES + 1)).toBe('released');
    expect(examStateOf(exam({ released: true }), OPENS + 1)).toBe('released');
  });

  it('ignores the server’s own `open`, which is stale the moment it is sent', () => {
    // The row says "not open"; the clock says otherwise, and the clock is what she is watching.
    expect(examStateOf(exam({ open: false }), OPENS + 60_000)).toBe('open');
  });

  it('freezes the settings from the opening instant, not from the first sitting', () => {
    expect(settingsFrozen(exam(), OPENS - 1)).toBe(false);
    expect(settingsFrozen(exam(), OPENS)).toBe(true);
  });
});

describe('the window a teacher types', () => {
  const draft = {
    opensDate: '2026-09-18',
    opensTime: '09:00',
    closesDate: '2026-09-18',
    closesTime: '10:00',
  };

  it('turns four fields into two instants, in the school’s clock', () => {
    expect(windowOf(draft, 'UTC')).toEqual({ opensAt: OPENS, closesAt: CLOSES });
  });

  it('reads the same wall clock as a different instant in a different zone', () => {
    // Riyadh is UTC+3 all year: 09:00 there is 06:00 UTC, three hours before the UTC reading.
    const riyadh = windowOf(draft, 'Asia/Riyadh');
    expect(riyadh?.opensAt).toBe(OPENS - 3 * 60 * 60 * 1000);
  });

  it('survives a zone the browser has never heard of by falling back to UTC', () => {
    expect(windowOf(draft, 'Mars/Olympus')).toEqual({ opensAt: OPENS, closesAt: CLOSES });
  });

  it('refuses a window that closes before it opens, and one that closes exactly when it opens', () => {
    expect(windowErrorOf({ ...draft, closesTime: '08:00' }, 'UTC')).toBe('order');
    expect(windowErrorOf({ ...draft, closesTime: '09:00' }, 'UTC')).toBe('order');
    expect(windowOf({ ...draft, closesTime: '08:00' }, 'UTC')).toBeNull();
  });

  it('calls a half-filled window incomplete rather than wrong', () => {
    expect(windowErrorOf({ ...draft, closesDate: '' }, 'UTC')).toBe('incomplete');
    expect(windowErrorOf({ ...draft, opensTime: '9:00' }, 'UTC')).toBe('incomplete');
  });

  it('round-trips an instant back into the fields it came from', () => {
    expect(draftOfWindow(OPENS, CLOSES, 'UTC')).toEqual(draft);
    expect(draftOfWindow(OPENS, CLOSES, 'Asia/Riyadh')).toEqual({
      ...draft,
      opensTime: '12:00',
      closesTime: '13:00',
    });
  });

  it('puts midnight at 00:00 rather than at the 24:00 some locales report', () => {
    const midnight = Date.UTC(2026, 8, 18, 0, 0);
    expect(draftOfWindow(midnight, midnight + 3600_000, 'UTC').opensTime).toBe('00:00');
  });

  it('lands on the right side of a daylight-saving change', () => {
    // London moves to GMT on 25 Oct 2026 at 02:00 BST. 09:00 that morning is 09:00 UTC.
    expect(zonedEpoch('2026-10-25', '09:00', 'Europe/London')).toBe(Date.UTC(2026, 9, 25, 9, 0));
    // The day before, the same wall clock is an hour earlier in UTC.
    expect(zonedEpoch('2026-10-24', '09:00', 'Europe/London')).toBe(Date.UTC(2026, 9, 24, 8, 0));
  });

  it('spells an instant in the school’s clock, and a missing one as a dash', () => {
    expect(zonedText(OPENS, 'UTC', 'en-GB', { hour: '2-digit', minute: '2-digit' })).toContain('09');
    expect(zonedText(null, 'UTC', 'en-GB', {})).toBe('—');
    expect(zonedText(0, 'UTC', 'en-GB', {})).toBe('—');
  });
});

describe('re-opening, which is once per child', () => {
  it('is offered to a child who was absent or was cut off', () => {
    expect(canReopen({ childId: 'a', state: 'absent' })).toBe(true);
    expect(canReopen({ childId: 'a', state: 'started' })).toBe(true);
  });

  it('is not offered to a child who handed in', () => {
    expect(canReopen({ childId: 'a', state: 'submitted' })).toBe(false);
  });

  it('is not offered twice — the server answers 409 and the button would be a lie', () => {
    expect(canReopen({ childId: 'a', state: 'absent', reopened: true })).toBe(false);
    expect(canReopen({ childId: 'a', state: 'started', reopened: true })).toBe(false);
  });
});

describe('the distribution', () => {
  it('draws all four bands in §7’s order, including the empty ones', () => {
    const bars = distributionBars([
      { band: 'exceeding', children: 2 },
      { band: 'emerging', children: 1 },
    ]);
    expect(bars.map((bar) => bar.band)).toEqual(['emerging', 'developing', 'secure', 'exceeding']);
    expect(bars.map((bar) => bar.children)).toEqual([1, 0, 0, 2]);
  });

  it('scales the bars to the tallest, and the labels to everyone who sat it', () => {
    const bars = distributionBars([
      { band: 'emerging', children: 1 },
      { band: 'secure', children: 3 },
    ]);
    const emerging = bars[0]!;
    const secure = bars[2]!;
    expect(secure.percent).toBe(100);
    expect(emerging.percent).toBe(33);
    expect(emerging.share).toBe(25);
    expect(secure.share).toBe(75);
  });

  it('is four empty columns, not a crash, when nobody has sat it', () => {
    expect(distributionBars(undefined).every((bar) => bar.children === 0 && bar.percent === 0)).toBe(true);
  });

  it('ignores a band the server does not name and folds duplicates together', () => {
    const bars = distributionBars([
      { band: 'secure', children: 1 },
      { band: 'secure', children: 2 },
      { band: 'brilliant', children: 9 },
    ]);
    expect(bars[2]!.children).toBe(3);
    expect(bars.reduce((sum, bar) => sum + bar.children, 0)).toBe(3);
  });
});

describe('the questions', () => {
  it('puts the hardest first and keeps the paper’s order among equals', () => {
    const rows = questionRows([
      { stopId: 'q1', title: 'One', missedPercent: 10 },
      { stopId: 'q2', title: 'Two', missedPercent: 80 },
      { stopId: 'q3', title: 'Three', missedPercent: 10 },
    ]);
    expect(rows.map((row) => row.stopId)).toEqual(['q2', 'q1', 'q3']);
  });

  it('fills in the numbers the server left out rather than printing undefined', () => {
    const [row] = questionRows([{ stopId: 'q1' }]);
    expect(row).toMatchObject({ answered: 0, correct: 0, missedPercent: 0, averageStars: null });
    expect(row!.open).toBe(false);
  });
});

describe('the per-child table', () => {
  const results: ExamResults = {
    examId: 'e-1',
    children: [
      { childId: 'c1', name: 'Amina', state: 'submitted', score: 90, percent: 90, band: 'exceeding' },
      { childId: 'c2', name: 'Sami', state: 'started', needsMarking: 2 },
    ],
    absentees: [
      { childId: 'c3', name: 'Lina', state: 'absent' },
      // The server lists an absentee in both arrays; she is one row, not two.
      { childId: 'c2', name: 'Sami', state: 'started' },
    ],
  };

  it('is everyone once, those who sat it first and the absentees after', () => {
    const rows = childRows(results);
    expect(rows.map((row) => row.childId)).toEqual(['c1', 'c2', 'c3']);
  });

  it('carries the band as a band and the missing numbers as null, not zero', () => {
    const [amina, sami] = childRows(results);
    expect(amina!.band).toBe('exceeding');
    expect(sami!.score).toBeNull();
    expect(sami!.percent).toBeNull();
    expect(sami!.needsMarking).toBe(2);
  });

  it('decides Re-open per row, so the template never has to', () => {
    const rows = childRows(results);
    expect(rows.map((row) => row.canReopen)).toEqual([false, true, true]);
  });

  it('is an empty table, not a crash, for an exam nobody has sat', () => {
    expect(childRows({})).toEqual([]);
  });
});

describe('the time taken', () => {
  it('is minutes and seconds, zero-padded, because 25:4 is not a time', () => {
    expect(minutesTaken(1542)).toBe('25:42');
    expect(minutesTaken(64)).toBe('1:04');
    expect(minutesTaken(0)).toBe('0:00');
  });

  it('is a dash for a child who never started', () => {
    expect(minutesTaken(null)).toBe('—');
  });
});
