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
                data: { readOnly: screen.readOnly === true },
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
  // R5: the coordinator's four screens are her own — `GET /home`, `/teacher/**` and `/admin/**`
  // all answer 403 for her — except the lesson, which is the teacher's page in read-only mode.
  if (role === 'COORDINATOR' && screen.id !== 'lesson') return coordinatorComponentFor(screen);
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
  if (screen.id === 'chat') return import('../../features/chat/chat.page').then((m) => m.ChatPage);
  return import('../../features/stub/stub.page').then((m) => m.StubPage);
}

/** R5's area, one lazy chunk per screen so her Home never carries the calendar or the filters. */
function coordinatorComponentFor(screen: Screen) {
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
