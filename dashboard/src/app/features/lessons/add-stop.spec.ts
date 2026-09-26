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
 * E4a's save is fire-and-forget, but the two requests it starts still land on later microtasks
 * than the click. Zoneless change detection needs to be let run before the DOM is asked about it.
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
    inputs: { open: true, lessonId: 'l-1', playId: 'p-1', subject: 'math', images: [] },
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

  /**
   * `Play.schema.json` caps a stop's `title` at 40 on every branch. This form said 80, so a
   * 41-character title passed it and `POST …/plays/{id}/stops` answered 400 on the template
   * document — before the assistant was asked anything. The field now stops at the 40th letter.
   */
  it('cannot produce a title the schema would refuse', async () => {
    const { backend, rendered } = await renderForm();
    const title = form().getByLabelText(/^Title/);

    expect(title).toHaveAttribute('maxlength', '40');
    await userEvent.type(title, 'x'.repeat(41));
    expect((title as HTMLInputElement).value).toHaveLength(40);
    expect(form().getByText(/Up to 40 characters/)).toBeInTheDocument();

    await userEvent.type(form().getByLabelText(/^Question \/ what the child does/), 'Join them up.');
    await userEvent.selectOptions(form().getByLabelText(/^Type/), 'match');
    await pressSave(rendered);

    const created = backend.expectOne('/admin/plays/p-1/stops');
    expect((JSON.parse(created.request.body as string) as { title: string }).title).toHaveLength(40);
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

  it('closes on the click and leaves both calls to the draft service', async () => {
    const { backend, added, rendered } = await renderForm();

    await fill();
    await pressSave(rendered);

    // The sheet is gone before the model has been asked anything — the whole point of E4a.
    expect(rendered.fixture.componentInstance.open()).toBe(false);
    expect(added).toHaveBeenCalledOnce();

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
  });

  /** Ten questions of the same kind, without closing and re-opening the sheet nine times. */
  it('keeps the sheet, the type and the tip on "Save and add another", and empties her words', async () => {
    const { backend, added, rendered } = await renderForm();

    await fill('Which shape has three sides', 'Show a triangle and a circle.', 'choice');
    await userEvent.type(form().getByLabelText(/^Parent tip \(English\)/), 'Count the sides together.');
    await userEvent.click(screen.getByRole('button', { name: 'Save and add another' }));
    await settle(rendered);

    expect(rendered.fixture.componentInstance.open()).toBe(true);
    expect(form().getByLabelText(/^Title/)).toHaveValue('');
    expect(form().getByLabelText(/^Question \/ what the child does/)).toHaveValue('');
    // The type and the tip are the settings for a run of questions, so they stay.
    expect(form().getByLabelText(/^Type/)).toHaveValue('choice');
    expect(form().getByLabelText(/^Parent tip \(English\)/)).toHaveValue('Count the sides together.');
    // And nothing is red: an emptied form she has not submitted again has nothing to complain of.
    expect(form().queryByText('Give this question a title.')).not.toBeInTheDocument();

    // `match` takes the requests off the queue, so each call below is "what arrived since".
    expect(backend.match('/admin/plays/p-1/stops')).toHaveLength(1);
    expect(added).toHaveBeenCalledOnce();

    // The second question is a second draft, not a second attempt at the first.
    await fill('Which shape is round', 'Show the same three shapes.', 'choice');
    await userEvent.click(screen.getByRole('button', { name: 'Save and add another' }));
    await settle(rendered);
    expect(backend.match('/admin/plays/p-1/stops')).toHaveLength(1);
    expect(added).toHaveBeenCalledTimes(2);
  });

  /** The emptied form is its own double-submit guard: there is nothing valid left to send. */
  it('sends nothing on a second press with the words already saved', async () => {
    const { backend, rendered } = await renderForm();

    await fill();
    await userEvent.click(screen.getByRole('button', { name: 'Save and add another' }));
    await settle(rendered);
    await userEvent.click(screen.getByRole('button', { name: 'Save and add another' }));
    await settle(rendered);

    expect(backend.match('/admin/plays/p-1/stops')).toHaveLength(1);
    expect(form().getByText('Give this question a title.')).toBeInTheDocument();
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
