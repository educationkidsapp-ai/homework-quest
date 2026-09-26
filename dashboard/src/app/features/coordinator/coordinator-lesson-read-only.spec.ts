import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { COORDINATOR_USER, TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { BASE_PATH } from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { LessonPage } from '../lessons/lesson.page';

/** R2: her two keys and nothing else — no `lesson.*`, no `play.write`, no `stop.write`. */
const COORDINATOR_PERMISSIONS = {
  role: 'COORDINATOR',
  permissions: ['coordinator.read', 'coordinator.lesson.read'],
  readOnly: false,
};

/** The teacher who owns this lesson — the negative control's account. */
const TEACHER_PERMISSIONS = {
  role: 'TEACHER',
  permissions: ['lesson.read', 'lesson.write', 'lesson.publish', 'play.write', 'stop.write'],
  readOnly: false,
};

/**
 * The controls this spec is about, by the label a person reads.
 *
 * Copied from `en.json` rather than paraphrased, because the review found the first version of
 * this spec querying names that do not exist — `/Move to another day/i` for a control labelled
 * "Lesson day", `/^Add a question/i` for one labelled "+ Add stop" — so it matched nothing
 * whatever the page drew and passed with the date input on screen. `rendersForATeacher` below is
 * the guard against that happening again: every name here is proved to match something.
 */
const WRITE_CONTROLS = ['+ Add stop', 'Publish', 'Regenerate this level'] as const;

/**
 * Not a write — and still not hers.
 *
 * "Preview text" reads `GET /teacher/lessons/{id}/files/{fileId}/markdown`, which has no
 * coordinator alias and no coordinator route to have one. The call is wrapped in `silentErrors()`,
 * so her 403 was swallowed and the dialog told her the text "isn't ready yet … or type the text" —
 * wrong twice over. It is in its own constant because it is closed by `readOnly` rather than by a
 * permission she lacks, which is the distinction the two tests below are about.
 */
const PREVIEW_TEXT = 'Preview text';
const DATE_LABEL = 'Lesson day';

/**
 * A lesson in `review` with one level and one question on it — the state with the most write
 * controls on the teacher's copy of this page, which is what makes it the right one to prove
 * none of them is drawn here.
 */
const LESSON = {
  id: 'l-1',
  classId: 'c-1',
  className: '1A',
  course: { curriculum: 'british', grade: 1 },
  createdAt: 0,
  date: '2026-09-10',
  // One converted file, so "Preview text" has a row to render on. Without it the assertion below
  // would be vacuous — the same trap the review caught the first time round.
  files: [
    {
      id: 'f-1',
      fileName: 'page-1.pdf',
      fileHash: 'h1',
      pageCount: 2,
      cacheHit: false,
      deleted: false,
      convertStatus: 'ready',
      convertMethod: 'text',
      markdownChars: 420,
    },
  ],
  images: [],
  skills: [],
  source: 'pdf',
  status: 'review',
  steps: [],
  subject: 'math',
  teacherName: 'Sara Al Harbi',
  title: 'Adding to ten',
  tokenUsage: 0,
  tokensSaved: 0,
  version: 1,
  // The shape `ParentPanel` actually declares — `parent-panel-editor` reads `objectives.en`
  // straight off it, so a plausible-looking stand-in crashes the teacher's copy of the page.
  parentPanel: {
    objectives: { en: ['Add to ten.'], ar: ['الجمع إلى عشرة.'] },
    supported: [],
    challenge: [],
    stopTips: [],
    modelAnswers: [],
  },
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
            id: 's-1',
            type: 'choice',
            title: 'Which is five?',
            speak: 'Which one is five?',
            ingredient: { emoji: '🥕', name: 'carrot' },
            parentTip: { en: 'Count together.', ar: 'عُدّا معًا.' },
            hint: 'Look at the page.',
            question: 'Which one is five?',
            options: [
              { id: 'a', label: 'Five' },
              { id: 'b', label: 'Four' },
            ],
            correctOptionId: 'a',
          },
        ],
      },
    },
  ],
};

/**
 * R5's read-only lesson (`docs/coordinator-flow.md` §5).
 *
 * The page is the teacher's own — reusing it is the point, so that what a coordinator reads is
 * literally what the teacher wrote rather than a second rendering of it that could drift. What
 * this spec pins is the *mode*: `data.readOnly` on the route (`core/nav/screens.ts`) plus the
 * permissions she actually holds must leave the screen with no control that would write.
 *
 * Asserted by absence on purpose, and by name rather than by count: a control that came back
 * would come back with its own label, and a count would pass the day two of them were swapped.
 */
describe('the lesson page in read-only mode', () => {
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    // Fake timers so the poll test can jump past four poll windows rather than wait them out.
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });

  afterEach(() => vi.useRealTimers());

  /** Her own namespace, never `/admin/lessons/{id}` (403) or `/teacher/lessons/{id}` (404). */
  function renderAsCoordinator(lesson: object = LESSON) {
    return renderPage(true, COORDINATOR_USER, COORDINATOR_PERMISSIONS, '/coordinator/lessons/l-1', lesson);
  }

  function renderAsTeacher(lesson: object = LESSON) {
    return renderPage(false, TEACHER_USER, TEACHER_PERMISSIONS, '/teacher/lessons/l-1', lesson);
  }

  async function renderPage(
    readOnly: boolean,
    user: typeof COORDINATOR_USER,
    permissions: typeof COORDINATOR_PERMISSIONS,
    url: string,
    lesson: object,
  ) {
    const rendered = await renderHq(LessonPage, {
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: '**', children: [] }]),
        { provide: BASE_PATH, useValue: '' },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap({ id: 'l-1' }),
              queryParamMap: convertToParamMap({}),
              data: { readOnly },
            },
          },
        },
      ],
    });
    const backend = TestBed.inject(HttpTestingController);

    TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
    TestBed.inject(AuthService).loadMe().subscribe();
    backend.expectOne('/me').flush(user);
    await Promise.resolve();
    TestBed.tick();

    backend.expectOne(url).flush(lesson);
    await Promise.resolve();
    TestBed.tick();
    backend.expectOne('/me/permissions').flush(permissions);
    await Promise.resolve();
    TestBed.tick();

    return { rendered, backend };
  }

  it('reads the lesson through /coordinator/lessons/{id} and shows what it says', async () => {
    const { backend } = await renderAsCoordinator();

    expect(screen.getByRole('heading', { level: 1 }).textContent).toContain('Adding to ten');
    // The question is there to read — the whole point of her opening this. More than once,
    // because the stop list names it and the phone preview captions it.
    expect(screen.getAllByText('Which is five?').length).toBeGreaterThan(0);
    backend.verify();
  });

  it('draws no control that would change the lesson', async () => {
    await renderAsCoordinator();

    for (const name of WRITE_CONTROLS)
      expect(`${name}:${screen.queryAllByRole('button', { name }).length}`).toBe(`${name}:0`);

    // The one the review found: "Lesson day" is an `<input type="date">`, not a button, and
    // changing it would have called `PATCH /teacher/lessons/{id}` — a 403 for her, after the page
    // had already moved the date optimistically. Absent, and no date input of any kind is left.
    expect(screen.queryByLabelText(DATE_LABEL)).toBeNull();
    expect(document.querySelectorAll('input[type="date"]').length).toBe(0);

    // And the one read that is not a write: see PREVIEW_TEXT.
    expect(screen.queryAllByRole('button', { name: PREVIEW_TEXT })).toEqual([]);
    expect(document.querySelectorAll('[data-hq-preview]').length).toBe(0);

    // No editor, no parent-panel form, no exam settings, and no file input anywhere: hidden
    // rather than disabled, because a disabled Save still promises there is a way to press it.
    expect(document.querySelector('hq-stop-editor')).toBeNull();
    expect(document.querySelector('hq-parent-panel-editor')).toBeNull();
    expect(document.querySelector('hq-exam-settings-card')).toBeNull();
    expect(document.querySelector('hq-add-stop')).toBeNull();
    expect(document.querySelectorAll('input[type="file"]').length).toBe(0);
  });

  /**
   * The negative control: the same page, the same lesson, rendered for the teacher who owns it.
   *
   * Every name the test above asserts is *absent* is asserted **present** here. Without this, a
   * label that drifts — or one that was wrong to begin with, which is what happened — turns the
   * test above into a green assertion about nothing.
   */
  it('renders every one of those controls for the teacher who owns the lesson', async () => {
    await renderAsTeacher();

    for (const name of WRITE_CONTROLS)
      expect(`${name}:${screen.queryAllByRole('button', { name }).length}`).not.toBe(`${name}:0`);
    expect(screen.queryByLabelText(DATE_LABEL)).not.toBeNull();
    expect(screen.queryAllByRole('button', { name: PREVIEW_TEXT })).not.toEqual([]);
    expect(document.querySelector('hq-stop-editor')).not.toBeNull();
    expect(document.querySelector('hq-parent-panel-editor')).not.toBeNull();
  });

  /** R5: no `/status` poll in read-only mode — there is no coordinator route to poll. */
  it('polls nothing, and refreshes the lesson when she asks', async () => {
    const { backend } = await renderAsCoordinator({ ...LESSON, status: 'generating' });

    expect(screen.getByText(/still being generated/i)).toBeTruthy();
    // 2.5 s is the poll interval; a poll would have asked by now if one had been started.
    vi.advanceTimersByTime(10_000);
    backend.verify(); // no `/status` request of any kind — the teacher's alias 404s for her

    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    backend.expectOne('/coordinator/lessons/l-1').flush(LESSON);
    await Promise.resolve();
    TestBed.tick();

    expect(screen.queryByText(/still being generated/i)).toBeNull();
  });
});
