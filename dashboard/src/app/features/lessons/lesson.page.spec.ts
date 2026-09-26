import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { fireEvent, screen, waitFor, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BASE_PATH } from '../../api';
import { ADMIN_USER, MANAGERIAL_USER, TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { ViewModeService } from '../../core/view-mode/view-mode.service';
import { SessionStore } from '../../core/auth/session.store';
import { LessonPage } from './lesson.page';

const ADMIN_PERMISSIONS = {
  role: 'ADMIN',
  permissions: ['lesson.read', 'lesson.write', 'lesson.publish', 'lesson.delete', 'play.write', 'stop.write'],
  readOnly: false,
};

/** The stop editor validates asynchronously; CI's runner is slower than this Mac. */
const VALIDATION_TIMEOUT = 10_000;

const TEACHER_PERMISSIONS = {
  role: 'TEACHER',
  permissions: ['lesson.read', 'lesson.write', 'lesson.publish', 'play.write', 'stop.write'],
  readOnly: false,
};

/** A viewer with `lesson.read` only — every write control is denied. */
const READ_ONLY_PERMISSIONS = { role: 'MANAGERIAL', permissions: ['lesson.read'], readOnly: false };

/** A minimal, contract-shaped `AdminLesson` — the fields every test needs, varied per case. */
const BASE_LESSON = {
  id: 'l-1',
  course: { curriculum: 'british', grade: 1 },
  createdAt: 0,
  date: '2026-09-10',
  files: [],
  images: [],
  plays: [],
  skills: [],
  source: 'pdf',
  status: 'review',
  steps: [],
  subject: 'math',
  title: 'Adding to ten',
  tokenUsage: 0,
  tokensSaved: 0,
  version: 1,
};

function routeFor(id: string, query: Record<string, string> = {}): Partial<ActivatedRoute> {
  return {
    snapshot: {
      paramMap: convertToParamMap({ id }),
      queryParamMap: convertToParamMap(query),
    } as ActivatedRoute['snapshot'],
  };
}

function providersFor(id: string, query: Record<string, string> = {}): (Provider | EnvironmentProviders)[] {
  return [
    provideHttpClient(),
    provideHttpClientTesting(),
    provideRouter([{ path: '**', children: [] }]),
    { provide: BASE_PATH, useValue: '' },
    { provide: ActivatedRoute, useValue: routeFor(id, query) },
  ];
}

/**
 * Signs an Admin in and flushes what every render asks for.
 *
 * Sign-in comes *first* since N2.4: the role picks which family of routes the page reads
 * (`LessonApiService`), so `getLesson` waits for `/me` rather than guessing — an Admin's page
 * must not open by asking `/teacher/lessons/{id}` and getting someone else's 404.
 */
async function renderLesson(lesson: object, notice?: string, query: Record<string, string> = {}) {
  return renderLessonAs(lesson, ADMIN_USER, ADMIN_PERMISSIONS, notice, query);
}

/**
 * CR5: the Raw JSON panel is an Admin's, in debug view, and shut by default even for her. The
 * three JSON tests below are about that panel, so they open it the way the account menu does.
 */
function openRawJson(): void {
  TestBed.inject(ViewModeService).set('debug');
  TestBed.tick();
  document.querySelectorAll('details[data-hq-raw-json]').forEach((el) => el.setAttribute('open', ''));
}

async function renderLessonAs(
  lesson: object,
  user: typeof ADMIN_USER,
  permissions: typeof ADMIN_PERMISSIONS,
  notice?: string,
  query: Record<string, string> = {},
) {
  const providers = providersFor('l-1', { ...(notice ? { notice } : {}), ...query });
  const rendered = await renderHq(LessonPage, { providers });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(user);
  await Promise.resolve();
  TestBed.tick();

  // The lesson comes second and the permissions third: the fetch waits for the role, and
  // `*hqCan` — which is what asks for `/me/permissions` — only exists once the page has a
  // lesson to render instead of its skeleton.
  backend.expectOne(lessonUrlFor(user)).flush(lesson);
  await Promise.resolve();
  TestBed.tick();
  backend.expectOne('/me/permissions').flush(permissions);
  await Promise.resolve();
  TestBed.tick();

  return { rendered, backend };
}

/**
 * A teacher reads the `/teacher/**` aliases; everyone else reads `/admin/**`.
 *
 * MANAGERIAL is on the Admin side, not the teacher one: a manager holds no teaching
 * assignment, so every `/teacher/**` read of hers would 404 — see `LessonApiService.isAdmin`.
 */
function lessonUrlFor(user: typeof ADMIN_USER): string {
  return user.role === 'TEACHER' ? '/teacher/lessons/l-1' : '/admin/lessons/l-1';
}

/** A schema-valid `choice` stop, so the editor's live validation has something real to chew on. */
function choiceStop(id: string, title: string) {
  return {
    id,
    type: 'choice',
    title,
    speak: 'Which one is right?',
    ingredient: { emoji: '🥕', name: 'carrot' },
    parentTip: { en: 'Read it together.', ar: 'اقرآها معًا.' },
    hint: 'Think about the page.',
    question: 'Which one is right?',
    options: [
      { id: 'a', label: 'First' },
      { id: 'b', label: 'Second' },
    ],
    correctOptionId: 'a',
    // CR5: every read carries the stop in English — here the shape `StopText.describe` emits.
    teacherText: `${title}\nPip says: Which one is right?\n\nQuestion: Which one is right?\nOptions:\n- First (correct)\n- Second`,
  };
}

/** Zoneless: a signal written from an HTTP response renders on a later microtask than the flush. */
async function settle(): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
}

/**
 * CR2's form: the three required fields, by the labels a teacher reads.
 *
 * Scoped to the form rather than the page, because the stop editor behind it has a "Title" of
 * its own. A browser makes the page inert while a modal is up; jsdom does not.
 */
async function fillAddStopForm(title: string, question: string, type: string): Promise<void> {
  const form = within(document.querySelector<HTMLElement>('[data-hq-add-stop]')!);
  await userEvent.type(form.getByLabelText(/^Title/), title);
  await userEvent.type(form.getByLabelText(/^Question \/ what the child does/), question);
  await userEvent.selectOptions(form.getByLabelText(/^Type/), type);
}

function lessonWithStops(extra: object = {}) {
  return {
    ...BASE_LESSON,
    status: 'review',
    plays: [
      {
        id: 'p-1',
        level: 1,
        variant: 0,
        generatedAt: 0,
        promptVersion: '1',
        play: {
          kind: 'math',
          level: 1,
          variant: 0,
          theme: { potName: 'Soup', dishName: 'Stew', potEmoji: '🍲', servedText: 'Served!' },
          stops: [choiceStop('st-1', 'Pick the bigger number'), choiceStop('st-2', 'What number is missing')],
        },
      },
    ],
    ...extra,
  };
}

describe('Lesson', () => {
  // `sessionStorage` too: `ViewModeService` remembers debug there, and it would leak into the
  // next test's teacher view, where the whole point is that no JSON is on the page.
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  it("renders the pipeline steps with their state, and the failed step's message in a band", async () => {
    await renderLesson({
      ...BASE_LESSON,
      status: 'error',
      currentStep: 'generate_L2',
      steps: [
        { step: 'upload', status: 'done', attempt: 1, updatedAt: 0 },
        { step: 'analyze', status: 'done', attempt: 1, updatedAt: 0 },
        { step: 'generate_L1', status: 'running', attempt: 1, updatedAt: 0 },
        {
          step: 'generate_L2',
          status: 'error',
          attempt: 1,
          errorMessage: 'The model timed out.',
          updatedAt: 0,
        },
      ],
    });

    const strip = screen.getByRole('list', { name: 'Lesson pipeline' });
    expect(within(strip).getByText('generate L2').closest('li')).toHaveClass('steps__step--error');
    expect(within(strip).getByText('generate L1').closest('li')).toHaveClass('steps__step--running');
    expect(within(strip).getByText('upload').closest('li')).toHaveClass('steps__step--done');

    expect(screen.getByText('The model timed out.')).toBeInTheDocument();
    // `*hqCan` renders these once `/me/permissions` has settled, a tick after everything else.
    expect(await screen.findByRole('button', { name: 'Retry and continue' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry this step only' })).toBeInTheDocument();
  });

  it('retries optimistically and rolls the status back if the retry itself fails', async () => {
    const { backend } = await renderLesson({
      ...BASE_LESSON,
      status: 'error',
      currentStep: 'generate_L2',
      steps: [{ step: 'generate_L2', status: 'error', attempt: 1, errorMessage: 'boom', updatedAt: 0 }],
    });

    expect(screen.getByText('Failed')).toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', { name: 'Retry and continue' }));

    expect(screen.getByText(/Uploading/)).toBeInTheDocument();
    backend
      .expectOne((req) => req.url === '/admin/lessons/l-1/retry' && req.method === 'POST')
      .flush(null, { status: 500, statusText: 'Server Error' });
    await Promise.resolve();

    expect(await screen.findByText('Failed')).toBeInTheDocument();
  });

  it('retries only the failed step', async () => {
    const { backend } = await renderLesson({
      ...BASE_LESSON,
      status: 'error',
      currentStep: 'generate_L2',
      steps: [{ step: 'generate_L2', status: 'error', attempt: 1, updatedAt: 0 }],
    });

    await userEvent.click(await screen.findByRole('button', { name: 'Retry this step only' }));
    backend
      .expectOne((req) => req.url === '/admin/lessons/l-1/steps/generate_L2/retry' && req.method === 'POST')
      .flush({ jobId: 'j-1', status: 'generating' });

    expect(await screen.findByText(/Generating/)).toBeInTheDocument();
  });

  it('replaces the file: delete, then upload, then analyze', async () => {
    const { backend } = await renderLesson({
      ...BASE_LESSON,
      status: 'error',
      source: 'pdf',
      steps: [{ step: 'analyze', status: 'error', attempt: 1, updatedAt: 0 }],
    });

    await userEvent.click(await screen.findByRole('button', { name: 'Replace file' }));
    const input = screen.getByLabelText('Choose files');
    const file = new File(['%PDF-1.4'], 'lesson.pdf', { type: 'application/pdf' });
    fireEvent.change(input, { target: { files: [file] } });

    // The three API calls are one synchronous stack of `.subscribe()` calls, each firing the
    // next request the moment the previous one flushes; the final `reload()` that re-fetches
    // the lesson is the resource's own async scheduling, so it needs a tick of its own.
    backend.expectOne((req) => req.url === '/admin/lessons/l-1/files' && req.method === 'DELETE').flush(null);
    backend
      .expectOne((req) => req.url === '/admin/lessons/l-1/files' && req.method === 'POST')
      .flush({ jobId: 'j-1', status: 'uploading' });
    backend
      .expectOne((req) => req.url === '/admin/lessons/l-1/analyze' && req.method === 'POST')
      .flush({ jobId: 'j-1', status: 'analyzing' });
    TestBed.tick();
    await new Promise((resolve) => setTimeout(resolve, 0));
    backend.expectOne('/admin/lessons/l-1').flush({ ...BASE_LESSON, status: 'analyzing' });

    expect(await screen.findByText(/Reading the pages/)).toBeInTheDocument();
  });

  it('refuses to confirm skills with nothing kept, and posts the kept ones otherwise', async () => {
    const { backend } = await renderLesson({
      ...BASE_LESSON,
      status: 'needs_review',
      skills: [
        {
          id: 's-1',
          name: 'Counting to ten',
          subject: 'math',
          method: 'concrete',
          confidence: 0.9,
          examples: [],
          slideNumbers: [],
        },
      ],
    });

    await screen.findByDisplayValue('Counting to ten');
    fireEvent.click(screen.getByLabelText('Keep'));
    await userEvent.click(await screen.findByRole('button', { name: 'Make the quest' }));
    expect(screen.getByText('Keep at least one skill.')).toBeInTheDocument();

    fireEvent.click(screen.getByLabelText('Keep'));
    await userEvent.click(screen.getByRole('button', { name: 'Make the quest' }));

    const request = backend.expectOne(
      (req) => req.url === '/admin/lessons/l-1/skills' && req.method === 'POST',
    );
    expect(JSON.parse(request.request.body as string)).toEqual([
      { id: 's-1', name: 'Counting to ten', subject: 'math', method: 'concrete' },
    ]);
    request.flush({ jobId: 'j-1', status: 'generating' });
    TestBed.tick();
    await new Promise((resolve) => setTimeout(resolve, 0));
    backend.expectOne('/admin/lessons/l-1').flush({ ...BASE_LESSON, status: 'generating' });
    expect(await screen.findByText(/Generating/)).toBeInTheDocument();
  });

  it('switches the play tab and selects a stop, driving the pinned preview', async () => {
    await renderLesson(lessonWithStops());

    // The listbox pattern (`ui/tabs/tabs.component.ts`'s roving tabindex, applied to the stop
    // list): `aria-selected` and `tabindex` follow the selection, not just a CSS class.
    expect(screen.getAllByText('Pick the bigger number').length).toBeGreaterThan(0);
    const first = screen.getByRole('option', { name: /Pick the bigger number/ });
    const second = screen.getByRole('option', { name: /What number is missing/ });
    expect(first).toHaveAttribute('aria-selected', 'true');
    expect(first).toHaveAttribute('tabindex', '0');
    expect(second).toHaveAttribute('aria-selected', 'false');
    expect(second).toHaveAttribute('tabindex', '-1');

    await userEvent.click(second);
    expect(first).toHaveAttribute('aria-selected', 'false');
    expect(first).toHaveAttribute('tabindex', '-1');
    expect(second).toHaveAttribute('aria-selected', 'true');
    expect(second).toHaveAttribute('tabindex', '0');
  });

  // ---- N2.4a: the stop editor, manual authoring and the parent panel ------------------------

  it('saves the selected stop, quick field and JSON staying one document', async () => {
    const { backend } = await renderLesson(lessonWithStops());

    openRawJson();

    const title = screen.getByLabelText(/^Title/);
    await userEvent.clear(title);
    await userEvent.type(title, 'Pick the biggest');

    // The quick field rewrote the JSON, so the textarea is the thing that gets PUT.
    const json: HTMLTextAreaElement = screen.getByLabelText(/The whole stop/);
    expect(JSON.parse(json.value)).toMatchObject({ title: 'Pick the biggest' });

    // Ajv and the schema load on demand behind a 250 ms debounce, so "valid" lands a few
    // hundred milliseconds late — longer on a CI runner than on this Mac.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save the stop' })).toBeEnabled(), {
      timeout: VALIDATION_TIMEOUT,
    });
    await userEvent.click(screen.getByRole('button', { name: 'Save the stop' }));

    const request = backend.expectOne('/admin/stops/st-1');
    expect(request.request.method).toBe('PUT');
    expect(JSON.parse(request.request.body as string)).toMatchObject({
      id: 'st-1',
      title: 'Pick the biggest',
    });
  });

  /** Only the declared type's branch is reported, and Save stays off until it passes. */
  it('refuses to save a stop the schema would reject, naming the missing field only', async () => {
    await renderLesson(lessonWithStops());
    openRawJson();

    const json = screen.getByLabelText(/The whole stop/);
    const { question, ...withoutQuestion } = JSON.parse((json as HTMLTextAreaElement).value) as Record<
      string,
      unknown
    >;
    expect(question).toBeDefined();
    await userEvent.clear(json);
    await userEvent.paste(JSON.stringify(withoutQuestion, null, 2));

    const error = await screen.findByRole('alert', {}, { timeout: VALIDATION_TIMEOUT });
    expect(error).toHaveTextContent('question');
    expect(error).not.toHaveTextContent('statement');
    expect(screen.getByRole('button', { name: 'Save the stop' })).toBeDisabled();
  });

  it('will not save a stop whose id was edited, because the server addresses it by that id', async () => {
    await renderLesson(lessonWithStops());
    openRawJson();

    const json = screen.getByLabelText(/The whole stop/);
    const parsed = JSON.parse((json as HTMLTextAreaElement).value) as Record<string, unknown>;
    await userEvent.clear(json);
    await userEvent.paste(JSON.stringify({ ...parsed, id: 'st-renamed' }, null, 2));

    expect(await screen.findByRole('alert', {}, { timeout: VALIDATION_TIMEOUT })).toHaveTextContent(
      'The id cannot change',
    );
    expect(screen.getByRole('button', { name: 'Save the stop' })).toBeDisabled();
  });

  // ---- CR5: the stop is prose, and the JSON is the server's problem ------------------------

  it('shows the stop in English and posts the rewritten text to from-text', async () => {
    const { backend } = await renderLesson(lessonWithStops());

    // The read-only rendering above the field, as elements rather than a data format: the
    // description's `Options:` run has become a real list.
    const rendering = document.querySelector('[data-hq-stop-prose]')!;
    expect(rendering.textContent).toContain('Pip says: Which one is right?');
    expect(
      within(rendering as HTMLElement)
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual(['First (correct)', 'Second']);

    // Teacher view, which is everybody's default: no JSON on the page at all.
    expect(screen.queryByLabelText(/The whole stop/)).toBeNull();
    expect(document.querySelector('[data-hq-raw-json]')).toBeNull();

    const prose = screen.getByLabelText(/This stop, in your words/);
    await userEvent.clear(prose);
    await userEvent.type(prose, 'Which shape has three sides?');

    const save = screen.getByRole('button', { name: 'Save the stop' });
    expect(save).toBeEnabled();
    await userEvent.click(save);

    const request = backend.expectOne('/admin/stops/st-1/from-text');
    expect(request.request.method).toBe('POST');
    expect(JSON.parse(request.request.body as string)).toEqual({ text: 'Which shape has three sides?' });

    // E4a: the answer *is* the saved stop, so it replaces that one row — the lesson is not
    // re-read, and her level, her scroll and any other draft's row state survive the save.
    request.flush({ ...choiceStop('st-1', 'Which shape'), teacherText: 'Which shape has three sides?' });
    await settle();
    backend.expectNone(lessonUrlFor(ADMIN_USER));
    expect(screen.getByRole('option', { name: /Which shape/ })).toBeInTheDocument();
    expect(screen.getByLabelText(/This stop, in your words/)).toHaveValue('Which shape has three sides?');
  });

  it('answers a 422 with "please rephrase" and keeps the text she wrote', async () => {
    const { backend } = await renderLesson(lessonWithStops());

    const prose: HTMLTextAreaElement = screen.getByLabelText(/This stop, in your words/);
    await userEvent.clear(prose);
    await userEvent.type(prose, 'Draw a picture of whatever you like');
    await userEvent.click(screen.getByRole('button', { name: 'Save the stop' }));

    backend
      .expectOne('/admin/stops/st-1/from-text')
      .flush(
        { code: 'rephrase', message: "Couldn't save, please rephrase. #/options: minItems" },
        { status: 422, statusText: 'Unprocessable Entity' },
      );
    await Promise.resolve();
    TestBed.tick();

    expect(screen.getByText("Couldn't save, please rephrase.")).toBeInTheDocument();
    // The validator's own lines are not a teacher's problem, and never reach her.
    expect(screen.queryByText(/minItems/)).toBeNull();
    expect(prose.value).toBe('Draw a picture of whatever you like');
  });

  it('answers the 400 for a lesson still generating with the wait-for-the-pipeline message', async () => {
    const { backend } = await renderLesson(lessonWithStops());

    const prose = screen.getByLabelText(/This stop, in your words/);
    await userEvent.clear(prose);
    await userEvent.type(prose, 'Count the apples');
    await userEvent.click(screen.getByRole('button', { name: 'Save the stop' }));

    backend
      .expectOne('/admin/stops/st-1/from-text')
      .flush(
        { code: 'bad_request', message: 'Wait for this lesson to finish generating before editing a stop.' },
        { status: 400, statusText: 'Bad Request' },
      );
    await Promise.resolve();
    TestBed.tick();

    expect(screen.getByText(/Wait for this lesson to finish generating/)).toBeInTheDocument();
  });

  it('shows the Raw JSON panel, and the last 422\u2019s validator lines, only in debug view', async () => {
    const { backend } = await renderLesson(lessonWithStops());

    const prose = screen.getByLabelText(/This stop, in your words/);
    await userEvent.clear(prose);
    await userEvent.type(prose, 'Draw anything');
    await userEvent.click(screen.getByRole('button', { name: 'Save the stop' }));
    backend
      .expectOne('/admin/stops/st-1/from-text')
      .flush(
        {
          code: 'rephrase',
          message: "Couldn't save, please rephrase. #/options: minItems 2; #/hint: required",
        },
        { status: 422, statusText: 'Unprocessable Entity' },
      );
    await Promise.resolve();
    TestBed.tick();

    expect(screen.queryByText(/minItems/)).toBeNull();

    openRawJson();
    expect(screen.getByLabelText(/The whole stop/)).toBeInTheDocument();
    expect(screen.getByText('#/options: minItems 2')).toBeInTheDocument();
    expect(screen.getByText('#/hint: required')).toBeInTheDocument();
  });

  /**
   * CR2's form, E4a's timing: the sheet is shut before the model is asked anything, the new stop
   * is in the list with "The assistant is writing…" on it, and the answer replaces that one row.
   */
  it('adds a stop from the form without waiting for the assistant, and offers no template menu', async () => {
    const { backend } = await renderLesson(lessonWithStops());

    await userEvent.click(screen.getByRole('button', { name: '+ Add stop' }));
    expect(screen.queryByRole('menuitem', { name: 'Match pairs' })).not.toBeInTheDocument();

    await fillAddStopForm('Match the pairs', 'Join each word to its picture.', 'match');
    await userEvent.click(screen.getByRole('button', { name: /Save the question$/ }));
    await settle();

    // Shut, and the toast already said so — with the two calls still in flight.
    expect(document.querySelector('[data-hq-add-stop]')).toBeNull();
    expect(screen.getByText('Question added').getAttribute('role')).toBe('status');

    const created = backend.expectOne('/admin/plays/p-1/stops');
    expect(created.request.method).toBe('POST');
    expect(JSON.parse(created.request.body as string) as { type: string; title: string }).toMatchObject({
      type: 'match',
      title: 'Match the pairs',
    });
    created.flush(choiceStop('st-9', 'Match the pairs'));
    await settle();

    // In the list straight away, selected, and saying what is happening to it.
    const row = screen.getByRole('option', { name: /Match the pairs/ });
    expect(row).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByText('The assistant is writing…')).toBeInTheDocument();

    backend
      .expectOne('/admin/stops/st-9/from-text')
      .flush({ ...choiceStop('st-9', 'Match each word'), teacherText: 'Match each word to its picture.' });
    await settle();

    // One stop patched, no lesson re-read.
    expect(screen.queryByText('The assistant is writing…')).toBeNull();
    expect(screen.getByRole('option', { name: /Match each word/ })).toBeInTheDocument();
    backend.expectNone('/admin/lessons/l-1');
  });

  it('keeps a refused question on its row, with Retry and Remove, and says so once', async () => {
    const { backend } = await renderLesson(lessonWithStops());

    await userEvent.click(screen.getByRole('button', { name: '+ Add stop' }));
    await fillAddStopForm('Draw anything', 'Let them draw whatever they like.', 'match');
    await userEvent.click(screen.getByRole('button', { name: /Save the question$/ }));
    await settle();
    backend.expectOne('/admin/plays/p-1/stops').flush(choiceStop('st-9', 'Draw anything'));
    await settle();
    backend
      .expectOne('/admin/stops/st-9/from-text')
      .flush(
        { code: 'rephrase', message: "Couldn't save, please rephrase. #/options: minItems 2" },
        { status: 422, statusText: 'Unprocessable Entity' },
      );
    await settle();

    // The stop is still hers — title and all — and the row carries the refusal.
    expect(screen.getByRole('option', { name: /Draw anything/ })).toBeInTheDocument();
    backend.expectNone('/admin/stops/st-9');
    expect(screen.getAllByText("Couldn't save, please rephrase.").length).toBeGreaterThan(0);

    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await settle();
    const again = backend.expectOne('/admin/stops/st-9/from-text');
    expect((JSON.parse(again.request.body as string) as { text: string }).text).toBe(
      'Draw anything\n\nLet them draw whatever they like.',
    );
    again.flush(choiceStop('st-9', 'Draw a triangle'));
    await settle();
    expect(screen.getByRole('option', { name: /Draw a triangle/ })).toBeInTheDocument();
  });

  /** E4a deliverable 1: "Create and write the questions" lands on the sheet, not on an empty list. */
  it('opens with the Add question sheet up when it was navigated to with ?compose=1', async () => {
    await renderLesson(lessonWithStops({ source: 'manual' }), 'lessons.new.createdManual', {
      compose: '1',
    });

    expect(document.querySelector('[data-hq-add-stop]')).not.toBeNull();
    // And the three plain steps stand where a manual lesson's empty pipeline strip used to.
    expect(screen.getByText('3. Questions')).toBeInTheDocument();
    expect(screen.queryByRole('list', { name: /pipeline/i })).not.toBeInTheDocument();
  });

  /** D27: an empty Level 2 offers both ways to fill it, and "Write it myself" opens the sheet. */
  it('offers Add level on an empty level, and opens the sheet on the play it just made', async () => {
    const { backend } = await renderLesson(lessonWithStops({ source: 'manual' }));

    await userEvent.click(screen.getByRole('tab', { name: /Level 2/ }));
    expect(screen.getByText(/This level is empty/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Let the assistant write it' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Write it myself' }));
    const created = backend.expectOne('/admin/lessons/l-1/plays');
    expect(created.request.body).toBe('{"level":2,"variant":0}');
    created.flush({ id: 'p-2', level: 2, variant: 0, generatedAt: 0, promptVersion: '1', play: {} });
    await settle();

    // A new level changes the lesson's own publish readiness, so this one is re-read.
    const withTwo = lessonWithStops({ source: 'manual' });
    withTwo.plays.push({
      id: 'p-2',
      level: 2,
      variant: 0,
      generatedAt: 0,
      promptVersion: '1',
      play: { kind: 'math', level: 2, variant: 0, theme: withTwo.plays[0]!.play.theme, stops: [] },
    });
    backend.expectOne('/admin/lessons/l-1').flush(withTwo);
    await settle();
    expect(document.querySelector('[data-hq-add-stop]')).not.toBeNull();
  });

  it('deletes a stop only behind the red confirm band, and offers no Undo', async () => {
    const { backend } = await renderLesson(lessonWithStops());

    await userEvent.click(screen.getByRole('button', { name: 'Delete this stop' }));
    expect(screen.getByText(/goes for good/)).toBeInTheDocument();
    backend.expectNone('/admin/stops/st-1');

    await userEvent.click(screen.getByRole('button', { name: 'Delete the stop' }));
    expect(backend.expectOne('/admin/stops/st-1').request.method).toBe('DELETE');
    expect(screen.queryByRole('button', { name: /^Undo/ })).not.toBeInTheDocument();
  });

  /** Dragging is the gesture; the payload is the whole new order, which is what the server takes. */
  it('posts the reordered stop ids when a row is dropped', async () => {
    const { rendered, backend } = await renderLesson(lessonWithStops());
    const page = rendered.fixture.componentInstance as unknown as {
      dropStop(event: { previousIndex: number; currentIndex: number }): void;
    };

    page.dropStop({ previousIndex: 1, currentIndex: 0 });
    const request = backend.expectOne('/admin/plays/p-1/order');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toBe('{"stopIds":["st-2","st-1"]}');
  });

  it('skips the step strip for a manual lesson and offers "Generate the other levels" instead', async () => {
    const { backend } = await renderLesson(
      lessonWithStops({
        source: 'manual',
        steps: [{ step: 'upload', status: 'done', attempt: 1, updatedAt: 0 }],
      }),
    );

    expect(screen.queryByRole('list', { name: /pipeline/i })).not.toBeInTheDocument();
    await userEvent.type(
      screen.getByLabelText(/What this lesson is about/),
      'Adding to ten with number bonds.',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Generate the levels' }));

    const request = backend.expectOne('/admin/lessons/l-1/generate-from-text');
    expect(request.request.body).toBe('{"text":"Adding to ten with number bonds."}');
  });

  it('a teacher reads and writes the /teacher aliases, never /admin', async () => {
    const { backend } = await renderLessonAs(lessonWithStops(), TEACHER_USER, TEACHER_PERMISSIONS);

    await userEvent.click(screen.getByRole('button', { name: '+ Add stop' }));
    await fillAddStopForm('Is it true?', 'Say whether ten is bigger than five.', 'trueFalse');
    await userEvent.click(screen.getByRole('button', { name: 'Save the question' }));

    backend.expectOne('/teacher/plays/p-1/stops');
    backend.expectNone('/admin/plays/p-1/stops');
    // No teacher alias for clearing files, so the button that would 404 is simply not there.
    expect(screen.queryByRole('button', { name: /Remove all/i })).not.toBeInTheDocument();
  });

  it('publishes behind a confirm band and shows the notice after', async () => {
    const { backend } = await renderLesson({
      ...BASE_LESSON,
      status: 'review',
      source: 'manual',
      plays: [
        {
          id: 'p-1',
          level: 1,
          variant: 0,
          generatedAt: 0,
          promptVersion: '1',
          play: {
            kind: 'math',
            level: 1,
            variant: 0,
            theme: { potName: 'Soup', dishName: 'Stew', potEmoji: '🍲', servedText: 'Served!' },
            stops: [
              {
                id: 'st-1',
                type: 'choice',
                title: 'A stop',
                speak: '',
                ingredient: { emoji: '🥕', name: 'c' },
                parentTip: { en: '', ar: '' },
                hint: '',
                question: '',
                options: [],
                correctOptionId: '',
              },
            ],
          },
        },
      ],
    });

    await userEvent.click(await screen.findByRole('button', { name: 'Publish' }));
    // By its title, not "the one alert on the page": the stop editor's schema errors are
    // announced the same way, and whether one has settled by now is a matter of timing.
    const band = screen.getByRole('alert', { name: 'Publish this lesson?' });
    expect(within(band).getByText('Publish this lesson?')).toBeInTheDocument();
    await userEvent.click(within(band).getByRole('button', { name: 'Publish' }));

    backend
      .expectOne((req) => req.url === '/admin/lessons/l-1/publish' && req.method === 'POST')
      .flush({ ...BASE_LESSON, status: 'published' });

    expect(
      await screen.findByText('Published — every child on the course sees the island on its day.'),
    ).toBeInTheDocument();
  });

  it('unpublishes immediately and offers a 10 s Undo that republishes', async () => {
    const { backend } = await renderLesson({ ...BASE_LESSON, status: 'published' });

    await userEvent.click(await screen.findByRole('button', { name: 'Unpublish' }));
    backend
      .expectOne((req) => req.url === '/admin/lessons/l-1/unpublish' && req.method === 'POST')
      .flush({ ...BASE_LESSON, status: 'review' });

    await userEvent.click(await screen.findByRole('button', { name: 'Undo' }));

    backend
      .expectOne((req) => req.url === '/admin/lessons/l-1/publish' && req.method === 'POST')
      .flush({ ...BASE_LESSON, status: 'published' });
    // The status word sits inside the subtitle line ("British · Grade 1 · … · Published"),
    // not as a text node of its own, so this matches the substring rather than the whole line.
    expect(await screen.findByText(/Published/)).toBeInTheDocument();
  });

  it("shows the wizard's notice band from the `?notice=` query key", async () => {
    await renderLesson({ ...BASE_LESSON, status: 'draft' }, 'lessons.new.createdManual');
    expect(screen.getByText('Lesson created — write its questions below.')).toBeInTheDocument();
  });

  it('deletes the lesson from the overflow menu behind a confirm band', async () => {
    // A draft: N2.4b gates the menu on the status the server will actually accept a delete for.
    const { backend } = await renderLesson({ ...BASE_LESSON, status: 'draft' });

    await userEvent.click(await screen.findByRole('button', { name: 'Actions for Adding to ten' }));
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Delete' }));
    const band = screen.getByRole('alert');
    await userEvent.click(within(band).getByRole('button', { name: 'Delete' }));

    backend.expectOne((req) => req.url === '/admin/lessons/l-1' && req.method === 'DELETE').flush(null);
    await waitFor(() => expect(screen.queryByText('Delete this lesson?')).not.toBeInTheDocument());
  });

  // ---- N2.4b: the publish sheet, the lifecycle, the badge, the day --------------------------

  /**
   * §8's sheet. A teacher publishing a Grade 1 Math lesson is offered her *other* Grade 1 Math
   * sections and nothing else — 2C · Math is a different course and 1B · English a different
   * subject, and a copy in either would be a lesson nobody asked for.
   */
  const SARA_CLASSES = [
    { classId: 'c-1a', className: '1A', curriculum: 'british', grade: 1, subject: 'math' },
    { classId: 'c-1b', className: '1B', curriculum: 'british', grade: 1, subject: 'math' },
    { classId: 'c-1b-en', className: '1B', curriculum: 'british', grade: 1, subject: 'english' },
    { classId: 'c-2c', className: '2C', curriculum: 'british', grade: 2, subject: 'math' },
  ];

  const READY_TEACHER_LESSON = {
    ...BASE_LESSON,
    source: 'manual',
    classId: 'c-1a',
    className: '1A',
    plays: [
      {
        id: 'p-1',
        level: 1,
        variant: 0,
        play: {
          kind: 'math',
          level: 1,
          variant: 0,
          theme: { potName: 'Soup', dishName: 'Stew', potEmoji: '🍲', servedText: 'Served!' },
          stops: [
            {
              id: 'st-1',
              type: 'choice',
              title: 'A stop',
              speak: '',
              ingredient: { emoji: '🥕', name: 'c' },
              parentTip: { en: '', ar: '' },
              hint: '',
              question: '',
              options: [],
              correctOptionId: '',
            },
          ],
        },
      },
    ],
  };

  it('publishes a teacher lesson to her own class plus the siblings she ticks', async () => {
    const { backend } = await renderLessonAs(READY_TEACHER_LESSON, TEACHER_USER, TEACHER_PERMISSIONS);

    await userEvent.click(await screen.findByRole('button', { name: 'Publish' }));
    backend.expectOne('/teacher/classes').flush(SARA_CLASSES);
    await waitFor(() => expect(screen.getByLabelText('1B')).toBeInTheDocument());

    // Her own class is the title, never a checkbox; 1B · English and 2C · Math are not siblings.
    expect(screen.getByText('Publish to 1A on Sep 10, 2026')).toBeInTheDocument();
    expect(screen.queryByLabelText('1A')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('2C')).not.toBeInTheDocument();
    expect(screen.getAllByLabelText('1B')).toHaveLength(1);

    await userEvent.click(screen.getByLabelText('1B'));
    const sheet = screen.getByRole('dialog');
    await userEvent.click(within(sheet).getByRole('button', { name: 'Publish' }));

    const published = backend.expectOne((req) => req.url === '/teacher/lessons/l-1/publish');
    expect(published.request.body).toEqual({ classIds: ['c-1a', 'c-1b'] });
    published.flush([
      { classId: 'c-1a', lessonId: 'l-1', version: 2 },
      { classId: 'c-1b', lessonId: 'l-9', version: 1 },
    ]);
    await waitFor(() =>
      backend
        .expectOne('/teacher/lessons/l-1')
        .flush({ ...READY_TEACHER_LESSON, status: 'published', version: 2 }),
    );

    // One link per copy, named after its class: the point of the fan-out is the other class.
    expect(await screen.findByRole('link', { name: '1B' })).toHaveAttribute('href', '/teacher/lessons/l-9');
    expect(screen.getByRole('link', { name: '1A' })).toHaveAttribute('href', '/teacher/lessons/l-1');
  });

  it('keeps the plain confirm band for an Admin, who has no classes to fan out to', async () => {
    await renderLesson({ ...BASE_LESSON, source: 'manual', plays: READY_TEACHER_LESSON.plays });

    await userEvent.click(await screen.findByRole('button', { name: 'Publish' }));
    expect(screen.getByRole('alert', { name: 'Publish this lesson?' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('offers Delete on a draft only — a written lesson is unpublished, never deleted', async () => {
    const withDelete = {
      ...TEACHER_PERMISSIONS,
      permissions: [...TEACHER_PERMISSIONS.permissions, 'lesson.delete'],
    };
    await renderLessonAs({ ...BASE_LESSON, status: 'draft' }, TEACHER_USER, withDelete);
    expect(await screen.findByRole('button', { name: 'Actions for Adding to ten' })).toBeInTheDocument();

    // `review` is what an unpublished lesson falls back to, and the server answers 409 on a
    // delete of one — the screen must not offer what the endpoint refuses.
    TestBed.resetTestingModule();
    await renderLessonAs({ ...BASE_LESSON, status: 'review' }, TEACHER_USER, withDelete);
    await screen.findByText('Files');
    expect(screen.queryByRole('button', { name: 'Actions for Adding to ten' })).not.toBeInTheDocument();

    TestBed.resetTestingModule();
    await renderLessonAs({ ...BASE_LESSON, status: 'published' }, TEACHER_USER, withDelete);
    expect(await screen.findByRole('button', { name: 'Unpublish' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Actions for Adding to ten' })).not.toBeInTheDocument();
  });

  it('moves an unpublished lesson to another day optimistically, and rolls back on failure', async () => {
    const { backend } = await renderLessonAs(BASE_LESSON, TEACHER_USER, TEACHER_PERMISSIONS);

    const day = await screen.findByLabelText('Lesson day');
    fireEvent.input(day, { target: { value: '2026-09-14' } });
    expect(day).toHaveValue('2026-09-14');

    const moved = backend.expectOne((req) => req.url === '/teacher/lessons/l-1' && req.method === 'PATCH');
    expect(moved.request.body).toEqual({ date: '2026-09-14' });
    moved.flush({ code: 'conflict', message: 'That day is taken.' }, { status: 409, statusText: 'Conflict' });

    // Rolled back, not left showing a day the server refused.
    await waitFor(() => expect(screen.getByLabelText('Lesson day')).toHaveValue('2026-09-10'));
  });

  it('fixes the day of a published lesson, and never offers a date control for an Admin', async () => {
    await renderLessonAs({ ...BASE_LESSON, status: 'published' }, TEACHER_USER, TEACHER_PERMISSIONS);
    await screen.findByRole('button', { name: 'Unpublish' });
    expect(screen.queryByLabelText('Lesson day')).not.toBeInTheDocument();

    TestBed.resetTestingModule();
    await renderLesson(BASE_LESSON);
    await screen.findByText('Files');
    expect(screen.queryByLabelText('Lesson day')).not.toBeInTheDocument();
  });

  it('badges a cached analysis and puts what it saved in the tooltip', async () => {
    await renderLesson({ ...BASE_LESSON, analyzedBefore: true, tokensSaved: 14_200 });

    const badge = await screen.findByText('Analyzed before · 0 tokens');
    expect(badge).toHaveAttribute(
      'title',
      'This file was analyzed before, so the result came back instantly and saved 14200 tokens.',
    );
  });

  it('offers Preview as child on a ready lesson, pointing at the player route', async () => {
    await renderLessonAs(READY_TEACHER_LESSON, TEACHER_USER, TEACHER_PERMISSIONS);

    expect(await screen.findByRole('link', { name: 'Preview as child' })).toHaveAttribute(
      'href',
      '/player/gallery?lesson=l-1',
    );
  });

  it('gives a viewer without lesson.write no way to reach the file input, hidden or not', async () => {
    await renderLessonAs(BASE_LESSON, MANAGERIAL_USER, READ_ONLY_PERMISSIONS);

    // The label `*hqCan` was already hiding; the regression was the `<input type="file">`
    // sitting outside that guard, reachable via `hq-sr-only` even with no visible button.
    await screen.findByText('Files');
    expect(screen.queryByText('Upload more')).not.toBeInTheDocument();
    expect(document.querySelector('#lesson-upload-more')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Actions for Adding to ten' })).not.toBeInTheDocument();
  });
});

/**
 * CR4: the convert step, and the one reason the page keeps asking after the job is over.
 *
 * Fake timers rather than a wait: the poll is 2.5 s, and the thing under test is *whether* a
 * second request is made at all — waiting for one that must never come is a test that can only
 * be slow or wrong.
 */
describe('Lesson — the files being converted', () => {
  /** `GET …/lessons/{id}/status` as the server answers it for a given lesson fixture. */
  function statusOf(lesson: {
    status: string;
    files: readonly { id?: string; convertStatus?: string }[];
    steps: readonly { step: string; status: string }[];
  }) {
    return {
      status: lesson.status,
      steps: lesson.steps.map((step) => ({ ...step, attempt: 1, updatedAt: 0 })),
      files: lesson.files.map((file) => ({ id: file.id, convertStatus: file.convertStatus })),
      plays: [],
      panel: false,
      updatedAt: 0,
    };
  }

  const CONVERTING = {
    ...BASE_LESSON,
    status: 'draft',
    files: [
      { id: 'f-1', fileName: 'Shapes.pdf', fileHash: 'h', pageCount: 4, cacheHit: false, deleted: false, convertStatus: 'converting' },
    ],
    steps: [
      { step: 'upload', status: 'done', attempt: 1, updatedAt: 0 },
      { step: 'convert', status: 'running', attempt: 1, updatedAt: 0 },
      { step: 'analyze', status: 'pending', attempt: 1, updatedAt: 0 },
    ],
  };

  const READY = {
    ...CONVERTING,
    files: [{ ...CONVERTING.files[0], convertStatus: 'ready', convertMethod: 'anydoc', markdownChars: 6200 }],
    steps: [
      { step: 'upload', status: 'done', attempt: 1, updatedAt: 0 },
      { step: 'convert', status: 'done', attempt: 1, updatedAt: 0 },
      { step: 'analyze', status: 'pending', attempt: 1, updatedAt: 0 },
    ],
  };

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('names the new step in the strip, between Upload and Analyze', async () => {
    await renderLesson(READY);

    const strip = await screen.findByRole('list', { name: 'Lesson pipeline' });
    expect(within(strip).getAllByRole('listitem').map((step) => step.textContent?.trim())).toEqual([
      expect.stringContaining('upload'),
      expect.stringContaining('Convert to text'),
      expect.stringContaining('analyze'),
    ]);
  });

  it('keeps asking while a file is still converting, though the lesson itself is idle', async () => {
    // Before the render: the poll's `setInterval` is scheduled by an effect that runs during it,
    // and a timer created on the real clock is not one `advanceTimersByTime` can reach.
    vi.useFakeTimers();
    const { backend } = await renderLesson(CONVERTING);

    // E3: the poll asks the light body. The first one is the baseline — the page already holds
    // the lesson it describes, so nothing is fetched twice.
    vi.advanceTimersByTime(2_600);
    await Promise.resolve();
    backend.expectOne('/admin/lessons/l-1/status').flush(statusOf(CONVERTING));
    await Promise.resolve();
    TestBed.tick();
    backend.verify();

    // The file finishes converting: the signature moves, and *that* is what reads the lesson
    // back in full.
    vi.advanceTimersByTime(2_600);
    await Promise.resolve();
    backend.expectOne('/admin/lessons/l-1/status').flush(statusOf(READY));
    await Promise.resolve();
    TestBed.tick();
    backend.expectOne('/admin/lessons/l-1').flush(READY);
    await Promise.resolve();
    TestBed.tick();

    // Ready is terminal: the timer that would have fired again is gone.
    vi.advanceTimersByTime(10_000);
    await Promise.resolve();
    backend.verify();
    expect(await screen.findByText('Ready · 1,240 words')).toBeInTheDocument();
  });

  it('reads the lesson back when the very first poll already shows it finished', async () => {
    vi.useFakeTimers();
    const { backend } = await renderLesson(CONVERTING);

    // The stranding case: the file finishes inside the first 2.5 s window. The baseline is the
    // lesson the page loaded, not this poll, so the change is seen \u2014 a baseline taken here
    // would have recorded "ready" as the starting point, reloaded nothing, and left the page
    // drawing a running pipeline and polling for ever.
    vi.advanceTimersByTime(2_600);
    await Promise.resolve();
    backend.expectOne('/admin/lessons/l-1/status').flush(statusOf(READY));
    await Promise.resolve();
    TestBed.tick();
    backend.expectOne('/admin/lessons/l-1').flush(READY);
    await Promise.resolve();
    TestBed.tick();

    // And the timer is gone with it: nothing is converting any more.
    vi.advanceTimersByTime(10_000);
    await Promise.resolve();
    backend.verify();
    expect(await screen.findByText('Ready \u00b7 1,240 words')).toBeInTheDocument();
  });

  it('says what went wrong at Convert in the teacher\u2019s words, not the converter\u2019s', async () => {
    await renderLesson({
      ...CONVERTING,
      status: 'error',
      files: [{ ...CONVERTING.files[0], convertStatus: 'error', convertErrorCode: 'encrypted' }],
      steps: [
        { step: 'upload', status: 'done', attempt: 1, updatedAt: 0 },
        {
          step: 'convert',
          status: 'error',
          attempt: 1,
          updatedAt: 0,
          errorCode: 'encrypted',
          errorMessage: 'Conversion failed. (anydoc exited 1: encrypted document)',
        },
      ],
    });

    // Twice, and both are wanted: the red band under the strip, and the file's own row.
    expect(
      await screen.findAllByText(
        'This PDF is password-protected. Remove the password and upload again, or type the text.',
      ),
    ).toHaveLength(2);
    expect(screen.queryByText(/anydoc/)).not.toBeInTheDocument();
  });
});
