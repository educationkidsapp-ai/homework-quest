/* hq-flag: none (shell) — finishing a password reset from an emailed link is part of signing
   in, not a feature a school can be without. */
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { AuthApi, apiErrorOf } from '../../api';
import { BandComponent, ButtonComponent, InputComponent } from '../../ui';
import { AuthLayoutComponent } from './auth-layout.component';

/** The server's own floor (`@Size(min = 10)` on every password field). */
export const MIN_PASSWORD = 10;

/**
 * `/reset-password?token=…` — the far end of the email.
 *
 * The token arrives as a route input (`withComponentInputBinding`), is valid once and for an
 * hour, and is never shown: it is in the address bar already, and repeating it on the page
 * only makes it easier to paste somewhere it should not go.
 *
 * Not behind `anonymousGuard` on purpose. A link from an email opens in whatever browser the
 * person reads mail in, which may well be one where a colleague is still signed in; bouncing
 * them to a Home they did not ask for would make the link look broken.
 */
@Component({
  selector: 'hq-reset-password-page',
  imports: [AuthLayoutComponent, InputComponent, ButtonComponent, BandComponent, RouterLink, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-auth-layout [title]="'auth.reset.title' | transloco">
      <form class="reset" (submit)="submit($event)">
        <hq-band
          [open]="failure() !== null"
          [title]="'auth.reset.failedTitle' | transloco"
          (dismissed)="failure.set(null)"
        >
          {{ failure() }}
        </hq-band>

        <hq-input
          [label]="'auth.newPassword' | transloco"
          [(value)]="password"
          type="password"
          name="new-password"
          autocomplete="new-password"
          [required]="true"
          [hint]="'auth.passwordHint' | transloco: { min: minLength }"
          [error]="tooShort() ? ('auth.passwordTooShort' | transloco: { min: minLength }) : null"
          [enterSubmit]="true"
          (enterSubmitted)="reset()"
        />

        <hq-button
          type="submit"
          variant="primary"
          [block]="true"
          [loading]="busy()"
          [disabled]="!valid()"
          (pressed)="reset()"
        >
          {{ 'auth.reset.action' | transloco }}
        </hq-button>

        <a routerLink="/sign-in">{{ 'auth.backToSignIn' | transloco }}</a>
      </form>
    </hq-auth-layout>
  `,
  styles: `
    .reset {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-16);
      align-items: flex-start;
    }

    hq-input,
    hq-button,
    hq-band {
      inline-size: 100%;
    }
  `,
})
export class ResetPasswordPage {
  private readonly api = inject(AuthApi);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);

  /** Bound from `?token=` by the router's component input binding. */
  readonly token = input('');

  protected readonly minLength = MIN_PASSWORD;
  protected readonly password = signal('');
  protected readonly busy = signal(false);
  protected readonly failure = signal<string | null>(null);

  /** Only after something has been typed: an empty field is not yet a mistake. */
  protected readonly tooShort = computed(
    () => this.password().length > 0 && this.password().length < MIN_PASSWORD,
  );
  protected readonly valid = computed(() => this.password().length >= MIN_PASSWORD && this.token() !== '');

  protected submit(event: Event): void {
    event.preventDefault();
    this.reset();
  }

  protected reset(): void {
    if (this.busy() || !this.valid()) return;
    this.busy.set(true);
    this.failure.set(null);
    this.api.resetPassword({ token: this.token(), newPassword: this.password() }).subscribe({
      next: () => {
        this.busy.set(false);
        void this.router.navigate(['/sign-in'], { queryParams: { reset: 1 } });
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.failure.set(apiErrorOf(error)?.message ?? this.transloco.translate('band.unreachable'));
      },
    });
  }
}
