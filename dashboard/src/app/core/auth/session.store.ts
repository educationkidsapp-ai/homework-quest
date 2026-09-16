import { DOCUMENT, Injectable, computed, inject, signal } from '@angular/core';

/** The one localStorage key the session uses. */
const REFRESH_KEY = 'hq.refresh';

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
  private readonly refresh = signal<string | null>(this.read());

  readonly accessToken = this.access.asReadonly();
  readonly refreshToken = this.refresh.asReadonly();

  /** True when a reload could plausibly restore a session — there is a refresh token to spend. */
  readonly restorable = computed(() => this.refresh() !== null);

  set(tokens: { token?: string; refreshToken?: string }): void {
    if (tokens.token) this.access.set(tokens.token);
    if (tokens.refreshToken) {
      this.refresh.set(tokens.refreshToken);
      this.write(tokens.refreshToken);
    }
  }

  clear(): void {
    this.access.set(null);
    this.refresh.set(null);
    this.write(null);
  }

  private read(): string | null {
    return this.storage()?.getItem(REFRESH_KEY) ?? null;
  }

  private write(value: string | null): void {
    const storage = this.storage();
    if (!storage) return;
    if (value === null) storage.removeItem(REFRESH_KEY);
    else storage.setItem(REFRESH_KEY, value);
  }

  /** localStorage throws in private-mode Safari and is absent when prerendering. */
  private storage(): Storage | null {
    try {
      return this.doc.defaultView?.localStorage ?? null;
    } catch {
      return null;
    }
  }
}
