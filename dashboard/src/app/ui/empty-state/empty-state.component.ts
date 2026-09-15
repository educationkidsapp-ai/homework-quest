import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { ButtonComponent } from '../button/button.component';

/**
 * An empty state: the mascot, one sentence, one action.
 *
 * One sentence, not a paragraph — an empty list needs to say what would be here and
 * how to put something here, and nothing else. The mascot is a projected slot so the
 * real Pip artwork can drop in without touching this component.
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
      @if (actionLabel(); as label) {
        <hq-button variant="primary" (pressed)="action.emit()">{{ label }}</hq-button>
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
      gap: var(--hq-space-16);
      padding: var(--hq-space-48) var(--hq-space-24);
      text-align: center;
    }

    // Placeholder until the mascot artwork lands: a square in Pip's colour.
    .empty__placeholder {
      display: block;
      inline-size: var(--hq-size-grade-card);
      block-size: var(--hq-size-grade-card);
      background: var(--hq-mascot-color-body);
      border: var(--hq-size-rule) solid var(--hq-color-line);
    }

    .empty__message {
      max-inline-size: var(--hq-size-stop-list-width);
      color: var(--hq-color-ink-soft);
    }
  `,
})
export class EmptyStateComponent {
  /** One sentence. */
  readonly message = input.required<string>();
  readonly actionLabel = input<string | null>(null);

  readonly action = output<void>();
}
