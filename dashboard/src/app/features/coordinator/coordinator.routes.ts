/* hq-flag: none (shell) — the coordinator's area is a role, not a feature. R2 gates every
   `/coordinator/**` route on the `coordinator.read` / `coordinator.lesson.read` permissions
   (`permissions.json`), which `areaRoutes` puts on each row; there is no flag that could turn
   a person's own area off, and inventing one would hide the only screens she has. */
import { Routes } from '@angular/router';
import { areaRoutes } from '../../core/nav/area.routes';

/**
 * `/coordinator/**` (R5, `docs/coordinator-flow.md`).
 *
 * Declared in `core/nav/screens.ts` and gated by `areaRoutes`, like every other area: Home,
 * Teachers, Classes + calendar, Lessons, and the lesson itself in read-only mode. She writes
 * nothing here — DR2 — so no row of hers carries a write permission and none ever should.
 */
export const COORDINATOR_ROUTES: Routes = areaRoutes('COORDINATOR');
