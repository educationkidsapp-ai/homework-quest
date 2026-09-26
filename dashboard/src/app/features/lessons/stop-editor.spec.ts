import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { BASE_PATH } from '../../api';
import { ADMIN_USER, TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { ViewModeService } from '../../core/view-mode/view-mode.service';
import type { Stop } from '../../ui/phone-preview';
import { StopEditorComponent } from './stop-editor.component';

/** `VALIDATE_DEBOUNCE_MS` plus room: long enough that a load that was going to happen has. */
const PAST_DEBOUNCE_MS = 400;

async function pastTheDebounce(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, PAST_DEBOUNCE_MS));
  TestBed.tick();
}

const STOP = {
  id: 'st-1',
  type: 'trueFalse',
  title: 'True or false?',
  speak: 'Alan carries the bowl.',
  ingredient: { emoji: '🥕', name: 'carrot' },
  parentTip: { en: 'Read it together.', ar: 'اقرآها معًا.' },
  hint: 'Page 5 tells us.',
  statement: 'Alan carries the bowl.',
  answer: true,
  teacherText: 'True or false?\nPip says: Alan carries the bowl.\n\nThe statement is true.',
} as unknown as Stop;

/**
 * Signed in with `stop.write`, because Save sits behind `*hqCan`.
 *
 * `debug` signs an Admin in instead and opens the Raw JSON panel — `ViewModeService` allows the
 * mode for ADMIN and for nobody else, so a teacher cannot be put into it even by a test.
 */
async function renderEditor({ debug = false }: { debug?: boolean } = {}) {
  const saved = vi.fn();
  const textSaved = vi.fn();
  const rendered = await renderHq(StopEditorComponent, {
    providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: '' }],
    inputs: { stop: STOP },
    on: { saved, textSaved },
  });

  const backend = TestBed.inject(HttpTestingController);
  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(debug ? ADMIN_USER : TEACHER_USER);
  TestBed.tick();
  backend.expectOne('/me/permissions').flush({
    role: debug ? 'ADMIN' : 'TEACHER',
    permissions: ['lesson.read', 'stop.write'],
    readOnly: false,
  });
  await Promise.resolve();
  TestBed.tick();
  if (debug) {
    TestBed.inject(ViewModeService).set('debug');
    TestBed.tick();
  }
  await rendered.fixture.whenStable();

  return { rendered, saved, textSaved };
}

describe('Stop editor', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  it('opens on the stop in English, with no JSON anywhere for a teacher', async () => {
    await renderEditor();

    const prose: HTMLTextAreaElement = screen.getByLabelText(/This stop, in your words/);
    expect(prose.value).toBe(STOP.teacherText);
    expect(screen.queryByLabelText(/The whole stop/)).toBeNull();
    expect(document.querySelector('[data-hq-raw-json]')).toBeNull();
  });

  it('keeps teacherText out of the document the Raw JSON panel shows an Admin', async () => {
    await renderEditor({ debug: true });

    // `Play.schema.json` is `additionalProperties: false`: left in, the document never validates.
    const json: HTMLTextAreaElement = screen.getByLabelText(/The whole stop/);
    expect(JSON.parse(json.value)).not.toHaveProperty('teacherText');
  });

  /**
   * The two saves take different inputs — Prompt D is handed the *stored* JSON — so being dirty
   * in both halves at once is the one state that could silently drop an edit. It cannot happen.
   */
  it('locks the quick fields while the prose is dirty, and the prose while a field is', async () => {
    await renderEditor();
    const prose = screen.getByLabelText(/This stop, in your words/);
    const title = screen.getByLabelText(/^Title/);

    await userEvent.type(prose, ' Ask it again.');
    expect(title).toBeDisabled();

    await userEvent.clear(prose);
    await userEvent.type(prose, STOP.teacherText!);
    expect(title).toBeEnabled();

    await userEvent.type(title, '!');
    expect(prose).toBeDisabled();
    expect(screen.getByText(/Save or undo the fields below/)).toBeInTheDocument();
  });

  it('emits the text rather than a document when the prose is what changed', async () => {
    const { textSaved, saved } = await renderEditor();

    const prose = screen.getByLabelText(/This stop, in your words/);
    await userEvent.clear(prose);
    await userEvent.type(prose, 'Is the bowl hot?');
    await userEvent.click(screen.getByRole('button', { name: 'Save the stop' }));

    expect(textSaved).toHaveBeenCalledWith('Is the bowl hot?');
    expect(saved).not.toHaveBeenCalled();
  });

  it('saves a quick field with no schema validator loaded', async () => {
    const { saved } = await renderEditor();

    await userEvent.type(screen.getByLabelText(/^Title/), '!');
    await pastTheDebounce();

    // No Ajv on this path (see `stop-editor-chunk.spec.ts`): the bounds on the five fields are
    // checked in the component instead, so Save still works.
    const save = screen.getByRole('button', { name: 'Save the stop' });
    expect(save).toBeEnabled();
    await userEvent.click(save);
    expect(saved).toHaveBeenCalledOnce();
  });

  it('refuses a quick field the schema would reject, naming it, with no validator loaded', async () => {
    await renderEditor();

    await userEvent.clear(screen.getByLabelText(/^What the pot says/));
    await pastTheDebounce();

    const save = screen.getByRole('button', { name: 'Save the stop' });
    expect(save).toBeDisabled();
    // Twice over: the band under the fields, and the disabled button's own reason.
    expect(save).toHaveAccessibleDescription(/What the pot says is empty or too long/);
    expect(screen.getAllByText(/is empty or too long/).length).toBeGreaterThan(0);
  });

  it('refuses an empty text, and says what to do instead of failing at the server', async () => {
    await renderEditor();

    await userEvent.clear(screen.getByLabelText(/This stop, in your words/));
    const save = screen.getByRole('button', { name: 'Save the stop' });
    expect(save).toBeDisabled();
    expect(save).toHaveAccessibleDescription(/Write what this stop should do first/);
  });

});
