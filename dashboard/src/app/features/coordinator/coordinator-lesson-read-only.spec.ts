import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import { beforeEach, describe, expect, it } from 'vitest';
import { COORDINATOR_USER } from '../../../testing/fixtures';
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
  files: [],
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
  parentPanel: { summary: 'We added to ten.', tips: [] },
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
  });

  async function renderAsCoordinator() {
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
              data: { readOnly: true },
            },
          },
        },
      ],
    });
    const backend = TestBed.inject(HttpTestingController);

    TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
    TestBed.inject(AuthService).loadMe().subscribe();
    backend.expectOne('/me').flush(COORDINATOR_USER);
    await Promise.resolve();
    TestBed.tick();

    // Her own namespace, never `/admin/lessons/{id}` (403) or `/teacher/lessons/{id}` (404).
    backend.expectOne('/coordinator/lessons/l-1').flush(LESSON);
    await Promise.resolve();
    TestBed.tick();
    backend.expectOne('/me/permissions').flush(COORDINATOR_PERMISSIONS);
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

    for (const name of [
      /^Publish/i,
      /^Unpublish/i,
      /^Add a question/i,
      /^Save/i,
      /^Delete/i,
      /^Remove/i,
      /^Try again/i,
      /^Write level/i,
      /^Upload/i,
      /^Regenerate/i,
    ])
      expect(screen.queryAllByRole('button', { name })).toEqual([]);

    // No editor, no parent-panel form, no exam settings, and no file input anywhere: hidden
    // rather than disabled, because a disabled Save still promises there is a way to press it.
    expect(document.querySelector('hq-stop-editor')).toBeNull();
    expect(document.querySelector('hq-parent-panel-editor')).toBeNull();
    expect(document.querySelector('hq-exam-settings-card')).toBeNull();
    expect(document.querySelector('hq-add-stop')).toBeNull();
    expect(document.querySelectorAll('input[type="file"]').length).toBe(0);
    // The move-date control is an input, not a button, so it is named separately.
    expect(screen.queryByLabelText(/Move to another day/i)).toBeNull();
  });
});
