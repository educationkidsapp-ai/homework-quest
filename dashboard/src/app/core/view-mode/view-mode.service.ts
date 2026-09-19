import { Injectable, computed, inject, signal } from '@angular/core';
import { environment } from '../../../environments/environment';
import { AuthService } from '../auth/auth.service';

/** What the screens render as. `debug` adds the developer surfaces; `teacher` is every screen. */
export type ViewMode = 'teacher' | 'debug';

/** The key `debug` is remembered under, for an Admin, for this tab only. */
const STORAGE_KEY = 'hq.viewMode';

/**
 * Teacher view or debug view (CR5).
 *
 * CR5's rule is that a teacher never sees JSON. The rule is not "hide it from everyone": someone
 * has to be able to read the document the app actually plays when a stop comes out wrong, and
 * asking them to open devtools and re-authenticate a fetch is worse than a panel. So the raw
 * surfaces stay in the bundle and `debug` is what opens them — the stop editor's Raw JSON panel
 * today, whatever else needs one later.
 *
 * **Who may turn it on.** ADMIN, and nobody else. A teacher cannot reach it at all: there is no
 * control in her account menu, `?debug=1` is refused, and nothing is read back out of storage for
 * her, so a shared browser or a demoted account cannot leave her in it. The gate is a signal on
 * `AuthService.role()` rather than a check at the moment of toggling, so signing out of an Admin
 * session and into a teacher's collapses the mode in the same tick.
 *
 * **`?debug=1`** is for a developer running `ng serve` against a local server without an Admin
 * account to hand. It is refused on a `production`-configuration bundle, which is the *same
 * bundle QA and production serve* (D11) — so it never opens anything on either.
 *
 * **Never persisted for a teacher.** `sessionStorage`, not `localStorage`: debug is a thing you
 * are doing now, not a preference, and a tab that is closed has stopped doing it.
 */
@Injectable({ providedIn: 'root' })
export class ViewModeService {
  private readonly auth = inject(AuthService);
  private readonly wanted = signal<ViewMode>('teacher');

  /** Admins only. Everything else about this service is downstream of this one line. */
  readonly allowed = computed(() => this.auth.role() === 'ADMIN');

  readonly mode = computed<ViewMode>(() => (this.allowed() && this.wanted() === 'debug' ? 'debug' : 'teacher'));
  readonly debug = computed(() => this.mode() === 'debug');

  constructor() {
    if (stored() === 'debug' || queryAsksForDebug(environment.production, search())) this.wanted.set('debug');
  }

  set(mode: ViewMode): void {
    this.wanted.set(mode);
    remember(this.allowed() ? mode : 'teacher');
  }

  toggle(): void {
    this.set(this.debug() ? 'teacher' : 'debug');
  }
}

/**
 * `?debug=1`, and only off a build that is not the deployed one.
 *
 * `production` is true for the `qa` configuration as well as `production` — they are one bundle
 * (D11) — so this is false on everything a school can reach. Exported and given both its inputs
 * so the spec can state that, rather than having to rebuild the bundle to ask.
 */
export function queryAsksForDebug(production: boolean, search: string): boolean {
  if (production) return false;
  return new URLSearchParams(search).get('debug') === '1';
}

/** `location` is not there under a bare unit-test environment, and throws in a sandboxed frame. */
function search(): string {
  try {
    return location.search;
  } catch {
    return '';
  }
}

/** Storage throws rather than returning null in Lockdown Mode and behind a site-data block. */
function stored(): ViewMode | null {
  try {
    return sessionStorage.getItem(STORAGE_KEY) === 'debug' ? 'debug' : null;
  } catch {
    return null;
  }
}

function remember(mode: ViewMode): void {
  try {
    if (mode === 'debug') sessionStorage.setItem(STORAGE_KEY, mode);
    else sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // Not worth an exception: the mode still holds for this page.
  }
}
