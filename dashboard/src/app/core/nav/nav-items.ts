import type { Role } from '../auth/auth.service';
import { FLAGS } from '../flags/flag.service';

/** One entry in the 240 px rail. */
export interface ShellNavItem {
  readonly id: string;
  /** Translation key under `nav.`. */
  readonly labelKey: string;
  readonly link: string;
  /** When set, the item is shown only while that flag is on for the school in scope. */
  readonly flag?: string;
  /** When set, the item is shown only while the account holds that permission. */
  readonly permission?: string;
}

/**
 * The navigation, per role (§6).
 *
 * Every item is here even when the screen behind it is a later package: the rail is how a
 * person learns what the dashboard does, and an item that appears in phase 5 is a dashboard
 * that changes shape under someone who had already learned it. The ones not yet built route
 * to the stub page, which says which phase brings them — a promise, not a dead end.
 *
 * Order is deliberate: Home first, then the thing that role does most, then the rest.
 */
export const NAV: Readonly<Record<Role, readonly ShellNavItem[]>> = {
  ADMIN: [
    { id: 'home', labelKey: 'nav.home', link: '/admin' },
    { id: 'schools', labelKey: 'nav.schools', link: '/admin/schools', permission: 'school.read' },
    { id: 'users', labelKey: 'nav.users', link: '/admin/users', permission: 'user.read' },
    { id: 'flags', labelKey: 'nav.flags', link: '/admin/flags', permission: 'flag.read' },
    { id: 'lessons', labelKey: 'nav.allLessons', link: '/admin/lessons', permission: 'lesson.read' },
    { id: 'usage', labelKey: 'nav.platformUsage', link: '/admin/usage', permission: 'usage.platform' },
    // `platform.manage`, not `platform.read`: reading the platform's name is PUBLIC (the
    // sign-in page needs it before anyone has signed in), so gating the Admin screen on it
    // would hide the screen from the only role that can open it.
    {
      id: 'settings',
      labelKey: 'nav.platformSettings',
      link: '/admin/settings',
      permission: 'platform.manage',
    },
  ],
  TEACHER: [
    { id: 'home', labelKey: 'nav.home', link: '/teacher' },
    { id: 'lessons', labelKey: 'nav.myLessons', link: '/teacher/lessons', permission: 'lesson.read' },
    { id: 'new-lesson', labelKey: 'nav.newLesson', link: '/teacher/lessons/new', permission: 'lesson.write' },
    // No permission yet: `child.read` in permissions.json belongs to PARENT, and the key for
    // a teacher reading her own students arrives with P4.0's endpoints. Gating on the parent's
    // key would hide the item from every teacher.
    { id: 'students', labelKey: 'nav.myStudents', link: '/teacher/students' },
    {
      id: 'questions',
      labelKey: 'nav.questions',
      link: '/teacher/questions',
      flag: FLAGS.teacherQuestions,
    },
    {
      id: 'announcements',
      labelKey: 'nav.announcements',
      link: '/teacher/announcements',
      flag: FLAGS.announcements,
    },
  ],
  MANAGERIAL: [
    { id: 'home', labelKey: 'nav.home', link: '/management' },
    {
      id: 'complaints',
      labelKey: 'nav.complaints',
      link: '/management/complaints',
      flag: FLAGS.complaints,
    },
    { id: 'usage', labelKey: 'nav.schoolUsage', link: '/management/usage', permission: 'usage.school' },
    { id: 'teachers', labelKey: 'nav.teachers', link: '/management/teachers', permission: 'teacher.read' },
  ],
};

/**
 * Which phase brings each screen that is still a stub, so the stub page can say so rather
 * than showing an empty page with no explanation. Keyed by route path.
 */
export const STUB_PHASE: Readonly<Record<string, number>> = {
  '/admin/schools': 3,
  '/admin/users': 3,
  '/admin/flags': 3,
  '/admin/lessons': 3,
  '/admin/settings': 3,
  '/admin/usage': 6,
  '/teacher/lessons': 3,
  '/teacher/lessons/new': 3,
  '/teacher/students': 4,
  '/teacher/questions': 4,
  '/teacher/announcements': 4,
  '/management/complaints': 5,
  '/management/usage': 5,
  '/management/teachers': 5,
};
