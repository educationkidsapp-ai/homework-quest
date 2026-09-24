import { inject } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { CanActivateFn, Router } from '@angular/router';
import { filter, map, take } from 'rxjs/operators';
import { AuthService } from '../auth/auth.service';
import { PermissionService } from './permission.service';

/**
 * `canGuard('user.write')` — the route counterpart of `*hqCan`.
 *
 * Waits for `PermissionService.ready` — not for `loading`, which is still false in the tick
 * before the request starts, and would bounce a legitimate person off their own bookmark on a
 * cold start. Then a refusal goes to `/no-access`: unlike a flag, a missing permission *is*
 * about this person, so the page names the account and says who can change it.
 *
 * Applied by `core/nav/area.routes.ts` to every screen that declares a permission, from the
 * same table the rail is built from — so the door and the handle cannot disagree.
 */
export function canGuard(permission: string): CanActivateFn {
  return () => {
    const permissions = inject(PermissionService);
    const auth = inject(AuthService);
    const router = inject(Router);
    return toObservable(permissions.ready).pipe(
      filter(Boolean),
      take(1),
      map(() =>
        permissions.can(permission) || auth.role() === 'ADMIN' ? true : router.createUrlTree(['/no-access']),
      ),
    );
  };
}
