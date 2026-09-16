import { inject } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { CanActivateFn, Router } from '@angular/router';
import { filter, map, take } from 'rxjs/operators';
import { FlagService } from './flag.service';

/**
 * `featureGuard('complaints')` — keeps a flagged route from being reached by typing its URL.
 *
 * The `*hqFeature` directive hides the way in; this closes the other way in. It waits for the
 * flag map to settle rather than reading it immediately, so a cold start does not bounce
 * someone off their own bookmark while the map is still in flight.
 *
 * A flag that is off answers `/not-found`, not `/no-access`: "this school does not have that
 * feature" is not the same as "you may not", and saying the second would suggest the person
 * should go and ask for a permission that does not exist.
 */
export function featureGuard(key: string): CanActivateFn {
  return () => {
    const flags = inject(FlagService);
    const router = inject(Router);
    return toObservable(flags.ready).pipe(
      filter(Boolean),
      take(1),
      map(() => (flags.isOn(key) ? true : router.createUrlTree(['/not-found']))),
    );
  };
}
