import { Routes } from '@angular/router';
import { areaRoutes } from '../../core/nav/area.routes';

/**
 * `/management/**` (§6 screens 17–20).
 *
 * Declared in `core/nav/screens.ts` and gated by `areaRoutes`. Complaints is the role's main
 * screen and is flagged: a school that has not turned `complaints` on has no inbox, the
 * endpoint 404s, the rail does not offer it, and `featureGuard` closes the URL as well.
 */
export const MANAGEMENT_ROUTES: Routes = areaRoutes('MANAGERIAL');
