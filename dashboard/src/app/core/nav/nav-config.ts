import { InjectionToken } from '@angular/core';
import type { Screen } from './screens';

/**
 * **The one switch over what the navigation shows** (the owner's list of 2026-10-01, ADMIN item 5).
 *
 * `schoolSurfaces: false` hides — in design only — the three doors to the multi-school build: the
 * header's "All schools" switcher and the rail's Schools and Users rows. Nothing is deleted: the
 * routes, their guards and the screens behind them stay exactly where `screens.ts` declares them,
 * so a bookmark still resolves and turning the build back on is this one boolean.
 *
 * Without a switcher nobody can *choose* a school, so the same switch collapses the Admin's
 * school scope to "the one school there is" (`FlagService` → `SchoolScopeStore.setMultiSchool`).
 * Otherwise an id this browser kept from before — from a database that has since been re-created
 * — would go on scoping every request with no control left on screen to change it.
 */
export interface NavConfig {
  readonly schoolSurfaces: boolean;
}

export const NAV_CONFIG = new InjectionToken<NavConfig>('hq.nav.config', {
  providedIn: 'root',
  factory: () => ({ schoolSurfaces: false }),
});

/** Whether a screen's row is drawn in the rail under this configuration. Its route is not asked. */
export function inRail(screen: Screen, config: NavConfig): boolean {
  return screen.schoolSurface !== true || config.schoolSurfaces;
}
