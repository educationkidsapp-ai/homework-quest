import { inject } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { CanActivateFn, Router } from '@angular/router';
import { filter, map, take } from 'rxjs/operators';
import { PermissionService } from './permission.service';

/**
 * `canGuard('user.write')` — the route counterpart of `*hqCan`.
 *
 * Waits for `GET /me/permissions` rather than reading a half-loaded set, then sends a
 * refusal to `/no-access`: unlike a flag, a missing permission *is* about this person, and
 * the page says whose account it is and who can change it.
 */
export function canGuard(permission: string): CanActivateFn {
  return () => {
    const permissions = inject(PermissionService);
    const router = inject(Router);
    return toObservable(permissions.loading).pipe(
      filter((loading) => !loading),
      take(1),
      map(() => (permissions.can(permission) ? true : router.createUrlTree(['/no-access']))),
    );
  };
}
