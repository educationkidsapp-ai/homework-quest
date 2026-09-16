/* hq-flag: none (shell) — the answer to a URL that matches nothing, including one whose
   feature is switched off for this school. */
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { AuthService } from '../../core/auth/auth.service';
import { EmptyStateComponent, PageComponent } from '../../ui';

/**
 * Nothing here.
 *
 * Also where `featureGuard` sends a route whose flag is off: from the outside, a feature this
 * school does not have and an address that does not exist are the same thing, and saying
 * "you may not" would send someone to ask for a permission that is not the problem.
 *
 * The action goes to the person's own Home rather than "back", because the page they came
 * from is often the one that sent them here.
 */
@Component({
  selector: 'hq-not-found-page',
  imports: [PageComponent, EmptyStateComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'errors.notFound.title' | transloco">
      <hq-empty-state
        [message]="'errors.notFound.body' | transloco"
        [actionLabel]="'errors.goHome' | transloco"
        (action)="goHome()"
      />
    </hq-page>
  `,
})
export class NotFoundPage {
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);

  protected goHome(): void {
    void this.router.navigateByUrl(this.auth.home());
  }
}
