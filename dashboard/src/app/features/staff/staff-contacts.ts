import type { TranslocoService } from '@jsverse/transloco';
import type { StaffContact, StaffJobParts } from '../../api';
import { translateOr } from '../coordinator/coordinator.labels';

/** What a card shows: no optionals, so the template has no `?? ''` in it. */
export interface StaffContactRow {
  readonly userId: string;
  readonly name: string;
  readonly email: string;
  readonly phone: string;
  /** One line per entry of `jobParts` — see {@link jobLabel}. */
  readonly job: readonly string[];
  /** T1's `online`, or `undefined` when the server said nothing. */
  readonly online: boolean | undefined;
}

/**
 * **The job, in her language.** "Coordinator · Grade 1 · Arabic · British", "American department
 * manager" — the owner's two examples, and the reason this is a function rather than the `job`
 * string T1 also sends: every word of it is a value the dashboard already translates (`subject.*`,
 * `curriculum.*`), and a sentence assembled on the server is assembled in one language.
 *
 * **One line per entry of `jobParts`.** T1 keeps one entry per *track* and never flattens grades
 * across tracks, because "Grades 1, 2 · Arabic · British and American" would be a claim about four
 * posts when she holds two. The server joins its own `job` with "; " for a client that cannot lay
 * out lines; this one can, so each entry gets its own.
 *
 * A manager reads differently on purpose: "Department manager · American" is a list of two facts,
 * "American department manager" is what she is called — and `staff.job.manager` carries the
 * curriculum as a parameter so Arabic puts it where Arabic puts it.
 *
 * With no `jobParts` at all the row's own `curriculum` is read as a manager's single part, so a
 * contact the server describes only by its department still reads as that department's manager.
 */
export function jobLabel(transloco: TranslocoService, contact: StaffContact): readonly string[] {
  const parts: readonly StaffJobParts[] = contact.jobParts?.length
    ? contact.jobParts
    : [{ kind: 'manager', curriculum: contact.curriculum }];
  return parts.map((part) => onePart(transloco, part)).filter((line) => line !== '');
}

function onePart(transloco: TranslocoService, part: StaffJobParts): string {
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
    gradesLabel(transloco, part.grades),
    part.subject === undefined || part.subject === ''
      ? ''
      : translateOr(transloco, `subject.${part.subject}`, part.subject),
    track,
  ];
  return words.filter((word) => word !== '').join(' · ');
}

/** "Grade 1" for one, "Grades 1, 2" for several — a coordinator often holds a range. */
function gradesLabel(transloco: TranslocoService, grades: readonly number[] | undefined): string {
  if (!grades?.length) return '';
  const sorted = [...grades].sort((a, b) => a - b);
  return sorted.length === 1
    ? transloco.translate<string>('staff.job.grade', { grade: sorted[0] })
    : transloco.translate<string>('staff.job.grades', { grades: sorted.join(', ') });
}

export function contactRow(transloco: TranslocoService, contact: StaffContact): StaffContactRow {
  return {
    userId: contact.userId ?? '',
    name: contact.displayName ?? '',
    email: contact.email ?? '',
    phone: contact.phone ?? '',
    job: jobLabel(transloco, contact),
    online: contact.online,
  };
}
