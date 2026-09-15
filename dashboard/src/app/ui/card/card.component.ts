import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * A white panel behind a 2 px ink rule. The only container in the system.
 *
 * `title`/`eyebrow` are optional; `[card-actions]` and `[card-footer]` are slots so a
 * screen can put its overflow menu or a single action in the frame without a variant.
 */
@Component({
  selector: 'hq-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (title() || eyebrow()) {
      <header class="card__header">
        <div class="card__heading">
          @if (eyebrow(); as eyebrowText) {
            <p class="card__eyebrow">{{ eyebrowText }}</p>
          }
          @if (title(); as titleText) {
            <h2 class="card__title">{{ titleText }}</h2>
          }
        </div>
        <div class="card__actions"><ng-content select="[card-actions]" /></div>
      </header>
    }
    <div class="card__body" [class.card__body--flush]="flush()">
      <ng-content />
    </div>
    <ng-content select="[card-footer]" />
  `,
  styles: `
    @use 'mixins' as m;

    :host {
      display: block;
      @include m.surface;
    }

    .card__header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: var(--hq-space-16);
      padding: var(--hq-space-16) var(--hq-space-24);
      border-block-end: var(--hq-size-rule) solid var(--hq-color-line);
    }

    .card__eyebrow {
      @include m.label;
      color: var(--hq-color-ink-soft);
    }

    .card__title {
      font-size: var(--hq-font-body-size);
      font-weight: var(--hq-font-label-weight);
    }

    .card__body {
      padding: var(--hq-space-24);
    }

    .card__body--flush {
      padding: 0;
    }
  `,
})
export class CardComponent {
  readonly title = input<string | null>(null);
  readonly eyebrow = input<string | null>(null);
  /** Removes the body padding — for a card whose whole body is a table. */
  readonly flush = input(false);
}
