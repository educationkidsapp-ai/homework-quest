import { DOCUMENT, Injectable, computed, inject, signal } from '@angular/core';

const SCOPE_KEY = 'hq.school';

/** What the Admin header's switcher is pointing at. `null` is "All schools". */
export interface SchoolScope {
  readonly id: string;
  readonly name: string;
}

/**
 * The Admin school switcher's selection.
 *
 * An ADMIN token carries no `schoolId` claim; the server scopes an Admin request by the
 * `X-School-Id` header instead (runbook, "The Admin school switcher"), and with no header an
 * Admin reads across every school. So the switcher is not a filter the screens apply — it is
 * one header the interceptor adds, and every screen, every flag lookup and the theme follow
 * from it without knowing it exists.
 *
 * Persisted, because an Admin who has narrowed to one school expects to still be in it after
 * a reload; cleared on sign-out with the rest of the session.
 *
 * No HTTP lives here: the interceptor reads this store on every request, and a store that
 * injected the generated client would be a cycle through `HttpClient`.
 */
@Injectable({ providedIn: 'root' })
export class SchoolScopeStore {
  private readonly doc = inject(DOCUMENT);
  private readonly current = signal<SchoolScope | null>(this.read());

  readonly scope = this.current.asReadonly();
  readonly schoolId = computed(() => this.current()?.id ?? null);

  select(scope: SchoolScope | null): void {
    this.current.set(scope);
    this.write(scope);
  }

  clear(): void {
    this.select(null);
  }

  private read(): SchoolScope | null {
    const raw = this.storage()?.getItem(SCOPE_KEY);
    if (!raw) return null;
    try {
      const parsed: unknown = JSON.parse(raw);
      if (parsed && typeof parsed === 'object' && 'id' in parsed && 'name' in parsed) {
        const { id, name } = parsed as Record<string, unknown>;
        if (typeof id === 'string' && typeof name === 'string') return { id, name };
      }
    } catch {
      // A hand-edited or half-written value is not worth a crash on boot.
    }
    return null;
  }

  private write(scope: SchoolScope | null): void {
    const storage = this.storage();
    if (!storage) return;
    if (scope === null) storage.removeItem(SCOPE_KEY);
    else storage.setItem(SCOPE_KEY, JSON.stringify(scope));
  }

  private storage(): Storage | null {
    try {
      return this.doc.defaultView?.localStorage ?? null;
    } catch {
      return null;
    }
  }
}
