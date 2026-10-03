import { CanActivateFn, CanDeactivateFn, Routes } from '@angular/router';
import { lessonUnsavedGuard } from '../../features/lessons/lesson-unsaved.guard';
import type { LessonPage } from '../../features/lessons/lesson.page';
import { roleGuard } from '../auth/auth.guards';
import type { Role } from '../auth/auth.service';
import { featureGuard } from '../flags/feature.guard';
import { canGuard } from '../permissions/can.guard';
import { AREAS, type Screen } from './screens';

/**
 * Builds a role's routes from {@link AREAS} — the same table the rail is built from.
 *
 * This is the point of the table. A menu item hidden because a flag is off or a permission is
 * missing, behind a route that opens anyway, is not a closed door: it is a door with the
 * handle painted over. Generating both from one row means a screen cannot be gated in the menu
 * and ungated in the router, in either direction, ever.
 *
 * The guards run in the order listed, and the order is deliberate: role, then flag, then
 * permission. "This school does not have that feature" is a different answer from "your
 * account may not", and asking them the other way round would send someone off to request a
 * permission for a feature that is simply switched off.
 */
export function areaRoutes(role: Role): Routes {
  const area = AREAS[role];
  return [
    {
      path: '',
      canActivate: [roleGuard(role)],
      children: area.screens
        .filter((screen) => !screen.fullLink)
        .map((screen) =>
          // A redirect row (N2.2's `/teacher` → `/teacher/week`) carries no guards: Angular
          // resolves `redirectTo` before it runs them, and the row it points at is guarded anyway.
          screen.redirectTo === undefined
            ? {
                path: screen.path,
                canActivate: gatesOf(screen),
                canDeactivate: exitGatesOf(screen),
                // R5: `data.readOnly` is how the row's read-only flag reaches the component. The
                // key is always present so a page reading it never has to tell "false" from
                // "this route forgot to say".
                //
                // MA2: `screenId` for the same reason — one component serving two rows (the
                // Admin's Coordinators and Managers) needs to know which row opened it, and the
                // route is what the rail already agrees with. An `input()` would have been a
                // second declaration of "this URL is the managers one".
                data: { readOnly: screen.readOnly === true, screenId: screen.id },
                loadComponent: () => componentFor(screen, role),
              }
            : { path: screen.path, pathMatch: 'full' as const, redirectTo: screen.redirectTo },
        ),
    },
  ];
}

/**
 * The Home is the one screen of an area that exists from P1.0; `lessons` (P3.2b), `new-lesson`
 * (P3.2c) and `lesson` (P3.2d) are next — all three serve `/admin/**` and `/teacher/**` from
 * one component, keyed by `id` rather than by path. Everything else is the stub until its
 * phase lands, and the stub reads the phase back out of the same table.
 *
 * `teachers` and `classes` are the ids two areas share and two different screens answer: the
 * Admin's staff list (N1.2) and the Managerial read-only one (N5); the Admin's sections table
 * and the teacher's My classes (N2.3). Both are keyed by role as well as by id.
 */
function componentFor(screen: Screen, role: Role) {
  // R5/R6, RM3a: a supervisor's list screens are her own — `GET /home`, `/teacher/**` and
  // `/admin/**` all answer 403 for her — while the four detail screens are the teacher's pages
  // in read-only mode, reading her namespace through `LessonApiService`/`ResultsApiService`.
  if ((role === 'COORDINATOR' || role === 'MANAGERIAL') && !SHARED_WITH_TEACHER.has(screen.id)) {
    return supervisorComponentFor(screen, role);
  }
  if (screen.path === '') return import('../../features/home/home.page').then((m) => m.HomePage);
  if (screen.id === 'week') return import('../../features/week/week.page').then((m) => m.WeekPage);
  if (screen.id === 'lessons')
    return import('../../features/lessons/lessons.page').then((m) => m.LessonsPage);
  if (screen.id === 'new-lesson') {
    return import('../../features/lessons/new-lesson.page').then((m) => m.NewLessonPage);
  }
  if (screen.id === 'lesson') return import('../../features/lessons/lesson.page').then((m) => m.LessonPage);
  // N4.2: both are their own lazy chunks. Results pulls in the marking editor and the media
  // reader, the child page the SVG chart — none of which a teacher who never opens a score
  // should download, and none of which may land in the initial bundle (the 520 kB budget).
  if (screen.id === 'results')
    return import('../../features/results/results.page').then((m) => m.ResultsPage);
  if (screen.id === 'child') return import('../../features/results/child.page').then((m) => m.ChildPage);
  // N4.4: two more lazy chunks, for the same reason. The exam results page pulls in the
  // distribution chart and the marking panel; New exam pulls in the upload chain.
  if (screen.id === 'new-exam')
    return import('../../features/exams/new-exam.page').then((m) => m.NewExamPage);
  if (screen.id === 'exam-results')
    return import('../../features/exams/exam-results.page').then((m) => m.ExamResultsPage);
  if (screen.id === 'classes')
    return role === 'TEACHER'
      ? import('../../features/classes/my-classes.page').then((m) => m.MyClassesPage)
      : import('../../features/admin/classes.page').then((m) => m.ClassesPage);
  if (screen.id === 'class') return import('../../features/classes/class.page').then((m) => m.ClassPage);
  if (screen.id === 'teachers' && role === 'ADMIN') {
    return import('../../features/admin/teachers.page').then((m) => m.TeachersPage);
  }
  // MA2. Coordinators and Managers are **one** chunk: the same list, create, edit, reset and
  // scopes editor, told which account it is by `data.screenId`. A coordinator holds (subject,
  // track) pairs and a manager holds whole curricula, and that is the only branch inside.
  if (role === 'ADMIN' && (screen.id === 'coordinators' || screen.id === 'managers')) {
    return import('../../features/admin/staff-accounts.page').then((m) => m.StaffAccountsPage);
  }
  if (screen.id === 'workers') return import('../../features/admin/workers.page').then((m) => m.WorkersPage);
  // The Admin's Children & parents — admission, and the parent login it mints. The *manager's*
  // `children` row is her department's read-only directory and never reaches here
  // (`supervisorComponentFor` takes MANAGERIAL first).
  if (screen.id === 'children' && role === 'ADMIN') {
    return import('../../features/admin/children.page').then((m) => m.ChildrenPage);
  }
  // `chat` is the teacher's door to it, `messages` the manager's (R7): one screen, and for her
  // one fed by the socket alone, because R4 added no thread list a manager may read.
  if (screen.id === 'chat' || screen.id === 'messages')
    return import('../../features/chat/chat.page').then((m) => m.ChatPage);
  // RM3b: one feed for all three staff roles (`GET /me/broadcasts`), with the composer shown to
  // the two that hold a `*.broadcast` key. A fifth entry in `SHARED_WITH_TEACHER` rather than a
  // second copy under `supervisorComponentFor`, because the screen reads the same route for
  // everyone — the namespace it does *not* vary by is the whole reason it is one component.
  if (screen.id === 'announcements')
    return import('../../features/broadcasts/announcements.page').then((m) => m.AnnouncementsPage);
  // D5: one Complaints page for all four areas; `ComplaintsService` picks the routes by role.
  if (screen.id === 'complaints')
    return import('../../features/complaints/complaints.page').then((m) => m.ComplaintsPage);
  // T2 (a)/(b): the staff directory cards, shared by the teacher and the coordinator the way
  // Announcements is — `StaffAreaService` picks the namespace inside the component and the route's
  // `screenId` picks the directory. `coordinators` is the **teacher's** row here; the Admin's row of
  // the same name is her account editor, which is why that branch is role-scoped above.
  if (screen.id === 'manager' || screen.id === 'coordinators')
    return import('../../features/staff/staff-manager.page').then((m) => m.StaffManagerPage);
  return import('../../features/stub/stub.page').then((m) => m.StubPage);
}

/**
 * The screen ids a coordinator opens the **teacher's** component for.
 *
 * Each of the four is the screen with the content in it — a lesson, a lesson's results, an exam's
 * results, a child's report — and a second copy of any of them would drift from the first within
 * a phase. They draw themselves read-only from `data.readOnly` and from the permissions she does
 * not hold, and they read her namespace rather than the teacher's.
 */
const SHARED_WITH_TEACHER = new Set([
  'lesson',
  'results',
  'child',
  'exam-results',
  'announcements',
  // T2: Manager is the coordinator's row too, and the component reads her namespace itself.
  'manager',
  // D5: so is Complaints — `ComplaintsService` reads the role's own `/{area}/complaints`.
  'complaints',
]);

/**
 * The two read-only areas, one lazy chunk per screen so a Home never carries the calendar or the
 * grid.
 *
 * Most of the rows are **the same component twice**: `StaffScopeService` reads
 * `/coordinator/**` or `/management/**` off the role, so Teachers, Classes, All lessons and the
 * three record screens serve a manager unchanged (RM3a's whole point — her screens are the
 * coordinator's with a wider scope). Four rows are hers alone: the department Home, which is
 * DR5's statistics rather than a preview of two lists, the coordinators she manages, the people
 * directory and the staff register.
 */
function supervisorComponentFor(screen: Screen, role: Role) {
  // A row a later package fills: the stub names the phase, and routing it at a coordinator's
  // component would send a manager's request to `/coordinator/**` and a 403.
  if (screen.phase !== undefined) return import('../../features/stub/stub.page').then((m) => m.StubPage);
  if (role === 'MANAGERIAL') {
    if (screen.path === '')
      return import('../../features/management/management-home.page').then((m) => m.ManagementHomePage);
    if (screen.id === 'coordinators')
      return import('../../features/management/management-coordinators.page').then(
        (m) => m.ManagementCoordinatorsPage,
      );
    if (screen.id === 'children')
      return import('../../features/management/management-children.page').then(
        (m) => m.ManagementChildrenPage,
      );
    if (screen.id === 'staff-attendance')
      return import('../../features/management/staff-attendance.page').then((m) => m.StaffAttendancePage);
    // MG2b: the weekly plan per grade, with the archive (`GET /management/weekly-plans`).
    if (screen.id === 'weekly-plans')
      return import('../../features/management/weekly-plans.page').then((m) => m.WeeklyPlansPage);
    // MG2a: `GET /school/usage`, which only she and an Admin hold `usage.school` for.
    if (screen.id === 'usage')
      return import('../../features/management/school-usage.page').then((m) => m.SchoolUsagePage);
  }
  if (screen.id === 'attendance')
    return import('../../features/coordinator/coordinator-attendance.page').then(
      (m) => m.CoordinatorAttendancePage,
    );
  if (screen.id === 'gradebook')
    return import('../../features/coordinator/coordinator-gradebook.page').then(
      (m) => m.CoordinatorGradebookPage,
    );
  if (screen.id === 'exams')
    return import('../../features/coordinator/coordinator-exams.page').then((m) => m.CoordinatorExamsPage);
  // R7: Messages is the teacher's chat screen over her own routes (`core/chat/chat-routes.ts`),
  // so it is the same chunk, not a copy of the list, the conversation and the composer.
  if (screen.id === 'messages') return import('../../features/chat/chat.page').then((m) => m.ChatPage);
  if (screen.id === 'teachers')
    return import('../../features/coordinator/coordinator-teachers.page').then(
      (m) => m.CoordinatorTeachersPage,
    );
  if (screen.id === 'classes')
    return import('../../features/coordinator/coordinator-classes.page').then(
      (m) => m.CoordinatorClassesPage,
    );
  if (screen.id === 'lessons')
    return import('../../features/coordinator/coordinator-lessons.page').then(
      (m) => m.CoordinatorLessonsPage,
    );
  return import('../../features/coordinator/coordinator-home.page').then((m) => m.CoordinatorHomePage);
}

/**
 * The one screen that can hold unsaved work (N2.4's stop and parent-panel editors). The guard
 * imports only the page's *type*, so naming it here adds nothing to the shell's bundle — the
 * component itself stays behind `loadComponent`.
 */
function exitGatesOf(screen: Screen): CanDeactivateFn<LessonPage>[] {
  return screen.id === 'lesson' ? [lessonUnsavedGuard] : [];
}

function gatesOf(screen: Screen): CanActivateFn[] {
  const gates: CanActivateFn[] = [];
  if (screen.flag !== undefined) gates.push(featureGuard(screen.flag));
  if (screen.permission !== undefined) gates.push(canGuard(screen.permission));
  return gates;
}
