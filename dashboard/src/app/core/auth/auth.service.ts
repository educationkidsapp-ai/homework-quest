import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, of, shareReplay, throwError } from 'rxjs';
import { catchError, finalize, map, switchMap, tap } from 'rxjs/operators';
import { AuthApi, DashboardUser, SignInResponse, TokenPair } from '../../api';
import { SchoolScopeStore } from './school-scope.store';
import { SessionStore } from './session.store';

export type Role = 'ADMIN' | 'TEACHER' | 'MANAGERIAL';
export type AuthStatus = 'unknown' | 'anonymous' | 'authenticated';

/** Where `/` sends each role, and where the nav's items hang off. */
export const ROLE_HOME: Readonly<Record<Role, string>> = {
  ADMIN: '/admin',
  TEACHER: '/teacher',
  MANAGERIAL: '/management',
};

export function isRole(value: string | undefined): value is Role {
  return value === 'ADMIN' || value === 'TEACHER' || value === 'MANAGERIAL';
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
 * request between every caller.
 *
 * **A failed refresh signs out.** There is nothing left to try: the refresh token is spent or
 * revoked, and retrying makes the theft heuristic worse.
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

  private readonly currentUser = signal<DashboardUser | null>(null);
  private readonly currentStatus = signal<AuthStatus>('unknown');
  private inFlightRefresh: Observable<string> | null = null;

  readonly user = this.currentUser.asReadonly();
  readonly status = this.currentStatus.asReadonly();
  readonly signedIn = computed(() => this.currentStatus() === 'authenticated');
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
        this.currentUser.set(user);
        this.currentStatus.set('authenticated');
      }),
    );
  }

  /** The new access token, from one shared request however many callers ask at once. */
  refresh(): Observable<string> {
    const existing = this.inFlightRefresh;
    if (existing) return existing;

    const token = this.session.refreshToken();
    if (token === null) return throwError(() => new Error('no refresh token'));

    const request = this.api.refresh({ refreshToken: token }).pipe(
      tap((pair: TokenPair) => this.session.set(pair)),
      map((pair) => pair.token ?? ''),
      catchError((error: unknown) => {
        this.forget();
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
    this.currentUser.set(null);
    this.currentStatus.set('anonymous');
    this.inFlightRefresh = null;
  }

  /** After `POST /auth/change-password` the flag is gone; re-read rather than guess. */
  reload(): Observable<DashboardUser> {
    return this.loadMe();
  }
}
