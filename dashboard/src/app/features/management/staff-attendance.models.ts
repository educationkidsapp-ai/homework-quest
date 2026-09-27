import type { StaffAttendanceDay, StaffAttendanceRow } from '../../api';

/** The four words the register knows. Anything else on the wire reads as unmarked. */
export const STAFF_STATUSES = ['present', 'absent', 'late', 'leave'] as const;
export type StaffStatus = (typeof STAFF_STATUSES)[number];

/** One person of the roster, with the mark she carries *today* and the note beside it. */
export interface RosterRow {
  readonly userId: string;
  readonly name: string;
  readonly email: string;
  readonly role: string;
  /** `null` while nobody has marked her — never `present` by default (RM5). */
  readonly status: StaffStatus | null;
  readonly note: string;
}

export function rosterOf(day: StaffAttendanceDay | undefined): readonly RosterRow[] {
  return [...(day?.people ?? [])]
    .map(rowOf)
    .sort((a, b) => a.role.localeCompare(b.role) || a.name.localeCompare(b.name));
}

/**
 * Why today's register may not be taken, or `null` when it may.
 *
 * The server owns the rule and says so on the response — a day is markable when it is a teaching
 * day of *this* school (so a Monday–Friday school is not measured against the Gulf week) and is
 * not after today in the school's own zone. The screen never re-derives it; it only has to tell
 * the two apart, because "we do not teach on Fridays" and "that day has not happened yet" are
 * different sentences and a disabled Save with neither of them is a dead control.
 */
export function notEditableReason(day: StaffAttendanceDay | undefined): 'future' | 'closed' | null {
  if (day === undefined || day.editable === true) return null;
  return day.schoolDay === false ? 'closed' : 'future';
}

/** Only the people whose mark or note the manager actually changed (RM5's upsert body). */
export function changedMarks(
  original: readonly RosterRow[],
  edited: readonly RosterRow[],
): readonly { userId: string; status: string; note?: string }[] {
  const before = new Map(original.map((row) => [row.userId, row]));
  return edited
    .filter((row) => {
      const was = before.get(row.userId);
      return row.status !== null && (was?.status !== row.status || (was?.note ?? '') !== row.note);
    })
    .map((row) => ({
      userId: row.userId,
      status: row.status as string,
      note: row.note === '' ? undefined : row.note,
    }));
}

function rowOf(person: StaffAttendanceRow): RosterRow {
  const status = person.status ?? '';
  return {
    userId: person.userId ?? '',
    name: person.displayName ?? '',
    email: person.email ?? '',
    role: person.role ?? '',
    status: (STAFF_STATUSES as readonly string[]).includes(status) ? (status as StaffStatus) : null,
    note: person.note ?? '',
  };
}
