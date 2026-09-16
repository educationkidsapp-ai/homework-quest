import { CdkMenu, CdkMenuItem, CdkMenuTrigger } from '@angular/cdk/menu';
import { ChangeDetectionStrategy, Component, computed, inject, output } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { SchoolSummary, SchoolsApi } from '../api';
import { AuthService } from '../core/auth/auth.service';
import { SchoolScopeStore } from '../core/auth/school-scope.store';
import { LANGUAGES, LanguageService } from '../core/i18n/language.service';
import { ThemeService } from '../core/theme/theme.service';
import { TourService } from '../core/tour/tour.service';

/**
 * The bar above every screen: whose dashboard this is, which school is in scope, and who is
 * signed in.
 *
 * The **school switcher** is Admin-only and is the whole of multi-tenancy in the UI: picking a
 * school stores an id, the auth interceptor turns it into `X-School-Id`, and the flag map,
 * the theme and every screen follow because they key on the school in scope. Nothing here
 * filters anything itself.
 *
 * The **View as** banner is not decoration. An impersonated session is read-only on the
 * server (`ReadOnlyGuard` refuses every non-GET) and audit-logged; without the banner an
 * Admin would be typing into a screen that will refuse to save, wondering why.
 *
 * Both menus are CDK menus rather than hand-rolled popups: the roving tab index, Esc, arrow
 * keys and `aria-expanded` are the platform's job, and this is exactly what §0 allows the CDK
 * for.
 */
@Component({
  selector: 'hq-shell-header',
  imports: [CdkMenu, CdkMenuItem, CdkMenuTrigger, RouterLink, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (auth.impersonatedBy(); as actor) {
      <p class="header__viewas" role="status">
        {{ 'shell.viewingAs' | transloco: { name: auth.displayName() } }}
        <span class="header__viewas-actor">{{ actor }}</span>
      </p>
    }

    <header class="header">
      <div class="header__brand">
        @if (logoUrl(); as logo) {
          <img class="header__logo" [src]="logo" alt="" />
        }
        <span class="header__name">{{ name() }}</span>
      </div>

      <div class="header__actions">
        @if (isAdmin()) {
          <button
            type="button"
            class="header__button"
            data-hq-tour="switcher"
            [cdkMenuTriggerFor]="schoolMenu"
            [attr.aria-label]="'shell.switcher.label' | transloco"
          >
            {{ scope.scope()?.name ?? ('shell.switcher.all' | transloco) }}
          </button>
        }

        <button type="button" class="header__button" data-hq-tour="profile" [cdkMenuTriggerFor]="profileMenu">
          {{ auth.displayName() }}
        </button>
      </div>
    </header>

    <ng-template #schoolMenu>
      <div cdkMenu class="menu" [attr.aria-label]="'shell.switcher.label' | transloco">
        <button type="button" cdkMenuItem class="menu__item" (cdkMenuItemTriggered)="choose(null)">
          {{ 'shell.switcher.all' | transloco }}
        </button>
        @for (school of schools.value(); track school.id) {
          <button type="button" cdkMenuItem class="menu__item" (cdkMenuItemTriggered)="choose(school)">
            {{ school.name }}
            <span class="menu__hint">{{ school.code }}</span>
          </button>
        }
      </div>
    </ng-template>

    <ng-template #profileMenu>
      <div cdkMenu class="menu" [attr.aria-label]="'shell.profile.label' | transloco">
        <a cdkMenuItem class="menu__item" routerLink="/profile">{{ 'shell.profile.open' | transloco }}</a>
        @for (option of languages; track option) {
          <button
            type="button"
            cdkMenuItem
            class="menu__item"
            [attr.aria-current]="option === language.language() ? 'true' : null"
            (cdkMenuItemTriggered)="language.use(option)"
          >
            {{ 'shell.language.' + option | transloco }}
          </button>
        }
        <button type="button" cdkMenuItem class="menu__item" (cdkMenuItemTriggered)="showMeAround()">
          {{ 'shell.showMeAround' | transloco }}
        </button>
        <button
          type="button"
          cdkMenuItem
          class="menu__item menu__item--danger"
          (cdkMenuItemTriggered)="signOut.emit()"
        >
          {{ 'shell.signOut' | transloco }}
        </button>
      </div>
    </ng-template>
  `,
  styles: `
    @use 'mixins' as m;

    :host {
      display: block;
    }

    .header__viewas {
      display: flex;
      gap: var(--hq-space-8);
      padding: var(--hq-space-8) var(--hq-size-page-padding);
      background: var(--hq-color-accent-soft);
      border-block-end: var(--hq-size-rule) solid var(--hq-color-accent);
      font-size: var(--hq-font-label-size);
    }

    .header__viewas-actor {
      color: var(--hq-color-ink-soft);
    }

    .header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--hq-space-16);
      min-block-size: var(--hq-size-row-height);
      padding-inline: var(--hq-size-page-padding);
      border-block-end: var(--hq-size-rule) solid var(--hq-color-line);
    }

    .header__brand {
      display: flex;
      align-items: center;
      gap: var(--hq-space-12);
      min-inline-size: 0;
    }

    .header__logo {
      inline-size: var(--hq-size-logo-size);
      block-size: var(--hq-size-logo-size);
      object-fit: contain;
    }

    .header__name {
      font-weight: var(--hq-font-label-weight);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .header__actions {
      display: flex;
      align-items: center;
      gap: var(--hq-space-8);
    }

    .header__button {
      min-block-size: var(--hq-size-touch-target);
      padding-inline: var(--hq-space-12);
      background: none;
      border: var(--hq-size-rule) solid transparent;
      cursor: pointer;
      @include m.hover-tint;
      @include m.focus-ring;

      &[aria-expanded='true'] {
        border-color: var(--hq-color-line);
      }
    }

    // Rendered into the CDK overlay container, but instantiated by this component — so the
    // emulated-encapsulation attribute travels with it and these rules still apply.
    .menu {
      min-inline-size: var(--hq-size-course-card);
      max-block-size: 60vh;
      overflow: auto;
      background: var(--hq-color-surface);
      border: var(--hq-size-rule) solid var(--hq-color-line);
      box-shadow: var(--hq-shadow-dialog);
    }

    .menu__item {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--hq-space-16);
      inline-size: 100%;
      min-block-size: var(--hq-size-touch-target);
      padding-inline: var(--hq-space-16);
      background: none;
      border: 0;
      color: var(--hq-color-ink);
      text-align: start;
      text-decoration: none;
      cursor: pointer;
      @include m.hover-tint;
      @include m.focus-ring;

      &[aria-current='true'] {
        font-weight: var(--hq-font-label-weight);
      }
    }

    .menu__item--danger {
      color: var(--hq-color-accent-strong);
    }

    .menu__hint {
      font-size: var(--hq-font-label-size);
      color: var(--hq-color-ink-soft);
    }
  `,
})
export class ShellHeaderComponent {
  private readonly schoolsApi = inject(SchoolsApi);
  protected readonly scope = inject(SchoolScopeStore);
  private readonly theme = inject(ThemeService);
  private readonly tour = inject(TourService);

  protected readonly auth = inject(AuthService);
  protected readonly language = inject(LanguageService);
  protected readonly languages = LANGUAGES;

  protected readonly isAdmin = computed(() => this.auth.role() === 'ADMIN');
  protected readonly logoUrl = computed(() => this.theme.logoUrl());
  protected readonly name = computed(() => this.scope.scope()?.name ?? this.theme.appName());

  /**
   * Only an Admin may list schools, and only an Admin has a switcher — so the request is
   * made once, when the menu is first about to be useful, and not at all for anyone else.
   */
  protected readonly schools = rxResource<readonly SchoolSummary[], boolean | undefined>({
    params: () => (this.isAdmin() ? true : undefined),
    stream: () => this.schoolsApi.listSchools().pipe(catchError(() => of<SchoolSummary[]>([]))),
    defaultValue: [],
  });

  /** Emitted rather than handled here: signing out is the shell's business, not the header's. */
  readonly signOut = output<void>();

  protected choose(school: SchoolSummary | null): void {
    if (school?.id && school.name) this.scope.select({ id: school.id, name: school.name });
    else this.scope.select(null);
  }

  protected showMeAround(): void {
    const role = this.auth.role();
    if (role) this.tour.start(role);
  }
}
