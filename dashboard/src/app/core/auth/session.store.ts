import { DOCUMENT, Injectable, computed, inject, signal } from '@angular/core';

/** The persisted half of the session: the refresh token, and nothing else. */
export const REFRESH_KEY = 'hq.refresh';
/**
 * Who the persisted refresh token belongs to — `<userId>:<role>`, no name and no email.
 *
 * T2 item (e): a browser profile has one `localStorage`, so signing in as a second role in a
 * second tab overwrites {@link REFRESH_KEY} and the first tab silently continues with somebody
 * else's session — refreshing with a foreign token, and running that role's socket. This is the
 * one value that makes the takeover visible to the tab it happened to, which is why it is
 * written beside the token rather than derived from it: the token is opaque to the client.
 */
export const SESSION_OWNER_KEY = 'hq.session.owner';

/**
 * D1: `<userId>:<role>` of the session **this tab** last held, in `sessionStorage`.
 *
 * {@link SESSION_OWNER_KEY} says whose the stored session is now and is erased when it ends; this
 * outlives the ending, per tab, so the sign-in that follows can tell "the same person coming
 * back" from "somebody else on this browser" — see {@link SessionStore.lastOwner}.
 */
export const LAST_OWNER_KEY = 'hq.session.last';

/**
 * Where the two tokens live.
 *
 * The **access** token is held in memory only. It is the token that grants access to every
 * endpoint, it lives fifteen minutes, and anything that can read `localStorage` (an injected
 * script, an extension) can replay it — so it never goes to disk and a reload starts without
 * one. The **refresh** token is persisted, because otherwise every reload would be a sign-in;
 * it is single-use and rotated on the server, which is what makes persisting it acceptable.
 *
 * This store holds no HTTP of its own on purpose: the auth interceptor reads the token from
 * here, and injecting {@link AuthService} (which injects the generated client, which injects
 * `HttpClient`) into the interceptor would be a cycle through the very client it decorates.
 */
@Injectable({ providedIn: 'root' })
export class SessionStore {
  private readonly doc = inject(DOCUMENT);
  private readonly access = signal<string | null>(null);
  private readonly refresh = signal<string | null>(this.read(REFRESH_KEY));
  private readonly owner = signal<string | null>(null);
  /**
   * The owner stamp this tab **found in storage when it loaded**.
   *
   * `identity` is null for the length of a reload's `restore()` — `/me` has not landed — and a
   * listener that compared against it in that window could not tell a takeover from an ordinary
   * rotation by a tab of the same account. This survives the reload, so it can.
   */
  private readonly loadedOwner = signal<string | null>(this.read(SESSION_OWNER_KEY));
  /** The stamp found at load, kept after `clear()` so a failed restore still knows whose it was. */
  private readonly ownerAtLoad = this.read(SESSION_OWNER_KEY);

  readonly accessToken = this.access.asReadonly();
  readonly refreshToken = this.refresh.asReadonly();
  /**
   * `<userId>:<role>` for **this tab's** session, in memory only.
   *
   * Memory rather than storage on purpose: storage is what another tab overwrites, so comparing
   * it with itself could never detect a takeover. This is the value the `storage` listener
   * compares the arriving one against ({@link SessionSyncService}).
   */
  readonly identity = this.owner.asReadonly();
  /**
   * Whose session this tab believes it is holding — what `/me` confirmed, or, while a reload is
   * still restoring, what was in storage when it started. `null` is "no claim to contradict".
   */
  readonly claimedOwner = computed(() => this.owner() ?? this.loadedOwner());

  /** True when a reload could plausibly restore a session — there is a refresh token to spend. */
  readonly restorable = computed(() => this.refresh() !== null);

  set(tokens: { token?: string; refreshToken?: string }): void {
    if (tokens.token) this.access.set(tokens.token);
    if (tokens.refreshToken) {
      this.refresh.set(tokens.refreshToken);
      this.write(REFRESH_KEY, tokens.refreshToken);
    }
  }

  /** `/me` has landed: stamp the stored session with who it belongs to. */
  claim(userId: string, role: string): void {
    const identity = `${userId}:${role}`;
    this.owner.set(identity);
    this.loadedOwner.set(identity);
    this.write(SESSION_OWNER_KEY, identity);
    this.writeLast(identity);
  }

  /**
   * **Whose session this tab last held**, or null when it has never held one.
   *
   * What a remembered address is checked against (D1, the owner's list of 2026-10-01): a
   * `returnTo`, a `?thread=` or a class id left behind by one account must not be followed by the
   * next one to sign in here — to her it is a row she has never seen, and the server's honest 404
   * for it was the red "class not found" on first open. The tab's own record first; failing that,
   * the stamp that was in storage when the tab loaded, which is what a reload onto a dead session
   * (a re-created database, a revoked token) still has after `clear()` erased the live one.
   */
  lastOwner(): string | null {
    return this.readLast() ?? this.ownerAtLoad;
  }

  /**
   * **A new session is being written, and nobody owns it yet.**
   *
   * Called before the tokens of a sign-in. The user id only arrives with `/me`, so for a moment
   * `hq.refresh` holds a token and `hq.session.owner` says whose it is *not*. That moment is what
   * makes a second-role sign-in safe: the other tab's listener sees a token arrive with no owner,
   * which is never something it may adopt (`SessionSyncService.onStorage`). Stamping afterwards
   * rather than before is the only order available — and it is the safe one, because "unknown
   * owner" is refused while a stale owner would be believed.
   */
  disown(): void {
    this.owner.set(null);
    this.loadedOwner.set(null);
    this.write(SESSION_OWNER_KEY, null);
  }

  /** What the *storage* says right now — which is what another tab has just written. */
  ownerInStorage(): string | null {
    return this.read(SESSION_OWNER_KEY);
  }

  /**
   * Another tab rotated the refresh token: take the new one without writing it back.
   *
   * The token in this signal is what {@link AuthService.refresh} spends, and a token another tab
   * has already rotated is the one the server reads as theft — so keeping it current is not a
   * nicety, it is what stops the second tab revoking the account.
   */
  adopt(refreshToken: string | null): void {
    this.refresh.set(refreshToken);
  }

  clear(): void {
    this.access.set(null);
    this.refresh.set(null);
    this.owner.set(null);
    this.loadedOwner.set(null);
    this.write(REFRESH_KEY, null);
    this.write(SESSION_OWNER_KEY, null);
  }

  private read(key: string): string | null {
    return this.storage()?.getItem(key) ?? null;
  }

  private write(key: string, value: string | null): void {
    const storage = this.storage();
    if (!storage) return;
    if (value === null) storage.removeItem(key);
    else storage.setItem(key, value);
  }

  /** localStorage throws in private-mode Safari and is absent when prerendering. */
  private storage(): Storage | null {
    try {
      return this.doc.defaultView?.localStorage ?? null;
    } catch {
      return null;
    }
  }

  private readLast(): string | null {
    try {
      return this.doc.defaultView?.sessionStorage.getItem(LAST_OWNER_KEY) ?? null;
    } catch {
      return null;
    }
  }

  private writeLast(identity: string): void {
    try {
      this.doc.defaultView?.sessionStorage.setItem(LAST_OWNER_KEY, identity);
    } catch {
      // Private browsing / a full quota: the tab just stops remembering, silently.
    }
  }
}
