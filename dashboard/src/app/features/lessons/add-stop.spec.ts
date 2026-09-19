import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { screen, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { BASE_PATH } from '../../api';
import { renderHq } from '../../../testing/render';
import { AddStopComponent } from './add-stop.component';

/** The form's own well, so the Save and Cancel buttons in the dialog footer stay out of the way. */
function form() {
  return within(document.querySelector<HTMLElement>('[data-hq-add-stop]')!);
}

/** `[0]` is the form, `[1]` the discard confirm; `open` is what `showModal()` leaves behind. */
function dialogs(): readonly HTMLDialogElement[] {
  return [...document.querySelectorAll('dialog')];
}

/**
 * The save path is two awaited requests, so a signal written after the first response lands in
 * a later microtask than the click. Zoneless change detection needs to be let run before the
 * DOM is asked about it.
 */
async function settle(rendered: { fixture: { detectChanges(): void; whenStable(): Promise<unknown> } }) {
  rendered.fixture.detectChanges();
  await rendered.fixture.whenStable();
  rendered.fixture.detectChanges();
}

async function renderForm() {
  const added = vi.fn();
  const rendered = await renderHq(AddStopComponent, {
    providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: '' }],
    inputs: { open: true, playId: 'p-1', subject: 'math', images: [] },
    on: { added },
  });
  return { rendered, added, backend: TestBed.inject(HttpTestingController) };
}

async function pressSave(rendered: Awaited<ReturnType<typeof renderForm>>['rendered']) {
  await userEvent.click(screen.getByRole('button', { name: /Save the question$/ }));
  await settle(rendered);
}

/** Title, question, type — the three the form refuses to save without. */
async function fill(title = 'Match the pairs', question = 'Join each word to its picture.', type = 'match') {
  await userEvent.type(form().getByLabelText(/^Title/), title);
  await userEvent.type(form().getByLabelText(/^Question \/ what the child does/), question);
  await userEvent.selectOptions(form().getByLabelText(/^Type/), type);
}

describe('hq-add-stop', () => {
  it('is one card, one column, five fields — no tabs, no wizard, no hidden section', async () => {
    await renderForm();

    expect(form().getByLabelText(/^Title/)).toBeInTheDocument();
    expect(form().getByLabelText(/^Question \/ what the child does/)).toBeInTheDocument();
    expect(form().getByLabelText(/^Type/)).toBeInTheDocument();
    expect(form().getByLabelText(/^Picture/)).toBeInTheDocument();
    expect(form().getByText('Parent tip')).toBeInTheDocument();
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
    expect(document.querySelectorAll('details')).toHaveLength(0);
  });

  it('asks the server for nothing when Save is pressed on an empty form, and says what is missing', async () => {
    const { backend, rendered } = await renderForm();

    await pressSave(rendered);

    expect(form().getByText('Give this question a title.')).toBeInTheDocument();
    expect(form().getByText('Write what the child should do.')).toBeInTheDocument();
    expect(form().getByText('Choose the kind of question this is.')).toBeInTheDocument();
    backend.verify();
  });

  it('says what is wrong on blur, not while the first letter is still being typed', async () => {
    const { rendered } = await renderForm();

    await userEvent.click(form().getByLabelText(/^Title/));
    expect(form().queryByText('Give this question a title.')).not.toBeInTheDocument();

    await userEvent.tab();
    await settle(rendered);
    expect(form().getByText('Give this question a title.')).toBeInTheDocument();
  });

  /** The helper and the error are the field's own, bound where a screen reader will read them. */
  it('binds its label, helper and error to the field', async () => {
    const { rendered } = await renderForm();
    const title = form().getByLabelText(/^Title/);

    expect(title.id).not.toBe('');
    expect(document.querySelector(`label[for="${title.id}"]`)).not.toBeNull();
    expect(title.getAttribute('aria-describedby')).toContain(`${title.id}-hint`);

    await pressSave(rendered);
    expect(title.getAttribute('aria-describedby')).toContain(`${title.id}-error`);
    expect(title.getAttribute('aria-invalid')).toBe('true');
  });

  it('creates the chosen type from its template, then converts the teacher’s words', async () => {
    const { backend, added, rendered } = await renderForm();

    await fill();
    await pressSave(rendered);

    const created = backend.expectOne('/admin/plays/p-1/stops');
    expect(created.request.method).toBe('POST');
    expect(JSON.parse(created.request.body as string) as object).toMatchObject({
      type: 'match',
      title: 'Match the pairs',
    });
    created.flush({ id: 'st-9', type: 'match', title: 'Match the pairs' });
    await settle(rendered);

    const converted = backend.expectOne('/admin/stops/st-9/from-text');
    expect(converted.request.method).toBe('POST');
    // Her title leads the text, which is the shape `StopText.describe` reads a stop back in.
    expect((JSON.parse(converted.request.body as string) as { text: string }).text).toBe(
      'Match the pairs\n\nJoin each word to its picture.',
    );
    converted.flush({ id: 'st-9', type: 'match', title: 'Match the pairs' });
    await settle(rendered);

    expect(added).toHaveBeenCalledWith('st-9');
    expect(rendered.fixture.componentInstance.open()).toBe(false);
  });

  it('deletes the template stop again when the conversion is refused, and keeps the words', async () => {
    const { backend, added, rendered } = await renderForm();

    await fill();
    await pressSave(rendered);
    backend
      .expectOne('/admin/plays/p-1/stops')
      .flush({ id: 'st-9', type: 'match', title: 'Match the pairs' });
    await settle(rendered);
    backend
      .expectOne('/admin/stops/st-9/from-text')
      .flush(
        { code: 'rephrase', message: 'Couldn’t save' },
        { status: 422, statusText: 'Unprocessable Entity' },
      );
    await settle(rendered);

    // Nothing half-made: the stop that only existed to be rewritten goes with the refusal.
    expect(backend.expectOne('/admin/stops/st-9').request.method).toBe('DELETE');
    expect(form().getByText("Couldn't save, please rephrase.")).toBeInTheDocument();
    expect(rendered.fixture.componentInstance.open()).toBe(true);
    expect(form().getByLabelText(/^Title/)).toHaveValue('Match the pairs');
    expect(added).not.toHaveBeenCalled();
  });

  it('cannot be submitted twice', async () => {
    const { backend, rendered } = await renderForm();

    await fill();
    await pressSave(rendered);
    // In flight, the primary action is `aria-busy` and disabled — there is no second press to
    // make. Pressing the form's submit again anyway is refused by the guard behind it.
    const save = screen.getByRole('button', { name: /Save the question$/ });
    expect(save).toBeDisabled();
    expect(save).toHaveAttribute('aria-busy', 'true');
    document.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle(rendered);

    expect(backend.match('/admin/plays/p-1/stops')).toHaveLength(1);
  });

  it('asks before throwing away words, and does not ask when there are none', async () => {
    const { rendered } = await renderForm();

    // Esc is the platform's `cancel`, which a guarded dialog refuses on its host's behalf.
    dialogs()[0]!.dispatchEvent(new Event('cancel', { cancelable: true }));
    await settle(rendered);
    expect(dialogs()[1]!.hasAttribute('open')).toBe(false);
    expect(rendered.fixture.componentInstance.open()).toBe(false);

    rendered.fixture.componentInstance.open.set(true);
    await settle(rendered);
    await userEvent.type(form().getByLabelText(/^Title/), 'Half a thought');
    dialogs()[0]!.dispatchEvent(new Event('cancel', { cancelable: true }));
    await settle(rendered);

    expect(dialogs()[1]!.hasAttribute('open')).toBe(true);
    expect(rendered.fixture.componentInstance.open()).toBe(true);

    await userEvent.click(screen.getByRole('button', { name: 'Throw it away' }));
    await settle(rendered);
    expect(rendered.fixture.componentInstance.open()).toBe(false);
  });
});
