import type { TranslocoService } from '@jsverse/transloco';
import type { CoordinatorManager } from '../../api';
import { translateOr } from '../coordinator/coordinator.labels';

/**
 * One line of a staff directory row's job, as the server describes it (T1's `StaffContact`).
 *
 * `kind` is the post — a coordinator, a department manager — and the three optional fields are
 * how much of the school it covers. A manager's part carries a curriculum and nothing else,
 * because a department *is* the curriculum; a coordinator's names the grade, the subject and the
 * track her scope row does.
 */
export interface StaffJobPart {
  readonly kind?: string;
  readonly grade?: string;
  readonly subject?: string;
  readonly curriculum?: string;
}

/**
 * T1's additions to `CoordinatorManager`, read tolerantly until the generator has them.
 *
 * One widening in one file: the pages below never cast, and the day `pnpm gen:api` answers with
 * `StaffContact` this interface goes and the field names stay.
 */
export interface StaffContactFields {
  readonly email?: string;
  readonly phone?: string;
  readonly role?: string;
  readonly jobParts?: readonly StaffJobPart[];
}

export type StaffContact = CoordinatorManager & StaffContactFields;

/** What a card shows: no optionals, so the template has no `?? ''` in it. */
export interface StaffContactRow {
  readonly userId: string;
  readonly name: string;
  readonly email: string;
  readonly phone: string;
  /** One line per post she holds — a coordinator of two subjects has two. */
  readonly job: readonly string[];
}

/**
 * **The job, in her language.** "Coordinator · Grade 1 · Arabic · British", "American department
 * manager" — the owner's two examples, and the reason this is a function rather than a string on
 * the wire: every word of it is a value the dashboard already translates (`subject.*`,
 * `curriculum.*`), and a sentence assembled on the server would be assembled in one language.
 *
 * A manager reads differently on purpose: "Department manager · American" is a list of two facts,
 * "American department manager" is what she is called — and `staff.job.manager` carries the
 * curriculum as a parameter so Arabic puts it where Arabic puts it.
 *
 * With **no** `jobParts` — every row until T1 lands — the row's own `curriculum` is read as a
 * manager's single part, so the screen says the same thing before and after the contract widens.
 */
export function jobLabel(transloco: TranslocoService, contact: StaffContact): readonly string[] {
  const parts = contact.jobParts?.length
    ? contact.jobParts
    : [{ kind: 'manager', curriculum: contact.curriculum }];
  return parts.map((part) => onePart(transloco, part)).filter((line) => line !== '');
}

function onePart(transloco: TranslocoService, part: StaffJobPart): string {
  const track =
    part.curriculum === undefined || part.curriculum === ''
      ? ''
      : translateOr(transloco, `curriculum.${part.curriculum}`, part.curriculum);
  if (part.kind === 'manager' || part.kind === 'MANAGERIAL') {
    return track === ''
      ? transloco.translate<string>('staff.job.kind.manager')
      : transloco.translate<string>('staff.job.manager', { curriculum: track });
  }
  const words = [
    part.kind === undefined || part.kind === ''
      ? ''
      : translateOr(transloco, `staff.job.kind.${part.kind.toLowerCase()}`, part.kind),
    part.grade === undefined || part.grade === ''
      ? ''
      : transloco.translate<string>('staff.job.grade', { grade: part.grade }),
    part.subject === undefined || part.subject === ''
      ? ''
      : translateOr(transloco, `subject.${part.subject}`, part.subject),
    track,
  ];
  return words.filter((word) => word !== '').join(' · ');
}

export function contactRow(transloco: TranslocoService, contact: StaffContact): StaffContactRow {
  return {
    userId: contact.userId ?? '',
    name: contact.displayName ?? '',
    email: contact.email ?? '',
    phone: contact.phone ?? '',
    job: jobLabel(transloco, contact),
  };
}
