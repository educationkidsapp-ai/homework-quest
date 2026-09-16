import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import en from '../../../assets/i18n/en.json';
import { BASE_PATH } from '../../api';
import { AuthService } from '../auth/auth.service';
import { SchoolScopeStore } from '../auth/school-scope.store';
import { SessionStore } from '../auth/session.store';
import { BandService } from '../band/band.service';
import { ADMIN_USER, TEACHER_USER } from '../../../testing/fixtures';
import { authInterceptor } from './auth.interceptor';
import { errorInterceptor } from './error.interceptor';

/**
 * The interceptors are tested through a bare `HttpClient` rather than through a generated
 * service: what is under test is the pipeline, and a generated service would only add a URL.
 */
describe('interceptors', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let session: SessionStore;
  let scope: SchoolScopeStore;
  let auth: AuthService;
  let band: BandService;
  let router: Router;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [
        TranslocoTestingModule.forRoot({
          langs: { en },
          translocoConfig: { availableLangs: ['en'], defaultLang: 'en' },
          preloadLangs: true,
        }),
      ],
      providers: [
        provideHttpClient(withInterceptors([errorInterceptor, authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: BASE_PATH, useValue: '' },
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    session = TestBed.inject(SessionStore);
    scope = TestBed.inject(SchoolScopeStore);
    auth = TestBed.inject(AuthService);
    band = TestBed.inject(BandService);
    router = TestBed.inject(Router);
  });

  function signedInAs(user: typeof TEACHER_USER): void {
    session.set({ token: 'access-1', refreshToken: 'refresh-1' });
    auth.loadMe().subscribe();
    backend.expectOne('/me').flush(user);
  }

  it('adds the bearer token', () => {
    session.set({ token: 'access-1' });
    http.get('/me/home').subscribe();

    expect(backend.expectOne('/me/home').request.headers.get('Authorization')).toBe('Bearer access-1');
  });

  it('adds X-School-Id for an Admin who has picked a school, and not otherwise', () => {
    signedInAs(ADMIN_USER);
    scope.select({ id: 'school-a', name: 'Al Noor School' });

    http.get('/me/home').subscribe();
    expect(backend.expectOne('/me/home').request.headers.get('X-School-Id')).toBe('school-a');

    scope.select(null);
    http.get('/me/home').subscribe();
    expect(backend.expectOne('/me/home').request.headers.has('X-School-Id')).toBe(false);
  });

  it('never sends X-School-Id for a teacher — her token already carries the claim', () => {
    signedInAs(TEACHER_USER);
    scope.select({ id: 'school-b', name: 'Someone else' });

    http.get('/me/home').subscribe();

    expect(backend.expectOne('/me/home').request.headers.has('X-School-Id')).toBe(false);
  });

  it('sends no token to a bundled asset', () => {
    session.set({ token: 'access-1' });
    http.get('assets/i18n/ar.json').subscribe();

    expect(backend.expectOne('assets/i18n/ar.json').request.headers.has('Authorization')).toBe(false);
  });

  it('refreshes once and replays the request after a 401', async () => {
    session.set({ token: 'stale', refreshToken: 'refresh-1' });
    const answered = new Promise<unknown>((resolve) => http.get('/me/home').subscribe(resolve));

    backend.expectOne('/me/home').flush({}, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne('/auth/refresh').flush({ token: 'access-2', refreshToken: 'refresh-2' });

    const replay = backend.expectOne('/me/home');
    expect(replay.request.headers.get('Authorization')).toBe('Bearer access-2');
    replay.flush({ role: 'TEACHER' });

    expect(await answered).toEqual({ role: 'TEACHER' });
    expect(band.current()).toBeNull();
  });

  /**
   * The scenario the single-flight design is actually about, and the one the unit spec for
   * `AuthService.refresh` cannot reach: a Home whose requests all 401 together. Five refreshes
   * would rotate the refresh token five times, and the server treats a replayed one as theft.
   */
  it('refreshes once for a whole screen whose requests all 401 at the same moment', async () => {
    session.set({ token: 'stale', refreshToken: 'refresh-1' });
    const paths = ['/me/home', '/me/permissions', '/schools/s/flags', '/schools/s/theme', '/admin/schools'];
    const answered = Promise.all(
      paths.map((path) => new Promise((resolve) => http.get(path).subscribe(resolve))),
    );

    for (const path of paths) backend.expectOne(path).flush({}, { status: 401, statusText: 'Unauthorized' });

    const refreshes = backend.match('/auth/refresh');
    expect(refreshes).toHaveLength(1);
    refreshes[0]?.flush({ token: 'access-2', refreshToken: 'refresh-2' });

    for (const path of paths) {
      const replay = backend.expectOne(path);
      expect(replay.request.headers.get('Authorization')).toBe('Bearer access-2');
      replay.flush({ ok: path });
    }

    expect(await answered).toEqual(paths.map((path) => ({ ok: path })));
    expect(band.current()).toBeNull();
  });

  it('gives up after one retry and sends the person to sign in', async () => {
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    session.set({ token: 'stale', refreshToken: 'refresh-1' });
    const failed = new Promise<void>((resolve) => http.get('/me/home').subscribe({ error: () => resolve() }));

    backend.expectOne('/me/home').flush({}, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne('/auth/refresh').flush({ token: 'access-2', refreshToken: 'refresh-2' });
    backend.expectOne('/me/home').flush({}, { status: 401, statusText: 'Unauthorized' });
    await failed;

    backend.expectNone('/auth/refresh');
    expect(auth.signedIn()).toBe(false);
    expect(navigate.mock.calls[0]?.[0]).toEqual(['/sign-in']);
    expect(band.current()?.message).toContain('expired');
  });

  it('never refreshes a wrong password, and leaves the band to the sign-in screen', async () => {
    const failed = new Promise<void>((resolve) =>
      http.post('/auth/sign-in', {}).subscribe({ error: () => resolve() }),
    );
    backend
      .expectOne('/auth/sign-in')
      .flush(
        { code: 'unauthorized', message: 'Email or password is wrong.' },
        { status: 401, statusText: 'x' },
      );
    await failed;

    backend.expectNone('/auth/refresh');
    expect(band.current()).toBeNull();
  });

  it('turns a server failure into the band, with the server’s own sentence', async () => {
    const failed = new Promise<void>((resolve) =>
      http.get('/admin/schools').subscribe({ error: () => resolve() }),
    );
    backend
      .expectOne('/admin/schools')
      .flush(
        { code: 'bad_request', message: 'That code is already taken.' },
        { status: 400, statusText: 'x' },
      );
    await failed;

    expect(band.current()?.message).toBe('That code is already taken.');
  });

  it('sends a 403 to the no-access page', async () => {
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const failed = new Promise<void>((resolve) =>
      http.get('/admin/schools').subscribe({ error: () => resolve() }),
    );
    backend
      .expectOne('/admin/schools')
      .flush({ code: 'forbidden', message: 'Not yours.' }, { status: 403, statusText: 'x' });
    await failed;

    expect(navigate).toHaveBeenCalledWith(['/no-access']);
  });

  it('says so in our own words when the server says nothing at all', async () => {
    const failed = new Promise<void>((resolve) =>
      http.get('/admin/schools').subscribe({ error: () => resolve() }),
    );
    backend.expectOne('/admin/schools').error(new ProgressEvent('network error'));
    await failed;

    expect(band.current()?.message).toContain('Could not reach the server');
  });
});
