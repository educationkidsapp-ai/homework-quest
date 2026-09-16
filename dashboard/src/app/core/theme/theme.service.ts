import { DOCUMENT, Injectable, computed, effect, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { SchoolTheme, ThemesApi } from '../../api';
import { AuthService } from '../auth/auth.service';
import { PlatformService } from '../platform/platform.service';

/**
 * Which `--hq-*` property each field of a school's theme overrides.
 *
 * §3: "the theme service maps the JSON onto the CSS custom properties from `tokens.json`
 * (`--hq-primary`, `--hq-accent`, …) on the root element, so every component re-themes
 * instantly". Only these seven map; the rest of the token set (sizes, spacing, motion, the
 * type scale) is the design system and is not a school's to change — a school picks colours,
 * not a layout.
 *
 * `primary` is the school's surface colour and `primaryInk` the text on it, which is why they
 * land on `surface`/`ink` rather than on anything called "primary": the dashboard's own
 * vocabulary is ground/surface/ink/accent and the theme JSON's is the mobile app's.
 */
const THEME_PROPERTIES = {
  primary: '--hq-color-surface',
  primaryInk: '--hq-color-ink',
  accent: '--hq-color-accent',
  ground: '--hq-color-bg',
  softBorder: '--hq-color-rule',
  mascotColor: '--hq-mascot-color-body',
} as const satisfies Partial<Record<keyof SchoolTheme, string>>;

/** How long a colour change takes to cross the whole page (§3: "a 300 ms eased transition"). */
const TRANSITION_MS = 300;

/**
 * Paints the school's colours onto the document.
 *
 * Every visual value in this workspace resolves through a custom property on `:root`, so
 * applying a theme is six `setProperty` calls and no rebuild, no per-school bundle and no
 * component that knows a school exists. Removing one is `removeProperty`, which falls back to
 * the generated default rather than to a second hard-coded palette.
 *
 * The colours arrive already validated: `PUT /admin/schools/{id}/theme` rejects a pair below
 * 4.5:1 and names it (§3), so nothing here has to second-guess a contrast ratio.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly api = inject(ThemesApi);
  private readonly auth = inject(AuthService);
  private readonly platform = inject(PlatformService);
  private readonly doc = inject(DOCUMENT);

  private readonly resource = rxResource<SchoolTheme | null, string | null | undefined>({
    params: () => (this.auth.signedIn() ? this.auth.effectiveSchoolId() : undefined),
    stream: ({ params: schoolId }) =>
      schoolId === null
        ? of(this.platform.settings().defaultTheme ?? null)
        : this.api.schoolTheme(schoolId).pipe(
            map((theme): SchoolTheme | null => theme),
            // A theme that will not load is a cosmetic failure: keep the default palette and
            // the dashboard, rather than a red band over a screen that otherwise works.
            catchError(() => of(null)),
          ),
    defaultValue: null,
  });

  readonly theme = computed(() => this.resource.value());
  /** The school's logo when it has one, else the platform's. */
  readonly logoUrl = computed(() => this.resource.value()?.logoUrl || this.platform.platformLogoUrl());
  /** §A's resolution order, for the header and the sign-in heading. */
  readonly appName = computed(() => this.resource.value()?.appName || this.platform.displayName());

  constructor() {
    effect(() => this.apply(this.resource.value()));
  }

  reload(): void {
    this.resource.reload();
  }

  /** Exposed for the Theme editor's live preview (P3.3), which paints before it saves. */
  apply(theme: SchoolTheme | null): void {
    const root = this.doc.documentElement;
    root.style.setProperty('--hq-theme-transition', `${TRANSITION_MS}ms`);
    for (const [field, property] of Object.entries(THEME_PROPERTIES)) {
      const value = theme?.[field as keyof typeof THEME_PROPERTIES];
      if (typeof value === 'string' && value.length > 0) root.style.setProperty(property, value);
      else root.style.removeProperty(property);
    }
  }
}
