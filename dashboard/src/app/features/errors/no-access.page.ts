/* hq-flag: none (shell) — where a 403 lands, whatever feature the refused screen belonged to. */
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { AuthService } from '../../core/auth/auth.service';
import { PlatformService } from '../../core/platform/platform.service';
import { EmptyStateComponent, PageComponent } from '../../ui';

/**
 * "Your account may not open that."
 *
 * Distinct from `/not-found` on purpose: this one is about the person, so it names the
 * account it is about and offers the support address, because the fix is someone else
 * changing a role or a school — not a different URL.
 *
 * It is also where an account with no school lands. The server refuses *every* tenant route
 * for a staff account whose `school_id` is null (runbook: "a staff account whose school was
 * deleted, or that was never attached to one, cannot use the dashboard at all"), and a wall
 * of red bands would not say why.
 */
@Component({
  selector: 'hq-no-access-page',
  imports: [PageComponent, EmptyStateComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'errors.noAccess.title' | transloco" [subtitle]="auth.user()?.email ?? null">
      <hq-empty-state
        [message]="'errors.noAccess.body' | transloco"
        [actionLabel]="'errors.goHome' | transloco"
        (action)="goHome()"
      />
      @if (platform.supportEmail(); as email) {
        <p class="no-access__support">
          <a [href]="'mailto:' + email">{{ email }}</a>
        </p>
      }
    </hq-page>
  `,
  styles: `
    .no-access__support {
      text-align: center;
    }
  `,
})
export class NoAccessPage {
  private readonly router = inject(Router);

  protected readonly auth = inject(AuthService);
  protected readonly platform = inject(PlatformService);

  protected goHome(): void {
    void this.router.navigateByUrl(this.auth.home());
  }
}
