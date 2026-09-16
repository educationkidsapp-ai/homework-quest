import { Routes } from '@angular/router';
import { areaRoutes } from '../../core/nav/area.routes';

/**
 * `/admin/**` (§6 screens 4–10).
 *
 * Every screen, its flag and its permission are declared once in `core/nav/screens.ts`;
 * `areaRoutes` turns that row into a route with `featureGuard` and `canGuard` attached, and
 * the rail is built from the same row. Listing them again here is exactly how a menu item and
 * the route behind it come to disagree.
 */
export const ADMIN_ROUTES: Routes = areaRoutes('ADMIN');
