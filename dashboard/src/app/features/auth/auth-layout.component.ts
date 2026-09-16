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
 * The logo slot is projected so the sign-in page can swap in the school's logo once the email
 * identifies one, while the other three screens keep the platform's.
 */
@Component({
  selector: 'hq-auth-layout',
  imports: [TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="auth">
      <div class="auth__panel">
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
      </div>

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
    }

    .auth {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: var(--hq-space-24);
      min-block-size: 100vh;
      padding: var(--hq-size-page-padding);
    }

    .auth__panel {
      inline-size: 100%;
      max-inline-size: var(--hq-size-stop-list-width);
      padding: var(--hq-space-32);
      @include m.surface;
    }

    .auth__logo {
      min-block-size: var(--hq-size-logo-size);
      margin-block-end: var(--hq-space-16);
    }

    .auth__logo-image {
      inline-size: var(--hq-size-logo-size);
      block-size: var(--hq-size-logo-size);
      object-fit: contain;
    }

    // The name arrives from the API a beat after first paint; reserving its line keeps the
    // heading below from jumping when it lands.
    .auth__name {
      @include m.label;
      min-block-size: var(--hq-font-label-line);
      color: var(--hq-color-ink-soft);
    }

    .auth__title {
      @include m.title;
      margin-block: var(--hq-space-4) var(--hq-space-24);
    }

    .auth__footer {
      display: flex;
      gap: var(--hq-space-16);
      font-size: var(--hq-font-label-size);
      color: var(--hq-color-ink-soft);
    }
  `,
})
export class AuthLayoutComponent {
  protected readonly platform = inject(PlatformService);

  /** The screen's own heading: "Sign in", "Choose a password", … */
  readonly title = input.required<string>();

  protected readonly footer = computed(() => this.platform.platformName());
}
