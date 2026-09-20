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
      children: area.screens.map((screen) =>
        // A redirect row (N2.2's `/teacher` → `/teacher/week`) carries no guards: Angular
        // resolves `redirectTo` before it runs them, and the row it points at is guarded anyway.
        screen.redirectTo === undefined
          ? {
              path: screen.path,
              canActivate: gatesOf(screen),
              canDeactivate: exitGatesOf(screen),
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
  if (screen.id === 'classes')
    return role === 'TEACHER'
      ? import('../../features/classes/my-classes.page').then((m) => m.MyClassesPage)
      : import('../../features/admin/classes.page').then((m) => m.ClassesPage);
  if (screen.id === 'class') return import('../../features/classes/class.page').then((m) => m.ClassPage);
  if (screen.id === 'teachers' && role === 'ADMIN') {
    return import('../../features/admin/teachers.page').then((m) => m.TeachersPage);
  }
  return import('../../features/stub/stub.page').then((m) => m.StubPage);
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
