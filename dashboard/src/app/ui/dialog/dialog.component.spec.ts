import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHq } from '../../../testing/render';
import { DialogComponent } from './dialog.component';

/**
 * A host with a trigger outside the dialog, because half of what this component promises is
 * about focus: `showModal()` traps it, and closing must give it back to whatever opened it.
 */
@Component({
  selector: 'hq-dialog-host',
  imports: [DialogComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button type="button" (click)="open.set(true)">Open it</button>
    <hq-dialog
      [(open)]="open"
      title="Create a class"
      confirmLabel="Create"
      [confirmDisabled]="blocked()"
      [loading]="busy()"
      (confirmed)="confirmed()"
    >
      <input aria-label="Class name" />
    </hq-dialog>
  `,
})
class DialogHost {
  readonly open = signal(false);
  readonly blocked = signal(false);
  readonly busy = signal(false);
  readonly confirmed = vi.fn();
}

function element(): HTMLDialogElement {
  return document.querySelector('dialog')!;
}

/**
 * jsdom ships `<dialog>` without the modal machinery, so the real `showModal` is replaced by a
 * spy that does what the platform would: mark the element open. That is the honest way to
 * assert "this uses the platform's modal", since the alternative — asserting the `open`
 * attribute — passes just as well for the non-modal fallback the component degrades to.
 */
function stubShowModal(): { showModal: ReturnType<typeof vi.fn>; close: ReturnType<typeof vi.fn> } {
  const dialog = element();
  const showModal = vi.fn(() => {
    dialog.setAttribute('open', '');
  });
  const close = vi.fn(() => {
    dialog.removeAttribute('open');
    dialog.dispatchEvent(new Event('close'));
  });
  dialog.showModal = showModal;
  dialog.close = close;
  return { showModal, close };
}

describe('hq-dialog', () => {
  beforeEach(() => vi.clearAllMocks());

  it('opens with showModal(), so the focus trap and the backdrop are the platform’s', async () => {
    const rendered = await renderHq(DialogHost);
    const { showModal } = stubShowModal();

    rendered.fixture.componentInstance.open.set(true);
    rendered.fixture.detectChanges();
    await rendered.fixture.whenStable();

    expect(showModal).toHaveBeenCalledTimes(1);
    expect(element().hasAttribute('open')).toBe(true);
  });

  it('closes and reports it when the platform closes the element (Esc, backdrop)', async () => {
    const rendered = await renderHq(DialogHost);
    stubShowModal();
    rendered.fixture.componentInstance.open.set(true);
    rendered.fixture.detectChanges();
    await rendered.fixture.whenStable();

    // Esc is the browser's own close, not a key handler of ours: it fires `close` on the element.
    element().close();
    rendered.fixture.detectChanges();

    expect(rendered.fixture.componentInstance.open()).toBe(false);
  });

  it('gives focus back to whatever opened it', async () => {
    const rendered = await renderHq(DialogHost);
    stubShowModal();
    const trigger = screen.getByRole('button', { name: 'Open it' });

    trigger.focus();
    await userEvent.click(trigger);
    rendered.fixture.detectChanges();
    await rendered.fixture.whenStable();

    element().close();
    rendered.fixture.detectChanges();
    await rendered.fixture.whenStable();

    expect(document.activeElement).toBe(trigger);
  });

  it('confirms on submit, and refuses to while the action is disabled or in flight', async () => {
    const rendered = await renderHq(DialogHost);
    stubShowModal();
    const host = rendered.fixture.componentInstance;
    host.open.set(true);
    rendered.fixture.detectChanges();
    await rendered.fixture.whenStable();

    const form = document.querySelector('form')!;

    host.blocked.set(true);
    rendered.fixture.detectChanges();
    form.dispatchEvent(new Event('submit', { cancelable: true }));
    expect(host.confirmed).not.toHaveBeenCalled();

    host.blocked.set(false);
    host.busy.set(true);
    rendered.fixture.detectChanges();
    form.dispatchEvent(new Event('submit', { cancelable: true }));
    expect(host.confirmed).not.toHaveBeenCalled();

    host.busy.set(false);
    rendered.fixture.detectChanges();
    form.dispatchEvent(new Event('submit', { cancelable: true }));
    expect(host.confirmed).toHaveBeenCalledTimes(1);
  });

  it('never lets a submit navigate the page', async () => {
    const rendered = await renderHq(DialogHost);
    stubShowModal();
    rendered.fixture.componentInstance.open.set(true);
    rendered.fixture.detectChanges();
    await rendered.fixture.whenStable();

    const submit = new Event('submit', { cancelable: true });
    document.querySelector('form')!.dispatchEvent(submit);

    expect(submit.defaultPrevented).toBe(true);
  });
});
