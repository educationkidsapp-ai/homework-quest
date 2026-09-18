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
  /**
   * A path inside the same area this screen redirects to instead of drawing anything.
   *
   * N2.2: a teacher's `/teacher` is her week, not a Home of her own. A redirect row rather than
   * a second route declared beside the table, so the rail, the router and `ROLE_HOME` still have
   * exactly one place to disagree — none.
   */
  readonly redirectTo?: string;
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
      // N1.2: the one-school screens. Classes reads with `section.read` and writes with
      // `class.write`; Teachers reads with `teacher.read` and writes with `teacher.manage`, so a
      // MANAGERIAL account gets both screens and none of their actions.
      { id: 'classes', path: 'classes', labelKey: 'nav.classes', permission: 'section.read' },
      { id: 'teachers', path: 'teachers', labelKey: 'nav.teachers', permission: 'teacher.read' },
      // D13: while `multiSchool` is off there is one school, it is the Admin's own, and these
      // three screens have nothing to show — the rail hides them and the router refuses them,
      // rather than offering a list of one and a switcher that switches to itself.
      {
        id: 'schools',
        path: 'schools',
        labelKey: 'nav.schools',
        flag: FLAGS.multiSchool,
        permission: 'school.read',
        phase: 3,
      },
      { id: 'school', path: 'schools/:id', flag: FLAGS.multiSchool, permission: 'school.read', phase: 3 },
      {
        id: 'school-users',
        path: 'schools/:id/users',
        flag: FLAGS.multiSchool,
        permission: 'user.read',
        phase: 3,
      },
      { id: 'users', path: 'users', labelKey: 'nav.users', permission: 'user.read', phase: 3 },
      { id: 'flags', path: 'flags', labelKey: 'nav.flags', permission: 'flag.read', phase: 3 },
      // lessons (P3.2b/c/d): the list, the new-lesson wizard and the review page are all
      // built. `new-lesson` and `lesson` have no `labelKey` — they are links the list's rows,
      // primary action and empty state open, not rail items of their own.
      { id: 'lessons', path: 'lessons', labelKey: 'nav.allLessons', permission: 'lesson.read' },
      { id: 'new-lesson', path: 'lessons/new', permission: 'lesson.write' },
      { id: 'lesson', path: 'lessons/:id', permission: 'lesson.read' },
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
  // N2.2 (`docs/teacher-flow.md` §4): "No other menu items render." A teacher's rail is **This
  // week · My classes**, with Profile in the header menu where every role's is. The screens the
  // later phases fill keep their rows and their routes — a bookmark still has to resolve to the
  // stub that names the phase — but they lose their `labelKey`, which is what puts them in the
  // rail. Telling a teacher about six screens she cannot open yet is the Admin's affordance, not
  // hers: she has thirty children and twenty minutes.
  TEACHER: {
    base: '/teacher',
    screens: [
      // Her Home *is* This week, so `/teacher` redirects rather than drawing a second landing.
      { id: 'home', path: '', redirectTo: 'week' },
      { id: 'week', path: 'week', labelKey: 'nav.thisWeek', permission: 'teacher.week' },
      // N2.3: the real My classes screen, and its class page. Both carry `teacher.week` —
      // the key `GET /teacher/classes` itself is gated by; the calendar and the roster inside
      // add `calendar.read`, `student.read` and `roster.teacher` at their own requests.
      // The class page has no `labelKey`: §5 puts the class in the rail as a *third* item only
      // while she is inside it, and that item is built from `ClassContextService`, not here.
      { id: 'classes', path: 'classes', labelKey: 'nav.myClasses', permission: 'teacher.week' },
      { id: 'class', path: 'classes/:classId', permission: 'teacher.week' },
      // Her lessons lost their rail label with N2.3: §4's rail is This week · My classes, and
      // the list is one tap away as "All lessons of this class". The route stays — a bookmark
      // and `/teacher/lessons?classId=…` from the class page both have to resolve.
      { id: 'lessons', path: 'lessons', permission: 'lesson.read' },
      { id: 'new-lesson', path: 'lessons/new', permission: 'lesson.write' },
      { id: 'lesson', path: 'lessons/:id', permission: 'lesson.read' },
      // No permission yet: `child.read` in permissions.json belongs to PARENT, and the key for
      // a teacher reading her own students arrives with P4.0's endpoints. Gating on the
      // parent's key would hide the item from every teacher.
      { id: 'students', path: 'students', phase: 4 },
      { id: 'questions', path: 'questions', flag: FLAGS.teacherQuestions, phase: 4 },
      { id: 'announcements', path: 'announcements', flag: FLAGS.announcements, phase: 4 },
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
