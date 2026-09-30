import {
  DOCUMENT,
  type EnvironmentProviders,
  Injectable,
  inject,
  provideAppInitializer,
} from '@angular/core';
import { Router } from '@angular/router';
import { AuthService, isRole, type Role } from './auth.service';
import { REFRESH_KEY, SESSION_OWNER_KEY, SessionStore } from './session.store';

/** What a change in another tab did to this one, for the sign-in page's notice. */
export type SessionEnd = 'takenOver' | 'signedOutElsewhere';

/**
 * **What the other tabs of this browser profile did to this session.**
 *
 * T2 item (e). One `localStorage` serves every tab of a profile, and the session lives in it, so
 * two tabs are not two sessions — they are two views of one, and until now neither knew it:
 *
 * - **A second role in a second tab.** Signing in as the manager overwrites the teacher tab's
 *   refresh token and its owner stamp. The teacher tab carried on: its next refresh spent a
 *   foreign token, the server revoked the pair, and she was signed out with no explanation — and
 *   in between it was running the manager's socket. It is now told, in as many words, that it is
 *   over and why ({@link SessionEnd}).
 * - **The same account in two tabs.** Each tab's refresh rotated the one single-use token, and
 *   the loser presented a spent one. {@link RefreshLock} serialises the rotation; this listener
 *   is the other half of that fix, because a serialised rotation is only safe if the waiting tab
 *   can see the token the winner wrote.
 * - **A sign-out in another tab** revokes the shared refresh token server-side. This tab holds
 *   nothing it can spend, so it ends here rather than at its next 401.
 *
 * `storage` fires in every tab **but** the one that wrote, which is exactly the audience.
 */
@Injectable({ providedIn: 'root' })
export class SessionSyncService {
  private readonly doc = inject(DOCUMENT);
  private readonly session = inject(SessionStore);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private listening = false;

  start(): void {
    const view = this.doc.defaultView;
    if (this.listening || !view) return;
    this.listening = true;
    view.addEventListener('storage', (event) => this.onStorage(event));
  }

  /**
   * One `storage` event. Public because a unit test cannot make a second tab, and because this
   * is the whole of the behaviour — `start` is only the wiring.
   */
  onStorage(event: Pick<StorageEvent, 'key' | 'newValue'>): void {
    if (event.key === SESSION_OWNER_KEY) this.onOwnerChanged(event.newValue);
    else if (event.key === REFRESH_KEY) this.onTokenChanged(event.newValue);
  }

  private onOwnerChanged(arriving: string | null): void {
    const mine = this.session.claimedOwner();
    // Nothing of ours to lose — an anonymous tab on the sign-in screen — or the same session being
    // re-stamped by a tab of the same account, which is what a plain reload elsewhere is.
    // `claimedOwner` rather than `identity` so a reload whose `/me` has not landed still counts as
    // holding a session: it does, and it is that session another tab has just replaced.
    if (mine === null || arriving === null || arriving === mine) return;
    this.end('takenOver', roleOf(arriving));
  }

  private onTokenChanged(arriving: string | null): void {
    const mine = this.session.claimedOwner();
    if (arriving === null) {
      if (mine === null) return;
      this.end('signedOutElsewhere', null);
      return;
    }
    // An anonymous tab has no claim to contradict: whatever is in storage is simply the newest
    // token there, and this tab will find out whose it is if it ever signs in.
    if (mine === null) {
      this.session.adopt(arriving);
      return;
    }

    // **Whose token is this?** A sign-in cannot stamp the owner before the tokens — the user id
    // arrives with `/me` — so `SessionStore.disown` clears the stamp first. A token that arrives
    // while the stored owner is *missing* or *somebody else's* therefore belongs to a session this
    // tab is not on, and adopting it would make this tab present a foreign token at its next
    // refresh: the revocation this listener exists to prevent. Only a rotation under our own
    // stamp is ours to take.
    const owner = this.session.ownerInStorage();
    if (owner === mine) {
      this.session.adopt(arriving);
      return;
    }
    this.end('takenOver', owner === null ? null : roleOf(owner));
  }

  private end(reason: SessionEnd, role: Role | null): void {
    this.auth.forget();
    const queryParams: Record<string, string> = { ended: reason };
    if (role !== null) queryParams['as'] = role;
    void this.router.navigate(['/sign-in'], { queryParams });
  }
}

/** `<userId>:<role>` → the role, when it is one this dashboard knows. */
function roleOf(identity: string): Role | null {
  const role = identity.split(':').pop();
  return isRole(role) ? role : null;
}

/**
 * Listening from the first tick rather than from the shell: a takeover that happens while she is
 * on a sign-in or a change-password screen is still a takeover, and the tab that missed it would
 * go on holding a session it does not own.
 */
export function provideSessionSync(): EnvironmentProviders {
  return provideAppInitializer(() => inject(SessionSyncService).start());
}
