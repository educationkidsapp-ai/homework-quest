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
   *
   * RM3b adds the third, and it is the exception the rule was written for: Broadcasts is a screen
   * she *can* open, behind the `announcements` flag, and the week's plan is the reason she comes
   * to the dashboard on a Sunday. A school without the flag has the two-item rail back.
   */
  it('gives a teacher This week, My classes and Broadcasts in the rail', () => {
    expect(navScreens('TEACHER').map(({ screen }) => screen.id)).toEqual(['week', 'classes', 'broadcasts']);
    expect(navScreens('TEACHER').map(({ link }) => link)).toEqual([
      '/teacher/week',
      '/teacher/classes',
      '/teacher/broadcasts',
    ]);
    const broadcasts = AREAS.TEACHER.screens.find((screen) => screen.id === 'broadcasts');
    expect(broadcasts?.flag).toBe('announcements');
    expect(broadcasts?.permission).toBe('broadcast.read');
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
   * R5 + R7 (`docs/coordinator-flow.md`): her rail is the four read-only screens and then the three
   * she writes on, and the lesson she opens from it is the *same* page the teacher writes on, in
   * read-only mode. Both halves are asserted here rather than in a page's own spec, because both
   * are properties of the table: a row that lost `readOnly` would hand her a publish button, and
   * the page would never know.
   */
  it('gives a coordinator her ten rail items and read-only detail routes', () => {
    expect(navScreens('COORDINATOR').map(({ screen }) => screen.id)).toEqual([
      'home',
      'teachers',
      'classes',
      'lessons',
      // R6's three record screens.
      'attendance',
      'gradebook',
      'exams',
      'messages',
      'complaints',
      'broadcasts',
    ]);
    expect(navScreens('COORDINATOR').map(({ link }) => link)).toEqual([
      '/coordinator',
      '/coordinator/teachers',
      '/coordinator/classes',
      '/coordinator/lessons',
      '/coordinator/attendance',
      '/coordinator/gradebook',
      '/coordinator/exams',
      '/coordinator/messages',
      '/coordinator/complaints',
      '/coordinator/broadcasts',
    ]);
    // R7: all three carry the flag their server routes carry, so a school without chat has none
    // of them — in the rail or at the URL.
    for (const id of ['messages', 'complaints'])
      expect(AREAS.COORDINATOR.screens.find((screen) => screen.id === id)?.flag).toBe('chat');
    expect(AREAS.COORDINATOR.screens.find((screen) => screen.id === 'broadcasts')?.flag).toBe(
      'announcements',
    );
    // RM3b: the rail item she learned in R7 still resolves, onto the screen that took its job.
    expect(AREAS.COORDINATOR.screens.find((screen) => screen.id === 'announcements')?.redirectTo).toBe(
      'broadcasts',
    );

    // R6: every one of her record screens is read-only too, and the two flagged ones carry the
    // flags their controllers carry — a school without `gradebook` must meet `/not-found` in the
    // router rather than a screen of 404s.
    for (const id of ['attendance', 'gradebook', 'exams', 'results', 'child', 'exam-results']) {
      const row = AREAS.COORDINATOR.screens.find((screen) => screen.id === id);
      expect(`${id}:${row?.readOnly}`).toBe(`${id}:true`);
      expect(row?.permission?.startsWith('coordinator.')).toBe(true);
    }
    const flagOf = (id: string) => AREAS.COORDINATOR.screens.find((s) => s.id === id)?.flag;
    expect([flagOf('gradebook'), flagOf('child'), flagOf('results')]).toEqual([
      'gradebook',
      'gradebook',
      'gradebook',
    ]);
    expect([flagOf('exams'), flagOf('exam-results')]).toEqual(['exams', 'exams']);
    // Attendance is not behind a toggle: a school with classes has registers.
    expect(flagOf('attendance')).toBeUndefined();

    const routes = childrenOf(areaRoutes('COORDINATOR'));
    const lesson = routes.find((route) => route.path === 'lessons/:id');
    expect(lesson?.data?.['readOnly']).toBe(true);
    // R5's list screens and R7's three are the rows without `readOnly`, and their flag is `false`
    // rather than absent: a page reads `data.readOnly` and must never have to tell false from
    // missing. Everything R6 added is `true` — asserted above, row by row.
    const listOnly = ['', 'teachers', 'classes', 'lessons', 'messages', 'complaints', 'broadcasts'];
    for (const route of routes.filter((candidate) => listOnly.includes(candidate.path ?? '')))
      expect(`${route.path}:${route.data?.['readOnly']}`).toBe(`${route.path}:false`);

    // DR2: nothing she reads is hers to change, and the things she does write — a complaint's
    // status, a broadcast — are her own keys too, so not one row of hers may carry a permission
    // from somebody else's namespace. `broadcast.read` is the one exception, and it is not one:
    // the *feed* is `GET /me/broadcasts`, which every dashboard role reads with the same key, and
    // her composer is gated on `coordinator.broadcast` inside the screen.
    const keys = AREAS.COORDINATOR.screens.map((screen) => screen.permission ?? '');
    expect(
      keys.every((key) => key === '' || key.startsWith('coordinator.') || key === 'broadcast.read'),
    ).toBe(true);
  });

  /**
   * MG2a (the owner's list, 2026-09-30): "Remove Classes, All lessons, Gradebook and Exams."
   *
   * Both halves are asserted, because the table is what makes them one fact: the rows are gone
   * from her rail, and the paths behind them redirect to her Home rather than 404 — a bookmark,
   * an old notification's `link` and the runbook's own URL all still resolve. The `/management/**`
   * API routes are untouched, which is why nothing here asserts anything about them.
   */
  it('gives a manager her ten rail items, and redirects the four screens MG2a removed', () => {
    expect(navScreens('MANAGERIAL').map(({ screen }) => screen.id)).toEqual([
      'home',
      'coordinators',
      'teachers',
      'attendance',
      'people',
      'staff-attendance',
      'broadcasts',
      'messages',
      'complaints',
      'usage',
    ]);

    const routes = childrenOf(areaRoutes('MANAGERIAL'));
    for (const path of [
      'classes',
      'lessons',
      'lessons/:id',
      'lessons/:id/results',
      'gradebook',
      'exams',
      'exams/:id/results',
      'children/:childId',
    ]) {
      const route = routes.find((candidate) => candidate.path === path);
      expect(`${path}:${String(route?.redirectTo)}`).toBe(`${path}:/management`);
      expect(`${path}:${route?.pathMatch}`).toBe(`${path}:full`);
      // A redirect draws nothing, so it must not also try to load a component.
      expect(route?.loadComponent).toBeUndefined();
    }
    // None of them is in the rail any more, redirect or not.
    const rail = navScreens('MANAGERIAL').map(({ link }) => link);
    for (const link of ['/management/classes', '/management/lessons', '/management/gradebook'])
      expect(rail).not.toContain(link);

    // MG2a item 5: School usage is built, so it is no longer a stub and carries the server's key.
    const usage = AREAS.MANAGERIAL.screens.find((screen) => screen.id === 'usage');
    expect(usage?.phase).toBeUndefined();
    expect(usage?.permission).toBe('usage.school');
    expect(phaseOf('/management/usage')).toBeUndefined();
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
