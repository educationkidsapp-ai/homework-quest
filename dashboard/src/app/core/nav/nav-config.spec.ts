import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { NAV_CONFIG, inRail } from './nav-config';
import { AREAS, linkOf, navScreens } from './screens';

/**
 * D1 (the owner's list of 2026-10-01, ADMIN item 5): Schools and Users leave the Admin's rail —
 * in design only. One switch decides it, and the routes behind the rows are untouched.
 */
describe('the nav switch', () => {
  const rail = (schoolSurfaces: boolean) =>
    navScreens('ADMIN')
      .filter(({ screen }) => inRail(screen, { schoolSurfaces }))
      .map(({ screen }) => screen.id);

  it('is off by default', () => {
    expect(TestBed.inject(NAV_CONFIG)).toEqual({ schoolSurfaces: false });
  });

  it('keeps Schools and Users out of the Admin rail while it is off', () => {
    expect(rail(false)).not.toContain('schools');
    expect(rail(false)).not.toContain('users');
    // …and takes nothing else with them.
    expect(rail(false)).toEqual([
      'home',
      'classes',
      'teachers',
      'coordinators',
      'managers',
      'workers',
      'children',
      'messages',
    ]);
  });

  it('puts both back with the one switch', () => {
    expect(rail(true)).toEqual(expect.arrayContaining(['schools', 'users']));
  });

  it('leaves the routes declared either way: hidden, not removed', () => {
    const paths = AREAS.ADMIN.screens.map((screen) => linkOf(AREAS.ADMIN, screen));
    expect(paths).toEqual(
      expect.arrayContaining(['/admin/schools', '/admin/schools/:id', '/admin/schools/:id/users', '/admin/users']),
    );
  });

  it('hides nothing from any other role', () => {
    for (const role of ['TEACHER', 'COORDINATOR', 'MANAGERIAL'] as const) {
      const all = navScreens(role);
      expect(all.filter(({ screen }) => inRail(screen, { schoolSurfaces: false }))).toEqual(all);
    }
  });
});
