/* hq-flag: none (shell) — every account has a profile; there is no school that does not. */
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { AuthService } from '../../core/auth/auth.service';
import { activeLang } from '../../core/i18n/active-lang';
import { LANGUAGES, LanguageService, type Language } from '../../core/i18n/language.service';
import { TourService } from '../../core/tour/tour.service';
import {
  ButtonComponent,
  CardComponent,
  InputComponent,
  PageComponent,
  SelectComponent,
  type SelectOption,
} from '../../ui';

/**
 * Profile (§6, Shared screen 3): name, photo, language, password.
 *
 * **Name and photo are read-only here.** The contract has `PATCH /admin/users/{id}` — an
 * Admin editing someone — and no self-edit: a teacher cannot change her own display name or
 * photo through the API as it stands. Showing the fields disabled says what the account holds
 * and who can change it, which is truer than a form whose Save would 403. The gap is reported
 * to the backend worker rather than papered over with a hand-written call.
 *
 * **The language toggle is instant and local.** `DashboardUser.language` exists on the read
 * side but there is nothing to write it with, so the choice lives in this browser
 * (`LanguageService`). Switching flips `dir` on `<html>` and re-renders in place — §6's "EN/AR
 * toggle flips the whole dashboard live", no reload.
 */
@Component({
  selector: 'hq-profile-page',
  imports: [
    PageComponent,
    CardComponent,
    InputComponent,
    SelectComponent,
    ButtonComponent,
    RouterLink,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'profile.title' | transloco" [subtitle]="auth.user()?.email ?? null">
      <div class="profile">
        <hq-card [title]="'profile.details' | transloco">
          <div class="profile__fields">
            <hq-input [label]="'auth.displayName' | transloco" [value]="displayName()" [disabled]="true" />
            <hq-input
              [label]="'profile.photoUrl' | transloco"
              [value]="photoUrl()"
              [disabled]="true"
              [hint]="'profile.managedByAdmin' | transloco"
            />
            <hq-input [label]="'profile.role' | transloco" [value]="roleLabel()" [disabled]="true" />
          </div>
        </hq-card>

        <hq-card [title]="'profile.language' | transloco">
          <hq-select
            [label]="'profile.language' | transloco"
            [options]="languageOptions()"
            [value]="language.language()"
            (valueChange)="setLanguage($event)"
            [hint]="'profile.languageHint' | transloco"
          />
        </hq-card>

        <hq-card [title]="'profile.security' | transloco">
          <div class="profile__fields">
            <p class="profile__hint">{{ 'profile.passwordHint' | transloco }}</p>
            <a routerLink="/change-password">{{ 'auth.change.title' | transloco }}</a>
          </div>
        </hq-card>

        <hq-card [title]="'profile.help' | transloco">
          <hq-button (pressed)="showMeAround()">{{ 'shell.showMeAround' | transloco }}</hq-button>
        </hq-card>
      </div>
    </hq-page>
  `,
  styles: `
    .profile {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-16);
    }

    .profile__fields {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-16);
    }

    .profile__hint {
      color: var(--hq-color-ink-soft);
    }
  `,
})
export class ProfilePage {
  private readonly tour = inject(TourService);
  private readonly transloco = inject(TranslocoService);
  private readonly lang = activeLang();

  protected readonly auth = inject(AuthService);
  protected readonly language = inject(LanguageService);

  protected readonly displayName = computed(() => this.auth.user()?.displayName ?? '');
  protected readonly photoUrl = computed(() => this.auth.user()?.photoUrl ?? '');
  protected readonly roleLabel = computed(() => {
    this.lang();
    const role = this.auth.role();
    return role === null ? '' : this.transloco.translate(`role.${role}`);
  });

  protected readonly languageOptions = computed<readonly SelectOption<Language>[]>(() => {
    this.lang();
    return LANGUAGES.map((value) => ({ value, label: this.transloco.translate(`shell.language.${value}`) }));
  });

  protected setLanguage(value: Language | ''): void {
    if (value !== '') this.language.use(value);
  }

  protected showMeAround(): void {
    const role = this.auth.role();
    if (role) this.tour.start(role);
  }
}
