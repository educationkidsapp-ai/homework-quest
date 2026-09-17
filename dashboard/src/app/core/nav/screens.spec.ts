import { Route, Routes } from '@angular/router';
import { describe, expect, it } from 'vitest';
import type { Role } from '../auth/auth.service';
import { areaRoutes } from './area.routes';
import { AREAS, linkOf, navScreens, phaseOf } from './screens';

const ROLES: readonly Role[] = ['ADMIN', 'TEACHER', 'MANAGERIAL'];

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

  it('names the phase of a stub, including on a detail route', () => {
    expect(phaseOf('/admin/users')).toBe(3);
    expect(phaseOf('/management/complaints')).toBe(5);
    expect(phaseOf('/admin/usage')).toBe(6);
    // Matched against the pattern, so a real id resolves.
    expect(phaseOf('/admin/schools/5c5bc15a-0e3b-4d87-b3a2-d04f7bc267e2')).toBe(3);
    // The lesson detail route is still a stub (P3.2d); a query string does not change that.
    expect(phaseOf('/teacher/lessons/l-1?notice=lessons.new.created')).toBe(3);
    // The Homes and the new-lesson wizard (P3.2c) are built, and an address matching nothing
    // has no phase.
    expect(phaseOf('/teacher')).toBeUndefined();
    expect(phaseOf('/teacher/lessons/new?classId=c-1')).toBeUndefined();
    expect(phaseOf('/admin/nothing-here')).toBeUndefined();
  });
});
