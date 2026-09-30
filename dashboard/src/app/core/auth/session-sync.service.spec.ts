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

    sync.onStorage({ key: REFRESH_KEY, newValue: 'refresh-2' });

    expect(session.refreshToken()).toBe('refresh-2');
    expect(auth.signedIn()).toBe(true);
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
