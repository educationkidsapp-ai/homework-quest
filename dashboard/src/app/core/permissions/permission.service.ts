import { Injectable, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { of } from 'rxjs';
import { AuthApi, MePermissions } from '../../api';
import { AuthService } from '../auth/auth.service';
import { WRITE_PERMISSIONS } from './permissions.generated';

/**
 * What this account may do, from `GET /me/permissions`.
 *
 * The same `permissions.json` the server enforces with `@PreAuthorize` is what this list comes
 * from, so the dashboard hides exactly what the server would refuse — never more (an action
 * that 403s is a broken promise) and never less (a hidden action the person could have used is
 * a support ticket).
 *
 * `readOnly` is true for an Admin's "View as" session, where the server refuses every non-GET.
 * That takes away every **write** in one move — and nothing else: `usage.platform` and
 * `usage.school` are reads, and an Admin viewing as a Managerial user who lost "School usage"
 * would have lost most of the reason to look. Which keys are writes is derived from the
 * server's own matrix by `pnpm gen:permissions`, not guessed from the shape of the key.
 */
@Injectable({ providedIn: 'root' })
export class PermissionService {
  private readonly api = inject(AuthApi);
  private readonly auth = inject(AuthService);

  private readonly resource = rxResource<MePermissions, boolean>({
    params: () => this.auth.signedIn(),
    stream: ({ params: signedIn }) => (signedIn ? this.api.myPermissions() : of({})),
    defaultValue: {},
  });

  private readonly keys = computed(() => new Set(this.resource.value().permissions ?? []));

  readonly loading = this.resource.isLoading;

  /**
   * True once the list has settled — resolved, failed, or never asked for because nobody is
   * signed in.
   *
   * `canGuard` waits on this rather than on `loading`, which is still `false` in the tick
   * between the parameters becoming known and the request starting. A guard reading `can()` in
   * that gap sees an empty set and bounces a legitimate person to `/no-access` on every cold
   * start from a bookmark. `FlagService.ready` exists for the same reason.
   */
  readonly ready = computed(() => {
    const status = this.resource.status();
    return status === 'resolved' || status === 'error' || status === 'local' || !this.auth.signedIn();
  });

  /** A "View as" session: the server refuses every write, so the UI must not offer one. */
  readonly readOnly = computed(() => this.resource.value().readOnly === true);

  /** `can('lesson.publish')` — false while the list is still loading, so nothing flashes in. */
  can(permission: string): boolean {
    if (!this.keys().has(permission)) return false;
    return !(this.readOnly() && WRITE_PERMISSIONS.has(permission));
  }

  /** The raw set, for a screen that needs to reason about several keys at once. */
  all(): ReadonlySet<string> {
    return this.keys();
  }

  reload(): void {
    this.resource.reload();
  }
}
