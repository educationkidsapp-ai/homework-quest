import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { PlatformService } from '../../core/platform/platform.service';

/**
 * The frame the four signed-out screens share: a logo, a heading, one card, one footer line.
 *
 * The heading and the footer both come from `PlatformSettings.name` — §A's "the sign-in page
 * heading and the footer all come from one place". Until that request answers they are empty
 * rather than a default, because a default is a product name in the code and the whole point
 * is that there is not one.
 *
 * The panel is a `<main>` landmark, not a div: these five screens have no nav and no header,
 * so without it the page has no landmark at all and "skip to content" has nowhere to go
 * (Lighthouse's `landmark-one-main`).
 *
 * The logo slot is projected so the sign-in page can swap in the school's logo once the email
 * identifies one, while the other three screens keep the platform's.
 */
@Component({
  selector: 'hq-auth-layout',
  imports: [TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="auth">
      <div class="auth__bg" aria-hidden="true"></div>
      <div class="auth__overlay" aria-hidden="true"></div>

      <main class="auth__panel">
        <div class="auth__logo">
          <ng-content select="[auth-logo]">
            @if (platform.platformLogoUrl(); as logo) {
              <img class="auth__logo-image" [src]="logo" alt="" />
            }
          </ng-content>
        </div>

        <!-- The platform's name is a brand line, not the page's heading: "Sign in" is what
             this page is, and a screen reader should hear that as the one top-level heading. -->
        <p class="auth__name">{{ platform.platformName() }}</p>
        <h1 class="auth__title">{{ title() }}</h1>

        <div class="auth__body"><ng-content /></div>
      </main>

      <footer class="auth__footer">
        <p>{{ footer() }}</p>
        @if (platform.supportEmail(); as email) {
          <a [href]="'mailto:' + email">{{ 'auth.support' | transloco }}</a>
        }
      </footer>
    </div>
  `,
  styles: `
    @use 'mixins' as m;

    :host {
      display: block;
      min-block-size: 100vh;
      position: relative;
      overflow-x: hidden;
    }

    .auth {
      position: relative;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: var(--hq-space-24);
      min-block-size: 100vh;
      padding: var(--hq-size-page-padding);
      z-index: 1;
    }

    .auth__bg {
      position: fixed;
      inset: -20px;
      z-index: 0;
      background-image: url('/dashboard/school-bg.jpg');
      background-image: url('/dashboard/school-bg.webp');
      background-position: center;
      background-size: cover;
      background-repeat: no-repeat;
      opacity: 0.22;
      filter: saturate(1.15);
      pointer-events: none;
      animation: auth-bg-pan 32s ease-in-out infinite alternate;
    }

    .auth__overlay {
      position: fixed;
      inset: 0;
      z-index: 0;
      background: radial-gradient(circle at 50% 45%, rgba(255, 255, 255, 0.45) 0%, rgba(255, 255, 255, 0.85) 100%);
      pointer-events: none;
    }

    :host-context(html.dark) .auth__bg {
      opacity: 0.12;
      filter: saturate(0.85) brightness(0.65);
    }

    :host-context(html.dark) .auth__overlay {
      background: radial-gradient(circle at 50% 45%, rgba(16, 24, 40, 0.45) 0%, rgba(16, 24, 40, 0.88) 100%);
    }

    .auth__panel {
      position: relative;
      z-index: 1;
      inline-size: 100%;
      max-inline-size: min(460px, 92vw);
      padding: var(--hq-space-32);
      background: rgba(255, 255, 255, 0.86);
      backdrop-filter: blur(20px) saturate(180%);
      -webkit-backdrop-filter: blur(20px) saturate(180%);
      border: 1px solid rgba(255, 255, 255, 0.7);
      border-radius: var(--hq-radius-card);
      box-shadow: 0 20px 48px -12px rgba(16, 24, 40, 0.12),
                  0 0 0 1px rgba(255, 255, 255, 0.7) inset;
      animation: auth-panel-enter 550ms cubic-bezier(0.16, 1, 0.3, 1) both;
      transition: transform 300ms cubic-bezier(0.16, 1, 0.3, 1), box-shadow 300ms ease;

      &:hover {
        box-shadow: 0 24px 56px -12px rgba(16, 24, 40, 0.16),
                    0 0 0 1px rgba(255, 255, 255, 0.9) inset;
      }
    }

    :host-context(html.dark) .auth__panel {
      background: rgba(23, 31, 46, 0.84);
      border-color: rgba(255, 255, 255, 0.12);
      box-shadow: 0 20px 48px -12px rgba(0, 0, 0, 0.5),
                  0 0 0 1px rgba(255, 255, 255, 0.08) inset;

      &:hover {
        box-shadow: 0 24px 56px -12px rgba(0, 0, 0, 0.65),
                    0 0 0 1px rgba(255, 255, 255, 0.14) inset;
      }
    }

    .auth__logo {
      min-block-size: var(--hq-size-logo-size);
      margin-block-end: var(--hq-space-16);
      animation: auth-fade-down 450ms cubic-bezier(0.16, 1, 0.3, 1) both 80ms;
    }

    .auth__logo-image {
      inline-size: var(--hq-size-logo-size);
      block-size: var(--hq-size-logo-size);
      object-fit: contain;
    }

    .auth__name {
      min-block-size: var(--hq-text-theme-sm-line);
      font-size: var(--hq-text-theme-sm);
      line-height: calc(var(--hq-text-theme-sm-line) / var(--hq-text-theme-sm));
      font-weight: var(--hq-text-weight-medium);
      color: var(--hq-color-ink-soft);
      animation: auth-fade-up 450ms cubic-bezier(0.16, 1, 0.3, 1) both 140ms;
    }

    .auth__title {
      @include m.title;
      margin-block: var(--hq-space-4) var(--hq-space-24);
      animation: auth-fade-up 450ms cubic-bezier(0.16, 1, 0.3, 1) both 180ms;
    }

    .auth__body {
      animation: auth-fade-up 450ms cubic-bezier(0.16, 1, 0.3, 1) both 220ms;
    }

    .auth__footer {
      position: relative;
      z-index: 1;
      display: flex;
      gap: var(--hq-space-16);
      font-size: var(--hq-text-theme-sm);
      color: var(--hq-color-ink-soft);
      animation: auth-fade-in 600ms ease both 320ms;

      a {
        transition: color var(--hq-motion-fast) var(--hq-motion-ease);
        &:hover {
          color: var(--hq-color-ink);
        }
      }
    }

    @keyframes auth-panel-enter {
      0% {
        opacity: 0;
        transform: translateY(24px) scale(0.98);
      }
      100% {
        opacity: 1;
        transform: translateY(0) scale(1);
      }
    }

    @keyframes auth-fade-up {
      0% {
        opacity: 0;
        transform: translateY(10px);
      }
      100% {
        opacity: 1;
        transform: translateY(0);
      }
    }

    @keyframes auth-fade-down {
      0% {
        opacity: 0;
        transform: translateY(-10px);
      }
      100% {
        opacity: 1;
        transform: translateY(0);
      }
    }

    @keyframes auth-fade-in {
      0% {
        opacity: 0;
      }
      100% {
        opacity: 1;
      }
    }

    @keyframes auth-bg-pan {
      0% {
        transform: scale(1.02) translate(0, 0);
      }
      50% {
        transform: scale(1.05) translate(-0.8%, -0.6%);
      }
      100% {
        transform: scale(1.03) translate(0.8%, 0.4%);
      }
    }

    @media (prefers-reduced-motion: reduce) {
      .auth__bg,
      .auth__panel,
      .auth__logo,
      .auth__name,
      .auth__title,
      .auth__body,
      .auth__footer {
        animation: none !important;
        transition: none !important;
      }
    }

    @media (max-width: 480px) {
      .auth__panel {
        padding: var(--hq-space-24);
      }
    }
  `,
})
export class AuthLayoutComponent {
  protected readonly platform = inject(PlatformService);

  /** The screen's own heading: "Sign in", "Choose a password", … */
  readonly title = input.required<string>();

  protected readonly footer = computed(() => this.platform.platformName());
}
