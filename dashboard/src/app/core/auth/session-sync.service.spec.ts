import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { BASE_PATH } from '../../api';
import { TEACHER_USER } from '../../../testing/fixtures';
import { AuthService } from './auth.service';
import { SessionSyncService } from './session-sync.service';
import { REFRESH_KEY, SESSION_OWNER_KEY, SessionStore } from './session.store';

/**
 * T2 item (e), the first half: **what another tab of this browser profile did to this session.**
 *
 * A `storage` event cannot be produced from inside one tab — the browser fires it in every tab
 * *but* the one that wrote — so the tests drive `onStorage` directly, which is exactly the
 * boundary the service is split at.
 */
describe('SessionSyncService', () => {
  let sync: SessionSyncService;
  let auth: AuthService;
  let session: SessionStore;
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: '' }],
    });
    sync = TestBed.inject(SessionSyncService);
    auth = TestBed.inject(AuthService);
    session = TestBed.inject(SessionStore);
    http = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  });

  /** Signs this tab in as the teacher of the fixtures, so it has an identity to lose. */
  async function signIn(): Promise<void> {
    const done = new Promise<void>((resolve) =>
      auth.signIn('sara@alnoor.test', 'secret').subscribe(() => resolve()),
    );
    http.expectOne('/auth/sign-in').flush({ token: 'access-1', refreshToken: 'refresh-1' });
    http.expectOne('/me').flush({ ...TEACHER_USER, id: 'u-sara', role: 'TEACHER' });
    await done;
  }

  it('stamps the stored session with who it belongs to', async () => {
    await signIn();
    expect(localStorage.getItem(SESSION_OWNER_KEY)).toBe('u-sara:TEACHER');
    expect(session.identity()).toBe('u-sara:TEACHER');
  });

  it('signs this tab out, naming the role, when another tab signs in as somebody else', async () => {
    await signIn();

    sync.onStorage({ key: SESSION_OWNER_KEY, newValue: 'u-mona:MANAGERIAL' });

    expect(auth.signedIn()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/sign-in'], {
      queryParams: { ended: 'takenOver', as: 'MANAGERIAL' },
    });
  });

  it('leaves a tab of the same account alone when it re-stamps the session', async () => {
    await signIn();

    sync.onStorage({ key: SESSION_OWNER_KEY, newValue: 'u-sara:TEACHER' });

    expect(auth.signedIn()).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('adopts a refresh token another tab rotated instead of spending the one it holds', async () => {
    await signIn();

    // Same stamp in storage: a rotation by a tab of this same account, which is ours to take.
    sync.onStorage({ key: REFRESH_KEY, newValue: 'refresh-2' });

    expect(session.refreshToken()).toBe('refresh-2');
    expect(auth.signedIn()).toBe(true);
  });

  /**
   * **The review's finding.** A sign-in writes the tokens before it can know whose they are — the
   * user id arrives with `/me` — so for a moment `hq.refresh` holds a token and the owner stamp
   * does not say whose. Adopting on the token event alone meant the teacher's tab took the
   * manager's token on the way past and was revoked at its next refresh: the very bug the listener
   * was added to stop, reintroduced by the listener.
   */
  it('refuses a token that arrives while the stored owner is missing, and ends the session', async () => {
    await signIn();
    // Exactly what `SessionStore.disown` leaves behind in the other tab's sign-in.
    localStorage.removeItem(SESSION_OWNER_KEY);

    sync.onStorage({ key: REFRESH_KEY, newValue: 'somebody-elses-token' });

    expect(session.refreshToken()).not.toBe('somebody-elses-token');
    expect(auth.signedIn()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/sign-in'], { queryParams: { ended: 'takenOver' } });
  });

  it('refuses a token that arrives under a foreign owner, and names the role', async () => {
    await signIn();
    localStorage.setItem(SESSION_OWNER_KEY, 'u-mona:MANAGERIAL');

    sync.onStorage({ key: REFRESH_KEY, newValue: 'somebody-elses-token' });

    expect(session.refreshToken()).not.toBe('somebody-elses-token');
    expect(auth.signedIn()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/sign-in'], {
      queryParams: { ended: 'takenOver', as: 'MANAGERIAL' },
    });
  });

  /**
   * A reload racing another tab. `identity()` is null until `/me` lands, so the only thing this tab
   * can compare is the stamp it *loaded* — which is why `SessionStore.claimedOwner` exists. Without
   * it, a rotation by a tab of the same account signed the reloading tab out, and a takeover during
   * the same window was ignored.
   */
  describe('while a reload is still restoring', () => {
    beforeEach(() => {
      localStorage.setItem(REFRESH_KEY, 'refresh-1');
      localStorage.setItem(SESSION_OWNER_KEY, 'u-sara:TEACHER');
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: '' }],
      });
      sync = TestBed.inject(SessionSyncService);
      auth = TestBed.inject(AuthService);
      session = TestBed.inject(SessionStore);
      http = TestBed.inject(HttpTestingController);
      navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    });

    it('takes a rotation by a tab of the same account', () => {
      expect(session.identity()).toBeNull();

      sync.onStorage({ key: REFRESH_KEY, newValue: 'refresh-2' });

      expect(session.refreshToken()).toBe('refresh-2');
      expect(navigate).not.toHaveBeenCalled();
    });

    it('abandons the restore when another role takes the browser over mid-reload', () => {
      sync.onStorage({ key: SESSION_OWNER_KEY, newValue: 'u-mona:MANAGERIAL' });

      expect(navigate).toHaveBeenCalledWith(['/sign-in'], {
        queryParams: { ended: 'takenOver', as: 'MANAGERIAL' },
      });
    });
  });

  it('ends the session when another tab signs out, because the shared token is revoked', async () => {
    await signIn();

    sync.onStorage({ key: REFRESH_KEY, newValue: null });

    expect(auth.signedIn()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/sign-in'], { queryParams: { ended: 'signedOutElsewhere' } });
  });

  it('ignores a key it does not own, and a takeover with nothing of ours to lose', () => {
    sync.onStorage({ key: 'hq.lang', newValue: 'ar' });
    sync.onStorage({ key: SESSION_OWNER_KEY, newValue: 'u-mona:MANAGERIAL' });

    expect(navigate).not.toHaveBeenCalled();
  });
});
