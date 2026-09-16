import { HttpContext, HttpContextToken, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { apiErrorOf } from '../../api';
import { AuthService } from '../auth/auth.service';
import { BandService } from '../band/band.service';

/**
 * Set on a request whose failure the screen handles itself — a sign-in with a wrong password
 * belongs under the password field, not in a band at the top of the page.
 *
 *     this.api.dashboardSignIn(body, 'body', false, { context: silentErrors() })
 */
export const SILENT_ERRORS = new HttpContextToken<boolean>(() => false);

export function silentErrors(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}

/**
 * Endpoints whose failures belong to the screen that called them.
 *
 * A wrong password on sign-in answers 401, and the generic handling below would read that as
 * "your session expired", sign the person out of a session they never had, and navigate them
 * to the page they are already on. An expired invite link answers 404, and "not found" in a
 * band over an otherwise blank page says less than the invite screen can. Each of these
 * screens shows its own band, in place, next to the field that caused it.
 */
const SCREEN_OWNED = [
  '/auth/sign-in',
  '/auth/change-password',
  '/auth/forgot-password',
  '/auth/reset-password',
  '/invites/',
];

/**
 * Every server failure becomes the red band, and the three that mean "you are in the wrong
 * place" also become a navigation.
 *
 * - **401** — the auth interceptor has already tried a refresh and a retry, so this is a dead
 *   session: forget it and go to sign-in with a `returnTo`, so finishing the sign-in lands
 *   back where the person was rather than on a Home.
 * - **403** — a real answer, not a mistake: the account may not do this. Routing to
 *   `/no-access` rather than showing a band on a half-loaded screen, because the screen the
 *   person is looking at is one they should not be on.
 * - **404** — the row is gone or was never theirs (the server answers 404, never 403, for
 *   another school's rows, so this is also what cross-tenant access looks like). The band
 *   says so; the screen decides whether it can still be useful, so there is no navigation.
 * - **0** — no response at all: offline, a dropped connection, a CORS refusal. The server
 *   said nothing, so the message has to be ours.
 */
export const errorInterceptor: HttpInterceptorFn = (request, next) => {
  const band = inject(BandService);
  const router = inject(Router);
  const auth = inject(AuthService);
  const transloco = inject(TranslocoService);

  return next(request).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse)) return throwError(() => error);
      if (request.context.get(SILENT_ERRORS)) return throwError(() => error);
      if (SCREEN_OWNED.some((path) => request.url.includes(path))) return throwError(() => error);

      const api = apiErrorOf(error);
      const message = api?.message ?? transloco.translate<string>('band.unreachable');

      if (error.status === 401) {
        auth.forget();
        void router.navigate(['/sign-in'], { queryParams: { returnTo: router.url } });
        band.show({
          message: transloco.translate<string>('band.sessionExpired'),
          titleKey: 'band.signedOut',
        });
      } else if (error.status === 403) {
        void router.navigate(['/no-access']);
        band.fail(message, 'band.notAllowed');
      } else {
        band.fail(message);
      }
      return throwError(() => error);
    }),
  );
};
