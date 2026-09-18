import type { SchoolClass, TeacherAccount, TeachingAssignment } from '../../api';
import type { Subject } from '../lessons/lessons.models';

/**
 * `GET /admin/classes` answers `quest.server.classes.ClassDto.SchoolClass`, which carries the
 * section's `name`, its `joinCode`, whether it is `active`, and the two counts the Classes
 * screen shows. The generated `SchoolClass` carries only the eight fields of the pre-V7 shape.
 *
 * **Why.** Two Java records are named `SchoolClass` — `quest.server.classes.ClassDto` (N1.1's,
 * fourteen components) and `quest.server.dashboard.SchoolDataDto` (the older eight) — and
 * springdoc keeps one schema per simple name, so `server/openapi.json` publishes the narrow one
 * under that name for both. The wire format is the wide record; only the description of it is
 * lossy. Widening the generated type here keeps every call going through the generated
 * `ClassesApi` while the contract is wrong, and is deleted the day one of the two records is
 * given a `@Schema(name = …)` of its own and `openapi.json` is regenerated.
 */
export type AdminClass = SchoolClass & {
  readonly name?: string;
  readonly joinCode?: string;
  readonly active?: boolean;
  readonly joinCodeEnabled?: boolean;
  readonly children?: number;
  readonly assignments?: number;
};

/** A section as the Classes table shows it, with everything already resolved for display. */
export interface ClassRow {
  readonly id: string;
  readonly course: string;
  readonly name: string;
  readonly joinCode: string;
  readonly children: number;
  readonly teachers: string;
  readonly active: boolean;
  readonly source: AdminClass;
}

/** A teacher as the Teachers table shows her. */
export interface TeacherRow {
  readonly id: string;
  readonly fullName: string;
  readonly email: string;
  readonly subjects: string;
  readonly curriculum: string;
  readonly assignments: readonly string[];
  readonly active: boolean;
  readonly source: TeacherAccount;
}

/**
 * One line of the assignment picker: an active section of the teacher's curriculum crossed with
 * one of her subjects, and — when somebody else already teaches that subject there — who.
 */
export interface AssignmentChoice {
  readonly classId: string;
  readonly subject: Subject;
  readonly className: string;
  readonly course: string;
  readonly checked: boolean;
  readonly takenBy: string | null;
}

/** `sara@…` → `Sara`, so a teacher with no display name is still addressable in a sentence. */
export function teacherLabel(teacher: TeacherAccount): string {
  return teacher.fullName?.trim() || teacher.email?.split('@')[0] || '';
}

/**
 * A section name: one or two digits for the grade and a letter for the section, as `1A` or `10B`
 * (`docs/teacher-flow.md` §1). Checked here only to catch the typo before the request — the
 * server is what decides, and a name it accepts that this misses would be a bug in this line,
 * so the field never blocks submission on its own.
 */
export const SECTION_NAME_PATTERN = /^[0-9]{1,2}[A-Za-z]$/;

export function isSectionName(value: string): boolean {
  return SECTION_NAME_PATTERN.test(value.trim());
}

/** Every (class, subject) pair a teacher holds today, as the picker's initial checkmarks. */
export function heldPairs(assignments: readonly TeachingAssignment[] | undefined): ReadonlySet<string> {
  return new Set((assignments ?? []).map((a) => `${a.classId ?? ''}|${a.subject ?? ''}`));
}

export function pairKey(classId: string, subject: string): string {
  return `${classId}|${subject}`;
}

/** Sections sort the way the screen reads them: curriculum, then grade, then section name. */
export function byCourseThenName(a: AdminClass, b: AdminClass): number {
  const curriculum = (a.curriculum ?? '').localeCompare(b.curriculum ?? '');
  if (curriculum !== 0) return curriculum;
  const grade = (a.grade ?? 0) - (b.grade ?? 0);
  if (grade !== 0) return grade;
  return (a.name ?? '').localeCompare(b.name ?? '', undefined, { numeric: true });
}
