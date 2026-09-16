import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { throwError } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { AuthService } from '../auth/auth.service';
import { SchoolScopeStore } from '../auth/school-scope.store';
import { SessionStore } from '../auth/session.store';

/** Endpoints where a 401 means "those credentials are wrong", not "your token aged out". */
const NO_RETRY = ['/auth/sign-in', '/auth/refresh', '/auth/sign-out'];

/**
 * Bearer token, `X-School-Id`, and one retry after a refresh.
 *
 * Three rules:
 *
 * 1. **Assets carry no token.** `assets/i18n/ar.json` is a file in the bundle; sending a
 *    bearer to it would put the token in the access log of whatever serves static files.
 * 2. **`/auth/**` is never retried.** A wrong password answers 401; refreshing and retrying
 *    it would spend the refresh token to ask the same wrong password again. A failing
 *    refresh answers 401 too, and retrying *that* is an infinite loop.
 * 3. **One retry, then give up.** {@link AuthService.refresh} is single-flight, so a page
 *    whose six requests all 401 at once refreshes once and replays six times. If the retry
 *    401s as well the session is genuinely gone and the error interceptor routes to sign-in.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const session = inject(SessionStore);
  const scope = inject(SchoolScopeStore);
  const auth = inject(AuthService);

  if (isAsset(request.url)) return next(request);

  const authorized = withSession(request, session.accessToken(), adminScope(auth, scope));
  if (NO_RETRY.some((path) => request.url.includes(path))) return next(authorized);

  return next(authorized).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401) return throwError(() => error);
      return auth
        .refresh()
        .pipe(switchMap((token) => next(withSession(request, token, adminScope(auth, scope)))));
    }),
  );
};

/** An Admin's chosen school, or null — a TEACHER/MANAGERIAL token carries its own claim. */
function adminScope(auth: AuthService, scope: SchoolScopeStore): string | null {
  return auth.role() === 'ADMIN' ? scope.schoolId() : null;
}

function withSession<T>(
  request: HttpRequest<T>,
  token: string | null,
  schoolId: string | null,
): HttpRequest<T> {
  const setHeaders: Record<string, string> = {};
  if (token) setHeaders['Authorization'] = `Bearer ${token}`;
  if (schoolId) setHeaders['X-School-Id'] = schoolId;
  return Object.keys(setHeaders).length === 0 ? request : request.clone({ setHeaders });
}

function isAsset(url: string): boolean {
  return url.startsWith('assets/') || url.includes('/assets/');
}
