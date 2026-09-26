import { ChangeDetectionStrategy, Component, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { BandComponent, ButtonComponent } from '../../ui';

/**
 * "That did not work" — the house band, with the one thing she can do about it.
 *
 * One component rather than the same six lines on each of her four screens. The review found why
 * it matters: three of them handled `isLoading()` only, so a 403 or a 500 painted "No teachers
 * teach your subject yet" — an empty state, which is a statement about her school, over a read
 * that never happened. Nothing on a coordinator's screen can be *fixed* by her, so the band is
 * never dismissible and Try again is the only action.
 */
@Component({
  selector: 'hq-coordinator-read-failed',
  imports: [BandComponent, ButtonComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hq-band variant="error" [open]="true" [title]="'band.failed' | transloco" [dismissible]="false">
      {{ 'band.unreachable' | transloco }}
    </hq-band>
    <hq-button variant="secondary" (pressed)="retry.emit()">{{ 'ui.retry' | transloco }}</hq-button>
  `,
})
export class CoordinatorReadFailedComponent {
  readonly retry = output<void>();
}
