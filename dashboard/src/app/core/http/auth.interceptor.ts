import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
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
  const router = inject(Router);

  if (isAsset(request.url)) return next(request);

  const scoped = (url: string) => adminScope(auth, scope, url, router.url);
  const authorized = withSession(request, session.accessToken(), scoped(request.url));
  if (NO_RETRY.some((path) => request.url.includes(path))) return next(authorized);

  return next(authorized).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401) return throwError(() => error);
      return auth
        .refresh()
        .pipe(switchMap((token) => next(withSession(request, token, scoped(request.url)))));
    }),
  );
};

/**
 * An Admin's chosen school, or null — a TEACHER/MANAGERIAL token carries its own claim.
 *
 * RM3b: `/admin/chat/**` is the one family of routes that is read **one school at a time** and
 * answers `400 Send X-School-Id` without the header, so with `multiSchool` off — where D13 masks
 * her stored scope and hides the switcher — it falls back to the single school
 * ({@link SchoolScopeStore.soleSchoolId}), resolved from the server rather than from storage.
 * D4 adds the chat upload, which is behind the same flag and read for the same school. Only these
 * routes: every other Admin read is deliberately cross-school with the flag off, and
 * scoping them all to the one school would be D13 undone by the back door.
 */
function adminScope(auth: AuthService, scope: SchoolScopeStore, url: string, screen: string): string | null {
  if (auth.role() !== 'ADMIN') return null;
  return scope.schoolId() ?? (isSchoolScopedChat(url, screen) ? scope.soleSchoolId() : null);
}

/**
 * `/admin/chat/**`, and (D4) `POST /media/chat-attachments`, the upload a message's files take.
 * D5: `/admin/complaints/**` too — the support view of a school's complaints is read one school
 * at a time like her chat — and the files on them, `GET /media/attachments/{id}`, when they are
 * read **from that screen**: the same route serves files elsewhere, which stay cross-school.
 */
function isSchoolScopedChat(url: string, screen: string): boolean {
  if (url.includes('/admin/chat/') || url.includes('/media/chat-attachments')) return true;
  if (url.includes('/admin/complaints')) return true;
  return (
    url.includes('/media/attachments/') &&
    (screen === '/admin/complaints' || screen.startsWith('/admin/complaints?'))
  );
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
