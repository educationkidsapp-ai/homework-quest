/* hq-flag: none (shell) — sign-in is how every flag-gated screen is reached; gating it
   would lock a school out of its own dashboard. */
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { of } from 'rxjs';
import { catchError, debounceTime, map } from 'rxjs/operators';
import { SchoolLogo, SchoolsApi, apiErrorOf } from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { PlatformService } from '../../core/platform/platform.service';
import { BandComponent, ButtonComponent, InputComponent } from '../../ui';
import { AuthLayoutComponent } from './auth-layout.component';

/** Long enough that a typed address settles, short enough that the logo feels like a reaction. */
const LOGO_DEBOUNCE_MS = 400;

/**
 * Sign in (§6, Shared screen 1).
 *
 * **The logo appears when the email does.** `GET /schools/logo?email=` looks a school up by
 * the address's domain and answers 204 when it recognises none. That is the first thing this
 * product tells a teacher: you are in the right place, this is your school. It fades rather
 * than appearing, because a hard swap next to a field someone is typing in reads as an error.
 *
 * **The failure is a band, not a toast and not a field error.** "Email or password is wrong"
 * is deliberately about neither field — saying which one was wrong is an account-enumeration
 * oracle, and the server is careful not to leak it either.
 *
 * The error interceptor leaves `/auth/sign-in` alone (`SCREEN_OWNED`), so there is no second
 * band in the shell for the same failure and no "your session expired" for a session that
 * never started.
 */
@Component({
  selector: 'hq-sign-in-page',
  imports: [AuthLayoutComponent, InputComponent, ButtonComponent, BandComponent, RouterLink, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-auth-layout [title]="'auth.signIn.title' | transloco">
      @if (schoolLogo(); as logo) {
        <img auth-logo class="sign-in__logo" [src]="logo" alt="" />
      }

      <form class="sign-in" (submit)="submit($event)">
        <hq-band
          [open]="failed()"
          [title]="'auth.signIn.failedTitle' | transloco"
          (dismissed)="failure.set(null)"
        >
          {{ failure() }}
        </hq-band>

        <hq-input
          [label]="'auth.email' | transloco"
          [(value)]="email"
          type="email"
          name="email"
          autocomplete="username"
          [required]="true"
          [enterSubmit]="true"
          (enterSubmitted)="signIn()"
        />

        <hq-input
          [label]="'auth.password' | transloco"
          [(value)]="password"
          type="password"
          name="password"
          autocomplete="current-password"
          [required]="true"
          [enterSubmit]="true"
          (enterSubmitted)="signIn()"
        />

        <hq-button type="submit" variant="primary" [block]="true" [loading]="busy()" (pressed)="signIn()">
          {{ 'auth.signIn.action' | transloco }}
        </hq-button>

        <a class="sign-in__forgot" routerLink="/forgot-password">{{ 'auth.forgot.link' | transloco }}</a>
      </form>
    </hq-auth-layout>
  `,
  styles: `
    .sign-in {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-16);
    }

    .sign-in__forgot {
      align-self: flex-start;
      font-size: var(--hq-font-label-size);
    }

    .sign-in__logo {
      inline-size: var(--hq-size-logo-size);
      block-size: var(--hq-size-logo-size);
      object-fit: contain;
      animation: hq-fade-in var(--hq-motion-base) var(--hq-motion-ease) both;
    }
  `,
})
export class SignInPage {
  private readonly schoolsApi = inject(SchoolsApi);
  private readonly auth = inject(AuthService);
  private readonly platform = inject(PlatformService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly transloco = inject(TranslocoService);

  protected readonly email = signal('');
  protected readonly password = signal('');
  protected readonly busy = signal(false);
  protected readonly failure = signal<string | null>(null);
  protected readonly failed = computed(() => this.failure() !== null);

  /** The typed address, once it has stopped changing and looks like an address at all. */
  private readonly settledEmail = toSignal(
    toObservable(this.email).pipe(
      debounceTime(LOGO_DEBOUNCE_MS),
      map((value) => (value.includes('@') && value.split('@')[1]?.includes('.') ? value : null)),
    ),
    { initialValue: null },
  );

  private readonly lookup = rxResource<SchoolLogo | null, string | undefined>({
    params: () => this.settledEmail() ?? undefined,
    stream: ({ params: email }) =>
      this.schoolsApi.schoolLogo({ email }).pipe(
        // 204 (no school for that domain) arrives as a null body; so does a failed lookup,
        // and both mean the same thing here: show the platform's logo.
        map((logo): SchoolLogo | null => logo ?? null),
        catchError(() => of(null)),
      ),
    defaultValue: null,
  });

  protected readonly schoolLogo = computed(
    () => this.lookup.value()?.logoUrl || this.platform.platformLogoUrl(),
  );

  protected submit(event: Event): void {
    event.preventDefault();
    this.signIn();
  }

  protected signIn(): void {
    if (this.busy() || this.email().trim() === '' || this.password() === '') return;
    this.busy.set(true);
    this.failure.set(null);
    this.auth.signIn(this.email().trim(), this.password()).subscribe({
      next: () => {
        this.busy.set(false);
        void this.router.navigateByUrl(this.destination());
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.password.set('');
        this.failure.set(apiErrorOf(error)?.message ?? this.transloco.translate('band.unreachable'));
      },
    });
  }

  /**
   * Back to whatever was refused, unless the account has to change its password first — the
   * guard would bounce it there anyway, and arriving on the change screen from sign-in is
   * one step rather than two.
   */
  private destination(): string {
    if (this.auth.mustChangePassword()) return '/change-password';
    const returnTo = this.route.snapshot.queryParamMap.get('returnTo');
    return returnTo && returnTo.startsWith('/') && !returnTo.startsWith('//') ? returnTo : this.auth.home();
  }
}
