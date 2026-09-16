/* hq-flag: none (shell) — accepting an invitation is how a staff account comes into being;
   it cannot depend on a flag that is read per school for an account that has no school yet. */
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { DashboardUsersApi, InviteInfo, apiErrorOf } from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { BandComponent, ButtonComponent, InputComponent, SkeletonComponent } from '../../ui';
import { AuthLayoutComponent } from './auth-layout.component';
import { MIN_PASSWORD } from './reset-password.page';

/**
 * `/accept-invite?token=…` — the other far end of an email.
 *
 * `GET /invites/{token}` is public and answers with the address, the role and the school, so
 * the page can say "You have been invited to Al Noor School as a teacher" before asking for a
 * password. That is the whole reason the endpoint exists: a bare password field reached from
 * an email is indistinguishable from a phishing page.
 *
 * Accepting signs the account in, so this lands on a Home rather than back at sign-in.
 */
@Component({
  selector: 'hq-accept-invite-page',
  imports: [
    AuthLayoutComponent,
    InputComponent,
    ButtonComponent,
    BandComponent,
    SkeletonComponent,
    RouterLink,
    TranslocoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-auth-layout [title]="'auth.invite.title' | transloco">
      @if (invite.isLoading()) {
        <hq-skeleton [loading]="true" [lines]="4" [label]="'auth.invite.loading' | transloco" />
      } @else if (invite.value(); as info) {
        <form class="invite" (submit)="submit($event)">
          <p class="invite__lede">
            {{ 'auth.invite.lede' | transloco: { school: info.schoolName ?? '', role: roleLabel(info) } }}
          </p>
          <p class="invite__email">{{ info.email }}</p>

          <hq-band
            [open]="failure() !== null"
            [title]="'auth.invite.failedTitle' | transloco"
            (dismissed)="failure.set(null)"
          >
            {{ failure() }}
          </hq-band>

          <hq-input
            [label]="'auth.displayName' | transloco"
            [(value)]="displayName"
            name="name"
            autocomplete="name"
          />

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
            (enterSubmitted)="accept()"
          />

          <hq-button
            type="submit"
            variant="primary"
            [block]="true"
            [loading]="busy()"
            [disabled]="!valid()"
            (pressed)="accept()"
          >
            {{ 'auth.invite.action' | transloco }}
          </hq-button>
        </form>
      } @else {
        <div class="invite">
          <hq-band [open]="true" [title]="'auth.invite.deadTitle' | transloco" [dismissible]="false">
            {{ 'auth.invite.dead' | transloco }}
          </hq-band>
          <a routerLink="/sign-in">{{ 'auth.backToSignIn' | transloco }}</a>
        </div>
      }
    </hq-auth-layout>
  `,
  styles: `
    .invite {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-16);
      align-items: flex-start;
    }

    .invite__lede {
      color: var(--hq-color-ink-soft);
    }

    .invite__email {
      font-weight: var(--hq-font-label-weight);
    }

    hq-input,
    hq-button,
    hq-band {
      inline-size: 100%;
    }
  `,
})
export class AcceptInvitePage {
  private readonly api = inject(DashboardUsersApi);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);

  readonly token = input('');

  protected readonly minLength = MIN_PASSWORD;
  protected readonly displayName = signal('');
  protected readonly password = signal('');
  protected readonly busy = signal(false);
  protected readonly failure = signal<string | null>(null);

  /**
   * An expired, spent or invented token is not an error to report — it is the page's other
   * state — so the resource resolves to `undefined` and the template says so in one sentence.
   */
  protected readonly invite = rxResource<InviteInfo, string | undefined>({
    params: () => this.token() || undefined,
    stream: ({ params: token }) => this.api.inviteInfo(token),
  });

  protected readonly tooShort = computed(
    () => this.password().length > 0 && this.password().length < MIN_PASSWORD,
  );
  protected readonly valid = computed(() => this.password().length >= MIN_PASSWORD);

  protected roleLabel(info: InviteInfo): string {
    return this.transloco.translate(`role.${info.role ?? 'TEACHER'}`);
  }

  protected submit(event: Event): void {
    event.preventDefault();
    this.accept();
  }

  protected accept(): void {
    if (this.busy() || !this.valid()) return;
    this.busy.set(true);
    this.failure.set(null);
    this.api
      .acceptInvite(this.token(), {
        password: this.password(),
        displayName: this.displayName().trim() || undefined,
      })
      .subscribe({
        next: (session) => {
          this.auth.adopt(session).subscribe({
            next: () => {
              this.busy.set(false);
              void this.router.navigateByUrl(this.auth.home());
            },
            error: () => {
              // The account exists and the password is set; only the follow-up read failed,
              // so send them to sign in rather than leave them on a form that is now spent.
              this.busy.set(false);
              void this.router.navigate(['/sign-in']);
            },
          });
        },
        error: (error: unknown) => {
          this.busy.set(false);
          this.failure.set(apiErrorOf(error)?.message ?? this.transloco.translate('band.unreachable'));
        },
      });
  }
}
