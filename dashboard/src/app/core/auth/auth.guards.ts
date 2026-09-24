import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';
import { map } from 'rxjs/operators';
import { AuthService, ROLE_HOME, Role } from './auth.service';

/**
 * Everything behind the shell goes through here.
 *
 * On a cold load there is no access token — it is never persisted — so the guard spends the
 * refresh token first and only then decides. That one round trip is why a reload lands on the
 * page you were on rather than on sign-in.
 *
 * `returnTo` carries the URL that was refused, so signing in finishes the navigation that
 * started this instead of dropping the person on a Home.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth
    .restore()
    .pipe(
      map((user) =>
        user === null ? router.createUrlTree(['/sign-in'], { queryParams: { returnTo: state.url } }) : true,
      ),
    );
};

/**
 * `/sign-in` and the other public pages: already signed in means go home, not sign in again.
 * `/accept-invite` and `/reset-password` are deliberately *not* guarded by this — a link from
 * an email has to work in a browser where somebody else is still signed in.
 */
export const anonymousGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.restore().pipe(map((user) => (user === null ? true : router.parseUrl(auth.home()))));
};

/**
 * `roleGuard('ADMIN')` — `/admin/**` is not a Teacher's to open.
 *
 * The server would refuse the data anyway (`@PreAuthorize`, and a Teacher token cannot even
 * name another school's rows); this is so the refusal is a redirect to the person's own Home
 * rather than a screen full of red bands.
 */
export function roleGuard(...roles: readonly Role[]): CanActivateFn {
  return () => {
    const auth = inject(AuthService);
    const router = inject(Router);
    const role = auth.role();
    if (role !== null && (roles.includes(role) || role === 'ADMIN')) return true;
    return router.parseUrl(role === null ? '/sign-in' : ROLE_HOME[role]);
  };
}

/**
 * `/` — whoever you are, this is your Home.
 *
 * Also the one place the first-login password change is enforced. The server does not block
 * anything while `mustChangePassword` is set (runbook, "First-login password change"), so
 * this guard, sitting in front of every authenticated route, is what actually makes it forced.
 */
export const homeRedirectGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.restore().pipe(
    map((user): boolean | UrlTree => {
      if (user === null) return router.createUrlTree(['/sign-in'], { queryParams: { returnTo: state.url } });
      return router.parseUrl(auth.home());
    }),
  );
};

/** Sits on the shell: an account that must change its password may go exactly one place. */
export const passwordChangeGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.mustChangePassword() ? router.parseUrl('/change-password') : true;
};
