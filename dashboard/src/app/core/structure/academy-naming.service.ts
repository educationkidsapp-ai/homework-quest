import { Injectable, computed, inject, signal } from '@angular/core';
import { type Academy, SchoolStructureApi } from '../../api';
import { FLAGS, FlagService } from '../flags/flag.service';
import { PermissionService } from '../permissions/permission.service';
import { type Naming, academyKeyOf } from './academy-naming';

/** The permission `GET /admin/structure/academies` carries — the Admin's alone. */
export const STRUCTURE_MANAGE = 'structure.manage';

/**
 * **Which academy calls a grade what** — the one copy of feature 36's naming the whole dashboard
 * reads, fed either by the screen that just loaded the academies or by one request of its own.
 *
 * The naming lives on an **ADMIN-only** route, so this service asks for it only when the account
 * holds `structure.manage` and the switch is on. Every other role reads `naming()` as `null` and
 * the pipe falls back to `Grade {{grade}}`, which is the string that role has always seen. That is
 * a deliberate limit of the feature and not a gap to paper over with a second public endpoint:
 * the names are the Admin's own setting, and her screens are where they matter.
 *
 * One request per session at most (`asked`), and a failure is silence: a label that falls back is
 * a worse label, never a broken screen, so a red band over somebody's gradebook because a naming
 * request failed would be the wrong trade.
 */
@Injectable({ providedIn: 'root' })
export class AcademyNamingService {
  private readonly api = inject(SchoolStructureApi);
  private readonly permissions = inject(PermissionService);
  private readonly flags = inject(FlagService);

  private readonly namings = signal<ReadonlyMap<string, Naming>>(new Map());
  private asked = false;

  /** Every academy key this service has a naming for, for a screen that wants to list them. */
  readonly keys = computed(() => [...this.namings().keys()]);

  /** The naming of one academy, or `null` while it is unknown — the pipe's fallback signal. */
  naming(academyKey: string | null | undefined): Naming | null {
    const key = academyKeyOf(academyKey);
    return key === '' ? null : (this.namings().get(key) ?? null);
  }

  /**
   * Remember what a screen has already read, so the School structure page does not pay for a
   * second request and its own tree labels come from the same place every other screen reads.
   */
  remember(academies: readonly Academy[]): void {
    this.asked = true;
    // `update` and not `set(new Map(this.namings()))`: the caller is an `effect` over the tree, and
    // reading the signal there would make the effect depend on what it is about to write — which
    // is an effect that re-runs on its own write, forever. (It did; the page spec hung on it.)
    this.namings.update((current) => {
      const next = new Map(current);
      for (const academy of academies) {
        const key = academyKeyOf(academy.academyKey);
        if (key !== '' && academy.naming) next.set(key, academy.naming);
      }
      return next;
    });
  }

  /** Read the academies once, when this account may. Called by the pipe, so it must stay cheap. */
  load(): void {
    if (this.asked) return;
    if (!this.permissions.ready() || !this.permissions.can(STRUCTURE_MANAGE)) return;
    if (!this.flags.isOn(FLAGS.schoolStructure)) return;
    this.asked = true;
    this.api.structureAcademies().subscribe({
      next: (academies) => this.remember(academies),
      error: () => undefined,
    });
  }
}
