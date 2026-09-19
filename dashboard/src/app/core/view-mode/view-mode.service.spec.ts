import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ADMIN_USER, TEACHER_USER } from '../../../testing/fixtures';
import { BASE_PATH } from '../../api';
import { AuthService } from '../auth/auth.service';
import { SessionStore } from '../auth/session.store';
import { ViewModeService, queryAsksForDebug } from './view-mode.service';

/** Signs the given account in, the way every screen's bootstrap does. */
async function signIn(user: typeof ADMIN_USER): Promise<ViewModeService> {
  const backend = TestBed.inject(HttpTestingController);
  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(user);
  await Promise.resolve();
  TestBed.tick();
  return TestBed.inject(ViewModeService);
}

describe('ViewModeService', () => {
  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: '' }],
    });
  });

  afterEach(() => sessionStorage.clear());

  it('starts in teacher view for everyone', async () => {
    const viewMode = await signIn(ADMIN_USER);
    expect(viewMode.mode()).toBe('teacher');
    expect(viewMode.debug()).toBe(false);
  });

  it('lets an Admin turn debug on, and remembers it for the tab', async () => {
    const viewMode = await signIn(ADMIN_USER);
    viewMode.toggle();

    expect(viewMode.debug()).toBe(true);
    expect(viewMode.allowed()).toBe(true);
    // The tab, not the browser: debug is a thing you are doing now, not a preference.
    expect(sessionStorage.getItem('hq.viewMode')).toBe('debug');
    expect(localStorage.getItem('hq.viewMode')).toBeNull();
  });

  it('refuses a teacher, whatever she asks for and whatever storage holds', async () => {
    sessionStorage.setItem('hq.viewMode', 'debug');
    const viewMode = await signIn(TEACHER_USER);

    expect(viewMode.allowed()).toBe(false);
    expect(viewMode.debug()).toBe(false);

    viewMode.set('debug');
    expect(viewMode.debug()).toBe(false);
    expect(sessionStorage.getItem('hq.viewMode')).toBeNull();
  });

  /** The gate is a signal on the role, so a session change collapses the mode in the same tick. */
  it('collapses to teacher view the moment the Admin session ends', async () => {
    const viewMode = await signIn(ADMIN_USER);
    viewMode.toggle();
    expect(viewMode.debug()).toBe(true);

    TestBed.inject(AuthService).forget();
    expect(viewMode.debug()).toBe(false);
  });

  it('opens on ?debug=1, and never on the bundle QA and production serve', () => {
    expect(queryAsksForDebug(false, '?debug=1')).toBe(true);
    expect(queryAsksForDebug(false, '?debug=0')).toBe(false);
    expect(queryAsksForDebug(false, '')).toBe(false);
    // `qa` and `production` are one bundle (D11), and `production` is true for both.
    expect(queryAsksForDebug(true, '?debug=1')).toBe(false);
  });
});
