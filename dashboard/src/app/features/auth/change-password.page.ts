/* hq-flag: none (shell) — the forced first-login change stands between an invited account
   and every other screen, flagged or not. */
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { AuthApi, apiErrorOf } from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { BandComponent, ButtonComponent, InputComponent } from '../../ui';
import { AuthLayoutComponent } from './auth-layout.component';
import { MIN_PASSWORD } from './reset-password.page';

/**
 * `/change-password` — the forced change on first sign-in (§5).
 *
 * The server sets `mustChangePassword` on invited and admin-reset accounts and then does
 * **not** enforce it: "Nothing on the server blocks other calls while the flag is set — it is
 * the client that must route to the change screen" (runbook). So this page sits outside the
 * shell, `passwordChangeGuard` sends every authenticated route here while the flag is set,
 * and there is deliberately no way past it but changing the password or signing out.
 */
@Component({
  selector: 'hq-change-password-page',
  imports: [AuthLayoutComponent, InputComponent, ButtonComponent, BandComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-auth-layout [title]="'auth.change.title' | transloco">
      <form class="change" (submit)="submit($event)">
        @if (auth.mustChangePassword()) {
          <p class="change__lede">{{ 'auth.change.forced' | transloco }}</p>
        }

        <hq-band
          [open]="failure() !== null"
          [title]="'auth.change.failedTitle' | transloco"
          (dismissed)="failure.set(null)"
        >
          {{ failure() }}
        </hq-band>

        <hq-input
          [label]="'auth.currentPassword' | transloco"
          [(value)]="current"
          type="password"
          name="current-password"
          autocomplete="current-password"
          [required]="true"
        />

        <hq-input
          [label]="'auth.newPassword' | transloco"
          [(value)]="next"
          type="password"
          name="new-password"
          autocomplete="new-password"
          [required]="true"
          [hint]="'auth.passwordHint' | transloco: { min: minLength }"
          [error]="tooShort() ? ('auth.passwordTooShort' | transloco: { min: minLength }) : null"
          [enterSubmit]="true"
          (enterSubmitted)="change()"
        />

        <hq-button
          type="submit"
          variant="primary"
          [block]="true"
          [loading]="busy()"
          [disabled]="!valid()"
          (pressed)="change()"
        >
          {{ 'auth.change.action' | transloco }}
        </hq-button>

        <hq-button variant="quiet" (pressed)="signOut()">{{ 'shell.signOut' | transloco }}</hq-button>
      </form>
    </hq-auth-layout>
  `,
  styles: `
    .change {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-16);
    }

    .change__lede {
      color: var(--hq-color-ink-soft);
    }
  `,
})
export class ChangePasswordPage {
  private readonly api = inject(AuthApi);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);

  protected readonly auth = inject(AuthService);
  protected readonly minLength = MIN_PASSWORD;
  protected readonly current = signal('');
  protected readonly next = signal('');
  protected readonly busy = signal(false);
  protected readonly failure = signal<string | null>(null);

  protected readonly tooShort = computed(() => this.next().length > 0 && this.next().length < MIN_PASSWORD);
  protected readonly valid = computed(() => this.current() !== '' && this.next().length >= MIN_PASSWORD);

  protected submit(event: Event): void {
    event.preventDefault();
    this.change();
  }

  protected change(): void {
    if (this.busy() || !this.valid()) return;
    this.busy.set(true);
    this.failure.set(null);
    this.api.changePassword({ currentPassword: this.current(), newPassword: this.next() }).subscribe({
      next: () => {
        // `mustChangePassword` is cleared server-side; re-read rather than assume, so the
        // guard sees the new answer and stops sending this person back here.
        this.auth.reload().subscribe({
          next: () => {
            this.busy.set(false);
            void this.router.navigateByUrl(this.auth.home());
          },
          error: () => {
            this.busy.set(false);
            void this.router.navigateByUrl(this.auth.home());
          },
        });
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.current.set('');
        this.failure.set(apiErrorOf(error)?.message ?? this.transloco.translate('band.unreachable'));
      },
    });
  }

  protected signOut(): void {
    this.auth.signOut().subscribe(() => void this.router.navigate(['/sign-in']));
  }
}
