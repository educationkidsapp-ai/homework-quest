import type { ClassAttendanceResponse } from '../../api';
import type { AttendanceStatus } from '../../core/attendance/attendance.models';
import { type Range, isoDate } from '../results/gradebook.models';

/** One child's line of the matrix: a mark per day, then the four totals. */
export interface AttendanceRangeRow {
  readonly childId: string;
  readonly childName: string;
  readonly avatarColor: string;
  /** One per column of {@link AttendanceRange.days}, in the same order. */
  readonly cells: readonly AttendanceStatus[];
  readonly present: number;
  readonly late: number;
  readonly absent: number;
  readonly excused: number;
  /**
   * Present + late over the days actually marked, to one decimal — and `null` when **nothing**
   * was marked.
   *
   * Not 100. The review found the old default printing "100%" for a child whose register was never
   * kept, on the screen whose whole question is whether the section has been marked at all this
   * week; a dash is the truthful answer and the only one she can act on.
   */
  readonly rate: number | null;
}

export interface AttendanceRange {
  readonly days: readonly string[];
  readonly rows: readonly AttendanceRangeRow[];
}

/**
 * The days the coordinator's attendance screen opens on: **this week**, Monday to today.
 *
 * Her own default rather than the gradebook's eight weeks: a term of days across the top of a
 * table is a horizontal scroll nobody reads, and "has this section been marked at all this week"
 * is the question she is on the screen to answer. `to` is today rather than Sunday, because a
 * column for Thursday on Tuesday is a column of blanks that reads as absences.
 *
 * `today` is a `YYYY-MM-DD` the caller has already resolved **in the school's timezone**, the way
 * the week page does it (`Intl.DateTimeFormat('en-CA', { timeZone })`). The review found the first
 * version reading the weekday off `getUTCDay()` of the *local* clock, which at 01:30 on a Monday
 * in +04 — QA runs in me-central1 — answered the whole of the week that had just ended. A string
 * in and two strings out: every step below is UTC arithmetic on a UTC-anchored instant, so there
 * is no second clock left to disagree with the first.
 */
export function defaultAttendanceWeek(today: string): Range {
  const monday = new Date(`${today}T00:00:00Z`);
  // `getUTCDay()` is 0 on Sunday, which belongs to the week that has just ended.
  const offset = (monday.getUTCDay() + 6) % 7;
  monday.setUTCDate(monday.getUTCDate() - offset);
  return { from: isoDate(monday), to: today };
}

/**
 * The per-day responses turned into children down the side and days across the top.
 *
 * The server answers a `ClassAttendanceResponse` per day, each with its own roster, so a child
 * who joined the section on Wednesday is missing from Monday's — hence the union of every day's
 * roster for the rows and `NOT_MARKED` for a cell no day named. Sorted by name, so the same
 * class reads the same way on Monday and on Friday whatever order the server used.
 */
export function attendanceRange(days: readonly ClassAttendanceResponse[]): AttendanceRange {
  const dates = [...days]
    .map((day) => day.date ?? '')
    .filter((date) => date !== '')
    .sort();
  const named = new Map<string, { name: string; color: string }>();
  const marks = new Map<string, AttendanceStatus>();
  for (const day of days) {
    for (const student of day.students ?? []) {
      const childId = student.childId ?? '';
      if (childId === '') continue;
      if (!named.has(childId))
        named.set(childId, { name: student.childName ?? '', color: student.avatarColor ?? 'sky' });
      marks.set(`${day.date ?? ''}\u0000${childId}`, statusOf(student.status));
    }
  }
  const rows = [...named.entries()]
    .map(([childId, who]) => {
      const cells = dates.map((date) => marks.get(`${date}\u0000${childId}`) ?? 'NOT_MARKED');
      const count = (status: AttendanceStatus): number => cells.filter((cell) => cell === status).length;
      const present = count('PRESENT');
      const late = count('LATE');
      const absent = count('ABSENT');
      const excused = count('EXCUSED');
      const marked = present + late + absent + excused;
      return {
        childId,
        childName: who.name,
        avatarColor: who.color,
        cells,
        present,
        late,
        absent,
        excused,
        rate: marked === 0 ? null : Math.round(((present + late) / marked) * 1000) / 10,
      };
    })
    .sort((a, b) => a.childName.localeCompare(b.childName));
  return { days: dates, rows };
}

/** An unknown status is `NOT_MARKED` rather than a crash: the server owns the enum. */
function statusOf(value: string | undefined): AttendanceStatus {
  return value === 'PRESENT' || value === 'LATE' || value === 'ABSENT' || value === 'EXCUSED'
    ? value
    : 'NOT_MARKED';
}

/** The column headings the CSV needs, already translated by the caller. */
export interface AttendanceCsvHeaders {
  readonly child: string;
  readonly present: string;
  readonly late: string;
  readonly absent: string;
  readonly excused: string;
  readonly rate: string;
}

/**
 * The matrix as a spreadsheet, built in the browser.
 *
 * `/coordinator/**` publishes no `.csv`, and asking for the teacher's would be a 403 — so this is
 * the one export of hers, and it is the rows already on her screen rather than a second read that
 * could disagree with them. `\r\n` and a byte-order mark because Excel on Windows reads
 * a bare `\n` UTF-8 CSV as one column of mojibake, and an Arabic roster is exactly that case.
 */
export function attendanceCsv(range: AttendanceRange, headers: AttendanceCsvHeaders): string {
  const head = [
    headers.child,
    ...range.days,
    headers.present,
    headers.late,
    headers.absent,
    headers.excused,
    headers.rate,
  ];
  const lines = range.rows.map((row) =>
    [
      row.childName,
      ...row.cells.map((cell) => (cell === 'NOT_MARKED' ? '' : cell)),
      String(row.present),
      String(row.late),
      String(row.absent),
      String(row.excused),
      row.rate === null ? '' : `${row.rate}%`,
    ]
      .map(quote)
      .join(','),
  );
  return `\uFEFF${[head.map(quote).join(','), ...lines].join('\r\n')}\r\n`;
}

/**
 * One field, quoted.
 *
 * A leading `=`, `+`, `-` or `@` is prefixed with a tab: a child called "-Ali" is a formula to
 * Excel, and a CSV a coordinator opens is exactly where that matters.
 */
function quote(field: string): string {
  const safe = /^[=+\-@]/.test(field) ? `\t${field}` : field;
  return `"${safe.replace(/"/g, '""')}"`;
}
