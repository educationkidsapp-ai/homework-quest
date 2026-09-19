import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  input,
  model,
  viewChild,
} from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { ButtonComponent } from '../button/button.component';

export interface Shortcut {
  /** The keys as the user would press them, e.g. `/` or `Shift + ?`. */
  readonly keys: string;
  readonly description: string;
}

/**
 * The `?` shortcut sheet.
 *
 * A native `<dialog>` opened with `showModal()`, so the focus trap, the Esc key and
 * the inert background come from the platform rather than from a focus-management
 * library — the dashboard has exactly one modal and it does not need a CDK overlay.
 */
@Component({
  selector: 'hq-shortcuts-dialog',
  imports: [TranslocoPipe, ButtonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown)': 'onDocumentKeydown($event)' },
  template: `
    <dialog #dialog class="sheet" [attr.aria-label]="title()" (close)="open.set(false)">
      <header class="sheet__header">
        <h2 class="sheet__title">{{ title() }}</h2>
        <hq-button variant="quiet" (pressed)="open.set(false)">{{ 'ui.close' | transloco }}</hq-button>
      </header>
      <dl class="sheet__list">
        @for (shortcut of shortcuts(); track shortcut.keys) {
          <div class="sheet__row">
            <dt class="sheet__keys">
              <kbd>{{ shortcut.keys }}</kbd>
            </dt>
            <dd class="sheet__description">{{ shortcut.description }}</dd>
          </div>
        }
      </dl>
    </dialog>
  `,
  styles: `
    @use 'mixins' as m;

    .sheet {
      inline-size: min(var(--hq-size-content-max-width), 90vw);
      padding: 0;
      // A floating panel: it sits on the CDK overlay with nothing opaque behind it, so it
      // takes the raised surface rather than the card one (§5 gives both, and they differ).
      background: var(--hq-color-surface-raised);
      border: var(--hq-size-rule) solid var(--hq-color-line);
      color: var(--hq-color-ink);
      box-shadow: var(--hq-shadow-dialog);

      &::backdrop {
        background: var(--hq-color-overlay);
      }
    }

    .sheet__header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--hq-space-16);
      padding: var(--hq-space-16) var(--hq-space-24);
      border-block-end: var(--hq-size-rule) solid var(--hq-color-line);
    }

    .sheet__title {
      @include m.title;
    }

    .sheet__list {
      padding: var(--hq-space-16) var(--hq-space-24) var(--hq-space-24);
      margin: 0;
    }

    .sheet__row {
      display: flex;
      align-items: center;
      gap: var(--hq-space-16);
      min-block-size: var(--hq-size-touch-target);
      border-block-end: var(--hq-size-rule-thin) solid var(--hq-color-rule);
    }

    .sheet__keys {
      inline-size: var(--hq-size-grade-card);
      flex: none;
    }

    kbd {
      display: inline-block;
      padding: var(--hq-space-4) var(--hq-space-8);
      font-family: var(--hq-font-family-mono);
      font-size: var(--hq-font-label-size);
      border: var(--hq-size-rule-thin) solid var(--hq-color-line);
    }

    .sheet__description {
      margin: 0;
    }
  `,
})
export class ShortcutsDialogComponent {
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  readonly open = model(false);
  readonly shortcuts = input.required<readonly Shortcut[]>();
  readonly title = input.required<string>();

  constructor() {
    afterRenderEffect(() => {
      const dialog = this.dialog().nativeElement;
      if (this.open()) {
        if (dialog.open) return;
        // Some jsdom versions ship <dialog> without showModal; degrade to the open attribute.
        if (typeof dialog.showModal === 'function') dialog.showModal();
        else dialog.open = true;
      } else if (dialog.open) {
        dialog.close();
      }
    });
  }

  /** `?` opens the sheet, unless the user is typing. */
  protected onDocumentKeydown(event: KeyboardEvent): void {
    if (event.key !== '?' || isTyping(event.target)) return;
    event.preventDefault();
    this.open.set(true);
  }
}

function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  return (
    target.isContentEditable ||
    ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName) ||
    target.closest('dialog') !== null
  );
}
