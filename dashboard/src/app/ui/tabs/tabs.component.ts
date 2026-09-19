import { ChangeDetectionStrategy, Component, ElementRef, input, model, viewChildren } from '@angular/core';

export interface Tab<T extends string = string> {
  readonly id: T;
  readonly label: string;
  readonly badge?: number;
  readonly disabled?: boolean;
  /**
   * `id` of the panel this tab controls. Omit when the screen does not render a
   * `role="tabpanel"` element — `aria-controls` pointing at a missing id is an
   * accessibility failure, not a harmless extra attribute.
   */
  readonly controls?: string;
}

/**
 * A tab strip following the WAI-ARIA tabs pattern: arrow keys move, Home/End jump,
 * and only the selected tab is in the tab order.
 *
 * Two skins, one behaviour. `underline` (the default) is the classic strip, selected by the
 * accent rule beneath it. `chips` is §3's filter chip — a row of radius-8 boxes that fill with
 * the brand when on, with the count in a pill — for the strips that are a *filter* over one
 * list rather than a switch between different panels.
 */
@Component({
  selector: 'hq-tabs',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="tabs" [class]="'tabs--' + variant()" role="tablist" [attr.aria-label]="label()">
      @for (tab of tabs(); track tab.id) {
        <button
          #tab
          class="tabs__tab"
          type="button"
          role="tab"
          [id]="'hq-tab-' + tab.id"
          [attr.aria-selected]="tab.id === selected()"
          [attr.aria-controls]="tab.controls ?? null"
          [attr.tabindex]="tab.id === selected() ? 0 : -1"
          [disabled]="tab.disabled ?? false"
          (click)="select(tab.id)"
          (keydown)="onKeydown($event)"
        >
          {{ tab.label }}
          @if (tab.badge !== undefined) {
            <span class="tabs__badge">{{ tab.badge }}</span>
          }
        </button>
      }
    </div>
  `,
  styles: `
    @use 'mixins' as m;

    :host {
      display: block;
    }

    .tabs {
      display: flex;
      flex-wrap: wrap;
      gap: var(--hq-space-8);
    }

    .tabs--underline {
      gap: var(--hq-space-24);
      border-block-end: var(--hq-size-rule-thin) solid var(--hq-color-rule);
    }

    .tabs__tab {
      display: inline-flex;
      align-items: center;
      gap: var(--hq-space-8);
      background: none;
      border: 0;
      font-size: var(--hq-text-theme-sm);
      line-height: calc(var(--hq-text-theme-sm-line) / var(--hq-text-theme-sm));
      font-weight: var(--hq-text-weight-medium);
      color: var(--hq-color-ink-soft);
      cursor: pointer;
      @include m.motion-safe('color, background-color, border-color');
      @include m.focus-ring;

      &:disabled {
        color: var(--hq-color-disabled);
        cursor: not-allowed;
      }
    }

    // --- underline ----------------------------------------------------------

    .tabs--underline .tabs__tab {
      min-block-size: var(--hq-size-touch-target);
      padding: 0 var(--hq-space-4);
      border-block-end: var(--hq-size-selected-border) solid transparent;
      margin-block-end: calc(var(--hq-size-rule-thin) * -1);

      // The :not([aria-selected='true']) is load-bearing, not decoration. Without it this rule
      // is .tabs--underline .tabs__tab:hover:not(:disabled) — two classes and two
      // pseudo-classes, specificity (0,4,0) — while the selected rule below is (0,3,0). So a
      // pointer resting on the tab a teacher is already looking at took the accent off its
      // label. Same shape as the chip fix in #82 (see the restyle report, §4.4b); this was the
      // half of it left open.
      &:hover:not(:disabled):not([aria-selected='true']) {
        color: var(--hq-color-ink);
      }

      &[aria-selected='true'] {
        // The label takes the accent as *text* (AA-held in dark mode), the rule under it takes
        // the accent itself — the school's colour where it is a fill, readable where it is a word.
        color: var(--hq-color-accent-ink);
        border-block-end-color: var(--hq-color-accent);
      }
    }

    // --- §3 filter chip -----------------------------------------------------

    .tabs--chips .tabs__tab {
      padding: var(--hq-space-chip);
      border: var(--hq-size-rule-thin) solid var(--hq-color-control-rule);
      border-radius: var(--hq-radius-control);
      background: var(--hq-color-surface);
      color: var(--hq-color-ink-strong);

      // The :not([aria-selected='true']) is load-bearing, not tidiness. Without it this
      // selector is two classes and two pseudo-classes — specificity (0,4,0) — against the
      // selected rule below, which is two classes and an attribute, (0,3,0). Hover won, so the
      // *selected* chip under the pointer kept its on-accent (white) label from that rule while
      // taking its background from here: white on gray-50, unreadable. Every class-* frame in
      // theme-t2, theme-t3 and T4's first set photographed it, because the pointer rests where
      // the last click left it and the specs reach the calendar by clicking this chip.
      &:hover:not(:disabled):not([aria-selected='true']) {
        background: var(--hq-color-surface-sunken);
      }

      &[aria-selected='true'] {
        background: var(--hq-color-accent);
        border-color: var(--hq-color-accent);
        color: var(--hq-color-on-accent);
      }
    }

    .tabs__badge {
      padding: 0 var(--hq-space-8);
      border-radius: var(--hq-radius-pill);
      font-size: var(--hq-text-theme-xs);
      line-height: calc(var(--hq-text-theme-xs-line) / var(--hq-text-theme-xs));
      color: var(--hq-color-ink-soft);
    }

    .tabs--chips .tabs__badge {
      background: var(--hq-color-divider);
    }

    .tabs--chips .tabs__tab[aria-selected='true'] .tabs__badge {
      background: var(--hq-color-on-accent-soft);
      color: var(--hq-color-on-accent);
    }
  `,
})
export class TabsComponent<T extends string = string> {
  private readonly tabButtons = viewChildren<ElementRef<HTMLButtonElement>>('tab');

  readonly tabs = input.required<readonly Tab<T>[]>();
  readonly selected = model.required<T>();
  /** Accessible name for the tablist — say what is being switched, not "Tabs". */
  readonly label = input.required<string>();
  /** `underline` switches between panels; `chips` filters one list (§3 Filter chip). */
  readonly variant = input<'underline' | 'chips'>('underline');

  protected select(id: T): void {
    this.selected.set(id);
  }

  protected onKeydown(event: KeyboardEvent): void {
    const enabled = this.tabs().filter((tab) => !tab.disabled);
    const current = enabled.findIndex((tab) => tab.id === this.selected());
    if (current === -1) return;

    const next = this.nextIndex(event.key, current, enabled.length);
    if (next === null) return;

    event.preventDefault();
    const target = enabled[next];
    if (!target) return;
    this.selected.set(target.id);
    const index = this.tabs().findIndex((tab) => tab.id === target.id);
    this.tabButtons().at(index)?.nativeElement.focus();
  }

  /** Arrow keys wrap; RTL is handled by the browser reporting logical keys, not visual ones. */
  private nextIndex(key: string, current: number, length: number): number | null {
    switch (key) {
      case 'ArrowRight':
      case 'ArrowDown':
        return (current + 1) % length;
      case 'ArrowLeft':
      case 'ArrowUp':
        return (current - 1 + length) % length;
      case 'Home':
        return 0;
      case 'End':
        return length - 1;
      default:
        return null;
    }
  }
}
