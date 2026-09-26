import { describe, expect, it } from 'vitest';
import { attendanceCsv, attendanceRange, defaultAttendanceWeek } from './attendance-range';

/**
 * Two days, three children, and every awkward case the server can answer with in one fixture:
 * a child who is on Tuesday's roster and not Monday's, a mark the enum does not have, and a name
 * that Excel would read as a formula.
 */
const DAYS = [
  {
    date: '2026-09-15',
    students: [
      { childId: 'c-2', childName: 'Omar', status: 'PRESENT', avatarColor: 'sky' },
      { childId: 'c-1', childName: '-Ali', status: 'ABSENT' },
    ],
  },
  {
    date: '2026-09-14',
    students: [
      { childId: 'c-1', childName: '-Ali', status: 'LATE' },
      { childId: 'c-2', childName: 'Omar', status: 'NOT_MARKED' },
      { childId: 'c-3', childName: 'Zaid', status: 'wat' },
    ],
  },
];

const HEADERS = {
  child: 'Child',
  present: 'Present',
  late: 'Late',
  absent: 'Absent',
  excused: 'Excused',
  rate: 'Rate',
};

describe('the coordinator attendance range', () => {
  it('puts the days in order whatever order the server sent them', () => {
    expect(attendanceRange(DAYS).days).toEqual(['2026-09-14', '2026-09-15']);
  });

  /**
   * A roster differs from day to day — a child placed on Tuesday is not on Monday's — so the rows
   * are the *union* and a day that never named her is a blank, not an absence. Getting this wrong
   * is the worst bug this screen could have: it would invent absences for a child who had not
   * joined yet, in a table a coordinator takes to a teacher.
   */
  it('takes the union of every day’s roster and leaves the gaps blank', () => {
    const range = attendanceRange(DAYS);

    expect(range.rows.map((row) => row.childName)).toEqual(['-Ali', 'Omar', 'Zaid']);
    const zaid = range.rows[2]!;
    // Monday had an unknown status, Tuesday did not list her at all: two blanks, no absences.
    expect(zaid.cells).toEqual(['NOT_MARKED', 'NOT_MARKED']);
    expect(zaid.absent).toBe(0);
    expect(zaid.rate).toBe(100);
  });

  it('counts each child’s marks and reads the rate off the days that were marked', () => {
    const [ali, omar] = attendanceRange(DAYS).rows;

    expect(ali!.cells).toEqual(['LATE', 'ABSENT']);
    expect({ late: ali!.late, absent: ali!.absent, rate: ali!.rate }).toEqual({
      late: 1,
      absent: 1,
      rate: 50,
    });
    // One present, one blank: the blank is not counted against her.
    expect({ present: omar!.present, rate: omar!.rate }).toEqual({ present: 1, rate: 100 });
  });

  it('opens on this week, Monday to today, and never past today', () => {
    // A Wednesday.
    expect(defaultAttendanceWeek(new Date('2026-09-16T09:00:00Z'))).toEqual({
      from: '2026-09-14',
      to: '2026-09-16',
    });
    // A Sunday belongs to the week that has just ended, not to the one starting tomorrow.
    expect(defaultAttendanceWeek(new Date('2026-09-20T09:00:00Z'))).toEqual({
      from: '2026-09-14',
      to: '2026-09-20',
    });
  });

  describe('as a CSV', () => {
    const csv = attendanceCsv(attendanceRange(DAYS), HEADERS);
    const lines = csv.split('\r\n');

    it('leads with the byte-order mark Excel needs to read Arabic names', () => {
      expect(csv.startsWith('\uFEFF')).toBe(true);
    });

    it('heads the columns with the days and the four totals', () => {
      expect(lines[0]).toBe(
        '\uFEFF"Child","2026-09-14","2026-09-15","Present","Late","Absent","Excused","Rate"',
      );
    });

    it('writes a blank for a day with no mark rather than the enum’s own word', () => {
      expect(lines[2]).toBe('"Omar","","PRESENT","1","0","0","0","100%"');
    });

    /** A name beginning `-` is a formula to Excel, and a register is exactly where that matters. */
    it('defuses a name a spreadsheet would run', () => {
      expect(lines[1]!.startsWith('"\t-Ali"')).toBe(true);
    });
  });
});
