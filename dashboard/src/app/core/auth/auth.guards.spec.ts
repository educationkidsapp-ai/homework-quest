import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { COORDINATOR_USER, TEACHER_USER } from '../../../testing/fixtures';
import { BASE_PATH } from '../../api';
import { type Role, AuthService, ROLE_HOME, isRole } from './auth.service';
import { roleGuard } from './auth.guards';
import { SessionStore } from './session.store';

/**
 * R5: the fourth dashboard role, at the door.
 *
 * Two things are asserted and they are the two that would go wrong quietly. `Role` is a union
 * the server can widen without asking — a sign-in returning `COORDINATOR` into a dashboard that
 * does not know the word leaves `role()` null, which reads as "signed out" everywhere — and
 * `roleGuard` is the only thing that keeps her out of `/teacher/**`, where every request she made
 * would 404 and paint a screen of red bands rather than send her home.
 */
async function signIn(user: typeof COORDINATOR_USER): Promise<void> {
  const backend = TestBed.inject(HttpTestingController);
  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(user);
  await Promise.resolve();
  TestBed.tick();
}

/** `roleGuard` takes the route and the state, and neither guard here reads either. */
function run(roles: readonly Role[]): boolean | UrlTree {
  return TestBed.runInInjectionContext(
    () => roleGuard(...roles)({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot) as boolean | UrlTree,
  );
}

describe('the coordinator at the door', () => {
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: '**', children: [] }]),
        { provide: BASE_PATH, useValue: '' },
      ],
    });
  });

  it('knows COORDINATOR as a role, and sends her to /coordinator', () => {
    expect(isRole('COORDINATOR')).toBe(true);
    expect(ROLE_HOME.COORDINATOR).toBe('/coordinator');
  });

  it('lands a signed-in coordinator on her own area and nobody else’s', async () => {
    await signIn(COORDINATOR_USER);
    const auth = TestBed.inject(AuthService);

    expect(auth.role()).toBe('COORDINATOR');
    expect(auth.home()).toBe('/coordinator');
    expect(run(['COORDINATOR'])).toBe(true);

    // `/teacher/**` is not hers: the refusal is a redirect to her Home, not a screen that paints
    // and then fills with the 404s every `/teacher/**` read of hers would answer.
    const refused = run(['TEACHER']);
    expect(refused).not.toBe(true);
    expect((refused as UrlTree).toString()).toBe('/coordinator');
  });

  it('keeps a teacher out of the coordinator’s area', async () => {
    await signIn(TEACHER_USER);
    const refused = run(['COORDINATOR']);

    expect(refused).not.toBe(true);
    expect((refused as UrlTree).toString()).toBe('/teacher');
  });
});
