import { Route, Routes } from '@angular/router';
import { describe, expect, it } from 'vitest';
import type { Role } from '../auth/auth.service';
import { areaRoutes } from './area.routes';
import { AREAS, linkOf, navScreens, phaseOf } from './screens';

const ROLES: readonly Role[] = ['ADMIN', 'TEACHER', 'MANAGERIAL', 'COORDINATOR'];

/** Guards are closures, so they are counted rather than identified by reference. */
function gateCount(route: Route): number {
  return route.canActivate?.length ?? 0;
}

function childrenOf(routes: Routes): Routes {
  return routes[0]?.children ?? [];
}

describe('the screen table', () => {
  it('gives every screen in the rail a route, and every route a screen', () => {
    for (const role of ROLES) {
      const declared = AREAS[role].screens.map((screen) => screen.path).sort();
      const routed = childrenOf(areaRoutes(role))
        .map((route) => route.path ?? '')
        .sort();

      expect(routed).toEqual(declared);
    }
  });

  it('guards every route whose screen declares a flag or a permission', () => {
    for (const role of ROLES) {
      const routes = childrenOf(areaRoutes(role));

      for (const screen of AREAS[role].screens) {
        const route = routes.find((candidate) => (candidate.path ?? '') === screen.path);
        const expected = (screen.flag ? 1 : 0) + (screen.permission ? 1 : 0);

        expect(`${screen.id}:${gateCount(route!)}`).toBe(`${screen.id}:${expected}`);
      }
    }
  });

  /**
   * The regression the reviewer found: `/admin/users` was hidden from a teacher's rail and
   * reachable by typing the URL. Rail and router now come from one row, so this asserts the
   * two can never disagree again rather than asserting one particular route.
   */
  it('gates a rail item exactly as it gates the route behind it', () => {
    for (const role of ROLES) {
      const routes = childrenOf(areaRoutes(role));

      for (const { screen, link } of navScreens(role)) {
        const route = routes.find((candidate) => (candidate.path ?? '') === screen.path);
        expect(link).toBe(linkOf(AREAS[role], screen));
        expect(gateCount(route!)).toBe((screen.flag ? 1 : 0) + (screen.permission ? 1 : 0));
      }
    }
  });

  it('guards the Admin screens the review named', () => {
    const routes = childrenOf(areaRoutes('ADMIN'));
    for (const path of ['settings', 'users', 'flags', 'usage'])
      expect(`${path}:${gateCount(routes.find((route) => route.path === path)!)}`).toBe(`${path}:1`);

    const teacher = childrenOf(areaRoutes('TEACHER'));
    expect(gateCount(teacher.find((route) => route.path === 'lessons/new')!)).toBe(1);
  });

  it('leaves each role Home open to that role — the redirect target cannot need a permission', () => {
    for (const role of ROLES) {
      const home = childrenOf(areaRoutes(role)).find((route) => route.path === '');
      expect(gateCount(home!)).toBe(0);
    }
  });

  /**
   * N2.2 (`docs/teacher-flow.md` §4): "No other menu items render." A rail that grows a seventh
   * item a teacher cannot open is the thing this package removed, so it is asserted rather than
   * left to the next person to re-add by habit.
   */
  it('gives a teacher exactly This week and My classes in the rail', () => {
    expect(navScreens('TEACHER').map(({ screen }) => screen.id)).toEqual(['week', 'classes']);
    expect(navScreens('TEACHER').map(({ link }) => link)).toEqual(['/teacher/week', '/teacher/classes']);
    // The screens later phases fill keep their routes — a bookmark still resolves to the stub.
    expect(AREAS.TEACHER.screens.map((screen) => screen.path)).toContain('students');
    // N2.3: the class page and the lessons list are routes, not rail items. §5 puts the class
    // in the rail as a third item only while she is inside it, from `ClassContextService`.
    expect(AREAS.TEACHER.screens.map((screen) => screen.path)).toContain('classes/:classId');
    expect(AREAS.TEACHER.screens.find((screen) => screen.id === 'lessons')?.labelKey).toBeUndefined();
  });

  it('sends /teacher to This week rather than drawing a Home of its own', () => {
    const home = childrenOf(areaRoutes('TEACHER')).find((route) => route.path === '');
    expect(home?.redirectTo).toBe('week');
    expect(home?.pathMatch).toBe('full');
    expect(home?.loadComponent).toBeUndefined();
    // And the week itself is behind the permission only a TEACHER holds.
    expect(AREAS.TEACHER.screens.find((screen) => screen.id === 'week')?.permission).toBe('teacher.week');
  });

  /**
   * R5 (`docs/coordinator-flow.md`): her rail is Home · Teachers · Classes · All lessons, and the
   * lesson she opens from it is the *same* page the teacher writes on, in read-only mode. Both
   * halves are asserted here rather than in the page's own spec, because both are properties of
   * the table: a row that lost `readOnly` would hand her a publish button, and the page would
   * never know.
   */
  it('gives a coordinator four rail items and a read-only lesson route', () => {
    expect(navScreens('COORDINATOR').map(({ screen }) => screen.id)).toEqual([
      'home',
      'teachers',
      'classes',
      'lessons',
    ]);
    expect(navScreens('COORDINATOR').map(({ link }) => link)).toEqual([
      '/coordinator',
      '/coordinator/teachers',
      '/coordinator/classes',
      '/coordinator/lessons',
    ]);

    const routes = childrenOf(areaRoutes('COORDINATOR'));
    const lesson = routes.find((route) => route.path === 'lessons/:id');
    expect(lesson?.data?.['readOnly']).toBe(true);
    // Every other screen of hers is writable by nobody, so the flag is false rather than absent:
    // the page reads `data.readOnly` and must never have to tell false from missing.
    for (const route of routes.filter((candidate) => candidate.path !== 'lessons/:id'))
      expect(`${route.path}:${route.data?.['readOnly']}`).toBe(`${route.path}:false`);

    // DR2: she writes nothing here, so not one row may carry a write permission.
    const keys = AREAS.COORDINATOR.screens.map((screen) => screen.permission ?? '');
    expect(keys.every((key) => key === '' || key.startsWith('coordinator.'))).toBe(true);
  });

  it('names the phase of a stub, including on a detail route', () => {
    expect(phaseOf('/admin/users')).toBe(3);
    expect(phaseOf('/management/complaints')).toBe(5);
    expect(phaseOf('/admin/usage')).toBe(6);
    // Matched against the pattern, so a real id resolves.
    expect(phaseOf('/admin/schools/5c5bc15a-0e3b-4d87-b3a2-d04f7bc267e2')).toBe(3);
    // A still-stubbed detail route (school) stays stubbed with a query string on it.
    expect(phaseOf('/admin/schools/5c5bc15a-0e3b-4d87-b3a2-d04f7bc267e2?tab=users')).toBe(3);
    // The Homes, the new-lesson wizard (P3.2c) and the lesson review page (P3.2d) are built,
    // and an address matching nothing has no phase.
    expect(phaseOf('/teacher')).toBeUndefined();
    expect(phaseOf('/teacher/lessons/new?classId=c-1')).toBeUndefined();
    expect(phaseOf('/teacher/lessons/l-1?notice=lessons.new.created')).toBeUndefined();
    expect(phaseOf('/admin/nothing-here')).toBeUndefined();
  });
});
