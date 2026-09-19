import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * §2's card: radius 16, a 1 px `--hq-color-rule` border, the surface colour, 24 px of padding,
 * and no shadow — a card is a rule on a ground, not a floating thing.
 *
 * Four shapes, all from the same element:
 *
 * * **Card** — no title, 24 px body.
 * * **Card with header** — `title` (18/600) over an optional `eyebrow` sub-line (14/400), split
 *   from the body by the *inner* divider weight (`--hq-color-divider`), never the container one.
 * * **Table card** — `flush`, so the table meets the card's own edges and the radius clips it.
 * * **Nested / two-tone** — `nested`, the monthly-target pattern: a `--hq-color-divider` outer
 *   with a surface-coloured inner panel, and `[card-footer]` left on the exposed strip.
 *
 * `title`/`eyebrow` are optional; `[card-actions]` and `[card-footer]` are slots so a screen can
 * put its overflow menu or a single action in the frame without a variant.
 */
@Component({
  selector: 'hq-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class.card--nested]': 'nested()', '[class.card--flush]': 'flush()' },
  template: `
    @if (title() || eyebrow()) {
      <header class="card__header">
        <div class="card__heading">
          @if (title(); as titleText) {
            <h2 class="card__title">{{ titleText }}</h2>
          }
          @if (eyebrow(); as eyebrowText) {
            <p class="card__sub">{{ eyebrowText }}</p>
          }
        </div>
        <div class="card__actions"><ng-content select="[card-actions]" /></div>
      </header>
    }
    <div class="card__body">
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

    // Only the table card clips: 'overflow' on every card would break the 'position: sticky'
    // the lesson editor's phone preview and the table's own header row depend on.
    :host(.card--flush) {
      overflow: hidden;
    }

    .card__header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: var(--hq-space-16);
      padding: var(--hq-space-card-header);
    }

    // The header's rule belongs to the *body*, so a flush card keeps the divider above its
    // table and a headerless card draws no stray line at its top.
    .card__header + .card__body {
      border-block-start: var(--hq-size-rule-thin) solid var(--hq-color-divider);
    }

    .card__title {
      @include m.card-title;
    }

    .card__sub {
      margin-block-start: var(--hq-space-4);
      font-size: var(--hq-text-theme-sm);
      font-weight: var(--hq-font-body-weight);
      color: var(--hq-color-ink-soft);
    }

    .card__body {
      padding: var(--hq-space-card);
    }

    // §2 Table card: the body is the table, flush to the card's edges.
    :host(.card--flush) .card__body {
      padding: 0;
    }

    // §2 Nested / two-tone. The outer takes the inner-divider colour and the body becomes a
    // surface panel inset by the grid gap, leaving the strip '[card-footer]' sits on.
    :host(.card--nested) {
      background: var(--hq-color-divider);
    }

    :host(.card--nested) .card__body {
      background: var(--hq-color-surface);
      border-radius: var(--hq-radius-card);
    }

    :host(.card--nested) .card__header + .card__body {
      border-block-start: 0;
    }
  `,
})
export class CardComponent {
  readonly title = input<string | null>(null);
  /** The sub-line under the title — 14/400, secondary ink (§2 "Card with header"). */
  readonly eyebrow = input<string | null>(null);
  /** Removes the body padding — for a card whose whole body is a table. */
  readonly flush = input(false);
  /** The two-tone card: a surface panel on a divider-coloured ground, footer on the strip. */
  readonly nested = input(false);
}
