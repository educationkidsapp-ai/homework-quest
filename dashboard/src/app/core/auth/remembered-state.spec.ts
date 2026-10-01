import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { ADMIN_USER, TEACHER_USER } from '../../../testing/fixtures';
import { AuthService } from './auth.service';
import { forgetRememberedState } from './remembered-state';
import { LAST_OWNER_KEY, SESSION_OWNER_KEY } from './session.store';

/**
 * D1 — "errors on first open" (the owner's list of 2026-10-01).
 *
 * QA was re-created as a new school and the owner moves between roles in one browser. What the
 * last account left behind — a `returnTo`, a chosen school, a cached map keyed by a school id —
 * was read by the next one, and the server's honest 404 for somebody else's row was a red band
 * on a screen nobody had touched. These cases are that browser.
 */
describe('remembered state across accounts', () => {
  const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

  function boot() {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: '' }],
    });
    return { auth: TestBed.inject(AuthService), http: TestBed.inject(HttpTestingController) };
  }

  async function signIn(
    auth: AuthService,
    http: HttpTestingController,
    user: typeof TEACHER_USER,
  ): Promise<void> {
    const done = new Promise<void>((resolve) => auth.signIn(user.email ?? '', 'secret-enough').subscribe(() => resolve()));
    http.expectOne('/auth/sign-in').flush({ token: 'access', refreshToken: `refresh-${user.id}`, role: user.role });
    http.expectOne('/me').flush(user);
    await done;
  }

  function signOut(auth: AuthService, http: HttpTestingController): void {
    auth.signOut().subscribe();
    http.expectOne('/auth/sign-out').flush(null);
  }

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  it('a first sign-in in a fresh browser may follow the address it was sent to', async () => {
    const { auth, http } = boot();
    await signIn(auth, http, TEACHER_USER);

    expect(auth.continuesLastSession()).toBe(true);
  });

  it('the same account coming back continues its own session', async () => {
    const { auth, http } = boot();
    await signIn(auth, http, TEACHER_USER);
    signOut(auth, http);
    await signIn(auth, http, TEACHER_USER);

    expect(auth.continuesLastSession()).toBe(true);
  });

  it('a different account does not: what the browser remembered was the other one’s', async () => {
    const { auth, http } = boot();
    await signIn(auth, http, TEACHER_USER);
    signOut(auth, http);
    await signIn(auth, http, ADMIN_USER);

    expect(auth.continuesLastSession()).toBe(false);
  });

  /**
   * The owner's exact case. The tab was left on a teacher's screen, the database was re-created,
   * and the reload found a stamp for an account whose refresh token no longer exists. `forget()`
   * erases the stamp — and the sign-in that follows still has to know whose tab this was.
   */
  it('a reload onto a dead session still knows whose tab it was', async () => {
    localStorage.setItem('hq.refresh', 'refresh-of-a-wiped-account');
    localStorage.setItem(SESSION_OWNER_KEY, 'u-gone:TEACHER');
    const { auth, http } = boot();

    auth.restore().subscribe();
    await settle();
    http.expectOne('/auth/refresh').flush({ code: 'unauthorized', message: 'no' }, { status: 401, statusText: 'x' });
    await settle();
    expect(auth.signedIn()).toBe(false);
    expect(localStorage.getItem(SESSION_OWNER_KEY)).toBeNull();

    await signIn(auth, http, ADMIN_USER);
    expect(auth.continuesLastSession()).toBe(false);
    // …and the tab's own record now names the account that is actually here.
    expect(sessionStorage.getItem(LAST_OWNER_KEY)).toBe('u-admin:ADMIN');
  });

  it('forgets the ids one account kept when it signs out', async () => {
    const { auth, http } = boot();
    await signIn(auth, http, TEACHER_USER);
    localStorage.setItem('hq.school', JSON.stringify({ id: 'school-gone', name: 'Gone' }));
    localStorage.setItem('hq.flags.school-gone', '{"chat":true}');
    sessionStorage.setItem('hq_chat_att_1', '{}');
    localStorage.setItem('hq.lang', 'ar');
    localStorage.setItem('hq.course.u-sara', '{"curriculum":"british","grade":1}');

    signOut(auth, http);

    expect(localStorage.getItem('hq.school')).toBeNull();
    expect(localStorage.getItem('hq.flags.school-gone')).toBeNull();
    expect(sessionStorage.getItem('hq_chat_att_1')).toBeNull();
    // The browser's preferences and a per-user, id-free memory are not an account's leftovers.
    expect(localStorage.getItem('hq.lang')).toBe('ar');
    expect(localStorage.getItem('hq.course.u-sara')).not.toBeNull();
  });

  it('forgets them when the owner changes without a sign-out in between', async () => {
    localStorage.setItem('hq.refresh', 'refresh-of-a-wiped-account');
    localStorage.setItem(SESSION_OWNER_KEY, 'u-gone:TEACHER');
    const { auth, http } = boot();
    // Left by the previous owner, and never cleared because her session did not end here.
    localStorage.setItem('hq.flags.school-gone', '{"chat":true}');

    await signIn(auth, http, ADMIN_USER);

    expect(localStorage.getItem('hq.flags.school-gone')).toBeNull();
  });

  it('survives a browser that refuses storage', () => {
    expect(() => forgetRememberedState(null)).not.toThrow();
    const hostile = {
      get localStorage(): Storage {
        throw new Error('denied');
      },
      get sessionStorage(): Storage {
        throw new Error('denied');
      },
    } as unknown as Window;
    expect(() => forgetRememberedState(hostile)).not.toThrow();
  });
});
