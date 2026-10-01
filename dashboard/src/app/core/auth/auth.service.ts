import { HttpErrorResponse } from '@angular/common/http';
import { DOCUMENT, Injectable, computed, inject, signal } from '@angular/core';
import { Observable, of, shareReplay, throwError, timer } from 'rxjs';
import { catchError, finalize, map, retry, switchMap, tap } from 'rxjs/operators';
import { AuthApi, DashboardUser, SignInResponse, TokenPair } from '../../api';
import { MediaService } from '../media/media.service';
import { RefreshLock } from './refresh-lock';
import { forgetRememberedState } from './remembered-state';
import { SchoolScopeStore } from './school-scope.store';
import { SessionStore } from './session.store';

/** How long a transport failure waits before the one retry. */
const RETRY_AFTER_MS = 600;

/**
 * **Is this refusal about the token, or about the network?**
 *
 * Only a 401 or a 403 from `/auth/refresh` says the refresh token is spent or revoked. A status
 * 0 (offline, DNS, a cancelled request), a 502/503/504 (a Cloud Run cold start, a deploy) or a
 * timeout says nothing at all about the token — and treating those as "your session is over" is
 * what turned one bad request at the fifteen-minute mark into a sign-out.
 */
function isCredentialFailure(error: unknown): boolean {
  return error instanceof HttpErrorResponse && (error.status === 401 || error.status === 403);
}

export type Role = 'ADMIN' | 'TEACHER' | 'MANAGERIAL' | 'COORDINATOR';
export type AuthStatus = 'unknown' | 'anonymous' | 'authenticated';

/**
 * Where `/` sends each role, and where the nav's items hang off.
 *
 * `/teacher` is kept as the Teacher's home rather than `/teacher/week`: the area's own `''` row
 * redirects there (`core/nav/screens.ts`), so both `/` and `/teacher` land on This week and the
 * redirect lives in one place instead of two that could drift.
 */
export const ROLE_HOME: Readonly<Record<Role, string>> = {
  ADMIN: '/admin',
  TEACHER: '/teacher',
  MANAGERIAL: '/management',
  COORDINATOR: '/coordinator',
};

export function isRole(value: string | undefined): value is Role {
  return value === 'ADMIN' || value === 'TEACHER' || value === 'MANAGERIAL' || value === 'COORDINATOR';
}

/**
 * The session: who is signed in, and the two calls that change that.
 *
 * Three things here are less obvious than they look.
 *
 * **Refresh is single-flight.** A Home makes four requests at once; when the access token has
 * expired all four come back 401 together. Four refreshes would rotate the refresh token four
 * times, and the server treats presenting an already-rotated token as theft and revokes every
 * live token of that user (runbook, "Refresh rotation") — so a burst of parallel 401s would
 * sign the person out rather than recover. {@link refresh} therefore shares one in-flight
 * request between every caller. T2 item (e) made it single-flight **across tabs** too, through
 * {@link RefreshLock}: two tabs of one account were two independent single-flights, and the
 * second to arrive presented a token the first had already rotated — the same revocation, and
 * the "logged out after a few minutes" the owner reported.
 *
 * **Only a refused *token* signs out.** A 401 or a 403 from `/auth/refresh` means the refresh
 * token is spent or revoked and there is nothing left to try. A status 0, a 502/503/504 or a
 * timeout means the request never happened, so the session is kept, the call is retried once
 * after {@link RETRY_AFTER_MS}, and {@link reconnecting} says so — signing out on a cold start
 * was the rest of the owner's "logged out after a few minutes".
 *
 * **`mustChangePassword` is the client's job.** The server does not block other calls while
 * the flag is set (runbook, "First-login password change"), so the guard in `auth.guards.ts`
 * is what actually stops an invited account from reaching the rest of the dashboard.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(AuthApi);
  private readonly session = inject(SessionStore);
  private readonly schoolScope = inject(SchoolScopeStore);
  private readonly media = inject(MediaService);
  private readonly lock = inject(RefreshLock);
  private readonly doc = inject(DOCUMENT);

  private readonly currentUser = signal<DashboardUser | null>(null);
  private readonly currentStatus = signal<AuthStatus>('unknown');
  private readonly reconnectingNow = signal(false);
  private readonly sameOwner = signal(true);
  private inFlightRefresh: Observable<string> | null = null;
  /** Whether the shared in-flight refresh is one whose 401 ends the session. */
  private inFlightEndsSession = true;

  readonly user = this.currentUser.asReadonly();
  readonly status = this.currentStatus.asReadonly();
  /**
   * A refresh is between its transport failure and its retry — "Reconnecting…", not "signed out".
   *
   * The shell shows it as a notice band (`shell.component.ts`). It is deliberately not an error:
   * nothing has been lost, and the session is still this person's.
   */
  readonly reconnecting = this.reconnectingNow.asReadonly();
  readonly signedIn = computed(() => this.currentStatus() === 'authenticated');
  /**
   * Whether the account that just signed in is the one this tab last held a session for (or the
   * tab had held none).
   *
   * D1: what decides whether a remembered address may be followed. `/sign-in?returnTo=…` is
   * written when a session dies under a screen, and the screen it names was the *previous*
   * account's — a class of another teacher, a thread of another role, a row of a school that has
   * since been re-created. Followed by a different account it is a 404 nobody caused, in a red
   * band, on first open. False means: go Home.
   */
  readonly continuesLastSession = this.sameOwner.asReadonly();
  readonly role = computed<Role | null>(() => {
    const role = this.currentUser()?.role;
    return isRole(role) ? role : null;
  });
  readonly displayName = computed(() => this.currentUser()?.displayName ?? this.currentUser()?.email ?? '');
  readonly mustChangePassword = computed(() => this.currentUser()?.mustChangePassword === true);
  /** Set while this is an Admin's read-only "View as" session; drives the banner. */
  readonly impersonatedBy = computed(() => this.currentUser()?.impersonatedBy ?? null);
  /** The school whose data this session sees: the token's own, or the Admin switcher's. */
  readonly effectiveSchoolId = computed(
    () => this.currentUser()?.schoolId ?? this.schoolScope.schoolId() ?? null,
  );

  /** `/` and every guard wait on this once, then never again. */
  readonly home = computed(() => {
    const role = this.role();
    return role === null ? '/sign-in' : ROLE_HOME[role];
  });

  signIn(email: string, password: string): Observable<DashboardUser> {
    return this.api
      .dashboardSignIn({ email, password })
      .pipe(switchMap((response: SignInResponse) => this.adopt(response)));
  }

  /** Accepting an invitation signs the new account in, so it lands here too. */
  adopt(response: SignInResponse): Observable<DashboardUser> {
    // **Disown first.** These tokens belong to whoever just signed in, and who that is only
    // arrives with `/me` — so the stamp cannot be written before them. Clearing it instead means
    // the other tabs of this browser profile see the new token land with *no* owner, which is
    // never something they may adopt (`SessionSyncService.onStorage`). Without this, a teacher's
    // tab took the manager's token on the way past and was revoked at its next refresh.
    this.session.disown();
    this.session.set(response);
    return this.loadMe();
  }

  /**
   * Restores a session after a reload. Resolves to `null` when there is nothing to restore —
   * an anonymous visit is not an error and must not paint an error band.
   */
  restore(): Observable<DashboardUser | null> {
    if (this.currentStatus() !== 'unknown') return of(this.currentUser());
    if (!this.session.restorable()) {
      this.currentStatus.set('anonymous');
      return of(null);
    }
    return this.refresh().pipe(
      switchMap(() => this.loadMe()),
      map((user): DashboardUser | null => user),
      catchError(() => {
        this.forget();
        return of(null);
      }),
    );
  }

  loadMe(): Observable<DashboardUser> {
    return this.api.me().pipe(
      tap((user) => {
        // D1: before the stamp is rewritten — is this the account the tab last held? If not,
        // whatever the browser remembered was the other account's, and goes before any screen
        // of this one can read it.
        if (user.id && user.role) {
          const last = this.session.lastOwner();
          const same = last === null || last === `${user.id}:${user.role}`;
          this.sameOwner.set(same);
          if (!same) {
            this.schoolScope.clear();
            forgetRememberedState(this.doc.defaultView);
          }
        }
        this.currentUser.set(user);
        this.currentStatus.set('authenticated');
        // T2 item (e): stamp the stored session with whose it is, so a second role signing in
        // in a second tab is something the first tab can see rather than inherit.
        if (user.id && user.role) this.session.claim(user.id, user.role);
      }),
    );
  }

  /**
   * The new access token, from one shared request however many callers ask at once.
   *
   * `endsSession: false` is for a **background reconnect** — the chat socket, which reopens by
   * itself every hour on Cloud Run and after every network blip. That refresh never ends the
   * session whatever the answer: the socket is not something the person did, so it must not be the
   * thing that signs her out. On a 401 it simply keeps backing off
   * (`ChatService.scheduleReconnect`), and the next request she actually makes is what discovers a
   * session that is really over.
   *
   * An option rather than a second method on purpose: every spec that stands a partial
   * `AuthService` in front of `ChatService` would otherwise have to grow a mock for it, and a mock
   * nobody remembered is a `TypeError` in a spec about something else entirely.
   */
  refresh(options?: { endsSession?: boolean }): Observable<string> {
    const endSession = options?.endsSession !== false;
    const existing = this.inFlightRefresh;
    // A caller that *would* end the session upgrades one that would not: it is the same 401, and
    // the person is waiting on this request rather than on a socket.
    if (existing) {
      this.inFlightEndsSession = this.inFlightEndsSession || endSession;
      return existing;
    }

    if (this.session.refreshToken() === null) return throwError(() => new Error('no refresh token'));
    this.inFlightEndsSession = endSession;

    const request = this.lock
      .run(() => {
        // Read the token **inside** the lock. Another tab may have rotated it while this one
        // waited, and the token this caller saw is then spent — presenting it is what the
        // server reads as theft and answers by revoking the account (T2 item e).
        const token = this.session.refreshToken();
        return token === null
          ? throwError(() => new Error('no refresh token'))
          : this.api.refresh({ refreshToken: token });
      })
      .pipe(
        // One retry, and only for a failure that was never about the token. `retry` resubscribes
        // the source, so the second attempt retakes the lock and re-reads the token — which is
        // what makes it safe to send at all.
        retry({
          count: 1,
          delay: (error: unknown) => {
            if (isCredentialFailure(error)) return throwError(() => error);
            this.reconnectingNow.set(true);
            return timer(RETRY_AFTER_MS);
          },
        }),
        tap((pair: TokenPair) => {
          this.session.set(pair);
          this.reconnectingNow.set(false);
        }),
        map((pair) => pair.token ?? ''),
        catchError((error: unknown) => {
          this.reconnectingNow.set(false);
          // **Only the token ends the session.** A transport failure leaves it exactly as it was:
          // the caller sees the error, the error interceptor paints its band, and she carries on.
          if (this.inFlightEndsSession && isCredentialFailure(error)) this.forget();
          return throwError(() => error);
        }),
        finalize(() => (this.inFlightRefresh = null)),
        shareReplay({ bufferSize: 1, refCount: false }),
      );
    this.inFlightRefresh = request;
    return request;
  }

  /** Revokes this one refresh token server-side, then forgets everything locally. */
  signOut(): Observable<void> {
    const token = this.session.refreshToken();
    const revoke = token === null ? of(undefined) : this.api.signOut({ refreshToken: token });
    return revoke.pipe(
      catchError(() => of(undefined)), // a token the server already revoked is still a sign-out
      map(() => undefined),
      finalize(() => this.forget()),
    );
  }

  /** Drops every trace of the session without calling the server (a refresh failure, a 401 on refresh). */
  forget(): void {
    this.session.clear();
    this.schoolScope.clear();
    // D1: the ids this account left in the browser leave with it.
    forgetRememberedState(this.doc.defaultView);
    this.currentUser.set(null);
    this.currentStatus.set('anonymous');
    this.inFlightRefresh = null;
    this.reconnectingNow.set(false);
    // Page crops are megabytes of one teacher's scanned pages, held in a root-provided cache
    // whose injector never dies in a single-page app. This is the moment that session ends.
    this.media.clear();
  }

  /** After `POST /auth/change-password` the flag is gone; re-read rather than guess. */
  reload(): Observable<DashboardUser> {
    return this.loadMe();
  }
}
