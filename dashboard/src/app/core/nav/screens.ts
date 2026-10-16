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
   * Absolute (`/management`) for MG2a's retired rows, which leave the rail entirely.
   *
   * N2.2: a teacher's `/teacher` is her week, not a Home of her own. A redirect row rather than
   * a second route declared beside the table, so the rail, the router and `ROLE_HOME` still have
   * exactly one place to disagree — none.
   */
  readonly redirectTo?: string;
  readonly fullLink?: string;
  /**
   * R5: this row opens a screen that may only be read.
   *
   * It becomes `data.readOnly` on the route (`area.routes.ts`), which is what the lesson page
   * reads to draw itself without a single write control. A flag on the *row* rather than a
   * signal inside the page, because the same component serves three areas and only the table
   * knows which of them is the coordinator's.
   */
  readonly readOnly?: boolean;
  /**
   * A door to the multi-school build: in the rail only while `NAV_CONFIG.schoolSurfaces` is on
   * (`core/nav/nav-config.ts`). The route is unaffected — hidden in design, not removed.
   */
  readonly schoolSurface?: boolean;
}

export interface Area {
  readonly base: string;
  readonly screens: readonly Screen[];
}

/**
 * MG2a: the paths the manager's rail lost — Classes, All lessons and the read-only lesson,
 * Gradebook, Exams and the three result screens they opened.
 *
 * Rows rather than deletions ({@link retired}). The `/management/**` API routes are untouched —
 * the coordinator reads the same screens through her own namespace.
 */
const MANAGER_RETIRED: readonly string[] = [
  'classes',
  'lessons',
  'lessons/:id',
  'lessons/:id/results',
  'gradebook',
  'exams',
  'exams/:id/results',
  'children/:childId',
];

/**
 * MA0 (the owner's admin list, 2026-09-30): the paths the **Admin's** rail lost — Feature flags,
 * All lessons, Platform usage and Platform settings.
 *
 * Three of the four never drew anything of their own: they were the stub naming a phase, and the
 * owner's answer to "what is coming" is that it is not. All lessons was a real screen
 * (`features/lessons/lessons.page.ts`), and it stays — the *teacher* opens it from her class page;
 * what went is the Admin's row and the Admin's way in to it.
 *
 * `lessons/new` and `lessons/:id` are **not** here. The new-lesson wizard and the lesson review
 * page are the Admin's authoring pair, the server's Home links straight at a lesson that needs
 * her (`/admin/lessons/{id}`, `core/notifications/notification-target.ts`), and the wizard is now
 * reached from her Home's quick actions rather than from the list's primary button.
 */
const ADMIN_RETIRED: readonly string[] = ['flags', 'lessons', 'usage', 'settings'];

/**
 * A row that draws nothing and sends the address to the area's Home.
 *
 * Rows rather than deletions, for the reason `/coordinator/announcements` is one: a bookmark, a
 * link in an old notification and the runbook's own URL all still have to land somewhere, and
 * where they land is a Home rather than `/not-found`.
 */
const retired =
  (base: string) =>
  (path: string): Screen => ({ id: `retired:${path}`, path, redirectTo: base });

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
      // Feature 20: the floors her classes are grouped into, beside Classes because that is what
      // a floor is — a named group of them in one academy. `floor.manage` is ADMIN's alone, and
      // the row (like the whole feature) only exists while `floorSupervisor` is on.
      {
        id: 'floors',
        path: 'floors',
        labelKey: 'nav.floors',
        flag: FLAGS.floorSupervisor,
        permission: 'floor.manage',
      },
      { id: 'teachers', path: 'teachers', labelKey: 'nav.teachers', permission: 'teacher.read' },
      // MA2 (the owner's admin list, 2026-09-30, items 3–5): the four people screens that were
      // missing. Their order is the order she reads them in — the three staff lists that carry a
      // login, then the staff who have none, then the families.
      //
      // Coordinators and Managers are **one component** (`admin/staff-accounts.page.ts`), told
      // which it is by `data.screenId`. Neither carries a flag, for the reason Teachers does not:
      // a school with classes has the people who run them, and `FlagKeys` has no key for either.
      {
        id: 'coordinators',
        path: 'coordinators',
        labelKey: 'nav.coordinators',
        permission: 'coordinator.manage',
      },
      { id: 'managers', path: 'managers', labelKey: 'nav.managers', permission: 'manager.manage' },
      // Feature 20: the third staff list that carries a login, and the same component as the two
      // above it (`data.screenId` says which). Her floors are assigned on the Floors screen, so
      // this one has no scopes editor at all.
      {
        id: 'floor-supervisors',
        path: 'floor-supervisors',
        labelKey: 'nav.floorSupervisors',
        flag: FLAGS.floorSupervisor,
        permission: 'floor.manage',
      },
      // `worker.read` and not `worker.write`, the way Teachers reads with `teacher.read`: the key on
      // the row has to be the one the GET carries, or a read-only "View as" session would be shown
      // no list at all rather than a list with no buttons. Every action inside is `worker.write`.
      { id: 'workers', path: 'workers', labelKey: 'nav.workers', permission: 'worker.read' },
      {
        id: 'children',
        path: 'children',
        labelKey: 'nav.childrenParents',
        permission: 'admin.children.read',
      },
      // Feature 14: where and when staff must be present, and every employee's direct manager.
      // Read with `attendance.settings.read` so a read-only "View as" session still sees the page.
      {
        id: 'attendance-settings',
        path: 'attendance-settings',
        labelKey: 'nav.attendanceSettings',
        flag: FLAGS.staffAttendance,
        permission: 'attendance.settings.read',
      },
      // Feature 18: the remote days and corrections her people ask for. One screen in four areas
      // — the server answers each caller with the requests *she* decides — and `attendance.correction.write`
      // (the Admin's direct correction) is the only part of it she alone has.
      {
        id: 'attendance-requests',
        path: 'attendance-requests',
        labelKey: 'nav.attendanceRequests',
        flag: FLAGS.staffAttendance,
        permission: 'attendance.requests.decide',
      },
      // Feature 17: the leaders' board and monthly report. The Admin's view is the whole school.
      {
        id: 'staff-attendance',
        path: 'staff-attendance',
        labelKey: 'nav.staffAttendance',
        flag: FLAGS.staffAttendance,
        permission: 'attendance.board',
      },
      // Feature 31: the leaders' board over the weekly teaching material — the same teachers the
      // Staff attendance board shows this leader (`GET /materials/board` is scoped by feature 17's
      // own `BoardScope`), so there is one answer to "whose material do I see" and not two.
      {
        id: 'materials',
        path: 'materials',
        labelKey: 'nav.materials',
        flag: FLAGS.teachingMaterials,
        permission: 'materials.board',
      },
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
        schoolSurface: true,
      },
      { id: 'school', path: 'schools/:id', flag: FLAGS.multiSchool, permission: 'school.read', phase: 3 },
      {
        id: 'school-users',
        path: 'schools/:id/users',
        flag: FLAGS.multiSchool,
        permission: 'user.read',
        phase: 3,
      },
      {
        id: 'users',
        path: 'users',
        labelKey: 'nav.users',
        permission: 'user.read',
        phase: 3,
        schoolSurface: true,
      },
      // RM3b: her side of the manager ↔ admin threads. `chat.support` and not `admin.chat`,
      // because the key that opens the screen has to be the one the GET carries — `admin.chat`
      // is a write (it covers the send), and gating the route on it would take the inbox away
      // from a read-only "View as" session rather than only the composer.
      {
        id: 'messages',
        path: 'messages',
        labelKey: 'nav.messages',
        flag: FLAGS.chat,
        permission: 'chat.support',
      },
      // D5: every complaint of the school, read-only (`GET /admin/complaints`, B6) — support, not a
      // party to any of them. `chat`, because a complaint is stored and delivered as a conversation.
      {
        id: 'complaints',
        path: 'complaints',
        labelKey: 'nav.complaints',
        flag: FLAGS.chat,
        permission: 'admin.complaints',
      },
      // Feature 22: **Announcements**, the Admin's own row at last. The screen is the one all
      // three staff roles read (`features/broadcasts/announcements.page.ts`) and she always held
      // `management.broadcast`; what she had no door to was writing one. Same flag and same key as
      // the manager's row — `broadcast.read` is the feed's key, and the composer inside is gated
      // on her write key — because it is the same screen.
      {
        id: 'announcements',
        path: 'announcements',
        labelKey: 'nav.announcements',
        flag: FLAGS.announcements,
        permission: 'broadcast.read',
      },
      // Feature 26: **Document reader**, under Announcements because that is what it feeds — a
      // ministry circular in, and the compose sheet one tap later with the announcement in it.
      // Her own flag, not the feed's: a school may read its circulars without posting a thing.
      {
        id: 'documents',
        path: 'documents',
        labelKey: 'nav.documents',
        flag: FLAGS.documentReader,
        permission: 'document.read',
      },
      // lessons (P3.2c/d): the new-lesson wizard and the review page. Neither has a `labelKey`
      // — the wizard is a quick action on her Home and the review page is what the Home's
      // "needs you" rows and the bell's notifications open, not rail items of their own. The
      // list they used to be reached from is in {@link ADMIN_RETIRED}.
      { id: 'new-lesson', path: 'lessons/new', permission: 'lesson.write' },
      { id: 'lesson', path: 'lessons/:id', permission: 'lesson.read' },
      ...ADMIN_RETIRED.map(retired('/admin')),
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
      // Feature 18: a teacher is a direct manager too — of the assistants and workers under her —
      // so she gets the same screen and the same key. A teacher who manages nobody is shown an
      // empty list rather than hidden the row: the server, not the rail, knows whose reports are
      // whose, and `attendance.requests.decide` is held by every one of the four staff roles.
      {
        id: 'attendance-requests',
        path: 'attendance-requests',
        labelKey: 'nav.attendanceRequests',
        flag: FLAGS.staffAttendance,
        permission: 'attendance.requests.decide',
      },
      // Her lessons lost their rail label with N2.3: §4's rail is This week · My classes, and
      // the list is one tap away as "All lessons of this class". The route stays — a bookmark
      // and `/teacher/lessons?classId=…` from the class page both have to resolve.
      { id: 'lessons', path: 'lessons', permission: 'lesson.read' },
      { id: 'new-lesson', path: 'lessons/new', permission: 'lesson.write' },
      { id: 'lesson', path: 'lessons/:id', permission: 'lesson.read' },
      // N4.2 (§4 step 9). Neither is a rail item: Results is opened from the lesson she is
      // looking at — its header, its card in This week, its square in the calendar — and the
      // child page from a gradebook row or the Children tab. Both are behind `gradebook`,
      // which is the flag the server's `GradingController` carries, so a school without it
      // gets the same answer from the router as from the API instead of a screen of red bands.
      {
        id: 'results',
        path: 'lessons/:id/results',
        flag: FLAGS.gradebook,
        permission: 'results.read',
      },
      { id: 'child', path: 'children/:childId', flag: FLAGS.gradebook, permission: 'results.read' },
      // N4.4 (§4 step 10). Neither is a rail item either: New exam is the class page's Exams
      // tab's own primary action, and the results page is opened from a row of that tab or from
      // the exam's card in This week. Both carry `exams` — the flag `ExamController` puts over
      // every one of its routes — so a school without it meets the same 404 in the router as in
      // the API. The exam's *editor* is `lessons/:id`: an exam is a lesson, and giving it a
      // second route would be a second copy of the editor to keep in step.
      { id: 'new-exam', path: 'exams/new', flag: FLAGS.exams, permission: 'lesson.write' },
      {
        id: 'exam-results',
        path: 'exams/:id/results',
        flag: FLAGS.exams,
        permission: 'results.read',
      },
      // No permission yet: `child.read` in permissions.json belongs to PARENT, and the key for
      // a teacher reading her own students arrives with P4.0's endpoints. Gating on the
      // parent's key would hide the item from every teacher.
      { id: 'students', path: 'students', phase: 4 },
      { id: 'questions', path: 'questions', flag: FLAGS.teacherQuestions, phase: 4 },
      // RM3b: the department's announcements and events, which is what the `announcements` stub
      // was standing in for — and, on its Weekly plans tab, the plan she reads every Sunday. MH2
      // item 5 gave the row the name the app has always used for what is on it.
      {
        id: 'announcements',
        path: 'announcements',
        labelKey: 'nav.announcements',
        flag: FLAGS.announcements,
        permission: 'broadcast.read',
      },
      { id: 'broadcasts', path: 'broadcasts', redirectTo: 'announcements' },
      // MG2b: **a rail row of her own.** It had none — she reached the screen from a roster row's
      // "Message parent" (a *parent* conversation, by `?childId=`) and from the header's chat icon —
      // so a thread the department manager started was invisible unless she happened to look. The
      // rail carries the unread count too (`shell.component.ts`), which is what makes it findable
      // rather than merely present.
      {
        id: 'chat',
        path: 'chat',
        labelKey: 'nav.messages',
        flag: FLAGS.chat,
        permission: 'teacher.chat',
      },
      // D5 (owner, 2026-10-03: "Complaints must be separate from messages"): the complaints parents
      // address to her, on B6's `/teacher/complaints`, beside Messages and never inside it.
      {
        id: 'complaints',
        path: 'complaints',
        labelKey: 'nav.complaints',
        flag: FLAGS.chat,
        permission: 'teacher.complaints',
      },
      // T2 (the owner's list, 2026-10-01): **who she reports to, and how to reach them.** Both rows
      // carry `chat` and `teacher.chat`, which is what `GET /teacher/coordinators`,
      // `GET /teacher/managers` and `POST /teacher/chat/staff-threads` carry — the directory is the
      // chooser for a conversation, so a school without chat has neither. Coordinators first: it is
      // the one she reaches for, and her manager is a step further up.
      {
        id: 'coordinators',
        path: 'coordinators',
        labelKey: 'nav.coordinators',
        flag: FLAGS.chat,
        permission: 'teacher.chat',
      },
      {
        id: 'manager',
        path: 'manager',
        labelKey: 'nav.manager',
        flag: FLAGS.chat,
        permission: 'teacher.chat',
      },
      // Feature 31: her own teaching material (دفتر التحضير) — one row per class and subject her
      // assignments give her, for this week and the next. `materials.self` is the teacher's alone;
      // the board row below it is the one every leader has.
      {
        id: 'my-materials',
        path: 'my-materials',
        labelKey: 'nav.myMaterials',
        flag: FLAGS.teachingMaterials,
        permission: 'materials.self',
      },
      // Feature 17: the leaders' board and monthly report. Every teacher and coordinator holds
      // `attendance.board` because any of them may be named someone's direct manager; the server
      // answers each only the people she leads, and an empty board says she leads nobody.
      {
        id: 'staff-attendance',
        path: 'staff-attendance',
        labelKey: 'nav.staffAttendance',
        flag: FLAGS.staffAttendance,
        permission: 'attendance.board',
      },
      // Feature 31: the leaders' board over the weekly teaching material — the same teachers the
      // Staff attendance board shows this leader (`GET /materials/board` is scoped by feature 17's
      // own `BoardScope`), so there is one answer to "whose material do I see" and not two.
      {
        id: 'materials',
        path: 'materials',
        labelKey: 'nav.materials',
        flag: FLAGS.teachingMaterials,
        permission: 'materials.board',
      },
    ],
  },
  // RM3a (DR5, DR7, `docs/management-flow.md`): the department manager's area. RM1 gave her
  // `/management/**` — one curriculum across every grade, every subject, read-only — so her rows
  // are the coordinator's rows with a wider scope, plus the two screens only she has: the people
  // directory and the staff register, the one thing in the whole namespace she writes.
  //
  // Every row carries one of RM1's own keys (`management.*`) rather than the tenant-wide
  // `teacher.read` / `section.read` the stubs used to: those two are the Admin's keys, which a
  // MANAGERIAL account happens to hold, and gating her area on them would have opened her screens
  // to an Admin's data the moment one of them stopped being department-scoped.
  MANAGERIAL: {
    base: '/management',
    screens: [
      { id: 'home', path: '', labelKey: 'nav.home' },
      {
        id: 'coordinators',
        path: 'coordinators',
        labelKey: 'nav.coordinators',
        permission: 'management.read',
      },
      { id: 'teachers', path: 'teachers', labelKey: 'nav.teachers', permission: 'management.read' },
      // Attendance carries no flag — a school with classes has registers — and stays after MG2a
      // because it is the one record screen she asked to keep.
      {
        id: 'attendance',
        path: 'attendance',
        labelKey: 'nav.attendance',
        permission: 'management.attendance.read',
        readOnly: true,
      },
      // RM5's two, and neither carries a flag: `ManagementPeopleController` is in the server's
      // own `FeatureFlagCoverageTest.INFRASTRUCTURE` list, because taking the staff register is
      // not an optional feature of a school and `FlagKeys` has no key for it.
      //
      // MH2 item 3: **Children**, not People. Its teachers and coordinators tabs were the two
      // lists the rows above already draw with a phone number and a Message action, so what is
      // left is the one list nothing else has — and the row says so.
      { id: 'children', path: 'children', labelKey: 'nav.children', permission: 'management.people' },
      { id: 'people', path: 'people', redirectTo: 'children' },
      {
        id: 'staff-attendance',
        path: 'staff-attendance',
        labelKey: 'nav.staffAttendance',
        permission: 'management.staff.attendance',
      },
      // Feature 31: the leaders' board over the weekly teaching material — the same teachers the
      // Staff attendance board shows this leader (`GET /materials/board` is scoped by feature 17's
      // own `BoardScope`), so there is one answer to "whose material do I see" and not two.
      {
        id: 'materials',
        path: 'materials',
        labelKey: 'nav.materials',
        flag: FLAGS.teachingMaterials,
        permission: 'materials.board',
      },
      // Feature 18: her direct reports' requests. Not `readOnly` — deciding them is the one thing
      // in the whole `/management` namespace she does by tapping a button, and the row carries
      // the write key the server's approve and reject carry.
      {
        id: 'attendance-requests',
        path: 'attendance-requests',
        labelKey: 'nav.attendanceRequests',
        flag: FLAGS.staffAttendance,
        permission: 'attendance.requests.decide',
      },
      // Feature 14: the rules her people are held to — hers to read, the Admin's to change.
      {
        id: 'attendance-settings',
        path: 'attendance-settings',
        labelKey: 'nav.attendanceSettings',
        flag: FLAGS.staffAttendance,
        permission: 'attendance.settings.read',
        readOnly: true,
      },
      // MG2b (owner's items 3 and 4): the weekly plan per grade, and the archive of every one of
      // them. Her own row rather than a tab on Broadcasts, because it is the screen she *writes*
      // the week from; a teacher and a coordinator read the same archive one tab over there.
      {
        id: 'weekly-plans',
        path: 'weekly-plans',
        labelKey: 'nav.weeklyPlans',
        flag: FLAGS.announcements,
        permission: 'management.broadcast',
      },
      // MH2 item 5: **Announcements**. RM3b called the screen Broadcasts because it carried three
      // kinds; the weekly plan now has the row above it entirely, so what is left is the
      // announcements and the events — which is the word the app has always used for them.
      {
        id: 'announcements',
        path: 'announcements',
        labelKey: 'nav.announcements',
        flag: FLAGS.announcements,
        permission: 'broadcast.read',
      },
      { id: 'broadcasts', path: 'broadcasts', redirectTo: 'announcements' },
      // Feature 26: the same Document reader the Admin has, on the same key — `document.read` is
      // hers too (`permissions.json`), and a circular from the ministry reaches a department
      // manager as often as it reaches the office.
      {
        id: 'documents',
        path: 'documents',
        labelKey: 'nav.documents',
        flag: FLAGS.documentReader,
        permission: 'document.read',
      },
      // RM3b: her real inbox, on RM2's `GET /management/chat/threads` — parents of her
      // department, her coordinators and the admin.
      {
        id: 'messages',
        path: 'messages',
        labelKey: 'nav.messages',
        flag: FLAGS.chat,
        permission: 'management.chat',
      },
      // D5: B6's `/management/complaints` — hers, and (read-only, but she may resolve or reopen)
      // those to her department's teachers and coordinators. `?open={id}` opens one, so the
      // phase-5 `complaints/:id` stub is gone.
      {
        id: 'complaints',
        path: 'complaints',
        labelKey: 'nav.complaints',
        flag: FLAGS.chat,
        permission: 'management.complaints',
      },
      // MG2a: built, on `GET /school/usage`. The key is the server's own (`usage.school`), which
      // is what `mySchoolUsage` is gated by — and the reason the screen says in words that the
      // numbers are the *school's*, not her department's.
      { id: 'usage', path: 'usage', labelKey: 'nav.schoolUsage', permission: 'usage.school' },
      ...MANAGER_RETIRED.map(retired('/management')),
    ],
  },
  // R5 (DR2, `docs/coordinator-flow.md`): the subject coordinator's area. Every row carries one
  // of the two keys `/coordinator/**` is gated by and nothing else — she holds no `lesson.*`,
  // `calendar.read` or `results.read` at all (`permissions.json`), which is what makes the
  // shared lesson page draw itself with no write control rather than with disabled ones.
  //
  // R6 adds Attendance, Gradebook and Exams to the rail, and the three detail screens they open:
  // a lesson's results, an exam's results and a child's report. All six are the teacher's own
  // components in read-only mode, keyed by `readOnly` on the row.
  COORDINATOR: {
    base: '/coordinator',
    screens: [
      // No permission on the Home, like every other area's: it is what `/` redirects to, and a
      // redirect target that can be refused has nowhere to send her. `roleGuard` is what keeps
      // everyone but her (and an Admin) out of `/coordinator/**`.
      { id: 'home', path: '', labelKey: 'nav.home' },
      { id: 'teachers', path: 'teachers', labelKey: 'nav.teachers', permission: 'coordinator.read' },
      { id: 'classes', path: 'classes', labelKey: 'nav.classes', permission: 'coordinator.read' },
      { id: 'lessons', path: 'lessons', labelKey: 'nav.allLessons', permission: 'coordinator.lesson.read' },
      // No `labelKey`: a lesson is opened from a row, a card or a calendar square, never from
      // the rail. `readOnly` is the whole of R5's "reuse the teacher lesson page in read mode".
      { id: 'lesson', path: 'lessons/:id', permission: 'coordinator.lesson.read', readOnly: true },
      // R6. Attendance carries no flag — a school with classes has registers, and the teacher's
      // own marking tab has none either. Gradebook and Exams carry the two flags their
      // controllers carry, so a school without one meets the same `/not-found` in the router as
      // the 404 the API would answer, rather than a screen of red bands.
      {
        id: 'attendance',
        path: 'attendance',
        labelKey: 'nav.attendance',
        permission: 'coordinator.attendance.read',
        readOnly: true,
      },
      // Feature 18: her teachers' remote days and corrections. The one row of hers that is not
      // read-only and the one that carries a key from outside `coordinator.*` — the decision is
      // the same server route for all four roles, so it is the same permission for all four.
      {
        id: 'attendance-requests',
        path: 'attendance-requests',
        labelKey: 'nav.attendanceRequests',
        flag: FLAGS.staffAttendance,
        permission: 'attendance.requests.decide',
      },
      {
        id: 'gradebook',
        path: 'gradebook',
        labelKey: 'nav.gradebook',
        flag: FLAGS.gradebook,
        permission: 'coordinator.results.read',
        readOnly: true,
      },
      {
        id: 'exams',
        path: 'exams',
        labelKey: 'nav.exams',
        flag: FLAGS.exams,
        permission: 'coordinator.exams.read',
        readOnly: true,
      },
      // No `labelKey` on the three below: each is opened from a row of the screen above it — a
      // lesson column of the gradebook, an exam of the list, a child's name in either — never
      // from the rail. All three are the teacher's page, read-only.
      {
        id: 'results',
        path: 'lessons/:id/results',
        flag: FLAGS.gradebook,
        permission: 'coordinator.results.read',
        readOnly: true,
      },
      {
        id: 'child',
        path: 'children/:childId',
        flag: FLAGS.gradebook,
        permission: 'coordinator.results.read',
        readOnly: true,
      },
      {
        id: 'exam-results',
        path: 'exams/:id/results',
        flag: FLAGS.exams,
        permission: 'coordinator.exams.read',
        readOnly: true,
      },
      // R7 (DR4), D5. All three carry the `chat` flag the server routes carry — a complaint is its
      // own conversation since B6, stored and delivered as one, not N5.2's complaints store — plus
      // her own key, so a school without chat has none of the three.
      {
        id: 'messages',
        path: 'messages',
        labelKey: 'nav.messages',
        flag: FLAGS.chat,
        permission: 'coordinator.chat',
      },
      {
        id: 'complaints',
        path: 'complaints',
        labelKey: 'nav.complaints',
        flag: FLAGS.chat,
        permission: 'coordinator.complaints',
      },
      // T2 item (b): the teacher's row, one namespace over — `GET /coordinator/managers` and
      // `POST /coordinator/chat/threads {managerUserId}`, the same screen.
      {
        id: 'manager',
        path: 'manager',
        labelKey: 'nav.manager',
        flag: FLAGS.chat,
        permission: 'coordinator.chat',
      },
      // RM3b: her announcements *are* broadcasts now — `POST /coordinator/broadcasts` writes the
      // `announcements` rows the app's shipped screen reads as a side effect, so one screen with
      // a kind and a title replaces two that would have posted the same note to two tables. MH2
      // item 5 put the rail item she learned in R7 back on the door: `announcements` is the row
      // and `broadcasts`, which RM3b made the path, is now the redirect.
      {
        id: 'announcements',
        path: 'announcements',
        labelKey: 'nav.announcements',
        flag: FLAGS.announcements,
        permission: 'broadcast.read',
      },
      { id: 'broadcasts', path: 'broadcasts', redirectTo: 'announcements' },
      // Feature 17: the leaders' board and monthly report. Every teacher and coordinator holds
      // `attendance.board` because any of them may be named someone's direct manager; the server
      // answers each only the people she leads, and an empty board says she leads nobody.
      {
        id: 'staff-attendance',
        path: 'staff-attendance',
        labelKey: 'nav.staffAttendance',
        flag: FLAGS.staffAttendance,
        permission: 'attendance.board',
      },
      // Feature 31: the leaders' board over the weekly teaching material — the same teachers the
      // Staff attendance board shows this leader (`GET /materials/board` is scoped by feature 17's
      // own `BoardScope`), so there is one answer to "whose material do I see" and not two.
      {
        id: 'materials',
        path: 'materials',
        labelKey: 'nav.materials',
        flag: FLAGS.teachingMaterials,
        permission: 'materials.board',
      },
    ],
  },
  // Feature 20 (`docs/features/20-floor-supervisor-role.md`): the floor supervisor's area. She
  // looks *across* the classes on her floor rather than down one subject, so her rail is short on
  // purpose — her Home, the children of her floor, and the people she talks to.
  //
  // **Every row carries `FLAGS.floorSupervisor`**, which no other area does. The two read-only
  // supervisors are roles the build has always had; she is a whole feature, and a school that has
  // not turned it on has no floors, no supervisors and nothing for these screens to draw. The
  // router answers `/not-found` for the same reason the server answers 404.
  FLOOR_SUPERVISOR: {
    base: '/floor',
    screens: [
      // No permission on the Home, as in every other area: it is what `/` redirects to, and a
      // redirect target that can be refused has nowhere to send her. The flag stays, because a
      // school without the feature has no Home here either — `roleGuard` keeps everyone else out.
      { id: 'home', path: '', labelKey: 'nav.home', flag: FLAGS.floorSupervisor },
      {
        id: 'children',
        path: 'children',
        labelKey: 'nav.children',
        flag: FLAGS.floorSupervisor,
        permission: 'floor.read',
      },
      // The teacher's child report, read-only, through `/floor/children/{id}` — no `labelKey`,
      // because it is opened from a row of the screen above and never from the rail.
      {
        id: 'child',
        path: 'children/:childId',
        flag: FLAGS.floorSupervisor,
        permission: 'floor.read',
        readOnly: true,
      },
      {
        id: 'messages',
        path: 'messages',
        labelKey: 'nav.messages',
        flag: FLAGS.floorSupervisor,
        permission: 'floor.chat',
      },
      // Features 17 and 18, one namespace over. Both carry **two** flags' worth of truth and only
      // one slot for a flag: `floorSupervisor` is the one that decides whether she exists at all,
      // so it is the one on the row, and the board's own `staffAttendance` gate is the server's
      // (her `/staff-attendance/board` answers 404 without it) and `*hqFeature`'s inside the page.
      {
        id: 'staff-attendance',
        path: 'staff-attendance',
        labelKey: 'nav.staffAttendance',
        flag: FLAGS.staffAttendance,
        permission: 'attendance.board',
      },
      {
        id: 'attendance-requests',
        path: 'attendance-requests',
        labelKey: 'nav.attendanceRequests',
        flag: FLAGS.staffAttendance,
        permission: 'attendance.requests.decide',
      },
      // Feature 31: the leaders' board over the weekly teaching material — the same teachers the
      // Staff attendance board shows this leader (`GET /materials/board` is scoped by feature 17's
      // own `BoardScope`), so there is one answer to "whose material do I see" and not two.
      {
        id: 'materials',
        path: 'materials',
        labelKey: 'nav.materials',
        flag: FLAGS.teachingMaterials,
        permission: 'materials.board',
      },
    ],
  },
};

/** The full route of a screen, e.g. `/admin/schools/:id`. */
export function linkOf(area: Area, screen: Screen): string {
  if (screen.fullLink) return screen.fullLink;
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
