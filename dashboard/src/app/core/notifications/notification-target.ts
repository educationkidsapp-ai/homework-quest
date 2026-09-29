import { type NotificationView, NotificationViewKindEnum } from '../../api';
import type { Role } from '../auth/auth.service';

/** Where a row of the bell goes: a path, and the query the screen at it reads. */
export interface NotificationTarget {
  readonly path: string;
  /** `null` rather than `{}` so `[queryParams]` can be bound straight to it. */
  readonly queryParams: Readonly<Record<string, string>> | null;
}

/** The notifications page: what a row goes to when nothing better is known. */
const INBOX: NotificationTarget = { path: '/notifications', queryParams: null };

/**
 * The area of the dashboard a role's own screens live under — the prefix a link has to start
 * with to be *hers*. Not `staffAreaOf`, which folds an Admin in with a teacher: an Admin's
 * lesson lives at `/admin/lessons/{id}` and the server already links her there.
 */
const AREA: Readonly<Record<Role, string>> = {
  ADMIN: '/admin',
  TEACHER: '/teacher',
  COORDINATOR: '/coordinator',
  MANAGERIAL: '/management',
};

/**
 * The manager's retired screens (MG2a, `core/nav/screens.ts`). A `link` the server wrote before
 * they went — or one a role-blind sender still writes — would resolve to a redirect back to her
 * Home, which reads as a click that did nothing; the resolver answers from the kind instead.
 */
const RETIRED_FOR_MANAGER = [
  '/management/lessons',
  '/management/classes',
  '/management/gradebook',
  '/management/exams',
  '/management/children',
];

/**
 * **The one place a notification becomes a route.** MG2a deliverable 4.
 *
 * E2 writes `link` at the moment the row is created, from the recipient's role as it was then —
 * so the bell has always had three ways to be wrong: a kind with no link at all
 * (`teacher.message` is written with `null`), a link into an area the viewer does not have
 * (`/teacher/broadcasts` in a coordinator's bell), and a link to a screen that has since been
 * taken out of a role's rail. Every screen that opens a notification now asks this function, so
 * fixing any of the three is one edit rather than three.
 *
 * **The server's link wins when it is the viewer's own.** MG1 is making the fan-out write
 * role-correct links; when it lands, this function stops guessing for the kinds it covers,
 * with no second deploy of the dashboard. It is only overruled when the path is outside the
 * viewer's area or names one of the manager's retired screens.
 *
 * `lessonId` is E2's **entity id**, not only a lesson's: `BroadcastService` puts the broadcast's
 * id there (it is how a superseded weekly plan's bell rows are found and forgotten), so that is
 * where `?open=` comes from. A kind nobody has taught this function goes to the notifications
 * page, which is the one screen that can always show the row itself.
 */
export function notificationTarget(item: NotificationView, role: Role | null): NotificationTarget {
  const area = role === null ? null : AREA[role];
  const link = item.link?.trim() ?? '';
  if (area !== null && inOwnArea(link, area) && !retired(link, role)) return split(link);

  const entityId = item.lessonId?.trim() ?? '';
  switch (item.kind) {
    // The three lesson kinds are a teacher's (and an Admin's, who reads the teacher's rows).
    // A coordinator still has the read-only lesson page; the manager no longer does.
    case NotificationViewKindEnum.LESSON_NEEDS_SKILLS:
    case NotificationViewKindEnum.LESSON_READY:
    case NotificationViewKindEnum.LESSON_FAILED:
      return area === null || area === '/management' || entityId === ''
        ? INBOX
        : { path: `${area}/lessons/${entityId}`, queryParams: null };
    // Only the three staff roles have a feed; an Admin reads broadcasts nowhere.
    case NotificationViewKindEnum.BROADCAST_POSTED:
      return area === null || area === '/admin'
        ? INBOX
        : { path: `${area}/broadcasts`, queryParams: entityId === '' ? null : { open: entityId } };
    // A teacher's message is read by whoever handles the school's messages — her coordinator,
    // her manager, the admin. A teacher never receives one, so she has nowhere to be sent.
    case NotificationViewKindEnum.TEACHER_MESSAGE:
      return area === null || area === '/teacher'
        ? INBOX
        : { path: `${area}/messages`, queryParams: entityId === '' ? null : { thread: entityId } };
    default:
      return INBOX;
  }
}

/** The whole URL again, for a caller that navigates rather than binds (the page's row). */
export function notificationUrl(target: NotificationTarget): string {
  const query = new URLSearchParams(target.queryParams ?? {}).toString();
  return query === '' ? target.path : `${target.path}?${query}`;
}

/** A path of this area, and a path: `//evil.example` and `https://…` are neither. */
function inOwnArea(link: string, area: string): boolean {
  if (!link.startsWith(`${area}/`) && link !== area) return false;
  return !link.startsWith('//');
}

function retired(link: string, role: Role | null): boolean {
  return (
    role === 'MANAGERIAL' && RETIRED_FOR_MANAGER.some((path) => link === path || link.startsWith(`${path}/`))
  );
}

function split(link: string): NotificationTarget {
  const [path = '', query = ''] = link.split('?', 2);
  if (query === '') return { path, queryParams: null };
  return { path, queryParams: Object.fromEntries(new URLSearchParams(query)) };
}
