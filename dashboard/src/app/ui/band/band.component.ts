import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { ExpandBandDirective } from '../motion';
import { ButtonComponent } from '../button/button.component';

export type BandVariant = 'error' | 'confirm' | 'notice';

/**
 * The band: this system's only way to say something went wrong or to ask "are you sure".
 *
 * Never a toast — a failure has to stay on screen next to the thing that failed, and a
 * destructive action has to be confirmed in place. It expands from 0 height over 250 ms,
 * pushing the page rather than covering it.
 */
@Component({
  selector: 'hq-band',
  imports: [TranslocoPipe, ExpandBandDirective, ButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div
      class="band"
      [class]="'band--' + variant()"
      [hqExpandBand]="open()"
      [attr.role]="variant() === 'notice' ? 'status' : 'alert'"
    >
      <div class="band__text">
        @if (title(); as titleText) {
          <p class="band__title">{{ titleText }}</p>
        }
        <div class="band__message"><ng-content /></div>
      </div>
      <div class="band__actions">
        @if (confirmLabel(); as label) {
          <hq-button [variant]="variant() === 'confirm' ? 'danger' : 'primary'" (pressed)="confirmed.emit()">
            {{ label }}
          </hq-button>
        }
        @if (dismissible()) {
          <hq-button variant="quiet" (pressed)="dismissed.emit()">
            {{ 'ui.dismiss' | transloco }}
          </hq-button>
        }
      </div>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }

    .band {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--hq-space-16);
      overflow: hidden;
      padding: var(--hq-space-12) var(--hq-space-16);
      border: var(--hq-size-rule) solid var(--hq-color-accent);
      background: var(--hq-color-accent-soft);
    }

    .band--notice {
      border-color: var(--hq-color-line);
      background: var(--hq-color-surface);
    }

    .band--confirm {
      border-inline-start-width: var(--hq-size-selected-border);
    }

    .band__title {
      font-weight: var(--hq-font-label-weight);
    }

    .band__message {
      font-size: var(--hq-font-body-size);
    }

    .band__actions {
      display: flex;
      flex: none;
      gap: var(--hq-space-8);
    }
  `,
})
export class BandComponent {
  readonly open = input.required<boolean>();
  readonly variant = input<BandVariant>('error');
  readonly title = input<string | null>(null);
  /** Showing a confirm button turns the band into a destructive confirmation. */
  readonly confirmLabel = input<string | null>(null);
  readonly dismissible = input(true);

  readonly confirmed = output<void>();
  readonly dismissed = output<void>();
}
