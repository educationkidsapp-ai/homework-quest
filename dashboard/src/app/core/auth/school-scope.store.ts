import { DOCUMENT, Injectable, computed, inject, signal } from '@angular/core';
import { NAV_CONFIG } from '../nav/nav-config';

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
  /**
   * D1: with the switcher out of the design (`NAV_CONFIG.schoolSurfaces`), a stored scope is one
   * nobody can change on screen — so it is not read at all, and is erased below. Before the flag
   * map has even loaded: a school id from a re-created database answers `404 school not found`
   * to `/me` itself, which is the request that restores the session.
   */
  private readonly switchable = inject(NAV_CONFIG).schoolSurfaces;
  private readonly current = signal<SchoolScope | null>(this.switchable ? this.read() : null);
  private readonly multiSchool = signal(this.switchable);

  private readonly sole = signal<string | null>(null);

  readonly scope = computed(() => (this.multiSchool() ? this.current() : null));
  /**
   * The school an Admin's requests are scoped to: the one she picked — or, **with the switcher
   * hidden, the only one there is** (D1).
   *
   * Hiding the switcher took away the only way to pick, and an Admin with no school is not a
   * neutral position: she reads the *platform's* flag defaults rather than her school's (so a
   * school with chat on had no Messages for her), the new-lesson wizard asks her to "pick a
   * school", and `/admin/chat/**` answers `400 Send X-School-Id`. So in that configuration she is
   * pinned to the one school exactly as if she had chosen it — from the server's own list
   * ({@link setSoleSchool}), never from storage. Null while it is unresolved, and for a
   * deployment that turns out to have several.
   */
  readonly schoolId = computed(() => this.scope()?.id ?? (this.switchable ? null : this.sole()));

  /**
   * The id of the **one** school this deployment has, while `multiSchool` is off.
   *
   * D13 masks `schoolId` with the flag off, for a good reason — a selection kept from a database
   * that has since been reseeded must not scope anything — and with the flag off there is no
   * switcher either, so nobody can pick. That leaves the handful of routes that are read *one
   * school at a time* (`/admin/chat/**`: `400 Send X-School-Id`) with no id at all.
   *
   * So this is the answer to "which school", not to "which school did she choose": it is written
   * by whoever **resolved** it from `GET /admin/schools` and never read from storage, so the stale
   * id the mask exists to stop still cannot come back. Null again the moment `multiSchool` is on,
   * because then there are several and choosing is hers.
   */
  readonly soleSchoolId = computed(() => (this.multiSchool() ? null : this.sole()));

  constructor() {
    if (!this.switchable) this.write(null);
  }

  /** Written by the resolver, never by a screen: no HTTP lives in this store (see the class note). */
  setSoleSchool(id: string | null): void {
    this.sole.set(id);
  }

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
