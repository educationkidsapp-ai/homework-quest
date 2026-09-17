import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { fireEvent, screen, waitFor, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { ADMIN_USER, MANAGERIAL_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { LessonPage } from './lesson.page';

const ADMIN_PERMISSIONS = {
  role: 'ADMIN',
  permissions: ['lesson.read', 'lesson.write', 'lesson.publish', 'lesson.delete', 'play.write', 'stop.write'],
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

function routeFor(id: string, notice?: string): Partial<ActivatedRoute> {
  return {
    snapshot: {
      paramMap: convertToParamMap({ id }),
      queryParamMap: convertToParamMap(notice ? { notice } : {}),
    } as ActivatedRoute['snapshot'],
  };
}

function providersFor(id: string, notice?: string): (Provider | EnvironmentProviders)[] {
  return [
    provideHttpClient(),
    provideHttpClientTesting(),
    provideRouter([{ path: '**', children: [] }]),
    { provide: BASE_PATH, useValue: '' },
    { provide: ActivatedRoute, useValue: routeFor(id, notice) },
  ];
}

/**
 * Signs an Admin in and flushes what every render asks for.
 *
 * Unlike the list page, `LessonPage`'s `getLesson` fetch has no course-chooser gate — it fires
 * the moment the component constructs, before the sign-in the render below has to do by hand.
 * So it is the *first* request the backend sees, not the last.
 */
async function renderLesson(lesson: object, notice?: string) {
  return renderLessonAs(lesson, ADMIN_USER, ADMIN_PERMISSIONS, notice);
}

async function renderLessonAs(
  lesson: object,
  user: typeof ADMIN_USER,
  permissions: typeof ADMIN_PERMISSIONS,
  notice?: string,
) {
  const rendered = await renderHq(LessonPage, { providers: providersFor('l-1', notice) });
  const backend = TestBed.inject(HttpTestingController);

  backend.expectOne('/admin/lessons/l-1').flush(lesson);
  await Promise.resolve();

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(user);
  TestBed.tick();
  backend.expectOne('/me/permissions').flush(permissions);
  await Promise.resolve();

  return { rendered, backend };
}

describe('Lesson', () => {
  beforeEach(() => localStorage.clear());

  it('renders the pipeline steps with their state, and the failed step\'s message in a band', async () => {
    await renderLesson({
      ...BASE_LESSON,
      status: 'error',
      currentStep: 'generate_L2',
      steps: [
        { step: 'upload', status: 'done', attempt: 1, updatedAt: 0 },
        { step: 'analyze', status: 'done', attempt: 1, updatedAt: 0 },
        { step: 'generate_L1', status: 'running', attempt: 1, updatedAt: 0 },
        { step: 'generate_L2', status: 'error', attempt: 1, errorMessage: 'The model timed out.', updatedAt: 0 },
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
    backend.expectOne((req) => req.url === '/admin/lessons/l-1/files' && req.method === 'POST').flush({ jobId: 'j-1', status: 'uploading' });
    backend.expectOne((req) => req.url === '/admin/lessons/l-1/analyze' && req.method === 'POST').flush({ jobId: 'j-1', status: 'analyzing' });
    TestBed.tick();
    await new Promise((resolve) => setTimeout(resolve, 0));
    backend.expectOne('/admin/lessons/l-1').flush({ ...BASE_LESSON, status: 'analyzing' });

    expect(await screen.findByText(/Reading the pages/)).toBeInTheDocument();
  });

  it('refuses to confirm skills with nothing kept, and posts the kept ones otherwise', async () => {
    const { backend } = await renderLesson({
      ...BASE_LESSON,
      status: 'needs_review',
      skills: [{ id: 's-1', name: 'Counting to ten', subject: 'math', method: 'concrete', confidence: 0.9, examples: [], slideNumbers: [] }],
    });

    await screen.findByDisplayValue('Counting to ten');
    fireEvent.click(screen.getByLabelText('Keep'));
    await userEvent.click(await screen.findByRole('button', { name: 'Make the quest' }));
    expect(screen.getByText('Keep at least one skill.')).toBeInTheDocument();

    fireEvent.click(screen.getByLabelText('Keep'));
    await userEvent.click(screen.getByRole('button', { name: 'Make the quest' }));

    const request = backend.expectOne((req) => req.url === '/admin/lessons/l-1/skills' && req.method === 'POST');
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
    const stop = (id: string, title: string) => ({
      id,
      type: 'choice',
      title,
      speak: '',
      ingredient: { emoji: '🥕', name: 'carrot' },
      parentTip: { en: '', ar: '' },
      hint: '',
      question: '',
      options: [],
      correctOptionId: '',
    });
    await renderLesson({
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
            stops: [stop('st-1', 'Pick the bigger number'), stop('st-2', 'What number is missing')],
          },
        },
      ],
    });

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
            stops: [{ id: 'st-1', type: 'choice', title: 'A stop', speak: '', ingredient: { emoji: '🥕', name: 'c' }, parentTip: { en: '', ar: '' }, hint: '', question: '', options: [], correctOptionId: '' }],
          },
        },
      ],
    });

    await userEvent.click(await screen.findByRole('button', { name: 'Publish' }));
    const band = screen.getByRole('alert');
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

  it('shows the wizard\'s notice band from the `?notice=` query key', async () => {
    await renderLesson({ ...BASE_LESSON, status: 'draft' }, 'lessons.new.createdManual');
    expect(screen.getByText("Lesson created — write its questions below.")).toBeInTheDocument();
  });

  it('deletes the lesson from the overflow menu behind a confirm band', async () => {
    const { backend } = await renderLesson(BASE_LESSON);

    await userEvent.click(await screen.findByRole('button', { name: 'Actions for Adding to ten' }));
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Delete' }));
    const band = screen.getByRole('alert');
    await userEvent.click(within(band).getByRole('button', { name: 'Delete' }));

    backend.expectOne((req) => req.url === '/admin/lessons/l-1' && req.method === 'DELETE').flush(null);
    await waitFor(() => expect(screen.queryByText('Delete this lesson?')).not.toBeInTheDocument());
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
