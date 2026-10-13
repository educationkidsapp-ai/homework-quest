import { Injectable, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { catchError, map, of, tap } from 'rxjs';
import { SchoolsApi } from '../../api';
import { NAV_CONFIG } from '../nav/nav-config';
import { AuthService } from './auth.service';
import { SchoolScopeStore } from './school-scope.store';

/**
 * **Which school an Admin is in when nobody can pick one.**
 *
 * An Admin's account carries no school. With `multiSchool` on and the switcher on screen, the
 * school is whichever she picked in the header. Otherwise there is no switcher to pick with, so
 * this resolves the one row of `GET /admin/schools` — the call the switcher itself makes, and
 * deliberately the server's answer rather than the id in `localStorage` that D13's mask exists to
 * distrust — and tells {@link SchoolScopeStore}, which is HTTP-free.
 *
 * **Its own root service, created with the flags.** It used to live in `ChatRoutes`, which only
 * exists once the shell renders. Angular runs every route guard before it creates any component,
 * so on a cold start `featureGuard` read the flag map while no school was resolved yet — the
 * *platform's* defaults, where `chat`, `staffAttendance` and the rest are off — and sent a
 * reloaded Admin screen to "Nothing here". `FlagService` injects this and treats the map as not
 * ready while {@link pending} is true.
 *
 * The store is written inside the stream, before the resource settles, so there is no tick in
 * which this reads settled while the store still holds no school.
 */
@Injectable({ providedIn: 'root' })
export class SoleSchoolService {
  private readonly auth = inject(AuthService);
  private readonly schools = inject(SchoolsApi);
  private readonly scope = inject(SchoolScopeStore);
  private readonly navConfig = inject(NAV_CONFIG);

  private readonly resource = rxResource<string | null, boolean>({
    // D1: with the switcher hidden nobody can pick whatever `multiSchool` says, so the one school
    // is resolved for every Admin. With the switcher on screen it is resolved only while the
    // store says `multiSchool` is off — which `FlagService` sets once its map has settled.
    params: () =>
      this.auth.role() === 'ADMIN' && (!this.navConfig.schoolSurfaces || !this.scope.multiSchoolOn()),
    stream: ({ params }) =>
      (params
        ? this.schools.listSchools().pipe(
            map((rows) => {
              if (rows.length === 1) return rows[0]?.id ?? null;
              // D1 review: with the switcher hidden a second school must not leave her with no
              // scope at all — `/admin/chat/**` answers 400 without one and nothing on screen can
              // fix it. The first active school of the server's list is hers until the switcher
              // is back; with the switcher on screen, several schools are still hers to choose.
              if (this.navConfig.schoolSurfaces) return null;
              return (rows.find((row) => row.status === 'active') ?? rows[0])?.id ?? null;
            }),
            catchError(() => of(null)),
          )
        : of(null)
      ).pipe(tap((id) => this.scope.setSoleSchool(id))),
    defaultValue: null,
  });

  /** The one school's id, or null while unresolved and for a deployment with several. */
  readonly id = this.resource.value;

  /**
   * True while the flags must not be trusted yet: the switcher is hidden, the session is signed
   * in, and either the role is not known or an Admin's one school is still being resolved. With
   * the switcher on screen nothing waits here — "All schools" is a real position there.
   */
  readonly pending = computed(() => {
    if (this.navConfig.schoolSurfaces || !this.auth.signedIn()) return false;
    const role = this.auth.role();
    if (role === null) return true;
    if (role !== 'ADMIN') return false;
    const status = this.resource.status();
    return !(status === 'resolved' || status === 'error' || status === 'local');
  });
}
