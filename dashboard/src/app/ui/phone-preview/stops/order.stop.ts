import { ChangeDetectionStrategy, Component, computed, input, linkedSignal } from '@angular/core';
import { ChildButtonComponent } from '../child-button.component';
import { IllustrationComponent } from '../illustration.component';
import { OrderItem, OrderStop } from '../stop.model';

interface Slot {
  readonly position: number;
  readonly item: OrderItem | null;
  readonly locked: boolean;
}

/**
 * Order: tap the cards into the numbered slots, then Check. The leading run that is already right
 * locks mint and stays; everything after it goes back to the pile. Tap-to-place rather than drag,
 * the same call the app made — a four-year-old's finger is not a mouse.
 */
@Component({
  selector: 'hq-stop-order',
  imports: [ChildButtonComponent, IllustrationComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="order">
      <p class="order__prompt">{{ stop().prompt }}</p>

      <ol class="order__slots">
        @for (slot of slots(); track slot.position) {
          <li>
            <button
              type="button"
              class="order__slot"
              [class.order__slot--locked]="slot.locked"
              [disabled]="!slot.item || slot.locked"
              [attr.aria-label]="
                'slot ' + (slot.position + 1) + ': ' + (slot.item ? slot.item.text : 'empty')
              "
              (click)="takeBack(slot.position)"
            >
              <span class="order__index" aria-hidden="true">{{ slot.position + 1 }}</span>
              @if (slot.item; as item) {
                @if (item.illustrationKey; as key) {
                  <hq-illustration [key]="key" size="xs" />
                }
                <span class="order__text">{{ item.text }}</span>
              }
            </button>
          </li>
        }
      </ol>

      <ul class="order__pile">
        @for (item of pile(); track item.id) {
          <li>
            <button type="button" class="order__card" (click)="place(item.id)">
              @if (item.illustrationKey; as key) {
                <hq-illustration [key]="key" size="xs" />
              }
              <span class="order__text">{{ item.text }}</span>
            </button>
          </li>
        }
      </ul>

      <hq-child-button
        label="Check"
        emoji="👀"
        [disabled]="placed().length !== stop().correctOrder.length"
        (pressed)="check()"
      />
    </div>
  `,
  styles: `
    @use '../child-tokens' as child;

    .order {
      @include child.child-stack(var(--hq-space-12));

      padding-inline: var(--hq-space-16);
    }

    .order__prompt {
      @include child.child-prompt;
    }

    .order__slots,
    .order__pile {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-8);
      inline-size: 100%;
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .order__slot,
    .order__card {
      @include child.child-target;
      @include child.child-label;

      display: flex;
      align-items: center;
      gap: var(--hq-space-12);
      inline-size: 100%;
      padding: var(--hq-space-8) var(--hq-space-12);
      border: none;
      border-radius: var(--hq-child-radius-tile);
      color: var(--hq-child-ink);
      text-align: start;
    }

    .order__slot {
      background: var(--hq-child-sand);
    }

    .order__slot--locked {
      background: var(--hq-child-mint);
    }

    .order__card {
      background: var(--hq-child-cream);
    }

    .order__index {
      color: var(--hq-child-ink-soft);
    }

    .order__text {
      flex: 1;
    }
  `,
})
export class OrderStopComponent {
  readonly stop = input.required<OrderStop>();

  private readonly placedIds = linkedSignal<string, readonly string[]>({
    source: () => this.stop().id,
    computation: () => [],
  });
  private readonly lockedCount = linkedSignal<string, number>({
    source: () => this.stop().id,
    computation: () => 0,
  });

  protected readonly placed = this.placedIds.asReadonly();

  protected readonly slots = computed<readonly Slot[]>(() =>
    this.stop().correctOrder.map((_, position) => ({
      position,
      item: this.byId(this.placedIds()[position]),
      locked: position < this.lockedCount(),
    })),
  );

  protected readonly pile = computed<readonly OrderItem[]>(() =>
    this.stop().items.filter((item) => !this.placedIds().includes(item.id)),
  );

  protected place(id: string): void {
    if (this.placedIds().length >= this.stop().correctOrder.length) return;
    this.placedIds.set([...this.placedIds(), id]);
  }

  protected takeBack(position: number): void {
    if (position < this.lockedCount()) return;
    this.placedIds.set(this.placedIds().filter((_, index) => index !== position));
  }

  protected check(): void {
    const correct = this.stop().correctOrder;
    const placed = this.placedIds();
    let prefix = 0;
    while (prefix < correct.length && placed[prefix] === correct[prefix]) prefix += 1;
    this.lockedCount.set(prefix);
    this.placedIds.set(placed.slice(0, prefix));
  }

  private byId(id: string | undefined): OrderItem | null {
    if (!id) return null;
    return this.stop().items.find((item) => item.id === id) ?? null;
  }
}
