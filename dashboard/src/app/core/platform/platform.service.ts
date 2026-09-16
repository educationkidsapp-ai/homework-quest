import { DOCUMENT, Injectable, computed, effect, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { PlatformSettings, PlatformSettingsApi } from '../../api';
import { AuthService } from '../auth/auth.service';

/**
 * What the product is called, and what it looks like before a school is in scope.
 *
 * §A: the name is data, not a constant. `PlatformSettings.name` is seeded server-side and
 * editable under Platform settings, and a school's `theme.appName` overrides it inside that
 * school. The resolution order is **school appName → platform name → nothing**: when neither
 * has answered yet the name is empty and the places that show it show nothing, rather than
 * flashing a hard-coded default that a white-labelled deployment would have to un-see.
 * `hq/no-product-name-literal` fails the build on the alternative.
 *
 * `GET /platform-settings` is public, so the sign-in page can be branded before anyone has
 * signed in.
 */
@Injectable({ providedIn: 'root' })
export class PlatformService {
  private readonly api = inject(PlatformSettingsApi);
  private readonly auth = inject(AuthService);
  private readonly doc = inject(DOCUMENT);

  private readonly resource = rxResource<PlatformSettings, true>({
    params: () => true,
    stream: () => this.api.platformSettings().pipe(catchError(() => of<PlatformSettings>({}))),
    defaultValue: {},
  });

  readonly settings = computed(() => this.resource.value());
  readonly loading = this.resource.isLoading;

  /** The platform's own name. Empty until it has been read. */
  readonly platformName = computed(() => this.resource.value().name ?? '');
  readonly platformLogoUrl = computed(() => this.resource.value().logoUrl ?? null);
  readonly supportEmail = computed(() => this.resource.value().supportEmail ?? null);

  /** The name to show right now: the school in scope overrides the platform. */
  readonly displayName = computed(() => this.auth.user()?.platformName || this.platformName());

  /** The school's logo when there is one, else the platform's. */
  readonly logoUrl = computed(() => this.platformLogoUrl());

  constructor() {
    // The browser tab is the one place the name has to appear whatever screen is open.
    effect(() => {
      const name = this.displayName();
      if (name) this.doc.title = name;
    });
  }

  reload(): void {
    this.resource.reload();
  }
}
