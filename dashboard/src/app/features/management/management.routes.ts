/* hq-flag: none (shell) — the manager's area is a role, not a feature. RM1 gates every
   `/management/**` route on her own `management.*` permissions (`permissions.json`), which
   `areaRoutes` puts on each row; the rows that *are* behind a flag — Gradebook, Exams, Messages,
   Complaints — declare it there, in the one table the rail is built from. */
import { Routes } from '@angular/router';
import { areaRoutes } from '../../core/nav/area.routes';

/**
 * `/management/**` (RM3a, `docs/management-flow.md`).
 *
 * Declared in `core/nav/screens.ts` and gated by `areaRoutes`, like every other area. Most of
 * her screens are the coordinator's, reading her department through `StaffScopeService`; the
 * four that are hers alone live beside this file.
 */
export const MANAGEMENT_ROUTES: Routes = areaRoutes('MANAGERIAL');
