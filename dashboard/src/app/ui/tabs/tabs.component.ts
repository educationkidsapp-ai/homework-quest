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
 * The selected tab is marked by the red rule underneath it, the same accent the nav uses.
 */
@Component({
  selector: 'hq-tabs',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="tabs" role="tablist" [attr.aria-label]="label()">
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
      gap: var(--hq-space-24);
      border-block-end: var(--hq-size-rule) solid var(--hq-color-line);
    }

    .tabs__tab {
      display: inline-flex;
      align-items: center;
      gap: var(--hq-space-8);
      min-block-size: var(--hq-size-touch-target);
      padding: 0 var(--hq-space-4);
      background: none;
      border: 0;
      border-block-end: var(--hq-size-selected-border) solid transparent;
      margin-block-end: calc(var(--hq-size-rule) * -1);
      font-weight: var(--hq-font-label-weight);
      color: var(--hq-color-ink-soft);
      cursor: pointer;
      @include m.motion-safe('color, border-color');
      @include m.focus-ring;

      &:disabled {
        color: var(--hq-color-disabled);
        cursor: not-allowed;
      }

      &[aria-selected='true'] {
        color: var(--hq-color-ink);
        border-block-end-color: var(--hq-color-accent);
      }
    }

    .tabs__badge {
      font-size: var(--hq-font-label-size);
      color: var(--hq-color-ink-soft);
    }
  `,
})
export class TabsComponent<T extends string = string> {
  private readonly tabButtons = viewChildren<ElementRef<HTMLButtonElement>>('tab');

  readonly tabs = input.required<readonly Tab<T>[]>();
  readonly selected = model.required<T>();
  /** Accessible name for the tablist — say what is being switched, not "Tabs". */
  readonly label = input.required<string>();

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
