import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { glyphOf, tintOf } from './illustrations';

/** The five sizes the app draws an illustration card at (44 / 56 / 84 / 120 / 180 px). */
export type IllustrationSize = 'xs' | 'sm' | 'md' | 'lg' | 'xl';

/**
 * One illustration key as the app draws it in v1 (`docs/design.md` §9): an emoji on a tinted,
 * rounded card, the tint chosen from the key so the same word always looks the same.
 */
@Component({
  selector: 'hq-illustration',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[class]': "'illus illus--' + size() + ' illus--tint-' + tint()",
    '[attr.role]': "'img'",
    '[attr.aria-label]': 'key()',
  },
  template: `<span class="illus__glyph" aria-hidden="true">{{ glyph() }}</span>`,
  styles: `
    :host {
      display: grid;
      place-items: center;
      container-type: inline-size;
      inline-size: var(--hq-child-illus-md);
      block-size: var(--hq-child-illus-md);
      flex: none;
      border-radius: var(--hq-child-radius-tile);
      background: var(--hq-child-tint-1);
    }

    :host(.illus--xs) {
      inline-size: var(--hq-child-illus-xs);
      block-size: var(--hq-child-illus-xs);
    }

    :host(.illus--sm) {
      inline-size: var(--hq-child-illus-sm);
      block-size: var(--hq-child-illus-sm);
    }

    :host(.illus--lg) {
      inline-size: var(--hq-child-illus-lg);
      block-size: var(--hq-child-illus-lg);
    }

    :host(.illus--xl) {
      inline-size: var(--hq-child-illus-xl);
      block-size: var(--hq-child-illus-xl);
    }

    :host(.illus--tint-2) {
      background: var(--hq-child-tint-2);
    }

    :host(.illus--tint-3) {
      background: var(--hq-child-tint-3);
    }

    :host(.illus--tint-4) {
      background: var(--hq-child-tint-4);
    }

    :host(.illus--tint-5) {
      background: var(--hq-child-tint-5);
    }

    :host(.illus--tint-6) {
      background: var(--hq-child-tint-6);
    }

    :host(.illus--tint-7) {
      background: var(--hq-child-tint-7);
    }

    // 55% of the card, the same ratio the app uses.
    .illus__glyph {
      font-size: 55cqi;
      line-height: 1;
    }
  `,
})
export class IllustrationComponent {
  readonly key = input.required<string>();
  readonly size = input<IllustrationSize>('md');

  protected readonly glyph = computed(() => glyphOf(this.key()));
  protected readonly tint = computed(() => tintOf(this.key()));
}
