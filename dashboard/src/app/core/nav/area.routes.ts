import { CanActivateFn, Routes } from '@angular/router';
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
      children: area.screens.map((screen) => ({
        path: screen.path,
        canActivate: gatesOf(screen),
        loadComponent: () => componentFor(screen),
      })),
    },
  ];
}

/**
 * The Home is the one screen of an area that exists from P1.0; `lessons` (P3.2b), `new-lesson`
 * (P3.2c) and `lesson` (P3.2d) are next — all three serve `/admin/**` and `/teacher/**` from
 * one component, keyed by `id` rather than by path. Everything else is the stub until its
 * phase lands, and the stub reads the phase back out of the same table.
 */
function componentFor(screen: Screen) {
  if (screen.path === '') return import('../../features/home/home.page').then((m) => m.HomePage);
  if (screen.id === 'lessons') return import('../../features/lessons/lessons.page').then((m) => m.LessonsPage);
  if (screen.id === 'new-lesson') {
    return import('../../features/lessons/new-lesson.page').then((m) => m.NewLessonPage);
  }
  if (screen.id === 'lesson') return import('../../features/lessons/lesson.page').then((m) => m.LessonPage);
  return import('../../features/stub/stub.page').then((m) => m.StubPage);
}

function gatesOf(screen: Screen): CanActivateFn[] {
  const gates: CanActivateFn[] = [];
  if (screen.flag !== undefined) gates.push(featureGuard(screen.flag));
  if (screen.permission !== undefined) gates.push(canGuard(screen.permission));
  return gates;
}
