import { Routes } from '@angular/router';
import { STYLEGUIDE_ROUTES } from './styleguide/styleguide.route';

/**
 * Shell routing. Role areas (`/admin/**`, `/teacher/**`, `/management/**`) arrive in P3.1
 * as lazy children under `src/app/features/`; this package ships only the styleguide so the
 * component library has somewhere to live.
 *
 * `STYLEGUIDE_ROUTES` is empty in the production configuration (file replacement), so the
 * default redirect is added only when there is something to redirect to.
 */
export const routes: Routes = [
  ...STYLEGUIDE_ROUTES,
  ...(STYLEGUIDE_ROUTES.length > 0
    ? ([{ path: '', pathMatch: 'full', redirectTo: 'styleguide' }] satisfies Routes)
    : []),
];
