import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { importProvidersFrom } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { translocoTesting } from '../../../testing/render';
import { ADMIN_USER } from '../../../testing/fixtures';
import { FlagService } from '../flags/flag.service';
import { authInterceptor } from '../http/auth.interceptor';
import { NAV_CONFIG } from '../nav/nav-config';
import { AuthService } from './auth.service';
import { SchoolScopeStore } from './school-scope.store';
import { SessionStore } from './session.store';

const SCOPE_KEY = 'hq.school';
const STALE = { id: 'school-gone', name: 'A school that was reseeded away' };

/**
 * `schoolSurfaces` is the nav switch (`core/nav/nav-config.ts`): on is the multi-school build these
 * D13 cases are about; off — the default since D1 — is the last block of this file.
 */
function configure(schoolSurfaces = true) {
  TestBed.configureTestingModule({
    providers: [
      { provide: NAV_CONFIG, useValue: { schoolSurfaces } },
      provideHttpClient(withInterceptors([authInterceptor])),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: BASE_PATH, useValue: '' },
      importProvidersFrom(translocoTesting()),
    ],
  });
}

/**
 * Signs an Admin in and lets the flag map settle on `flags`.
 *
 * `FlagService` is injected first on purpose: its resource — and the effect that follows the
 * `multiSchool` flag — only exist once something has asked for the service, exactly as the shell
 * does on its first render.
 */
async function signInAdmin(flags: Record<string, boolean>): Promise<HttpTestingController> {
  const backend = TestBed.inject(HttpTestingController);
  TestBed.inject(FlagService);
  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(ADMIN_USER);
  await Promise.resolve();
  TestBed.tick();

  // An Admin with a scope reads that school's flags; with none, the platform matrix.
  const scoped = backend.match((request) => request.url.startsWith('/schools/'));
  if (scoped.length > 0) scoped[0]!.flush(flags);
  else
    backend
      .expectOne('/admin/flags')
      .flush({ definitions: Object.entries(flags).map(([key, on]) => ({ key, defaultOn: on })) });
  // A resource answers on a microtask; the tick after it is what runs the effect that reads it.
  await Promise.resolve();
  TestBed.tick();
  return backend;
}

describe('the school scope, under multiSchool', () => {
  // `sessionStorage` too: the tab's own record of whose session it last held (D1) decides
  // whether a stored scope is somebody else's leftovers.
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  it('keeps a stored selection while multiSchool is on', async () => {
    localStorage.setItem(SCOPE_KEY, JSON.stringify(STALE));
    configure();
    const scope = TestBed.inject(SchoolScopeStore);
    const backend = await signInAdmin({ multiSchool: true });

    expect(scope.schoolId()).toBe(STALE.id);

    TestBed.inject(HttpClient).get('/me/home').subscribe();
    expect(backend.expectOne('/me/home').request.headers.get('X-School-Id')).toBe(STALE.id);
  });

  /**
   * D13. A browser that picked a school before the one-school build — or before a reseed gave
   * every school a new id — would otherwise go on scoping every request to an id the server no
   * longer knows, and the person would see an empty dashboard with nothing to click to fix it.
   */
  it('drops a stale selection, and sends no header, once multiSchool reads off', async () => {
    localStorage.setItem(SCOPE_KEY, JSON.stringify(STALE));
    configure();
    const scope = TestBed.inject(SchoolScopeStore);
    const backend = await signInAdmin({ multiSchool: false });

    expect(scope.scope()).toBeNull();
    expect(localStorage.getItem(SCOPE_KEY)).toBeNull();

    TestBed.inject(HttpClient).get('/me/home').subscribe();
    expect(backend.expectOne('/me/home').request.headers.has('X-School-Id')).toBe(false);
  });
});

/**
 * D1 (the owner's list of 2026-10-01, ADMIN item 5 and "errors on first open"). With the switcher
 * out of the design nobody can change a stored scope on screen — so it is never applied, whatever
 * `multiSchool` says, and it is erased before the first request is sent: a school id from a
 * re-created database answers `404 school not found` to `/me` itself.
 */
describe('the school scope, with the switcher hidden', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  it('never reads a stored selection, and erases it, even while multiSchool is on', async () => {
    localStorage.setItem(SCOPE_KEY, JSON.stringify(STALE));
    configure(false);
    const scope = TestBed.inject(SchoolScopeStore);

    // Before anybody has signed in or a flag has loaded.
    expect(scope.schoolId()).toBeNull();
    expect(localStorage.getItem(SCOPE_KEY)).toBeNull();

    const backend = await signInAdmin({ multiSchool: true });
    expect(scope.schoolId()).toBeNull();

    TestBed.inject(HttpClient).get('/me/home').subscribe();
    expect(backend.expectOne('/me/home').request.headers.has('X-School-Id')).toBe(false);
  });

  /**
   * No switcher means no way to pick — and an Admin with no school reads the platform's flag
   * defaults instead of her school's, is asked to "pick a school" by the lesson wizard and gets
   * `400 Send X-School-Id` from her own Messages. So she is pinned to the one school the server
   * says there is, exactly as if she had chosen it.
   */
  it('pins an Admin to the only school, once the server has said which it is', async () => {
    configure(false);
    const scope = TestBed.inject(SchoolScopeStore);
    const auth = TestBed.inject(AuthService);
    const backend = await signInAdmin({ multiSchool: true });
    expect(auth.effectiveSchoolId()).toBeNull();

    scope.setSoleSchool('school-now');

    expect(scope.schoolId()).toBe('school-now');
    expect(auth.effectiveSchoolId()).toBe('school-now');
    TestBed.inject(HttpClient).get('/admin/classes').subscribe();
    expect(backend.expectOne('/admin/classes').request.headers.get('X-School-Id')).toBe('school-now');
    // Resolved, never stored: a reload asks the server again.
    expect(localStorage.getItem(SCOPE_KEY)).toBeNull();
  });

  it('pins nobody while the switcher is on screen: choosing is hers', async () => {
    configure(true);
    const scope = TestBed.inject(SchoolScopeStore);
    await signInAdmin({ multiSchool: false });

    scope.setSoleSchool('school-now');

    expect(scope.schoolId()).toBeNull();
    // …though the one-school routes still have their answer (D13).
    expect(scope.soleSchoolId()).toBe('school-now');
  });
});
