import type { Role } from '../auth/auth.service';
import { FLAGS } from '../flags/flag.service';

/**
 * One screen of the dashboard: what it is called, where it lives, and what it takes to open it.
 *
 * `labelKey` present means it is in the rail; `phase` present means a later package builds it
 * and the stub stands in until then.
 */
export interface Screen {
  readonly id: string;
  /** Relative to the area's base; `''` is the role's Home. */
  readonly path: string;
  /** Translation key under `nav.`. Absent for a detail route that is not a menu item. */
  readonly labelKey?: string;
  /** Shown and reachable only while this flag is on for the school in scope. */
  readonly flag?: string;
  /** Shown and reachable only while the account holds this permission. */
  readonly permission?: string;
  /** The phase that replaces the stub. Absent means the screen is built. */
  readonly phase?: number;
}

export interface Area {
  readonly base: string;
  readonly screens: readonly Screen[];
}

/**
 * **The one table.** The rail is built from it and so are the routes, so a menu item and the
 * route behind it cannot disagree about what opens them.
 *
 * That mattered: before this table the rail hid `/admin/users` from a teacher and the route
 * let her in by typing the URL. The server refuses the data either way, but a screen that
 * paints itself and then fills with red bands is not a refusal anyone can act on.
 *
 * Screens later packages build are listed too, with their phase. The rail is how a person
 * learns what the dashboard does; one that grows new items under someone who has already
 * learned it is worse than one that says what is coming. The server's Home already links to
 * `/admin/schools/{id}/users` and `/teacher/lessons/new?classId=…`, so those paths have to
 * resolve today whatever is behind them.
 */
export const AREAS: Readonly<Record<Role, Area>> = {
  ADMIN: {
    base: '/admin',
    screens: [
      { id: 'home', path: '', labelKey: 'nav.home' },
      { id: 'schools', path: 'schools', labelKey: 'nav.schools', permission: 'school.read', phase: 3 },
      { id: 'school', path: 'schools/:id', permission: 'school.read', phase: 3 },
      { id: 'school-users', path: 'schools/:id/users', permission: 'user.read', phase: 3 },
      { id: 'users', path: 'users', labelKey: 'nav.users', permission: 'user.read', phase: 3 },
      { id: 'flags', path: 'flags', labelKey: 'nav.flags', permission: 'flag.read', phase: 3 },
      // lessons (P3.2b): the list is built; the wizard and the detail review page stay the
      // P3.1 stub until P3.2c/d. `new-lesson` has no `labelKey` — it is a link the list's
      // primary action and empty state open, not a rail item of its own.
      { id: 'lessons', path: 'lessons', labelKey: 'nav.allLessons', permission: 'lesson.read' },
      { id: 'new-lesson', path: 'lessons/new', permission: 'lesson.write', phase: 3 },
      { id: 'lesson', path: 'lessons/:id', permission: 'lesson.read', phase: 3 },
      { id: 'usage', path: 'usage', labelKey: 'nav.platformUsage', permission: 'usage.platform', phase: 6 },
      // `platform.manage`, not `platform.read`: reading the platform's name is PUBLIC (the
      // sign-in page needs it before anyone has signed in), so gating the Admin screen on it
      // would hide the screen from the only role that can open it.
      {
        id: 'settings',
        path: 'settings',
        labelKey: 'nav.platformSettings',
        permission: 'platform.manage',
        phase: 3,
      },
    ],
  },
  TEACHER: {
    base: '/teacher',
    screens: [
      { id: 'home', path: '', labelKey: 'nav.home' },
      // lessons (P3.2b): see the matching comment in the ADMIN area above.
      { id: 'lessons', path: 'lessons', labelKey: 'nav.myLessons', permission: 'lesson.read' },
      {
        id: 'new-lesson',
        path: 'lessons/new',
        labelKey: 'nav.newLesson',
        permission: 'lesson.write',
        phase: 3,
      },
      { id: 'lesson', path: 'lessons/:id', permission: 'lesson.read', phase: 3 },
      // No permission yet: `child.read` in permissions.json belongs to PARENT, and the key for
      // a teacher reading her own students arrives with P4.0's endpoints. Gating on the
      // parent's key would hide the item from every teacher.
      { id: 'students', path: 'students', labelKey: 'nav.myStudents', phase: 4 },
      {
        id: 'questions',
        path: 'questions',
        labelKey: 'nav.questions',
        flag: FLAGS.teacherQuestions,
        phase: 4,
      },
      {
        id: 'announcements',
        path: 'announcements',
        labelKey: 'nav.announcements',
        flag: FLAGS.announcements,
        phase: 4,
      },
    ],
  },
  MANAGERIAL: {
    base: '/management',
    screens: [
      { id: 'home', path: '', labelKey: 'nav.home' },
      {
        id: 'complaints',
        path: 'complaints',
        labelKey: 'nav.complaints',
        flag: FLAGS.complaints,
        phase: 5,
      },
      { id: 'complaint', path: 'complaints/:id', flag: FLAGS.complaints, phase: 5 },
      { id: 'usage', path: 'usage', labelKey: 'nav.schoolUsage', permission: 'usage.school', phase: 5 },
      { id: 'teachers', path: 'teachers', labelKey: 'nav.teachers', permission: 'teacher.read', phase: 5 },
    ],
  },
};

/** The full route of a screen, e.g. `/admin/schools/:id`. */
export function linkOf(area: Area, screen: Screen): string {
  return screen.path === '' ? area.base : `${area.base}/${screen.path}`;
}

/** The rail's items for a role, in order: every screen that has a label. */
export function navScreens(role: Role): readonly { screen: Screen; link: string }[] {
  const area = AREAS[role];
  return area.screens
    .filter((screen) => screen.labelKey !== undefined)
    .map((screen) => ({ screen, link: linkOf(area, screen) }));
}

/**
 * The phase that will replace the stub at this URL, or `undefined`.
 *
 * Matched against the declared paths rather than looked up, so `/admin/schools/abc123` finds
 * the `schools/:id` screen — the stub has to say something useful on a detail route too.
 */
export function phaseOf(url: string): number | undefined {
  const path = url.split('?')[0] ?? '';
  for (const area of Object.values(AREAS))
    for (const screen of area.screens) if (matches(linkOf(area, screen), path)) return screen.phase;
  return undefined;
}

function matches(pattern: string, path: string): boolean {
  const expected = pattern.split('/');
  const actual = path.split('/');
  if (expected.length !== actual.length) return false;
  return expected.every((segment, index) => segment.startsWith(':') || segment === actual[index]);
}
