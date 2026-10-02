/* hq-flag: none (shell) — sign-in is how every flag-gated screen is reached; gating it
   would lock a school out of its own dashboard. */
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
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
  imports: [AuthLayoutComponent, InputComponent, ButtonComponent, BandComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-auth-layout [title]="'auth.signIn.title' | transloco">
      @if (schoolLogo(); as logo) {
        <img auth-logo class="sign-in__logo" [src]="logo" alt="" (error)="markLogoFailed(logo)" />
      } @else {
        <!-- D3: neither a school's logo nor the platform's — the product's own mark. Projected
             here, not left to the layout's fallback: an empty block still fills the slot. -->
        <img auth-logo class="sign-in__logo sign-in__logo--mark" src="assets/brand/myschool-mark.svg" alt="" />
      }

      <form class="sign-in" (submit)="submit($event)">
        @if (ended(); as notice) {
          <hq-band
            variant="notice"
            [open]="true"
            [title]="'auth.signIn.endedTitle' | transloco"
            (dismissed)="ended.set(null)"
          >
            {{ notice }}
          </hq-band>
        }

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
      </form>
    </hq-auth-layout>
  `,
  styles: `
    .sign-in {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-16);
    }

    .sign-in__logo {
      inline-size: var(--hq-size-logo-size);
      block-size: var(--hq-size-logo-size);
      object-fit: contain;
      animation: hq-fade-in var(--hq-motion-base) var(--hq-motion-ease) both;
    }

    .sign-in__logo--mark {
      inline-size: var(--hq-size-touch-target);
      block-size: var(--hq-size-touch-target);
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

  /**
   * Why she is looking at this screen again, when another tab is the answer (T2 item e).
   *
   * A sentence rather than nothing: a teacher whose tab went back to sign-in the moment her
   * colleague signed in as the manager on the same laptop had no way to tell that from a bug,
   * and "it logs me out after a few minutes" is what that looked like from the outside.
   */
  protected readonly ended = signal<string | null>(null);

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

  /** Addresses that would not load. The school's falls back to the platform's, and that one to no image. */
  private readonly failedLogos = signal<readonly string[]>([]);
  protected readonly schoolLogo = computed(() => {
    const failed = this.failedLogos();
    return (
      [this.lookup.value()?.logoUrl, this.platform.platformLogoUrl()].find(
        (logo) => !!logo && !failed.includes(logo),
      ) ?? null
    );
  });

  protected markLogoFailed(logo: string): void {
    this.failedLogos.update((failed) => [...failed, logo]);
  }

  constructor() {
    const params = this.route.snapshot.queryParamMap;
    const reason = params.get('ended');
    const role = params.get('as');
    if (reason === 'takenOver' && role !== null) {
      this.ended.set(
        this.transloco.translate<string>('auth.signIn.takenOver', {
          role: this.transloco.translate<string>(`role.${role}`),
        }),
      );
    } else if (reason === 'takenOver' || reason === 'signedOutElsewhere') {
      this.ended.set(this.transloco.translate<string>('auth.signIn.signedOutElsewhere'));
    }
  }

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
   *
   * D1: and only when the person signing in is the one the address was kept *for*
   * (`AuthService.continuesLastSession`). A `returnTo` left by a teacher's dead session names her
   * class; an Admin or another teacher who signs in over it would open a row that is not theirs —
   * the "class not found" the owner met on first open — so anybody else simply goes Home.
   */
  private destination(): string {
    if (this.auth.mustChangePassword()) return '/change-password';
    const returnTo = this.route.snapshot.queryParamMap.get('returnTo');
    const mayFollow = this.auth.continuesLastSession();
    return mayFollow && returnTo && returnTo.startsWith('/') && !returnTo.startsWith('//')
      ? returnTo
      : this.auth.home();
  }
}
