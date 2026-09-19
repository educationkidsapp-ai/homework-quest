import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { screen, waitFor } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { BASE_PATH, type SourceFileInfo } from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { BandService } from '../../core/band/band.service';
import { SessionStore } from '../../core/auth/session.store';
import { LessonSourcesComponent } from './lesson-sources.component';

/**
 * A contract-shaped `SourceFileInfo`, overridden with plain wire values.
 *
 * `Record<string, unknown>` rather than `Partial<SourceFileInfo>`: the generator gives
 * `convertStatus` a nominal string enum, and a test that writes `'ready'` — which is exactly
 * what the wire carries — would not type-check against it. The cast at the end is the one place
 * that is admitted, instead of an enum import in every case.
 */
function file(over: Record<string, unknown> = {}): SourceFileInfo {
  return {
    id: 'f-1',
    fileName: 'Shapes.pdf',
    fileHash: 'h',
    pageCount: 4,
    cacheHit: false,
    deleted: false,
    convertStatus: 'pending',
    ...over,
  } as SourceFileInfo;
}

const LESSON = { id: 'l-1', status: 'analyzing', files: [], steps: [] };

/**
 * Answers `/me/permissions` **if** it has been asked for.
 *
 * `*hqCan` is what asks, and it only exists where a write control does: a list whose files all
 * converted has none, and the preview's "Edit the text" appears only once the dialog is open —
 * so the request arrives at different moments in different tests, and in some not at all.
 * `expectOne` would fail on the tests that never write.
 */
function flushPermissions(backend: HttpTestingController): void {
  for (const request of backend.match('/me/permissions')) {
    request.flush({ role: 'TEACHER', permissions: ['lesson.read', 'lesson.write'], readOnly: false });
  }
  TestBed.tick();
}

async function renderSources(files: readonly SourceFileInfo[], checkNow = false) {
  const lessonChanged = vi.fn();
  const rendered = await renderHq(LessonSourcesComponent, {
    providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: '' }],
    inputs: { lessonId: 'l-1', files, checkNow },
    on: { lessonChanged },
  });

  const backend = TestBed.inject(HttpTestingController);
  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(TEACHER_USER);
  TestBed.tick();
  flushPermissions(backend);
  await Promise.resolve();
  TestBed.tick();
  await rendered.fixture.whenStable();

  return { rendered, backend, lessonChanged };
}

describe('Lesson sources — the conversion a teacher sees', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  it('says a file is converting, out loud, and offers nothing to press', async () => {
    await renderSources([file({ convertStatus: 'converting' })]);

    expect(screen.getByText('Converting…')).toBeInTheDocument();
    // The live region exists from the first render, which is what makes the change audible.
    expect(document.querySelector('[aria-live="polite"]')).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'Preview text' })).toBeNull();
  });

  it('counts the words of a ready file and says how it was read', async () => {
    await renderSources([file({ convertStatus: 'ready', convertMethod: 'anydoc', markdownChars: 6200 })]);

    expect(screen.getByText('Ready · 1,240 words')).toBeInTheDocument();
    expect(screen.getByText('read as text')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Preview text' })).toBeInTheDocument();
  });

  it('names the moment to check what the AI will read', async () => {
    await renderSources([file({ convertStatus: 'ready', markdownChars: 100 })], true);

    expect(screen.getByText(/This is the moment to check/)).toBeInTheDocument();
  });

  it('shows the preview as a page, with no Markdown symbols and no markup', async () => {
    const { backend } = await renderSources([file({ convertStatus: 'ready', markdownChars: 100 })]);

    await userEvent.click(screen.getByRole('button', { name: 'Preview text' }));
    backend
      .expectOne('/teacher/lessons/l-1/files/f-1/markdown')
      .flush('## Page 1\n\n**Shapes** we know\n\n- a circle\n\n<script>alert(1)</script>');
    await waitFor(() => expect(screen.getByText('Page 1')).toBeInTheDocument());

    expect(screen.getByRole('heading', { name: 'Page 1' })).toBeInTheDocument();
    expect(screen.getByText('Shapes we know')).toBeInTheDocument();
    expect(screen.getByText('a circle')).toBeInTheDocument();
    // The tag is characters on the page, not an element in it.
    expect(document.querySelector('[data-hq-markdown-preview] script')).toBeNull();
    expect(screen.getByText(/<script>alert\(1\)<\/script>/)).toBeInTheDocument();
  });

  it('gives the failure one friendly sentence and the two ways out', async () => {
    await renderSources([file({ convertStatus: 'error', convertErrorCode: 'needs_ocr' })]);

    expect(screen.getByText("Couldn't read this file")).toBeInTheDocument();
    expect(screen.getByText(/The pages are pictures, not text/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Read with OCR' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Type the text' })).toBeInTheDocument();
  });

  it('offers only typing where OCR could not help', async () => {
    await renderSources([file({ convertStatus: 'error', convertErrorCode: 'encrypted' })]);

    expect(screen.queryByRole('button', { name: 'Read with OCR' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Type the text' })).toBeInTheDocument();
  });

  it('asks for OCR with an empty body and hands the whole lesson back', async () => {
    const { backend, lessonChanged } = await renderSources([
      file({ convertStatus: 'error', convertErrorCode: 'ocr_failed' }),
    ]);

    await userEvent.click(screen.getByRole('button', { name: 'Read with OCR' }));
    const call = backend.expectOne('/teacher/lessons/l-1/files/f-1/retry-conversion?method=ocr');
    expect(call.request.method).toBe('POST');
    expect(call.request.body).toBe('{}');
    call.flush(LESSON);
    await waitFor(() => expect(lessonChanged).toHaveBeenCalledWith(LESSON));
  });

  it('saves typed text as the markdown body, and will not send it twice', async () => {
    const { backend, lessonChanged } = await renderSources([
      file({ convertStatus: 'error', convertErrorCode: 'encrypted' }),
    ]);

    await userEvent.click(screen.getByRole('button', { name: 'Type the text' }));
    const box = await screen.findByLabelText('The text of this file');
    await userEvent.type(box, '# Shapes');

    const save = screen.getByRole('button', { name: 'Save this text' });
    await userEvent.click(save);
    // The second press lands while the first is in flight: one request, not two.
    await userEvent.click(save);

    const call = backend.expectOne('/teacher/lessons/l-1/files/f-1/retry-conversion?method=text');
    expect(call.request.body).toBe(JSON.stringify({ markdown: '# Shapes' }));
    call.flush(LESSON);
    await waitFor(() => expect(lessonChanged).toHaveBeenCalledTimes(1));
  });

  it('prefills the box from the text already in hand when she edits a ready file', async () => {
    const { backend } = await renderSources([file({ convertStatus: 'ready', markdownChars: 40 })]);

    await userEvent.click(screen.getByRole('button', { name: 'Preview text' }));
    backend.expectOne('/teacher/lessons/l-1/files/f-1/markdown').flush('# Shapes\n\nA circle is round.');
    // The dialog is where this list's first write control lives, so this is where `*hqCan` asks.
    flushPermissions(backend);
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Shapes' })).toBeInTheDocument());

    await userEvent.click(await screen.findByRole('button', { name: 'Edit the text' }));
    const box: HTMLTextAreaElement = await screen.findByLabelText('The text of this file');
    // No second fetch: the words are already on the screen she was looking at.
    backend.verify();
    expect(box.value).toBe('# Shapes\n\nA circle is round.');
  });

  it("puts a failed retry in the red band, never the server's own sentence", async () => {
    const { backend } = await renderSources([file({ convertStatus: 'error', convertErrorCode: 'needs_ocr' })]);

    await userEvent.click(screen.getByRole('button', { name: 'Read with OCR' }));
    backend
      .expectOne('/teacher/lessons/l-1/files/f-1/retry-conversion?method=ocr')
      .flush(
        { code: 'convert_failed', message: 'Conversion failed. (anydoc exited 3: {"pages":[1,2]})' },
        { status: 500, statusText: 'Server Error' },
      );
    await waitFor(() => expect(TestBed.inject(BandService).current()).not.toBeNull());

    const band = TestBed.inject(BandService).current()!;
    expect(band.variant).toBe('error');
    expect(band.message).toContain('Conversion failed.');
    // CR5's rule, which is what this is really asserting: no JSON reaches a teacher's screen.
    expect(band.message).not.toMatch(/[{}[\]"]|pages/);
    // Pressing again is possible: the busy flag is cleared by the failure.
    expect(screen.getByRole('button', { name: 'Read with OCR' })).toBeEnabled();
  });
});
