import { Routes } from '@angular/router';
import { areaRoutes } from '../../core/nav/area.routes';

/**
 * `/floor/**` (feature 20, `docs/features/20-floor-supervisor-role.md`).
 *
 * Declared in `core/nav/screens.ts` and gated by `areaRoutes`, like every other area. Unlike
 * every other area, **every row of hers carries `FLAGS.floorSupervisor`**: the two read-only
 * supervisors are roles the build has always had, while the floor supervisor is a whole feature,
 * and a school that has not turned it on has no floors for these screens to read.
 */
export const FLOOR_ROUTES: Routes = areaRoutes('FLOOR_SUPERVISOR');
