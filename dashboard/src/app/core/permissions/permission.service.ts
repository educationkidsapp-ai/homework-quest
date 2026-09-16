import { Injectable, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { of } from 'rxjs';
import { AuthApi, MePermissions } from '../../api';
import { AuthService } from '../auth/auth.service';

/**
 * What this account may do, from `GET /me/permissions`.
 *
 * The same `permissions.json` the server enforces with `@PreAuthorize` is what this list
 * comes from, so the dashboard hides exactly what the server would refuse — never more (an
 * action that 403s is a broken promise) and never less (a hidden action the person could
 * have used is a support ticket).
 *
 * `readOnly` is true for an Admin's "View as" session, where every non-GET is refused: it
 * takes away every write in one move rather than per key.
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
  /** A "View as" session: the server refuses every write, so the UI must not offer one. */
  readonly readOnly = computed(() => this.resource.value().readOnly === true);

  /** `can('lesson.publish')` — false while the list is still loading, so nothing flashes in. */
  can(permission: string): boolean {
    if (!this.keys().has(permission)) return false;
    return !(this.readOnly() && isWrite(permission));
  }

  /** The raw set, for a screen that needs to reason about several keys at once. */
  all(): ReadonlySet<string> {
    return this.keys();
  }

  reload(): void {
    this.resource.reload();
  }
}

/**
 * Whether a key names something the server would refuse in a read-only session.
 *
 * The matrix has no "is this a write" column, so the convention in the key itself is what
 * there is: `*.read`, `*.play` and `me.*` are reads, everything else changes something.
 */
function isWrite(permission: string): boolean {
  return !(permission.endsWith('.read') || permission.startsWith('me.') || permission.endsWith('.play'));
}
