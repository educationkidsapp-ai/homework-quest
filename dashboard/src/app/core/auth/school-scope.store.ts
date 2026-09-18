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
 * **D13.** While `multiSchool` is off there is one school and no switcher, so a selection this
 * browser kept from before — or from a database that has since been reseeded — must not keep
 * scoping requests to a school id that may no longer exist. `setMultiSchool(false)` both masks
 * the scope (so the interceptor sends no `X-School-Id`) and erases the stored value, because a
 * mask alone would come back the moment the flag was turned on again. `FlagService` calls it
 * once the flag map has settled; this store injects nothing but the document, so the
 * interceptor can keep reading it without a cycle through `HttpClient`.
 *
 * No HTTP lives here: the interceptor reads this store on every request, and a store that
 * injected the generated client would be a cycle through `HttpClient`.
 */
@Injectable({ providedIn: 'root' })
export class SchoolScopeStore {
  private readonly doc = inject(DOCUMENT);
  private readonly current = signal<SchoolScope | null>(this.read());
  private readonly multiSchool = signal(true);

  readonly scope = computed(() => (this.multiSchool() ? this.current() : null));
  readonly schoolId = computed(() => this.scope()?.id ?? null);

  /** Whether this deployment has more than one school; false collapses the scope to "mine". */
  setMultiSchool(on: boolean): void {
    this.multiSchool.set(on);
    if (!on && this.current() !== null) this.select(null);
  }

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
