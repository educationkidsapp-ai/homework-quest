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
    <dialog
      #dialog
      class="dialog"
      [class.dialog--sheet]="sheet()"
      [attr.aria-label]="title()"
      (cancel)="onCancel($event)"
      (close)="onClose()"
    >
      <!--
        novalidate: this system says what is wrong under the field, in its own words and its own
        language, and a native validation bubble would both duplicate that and, worse, silently
        swallow the submit event a form with an empty required field never fires. The primary
        action's own guard (confirmDisabled) and the host's validation are what refuse a save.
      -->
      <form method="dialog" class="dialog__form" novalidate (submit)="onSubmit($event)">
        <header class="dialog__header">
          <h2 class="dialog__title">{{ title() }}</h2>
          <hq-button variant="quiet" (pressed)="requestClose()">{{ 'ui.close' | transloco }}</hq-button>
        </header>

        <div class="dialog__body">
          <ng-content />
        </div>

        <footer class="dialog__footer">
          <hq-button variant="quiet" (pressed)="requestClose()">{{
            cancelLabel() ?? ('ui.cancel' | transloco)
          }}</hq-button>
          <!--
            A second, non-primary action beside the primary one — "Save and add another", and
            nothing that commits anything different. It is projected rather than configured
            because its label, its guard and its handler all belong to the form, and the footer
            only owns where it sits.
          -->
          <ng-content select="[hqDialogAction]" />
          @if (confirmLabel(); as label) {
            <hq-button
              variant="primary"
              type="submit"
              [disabled]="confirmDisabled()"
              [loading]="loading()"
              [reason]="confirmReason()"
            >
              {{ label }}
            </hq-button>
          }
        </footer>
      </form>
    </dialog>
  `,
  styles: `
    @use 'mixins' as m;

    .dialog {
      inline-size: min(var(--hq-size-content-max-width), 90vw);
      max-inline-size: 100%;
      padding: 0;
      border-radius: var(--hq-radius-card);
      // A floating panel: it sits on the CDK overlay with nothing opaque behind it, so it
      // takes the raised surface rather than the card one (§5 gives both, and they differ).
      background: var(--hq-color-surface-raised);
      border: var(--hq-size-rule-thin) solid var(--hq-color-rule);
      color: var(--hq-color-ink);
      box-shadow: var(--hq-shadow-dialog);

      &::backdrop {
        background: var(--hq-color-overlay);
      }
    }

    // A phone has no room for a floating panel with a page behind it: the same dialog becomes a
    // full-screen sheet, so the form gets the whole viewport and the body scrolls rather than
    // the 60vh well a 12-inch screen can afford. the :modal guard keeps it off the non-modal fallback,
    // which is not on top of anything and must not cover the page.
    @include m.below(m.$sheet-breakpoint) {
      .dialog--sheet:modal {
        inline-size: 100vw;
        max-inline-size: 100vw;
        block-size: 100dvh;
        max-block-size: 100dvh;
        margin: 0;
        border: 0;
        border-radius: 0;

        .dialog__form {
          block-size: 100%;
        }

        .dialog__body {
          flex: 1;
          max-block-size: none;
        }
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
      padding: var(--hq-space-card-header);
      border-block-end: var(--hq-size-rule-thin) solid var(--hq-color-rule);
    }

    .dialog__title {
      @include m.card-title;
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
      padding: var(--hq-space-card-header);
      border-block-start: var(--hq-size-rule-thin) solid var(--hq-color-rule);
    }
  `,
})
export class DialogComponent {
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  readonly open = model(false);
  readonly title = input.required<string>();
  /**
   * The primary action's words, or `null` for a dialog that has none.
   *
   * A list whose decision is taken on a row — "Place" next to each child — has no single thing
   * the footer could commit, and a disabled primary action in that footer would read as a step
   * the person has failed to complete. Every *form* dialog still passes one.
   */
  readonly confirmLabel = input<string | null>(null);
  /** The dismissing action's words when "Cancel" is not what it does. */
  readonly cancelLabel = input<string | null>(null);
  readonly confirmDisabled = input(false);
  /** Under 768 px this becomes a full-screen sheet rather than a floating panel. */
  readonly sheet = input(false);
  /**
   * When set, nothing closes this dialog by itself — Esc, the backdrop, Close and Cancel all
   * emit `closeRequested` and the host decides. That is how a form with unsaved words gets to
   * ask before it throws them away; without it the platform's Esc is unconditional.
   */
  readonly guarded = input(false);
  /** Why the primary action is disabled, shown on it rather than left to be guessed. */
  readonly confirmReason = input<string | null>(null);
  readonly loading = input(false);

  readonly confirmed = output<void>();
  /** Only while `guarded`: somebody asked to close this, and the host has to answer. */
  readonly closeRequested = output<void>();

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

  /** The Close and Cancel buttons: shut, or hand the decision to the host. */
  protected requestClose(): void {
    if (this.guarded()) this.closeRequested.emit();
    else this.open.set(false);
  }

  /**
   * Esc (and a light-dismiss backdrop) fire `cancel` before `close`, so this is the one place a
   * guard can stop the platform closing a form somebody is still writing in.
   */
  protected onCancel(event: Event): void {
    if (!this.guarded()) return;
    event.preventDefault();
    this.closeRequested.emit();
  }

  /** Esc and the backdrop close the element itself; the signal has to hear about it. */
  protected onClose(): void {
    this.open.set(false);
    const opener = this.opener;
    this.opener = null;
    opener?.focus();
  }
}
