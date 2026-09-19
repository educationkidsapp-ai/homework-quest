import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  input,
  model,
  output,
  viewChild,
} from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { ButtonComponent } from '../button/button.component';

/**
 * A modal form: a title, whatever is projected into it, and one primary action in a footer.
 *
 * A native `<dialog>` opened with `showModal()`, exactly as `hq-shortcuts-dialog` is — the focus
 * trap, the Esc key, the inert background and the backdrop all come from the platform, so the
 * dashboard still needs no overlay library for the one thing CDK would have been used for. The
 * shortcuts sheet stays its own component because it is a sheet of text with its own `?` binding;
 * this is the shape every *form* dialog takes.
 *
 * `confirmDisabled` rather than a hidden button: a primary action that vanishes while a required
 * field is empty gives no clue what is missing, and `hq-button`'s `reason` puts the clue on it.
 */
@Component({
  selector: 'hq-dialog',
  imports: [ButtonComponent, TranslocoPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <dialog #dialog class="dialog" [attr.aria-label]="title()" (close)="onClose()">
      <form method="dialog" class="dialog__form" (submit)="onSubmit($event)">
        <header class="dialog__header">
          <h2 class="dialog__title">{{ title() }}</h2>
          <hq-button variant="quiet" (pressed)="open.set(false)">{{ 'ui.close' | transloco }}</hq-button>
        </header>

        <div class="dialog__body">
          <ng-content />
        </div>

        <footer class="dialog__footer">
          <hq-button variant="quiet" (pressed)="open.set(false)">{{ 'ui.cancel' | transloco }}</hq-button>
          <hq-button
            variant="primary"
            type="submit"
            [disabled]="confirmDisabled()"
            [loading]="loading()"
            [reason]="confirmReason()"
          >
            {{ confirmLabel() }}
          </hq-button>
        </footer>
      </form>
    </dialog>
  `,
  styles: `
    @use 'mixins' as m;

    .dialog {
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

    .dialog__form {
      display: flex;
      flex-direction: column;
    }

    .dialog__header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--hq-space-16);
      padding: var(--hq-space-16) var(--hq-space-24);
      border-block-end: var(--hq-size-rule) solid var(--hq-color-line);
    }

    .dialog__title {
      @include m.title;
    }

    .dialog__body {
      display: flex;
      flex-direction: column;
      gap: var(--hq-space-16);
      padding: var(--hq-space-24);
      max-block-size: 60vh;
      overflow-y: auto;
    }

    .dialog__footer {
      display: flex;
      justify-content: flex-end;
      gap: var(--hq-space-12);
      padding: var(--hq-space-16) var(--hq-space-24);
      border-block-start: var(--hq-size-rule) solid var(--hq-color-line);
    }
  `,
})
export class DialogComponent {
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  readonly open = model(false);
  readonly title = input.required<string>();
  readonly confirmLabel = input.required<string>();
  readonly confirmDisabled = input(false);
  /** Why the primary action is disabled, shown on it rather than left to be guessed. */
  readonly confirmReason = input<string | null>(null);
  readonly loading = input(false);

  readonly confirmed = output<void>();

  /**
   * Whatever had focus when this opened.
   *
   * A modal `<dialog>` restores focus itself, but only on the path where `showModal()` exists —
   * not on the `open = true` fallback, and not for a dialog closed by its own Close button in
   * every engine. Remembering it here means the person who pressed "Create class" and changed
   * their mind is put back on that button rather than at the top of the document.
   */
  private opener: HTMLElement | null = null;

  constructor() {
    afterRenderEffect(() => {
      const dialog = this.dialog().nativeElement;
      if (this.open()) {
        if (dialog.open) return;
        const active = dialog.ownerDocument.activeElement;
        this.opener = active instanceof HTMLElement && !dialog.contains(active) ? active : this.opener;
        // Some jsdom versions ship <dialog> without showModal; degrade to the open attribute.
        if (typeof dialog.showModal === 'function') dialog.showModal();
        else dialog.open = true;
      } else if (dialog.open) {
        // Symmetrically: an engine without `showModal` has no `close` either, and throwing here
        // would leave a dialog on screen that the signal already believes is shut.
        if (typeof dialog.close === 'function') dialog.close();
        else dialog.open = false;
      }
    });
  }

  protected onSubmit(event: Event): void {
    event.preventDefault();
    if (this.confirmDisabled() || this.loading()) return;
    this.confirmed.emit();
  }

  /** Esc and the backdrop close the element itself; the signal has to hear about it. */
  protected onClose(): void {
    this.open.set(false);
    const opener = this.opener;
    this.opener = null;
    opener?.focus();
  }
}
