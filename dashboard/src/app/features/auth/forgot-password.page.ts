/* hq-flag: none (shell) — password recovery is part of signing in, not a feature a school
   can be without. */
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { AuthApi } from '../../api';
import { BandComponent, ButtonComponent, InputComponent } from '../../ui';
import { AuthLayoutComponent } from './auth-layout.component';

/**
 * "Send me a link" (§6, Shared screen 1).
 *
 * The server answers 204 whether or not the address has an account, so that this page cannot
 * be used to find out who has one (runbook, "Forgot / reset password"). This screen has to
 * keep that promise: it says the same sentence either way, and it says it for *every* address
 * — including the misspelled one, which is the price of not being an enumeration oracle.
 */
@Component({
  selector: 'hq-forgot-password-page',
  imports: [AuthLayoutComponent, InputComponent, ButtonComponent, BandComponent, RouterLink, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-auth-layout [title]="'auth.forgot.title' | transloco">
      @if (sent()) {
        <div class="forgot">
          <hq-band [open]="true" variant="notice" [dismissible]="false">
            {{ 'auth.forgot.sent' | transloco }}
          </hq-band>
          <a routerLink="/sign-in">{{ 'auth.backToSignIn' | transloco }}</a>
        </div>
      } @else {
        <form class="forgot" (submit)="submit($event)">
          <p class="forgot__lede">{{ 'auth.forgot.lede' | transloco }}</p>
          <hq-input
            [label]="'auth.email' | transloco"
            [(value)]="email"
            type="email"
            name="email"
            autocomplete="username"
            [required]="true"
            [enterSubmit]="true"
            (enterSubmitted)="send()"
          />
          <hq-button type="submit" variant="primary" [block]="true" [loading]="busy()" (pressed)="send()">
            {{ 'auth.forgot.action' | transloco }}
          </hq-button>
          <a routerLink="/sign-in">{{ 'auth.backToSignIn' | transloco }}</a>
        </form>
      }
    </hq-auth-layout>
  `,
  styles: `
    .forgot {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-16);
      align-items: flex-start;
    }

    .forgot__lede {
      color: var(--hq-color-ink-soft);
    }

    hq-input,
    hq-button,
    hq-band {
      inline-size: 100%;
    }
  `,
})
export class ForgotPasswordPage {
  private readonly api = inject(AuthApi);

  protected readonly email = signal('');
  protected readonly busy = signal(false);
  protected readonly sent = signal(false);

  protected submit(event: Event): void {
    event.preventDefault();
    this.send();
  }

  protected send(): void {
    if (this.busy() || this.email().trim() === '') return;
    this.busy.set(true);
    // Both arms say "sent": a failure that told the truth here would tell it about the
    // address too. A real outage is visible in the server's logs, not on this page.
    this.api.forgotPassword({ email: this.email().trim() }).subscribe({
      next: () => this.done(),
      error: () => this.done(),
    });
  }

  private done(): void {
    this.busy.set(false);
    this.sent.set(true);
  }
}
