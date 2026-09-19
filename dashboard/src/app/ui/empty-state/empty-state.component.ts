import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { ButtonComponent } from '../button/button.component';

/**
 * §3's empty state: centred in 56 px of air, a 56 × 56 radius-12 tile, one 16/500 line, an
 * optional 14/400 second line and at most one secondary button.
 *
 * One sentence, not a paragraph — an empty list needs to say what would be here and how to put
 * something here, and nothing else. The mascot is a projected slot so the real Pip artwork can
 * drop into the tile without touching this component.
 */
@Component({
  selector: 'hq-empty-state',
  imports: [ButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="empty">
      <div class="empty__mascot" aria-hidden="true">
        <ng-content select="[empty-mascot]">
          <span class="empty__placeholder"></span>
        </ng-content>
      </div>
      <p class="empty__message">{{ message() }}</p>
      @if (detail(); as detailText) {
        <p class="empty__detail">{{ detailText }}</p>
      }
      @if (actionLabel(); as label) {
        <hq-button variant="secondary" (pressed)="action.emit()">{{ label }}</hq-button>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
    }

    .empty {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: var(--hq-space-12);
      padding: var(--hq-size-empty-tile) var(--hq-space-24);
      text-align: center;
    }

    // §3's tile. The mascot artwork drops into it; until it lands, Pip's colour fills it.
    .empty__mascot {
      display: grid;
      place-items: center;
      inline-size: var(--hq-size-empty-tile);
      block-size: var(--hq-size-empty-tile);
      margin-block-end: var(--hq-space-4);
      border-radius: var(--hq-radius-tile);
      background: var(--hq-color-divider);
      color: var(--hq-color-ink-muted);
      overflow: hidden;
    }

    .empty__placeholder {
      display: block;
      inline-size: var(--hq-space-24);
      block-size: var(--hq-space-24);
      border-radius: var(--hq-radius-control);
      background: var(--hq-mascot-color-body);
    }

    .empty__message {
      max-inline-size: var(--hq-size-stop-list-width);
      font-size: var(--hq-text-lead);
      font-weight: var(--hq-text-weight-medium);
      color: var(--hq-color-ink-strong);
    }

    .empty__detail {
      max-inline-size: var(--hq-size-stop-list-width);
      margin-block-start: calc(var(--hq-space-8) * -1);
      font-size: var(--hq-text-theme-sm);
      color: var(--hq-color-ink-soft);
    }
  `,
})
export class EmptyStateComponent {
  /** One sentence. */
  readonly message = input.required<string>();
  /** The optional second line: 14/400, secondary ink. */
  readonly detail = input<string | null>(null);
  readonly actionLabel = input<string | null>(null);

  readonly action = output<void>();
}
