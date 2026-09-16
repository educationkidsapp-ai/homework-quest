/* hq-flag: none (shell) — the placeholder behind a nav item whose screen is a later package.
   The item that leads here is flag- and permission-filtered by the shell; gating the
   placeholder as well would hide the promise instead of keeping it. */
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { filter, map } from 'rxjs/operators';
import { phaseOf } from '../../core/nav/screens';
import { EmptyStateComponent, PageComponent } from '../../ui';

/**
 * A screen a later package builds.
 *
 * The nav rail shows every item a role has, including the ones whose screens arrive in phase
 * 4, 5 or 6 (see `nav-items.ts`): a dashboard that grows new menu items under someone who has
 * already learned it is worse than one that says what is coming. So the item is real, the
 * route is real, and this is what is behind it — the mascot, one sentence, and which phase
 * brings the screen.
 *
 * It is not a 404. A 404 says the person is wrong; this says the product is not finished, and
 * those are different apologies.
 */
@Component({
  selector: 'hq-stub-page',
  imports: [PageComponent, EmptyStateComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-page [title]="'stub.title' | transloco">
      <hq-empty-state [message]="message()" />
    </hq-page>
  `,
})
export class StubPage {
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map((event) => event.urlAfterRedirects.split('?')[0] ?? ''),
    ),
    { initialValue: this.router.url.split('?')[0] ?? '' },
  );

  protected readonly message = computed(() => {
    const phase = phaseOf(this.url());
    return phase === undefined
      ? this.transloco.translate('stub.soon')
      : this.transloco.translate('stub.phase', { phase });
  });
}
