import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { TEACHER_USER } from '../../../testing/fixtures';
import { AuthService } from './auth.service';
import { SessionStore } from './session.store';

describe('AuthService', () => {
  let auth: AuthService;
  let http: HttpTestingController;
  let session: SessionStore;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        // Same origin, as in the built bundle. The development base URL would turn every
        // expectation below into an absolute `http://localhost:8080/...` string for no reason.
        { provide: BASE_PATH, useValue: '' },
      ],
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
    session = TestBed.inject(SessionStore);
  });

  /**
   * Every pending microtask.
   *
   * T2 item (e) put a cross-tab lock in front of the rotation ({@link RefreshLock}), so a refresh
   * now leaves on the next microtask rather than on the same turn as the call. That is the whole
   * of the change these `await settle()` lines record.
   */
  const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

  it('signs in, keeps the refresh token and reads /me', async () => {
    const signedIn = auth.signIn('sara@alnoor.test', 'secret-enough');
    const done = new Promise<void>((resolve) => signedIn.subscribe(() => resolve()));

    http.expectOne('/auth/sign-in').flush({ token: 'access-1', refreshToken: 'refresh-1', role: 'TEACHER' });
    http.expectOne('/me').flush(TEACHER_USER);
    await done;

    expect(auth.signedIn()).toBe(true);
    expect(auth.role()).toBe('TEACHER');
    expect(auth.displayName()).toBe('Ms Sara');
    // The access token is memory-only; only the refresh token may reach disk.
    expect(localStorage.getItem('hq.refresh')).toBe('refresh-1');
    expect(JSON.stringify(localStorage)).not.toContain('access-1');
  });

  it('shares one refresh between every caller that asks at once', async () => {
    session.set({ token: 'stale', refreshToken: 'refresh-1' });

    const tokens: string[] = [];
    const all = Promise.all([
      new Promise<void>((resolve) => auth.refresh().subscribe((t) => (tokens.push(t), resolve()))),
      new Promise<void>((resolve) => auth.refresh().subscribe((t) => (tokens.push(t), resolve()))),
      new Promise<void>((resolve) => auth.refresh().subscribe((t) => (tokens.push(t), resolve()))),
    ]);

    // One request, not three: the server treats a replayed refresh token as theft and revokes
    // every live token of the user, so three parallel refreshes would sign the person out.
    await settle();
    const request = http.expectOne('/auth/refresh');
    request.flush({ token: 'access-2', refreshToken: 'refresh-2' });
    await all;

    expect(tokens).toEqual(['access-2', 'access-2', 'access-2']);
    expect(localStorage.getItem('hq.refresh')).toBe('refresh-2');
  });

  it('forgets the session when the refresh fails', async () => {
    session.set({ token: 'stale', refreshToken: 'spent' });
    const failed = new Promise<void>((resolve) => auth.refresh().subscribe({ error: () => resolve() }));

    await settle();
    http
      .expectOne('/auth/refresh')
      .flush(
        { code: 'unauthorized', message: 'That session has expired. Sign in again.' },
        { status: 401, statusText: 'Unauthorized' },
      );
    await failed;

    expect(auth.signedIn()).toBe(false);
    expect(localStorage.getItem('hq.refresh')).toBeNull();
  });

  it('asks a second time after a failed refresh rather than replaying the dead request', async () => {
    session.set({ token: 'stale', refreshToken: 'spent' });
    const firstAttempt = new Promise<void>((resolve) => auth.refresh().subscribe({ error: () => resolve() }));
    await settle();
    http.expectOne('/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });
    await firstAttempt;

    // The in-flight request is cleared on failure, so the next attempt is a new one (and,
    // with no refresh token left, fails without touching the network at all).
    let errored = false;
    auth.refresh().subscribe({ error: () => (errored = true) });
    expect(errored).toBe(true);
    http.expectNone('/auth/refresh');
  });

  it('revokes the refresh token on sign-out and forgets it even when the server refuses', async () => {
    session.set({ token: 'access-1', refreshToken: 'refresh-1' });

    await new Promise<void>((resolve) => {
      auth.signOut().subscribe(() => resolve());
      http.expectOne('/auth/sign-out').flush({}, { status: 500, statusText: 'Server Error' });
    });

    expect(localStorage.getItem('hq.refresh')).toBeNull();
    expect(auth.signedIn()).toBe(false);
  });

  it('restores nothing, and reports no error, when there is no refresh token', async () => {
    const user = await new Promise((resolve) => auth.restore().subscribe(resolve));

    expect(user).toBeNull();
    expect(auth.status()).toBe('anonymous');
    http.expectNone('/auth/refresh');
  });

  it('restores a session from the stored refresh token', async () => {
    session.set({ refreshToken: 'refresh-1' });

    const restored = new Promise((resolve) => auth.restore().subscribe(resolve));
    await settle();
    http.expectOne('/auth/refresh').flush({ token: 'access-2', refreshToken: 'refresh-2' });
    http.expectOne('/me').flush(TEACHER_USER);

    expect(await restored).toEqual(TEACHER_USER);
    expect(auth.signedIn()).toBe(true);
  });

  /**
   * The rest of "logged out after a few minutes" (T2 follow-up).
   *
   * Only a refused *token* ends a session. A refresh that never reached the server says nothing
   * about the token, and signing out on it meant one cold start or one lost second of Wi-Fi at the
   * fifteen-minute mark looked exactly like an expiry.
   */
  describe('a refresh that failed on the network rather than on the token', () => {
    /** Long enough for `RETRY_AFTER_MS` (600 ms) and the lock's microtask. */
    const afterRetry = () => new Promise((resolve) => setTimeout(resolve, 750));

    it('signs out on a 401, which is the token really being spent', async () => {
      session.set({ token: 'stale', refreshToken: 'spent' });
      const failed = new Promise<void>((resolve) => auth.refresh().subscribe({ error: () => resolve() }));

      await settle();
      http.expectOne('/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });
      await failed;

      expect(auth.signedIn()).toBe(false);
      expect(localStorage.getItem('hq.refresh')).toBeNull();
      // Not retried: a spent token presented twice is what the theft heuristic is for.
      http.expectNone('/auth/refresh');
    });

    it('signs out on a 403 as well', async () => {
      session.set({ token: 'stale', refreshToken: 'revoked' });
      const failed = new Promise<void>((resolve) => auth.refresh().subscribe({ error: () => resolve() }));

      await settle();
      http.expectOne('/auth/refresh').flush({}, { status: 403, statusText: 'Forbidden' });
      await failed;

      expect(auth.signedIn()).toBe(false);
    });

    it('keeps the session on a status 0 and succeeds on the retry', async () => {
      session.set({ token: 'stale', refreshToken: 'refresh-1' });
      const tokens: string[] = [];
      const done = new Promise<void>((resolve) =>
        auth.refresh().subscribe((token) => (tokens.push(token), resolve())),
      );

      await settle();
      // Status 0 is offline, DNS, a cancelled request — the request never happened.
      http.expectOne('/auth/refresh').error(new ProgressEvent('error'), { status: 0, statusText: '' });
      expect(auth.reconnecting()).toBe(true);

      await afterRetry();
      http.expectOne('/auth/refresh').flush({ token: 'access-2', refreshToken: 'refresh-2' });
      await done;

      expect(tokens).toEqual(['access-2']);
      expect(localStorage.getItem('hq.refresh')).toBe('refresh-2');
      expect(auth.reconnecting()).toBe(false);
      // Nothing was forgotten on the way through.
      expect(session.refreshToken()).toBe('refresh-2');
    });

    it('keeps the session when both attempts answer 503', async () => {
      session.set({ token: 'stale', refreshToken: 'refresh-1' });
      auth.loadMe().subscribe();
      http.expectOne('/me').flush(TEACHER_USER);
      const failed = new Promise<void>((resolve) => auth.refresh().subscribe({ error: () => resolve() }));

      await settle();
      http.expectOne('/auth/refresh').flush({}, { status: 503, statusText: 'Service Unavailable' });
      await afterRetry();
      http.expectOne('/auth/refresh').flush({}, { status: 503, statusText: 'Service Unavailable' });
      await failed;

      // Still hers: the caller sees the error and the band says so, but a deploy is not an expiry.
      expect(auth.signedIn()).toBe(true);
      expect(localStorage.getItem('hq.refresh')).toBe('refresh-1');
      expect(auth.reconnecting()).toBe(false);
    });

    it('never ends the session from a background reconnect, even on a 401', async () => {
      session.set({ token: 'stale', refreshToken: 'refresh-1' });
      auth.loadMe().subscribe();
      http.expectOne('/me').flush(TEACHER_USER);
      const failed = new Promise<void>((resolve) =>
        auth.refresh({ endsSession: false }).subscribe({ error: () => resolve() }),
      );

      await settle();
      http.expectOne('/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });
      await failed;

      // The socket reopens by itself every hour; it is not something she did, so it cannot be what
      // signs her out. The next request she makes is what discovers a session that is really over.
      expect(auth.signedIn()).toBe(true);
    });

    it('lets a request that is waiting on the refresh end the session a socket would not have', async () => {
      session.set({ token: 'stale', refreshToken: 'refresh-1' });
      auth.loadMe().subscribe();
      http.expectOne('/me').flush(TEACHER_USER);

      const quiet = new Promise<void>((resolve) =>
        auth.refresh({ endsSession: false }).subscribe({ error: () => resolve() }),
      );
      const loud = new Promise<void>((resolve) => auth.refresh().subscribe({ error: () => resolve() }));

      await settle();
      http.expectOne('/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });
      await Promise.all([quiet, loud]);

      expect(auth.signedIn()).toBe(false);
    });
  });
});
