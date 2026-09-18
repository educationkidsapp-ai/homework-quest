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
import { AuthService } from './auth.service';
import { SchoolScopeStore } from './school-scope.store';
import { SessionStore } from './session.store';

const SCOPE_KEY = 'hq.school';
const STALE = { id: 'school-gone', name: 'A school that was reseeded away' };

function configure() {
  TestBed.configureTestingModule({
    providers: [
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
  beforeEach(() => localStorage.clear());

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
