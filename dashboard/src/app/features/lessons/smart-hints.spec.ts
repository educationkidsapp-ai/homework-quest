import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { screen, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { BASE_PATH } from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { FlagService } from '../../core/flags/flag.service';
import { LessonPage } from './lesson.page';

/**
 * Feature 30 on the teacher's lesson page: the "Hints and practice" section.
 *
 * Rendered through {@link LessonPage} rather than against the component on its own, because two
 * of the things being asserted belong to the page — the `smartHints` gate (`*hqFeature`) and the
 * fact that the section's own read never happens with the flag off — and a spec that mounted
 * `hq-smart-hints` directly would have proved neither.
 *
 * `FlagService` is stubbed: the real one fetches `GET /schools/{id}/flags` through a resource,
 * and the flag being on or off is the parameter of half of these tests.
 */

const TEACHER_PERMISSIONS = {
  role: 'TEACHER',
  permissions: ['lesson.read', 'lesson.write', 'lesson.publish', 'play.write', 'stop.write'],
  readOnly: false,
};

/** A schema-valid `choice` stop — what the practice preview actually draws. */
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
  };
}

const THEME = { potName: 'Soup', dishName: 'Stew', potEmoji: '🍲', servedText: 'Served!' };

const LESSON = {
  id: 'l-1',
  classId: 'c-1',
  course: { curriculum: 'british', grade: 1 },
  createdAt: 0,
  date: '2026-09-10',
  files: [],
  images: [],
  skills: [],
  source: 'pdf',
  status: 'review',
  steps: [],
  subject: 'math',
  title: 'Adding to ten',
  tokenUsage: 0,
  tokensSaved: 0,
  version: 1,
  plays: [
    {
      id: 'p-1',
      level: 1,
      variant: 0,
      generatedAt: 0,
      promptVersion: '1',
      play: { kind: 'math', level: 1, variant: 0, theme: THEME, stops: [choiceStop('st-1', 'Pick the bigger number')] },
    },
  ],
};

/** `SmartHintsView`: two stops — one signed, one not — and one practice set, unapproved. */
const VIEW = {
  lessonId: 'l-1',
  hints: [
    {
      stopId: 'st-1',
      level: 1,
      variant: 0,
      stopType: 'choice',
      question: 'Which number is bigger, 7 or 4?',
      answer: '7',
      hints: ['Count up from four on your fingers.', 'The one further along the line is bigger.'],
      approved: false,
    },
    {
      stopId: 'st-2',
      level: 2,
      variant: 0,
      stopType: 'number',
      question: 'What is 3 + 4?',
      answer: '7',
      hints: ['Start at three and hop four times.', 'Use the number line on the page.'],
      approved: true,
      approvedBy: 'u-sara',
      approvedAt: 1,
    },
  ],
  practice: [
    {
      playId: 'pp-1',
      skillId: 'sk-1',
      skillName: 'Adding within ten',
      variant: 2,
      approved: false,
      play: {
        id: 'pp-1',
        kind: 'math',
        level: 1,
        variant: 2,
        theme: THEME,
        stops: [choiceStop('ps-1', 'Two more carrots'), choiceStop('ps-2', 'Three more carrots')],
      },
    },
  ],
};

function providersFor(flagOn: boolean): (Provider | EnvironmentProviders)[] {
  return [
    provideHttpClient(),
    provideHttpClientTesting(),
    provideRouter([{ path: '**', children: [] }]),
    { provide: BASE_PATH, useValue: '' },
    { provide: FlagService, useValue: { isOn: () => flagOn, ready: () => true, flags: () => ({}) } },
    {
      provide: ActivatedRoute,
      useValue: {
        snapshot: {
          paramMap: convertToParamMap({ id: 'l-1' }),
          queryParamMap: convertToParamMap({}),
          data: { readOnly: false },
        } as unknown as ActivatedRoute['snapshot'],
      },
    },
  ];
}

/** Signs the teacher in, flushes the lesson and her permissions, and returns the backend. */
async function renderPage(flagOn = true) {
  await renderHq(LessonPage, { providers: providersFor(flagOn) });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(TEACHER_USER);
  await settle();

  backend.expectOne('/teacher/lessons/l-1').flush(LESSON);
  await settle();
  backend.expectOne('/me/permissions').flush(TEACHER_PERMISSIONS);
  await settle();

  return backend;
}

/** The hints half of the section, by the name its heading gives the region. */
function hintsRegion() {
  return within(screen.getByRole('region', { name: 'Hints, two per question' }));
}

/** Zoneless: a signal written from an HTTP response renders on a later microtask than the flush. */
async function settle(): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
}

async function renderWithHints(view: object = VIEW) {
  const backend = await renderPage();
  backend.expectOne('/teacher/lessons/l-1/smart-hints').flush(view);
  await settle();
  return backend;
}

describe('Hints and practice', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  it('lists every stop with its question, its two hints and its approval state', async () => {
    await renderWithHints();

    expect(screen.getByText(/Which number is bigger, 7 or 4\?/)).toBeInTheDocument();
    // The answer is on screen so she can tell a hint that gives it away without opening the stop.
    expect(screen.getAllByText(/Never say: 7/)).toHaveLength(2);

    expect(screen.getByLabelText('Question 1, hint 1')).toHaveValue('Count up from four on your fingers.');
    expect(screen.getByLabelText('Question 1, hint 2')).toHaveValue(
      'The one further along the line is bigger.',
    );
    expect(screen.getByLabelText('Question 2, hint 1')).toHaveValue('Start at three and hop four times.');

    // One signed, one not — in words, not only as a tick.
    expect(hintsRegion().getAllByText('Not approved yet')).toHaveLength(1);
    expect(hintsRegion().getAllByText('Approved')).toHaveLength(1);

    // And the lesson's promise, once, at the top of the section.
    expect(
      screen.getByText('Only approved hints and practice reach children, at the next publish.'),
    ).toBeInTheDocument();
  });

  it('saves one stop’s two hints with a PUT, and the cleared approval comes back with it', async () => {
    const backend = await renderWithHints();

    const second = screen.getByLabelText('Question 2, hint 1');
    await userEvent.clear(second);
    await userEvent.type(second, 'Start at three.');
    await userEvent.click(screen.getByRole('button', { name: 'Save the hints for question 2' }));

    const put = backend.expectOne(
      (request) =>
        request.url === '/teacher/lessons/l-1/smart-hints/st-2' && request.method === 'PUT',
    );
    expect(JSON.parse(put.request.body as string)).toEqual({
      hints: ['Start at three.', 'Use the number line on the page.'],
    });

    put.flush({ ...VIEW.hints[1], hints: ['Start at three.', 'Use the number line on the page.'], approved: false });
    await settle();

    // Both rows unapproved now: editing cleared the one that was signed.
    expect(hintsRegion().getAllByText('Not approved yet')).toHaveLength(2);
    expect(hintsRegion().queryByText('Approved')).not.toBeInTheDocument();
  });

  it('shows the server’s 400 under the two fields it is about', async () => {
    const backend = await renderWithHints();

    const first = screen.getByLabelText('Question 1, hint 1');
    await userEvent.clear(first);
    await userEvent.type(first, 'The answer is 7.');
    await userEvent.click(screen.getByRole('button', { name: 'Save the hints for question 1' }));

    backend
      .expectOne('/teacher/lessons/l-1/smart-hints/st-1')
      .flush(
        { code: 'bad_request', message: 'A hint may not contain the answer.' },
        { status: 400, statusText: 'Bad Request' },
      );
    await settle();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('A hint may not contain the answer.');

    // Typing again clears it: the sentence was about the wording she has just changed.
    await userEvent.type(first, ' ok');
    await settle();
    expect(screen.queryByText('A hint may not contain the answer.')).not.toBeInTheDocument();
  });

  it('approves every stop at once with no stopIds in the body', async () => {
    const backend = await renderWithHints();

    await userEvent.click(screen.getByRole('button', { name: 'Approve all hints' }));

    const post = backend.expectOne(
      (request) =>
        request.url === '/teacher/lessons/l-1/smart-hints/approve' && request.method === 'POST',
    );
    // Omitted, not null and not an empty array: that is what the server reads as "all of them".
    expect(JSON.parse(post.request.body as string)).toEqual({});

    post.flush({
      ...VIEW,
      hints: VIEW.hints.map((row) => ({ ...row, approved: true, approvedBy: 'u-sara', approvedAt: 2 })),
    });
    await settle();

    expect(hintsRegion().queryByText('Not approved yet')).not.toBeInTheDocument();
    expect(hintsRegion().getAllByText('Approved')).toHaveLength(2);
  });

  it('approves one stop by name', async () => {
    const backend = await renderWithHints();

    await userEvent.click(screen.getByRole('button', { name: 'Approve the hints for question 1' }));

    const post = backend.expectOne('/teacher/lessons/l-1/smart-hints/approve');
    expect(JSON.parse(post.request.body as string)).toEqual({ stopIds: ['st-1'] });
  });

  it('previews a practice set read-only and approves it', async () => {
    const backend = await renderWithHints();

    expect(screen.getByText('Practice for Adding within ten')).toBeInTheDocument();
    // Its stops are listed, and the first is previewed without anything being clicked.
    expect(screen.getByRole('button', { name: 'Two more carrots' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByRole('group', { name: 'Practice question preview' })).toBeInTheDocument();
    expect(screen.getByText('Two more carrots', { selector: 'figcaption' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Three more carrots' }));
    await settle();
    expect(screen.getByRole('button', { name: 'Three more carrots' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByText('Three more carrots', { selector: 'figcaption' })).toBeInTheDocument();

    await userEvent.click(
      screen.getByRole('button', { name: 'Approve the practice for Adding within ten' }),
    );
    const post = backend.expectOne(
      (request) =>
        request.url === '/teacher/lessons/l-1/practice/pp-1/approve' && request.method === 'POST',
    );
    post.flush({ ...VIEW.practice[0], approved: true, approvedBy: 'u-sara', approvedAt: 3 });
    await settle();

    expect(
      screen.queryByRole('button', { name: 'Approve the practice for Adding within ten' }),
    ).toBeDisabled();
  });

  it('confirms a reject in the red band, then deletes the practice set', async () => {
    const backend = await renderWithHints();

    await userEvent.click(
      screen.getByRole('button', { name: 'Reject the practice for Adding within ten' }),
    );
    await settle();

    // Destructive, so it asks first — and asks nothing of the server until she says so.
    expect(screen.getByText('Reject this practice set?')).toBeInTheDocument();
    expect(
      screen.getByText(
        'The practice set for Adding within ten is deleted. The next build may write another.',
      ),
    ).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Reject and delete' }));
    backend
      .expectOne(
        (request) =>
          request.url === '/teacher/lessons/l-1/practice/pp-1/reject' && request.method === 'POST',
      )
      .flush(null, { status: 204, statusText: 'No Content' });
    await settle();

    expect(screen.queryByText('Practice for Adding within ten')).not.toBeInTheDocument();
  });

  it('says so when the step has written nothing yet', async () => {
    await renderWithHints({ lessonId: 'l-1', hints: [], practice: [] });

    expect(screen.getByText('Nothing has been written for this lesson yet.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve all hints' })).not.toBeInTheDocument();
  });

  it('draws no section and makes no request at all while the flag is off', async () => {
    const backend = await renderPage(false);

    expect(screen.queryByText('Hints and practice')).not.toBeInTheDocument();
    // The routes answer 404 for a school without the feature, so the section must not ask.
    backend.expectNone('/teacher/lessons/l-1/smart-hints');
  });
});
