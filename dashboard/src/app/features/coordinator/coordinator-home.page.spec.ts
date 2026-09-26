import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen, within } from '@testing-library/angular';
import { beforeEach, describe, expect, it } from 'vitest';
import { COORDINATOR_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { BASE_PATH } from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { CoordinatorHomePage } from './coordinator-home.page';

/** `GET /coordinator/me` — one subject, both tracks, and the three counts (R2). */
const ME = {
  userId: 'u-rasha',
  displayName: 'Rasha Kamal',
  email: 'coordinator.math@school.test',
  scopes: [{ subject: 'math', curriculum: null }],
  sections: 3,
  teachers: 2,
  children: 61,
};

const TEACHERS = [
  {
    userId: 't-1',
    displayName: 'Sara Al Harbi',
    email: 'sara@school.test',
    subjects: ['math'],
    sections: [],
  },
];

const CLASSES = [
  {
    classId: 'c-1',
    className: '1A',
    grade: 1,
    curriculum: 'british',
    subject: 'math',
    teacherId: 't-1',
    teacherName: 'Sara Al Harbi',
    childrenCount: 24,
    todayLessonId: 'l-9',
    todayStatus: 'published',
  },
  // The one with nothing on today: the third line of "What needs you".
  {
    classId: 'c-2',
    className: '1B',
    grade: 1,
    curriculum: 'british',
    subject: 'math',
    teacherId: 't-1',
    teacherName: 'Sara Al Harbi',
    childrenCount: 22,
    todayLessonId: null,
    todayStatus: null,
  },
];

const NEEDS_REVIEW = [{ id: 'l-1', title: 'Adding to ten', className: '1A', status: 'needs_review' }];
const FAILED = [{ id: 'l-2', title: 'Taking away', className: '1B', status: 'error' }];

/**
 * R5's Home (`docs/coordinator-flow.md` §2).
 *
 * The screen answers "is anything wrong in my six grades", so "What needs you" is what this spec
 * is about: the two statuses she has to chase and the classes that have nothing on today, in the
 * order she would act, each one a link into the lesson rather than a button that would change it.
 */
describe('the coordinator Home', () => {
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
  });

  async function renderHome() {
    const rendered = await renderHq(CoordinatorHomePage, {
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: '**', children: [] }]),
        { provide: BASE_PATH, useValue: '' },
      ],
    });
    const backend = TestBed.inject(HttpTestingController);

    TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
    TestBed.inject(AuthService).loadMe().subscribe();
    backend.expectOne('/me').flush(COORDINATOR_USER);
    await Promise.resolve();
    TestBed.tick();

    backend.expectOne('/coordinator/me').flush(ME);
    backend.expectOne('/coordinator/teachers').flush(TEACHERS);
    backend.expectOne('/coordinator/classes').flush(CLASSES);
    backend.expectOne('/coordinator/lessons?status=needs_review').flush(NEEDS_REVIEW);
    backend.expectOne('/coordinator/lessons?status=error').flush(FAILED);
    await Promise.resolve();
    TestBed.tick();
    await Promise.resolve();
    TestBed.tick();

    return { rendered, backend };
  }

  it('says what she is responsible for: the scope and the three counts', async () => {
    await renderHome();

    expect(screen.getByRole('heading', { level: 1 }).textContent).toContain('Rasha Kamal');
    // A scope row with no curriculum is both tracks (DR1) — never a blank half of a chip.
    expect(document.body.textContent).toContain('Math · both tracks');
    expect(screen.getByText('Classes in scope')).toBeTruthy();
    expect(screen.getByText('Children')).toBeTruthy();
  });

  it('puts the failed lesson first, then the review, then the class with nothing on today', async () => {
    await renderHome();

    const card = document.querySelectorAll('hq-card')[0]!;
    const rows = [...card.querySelectorAll('li')].map((row) => row.textContent ?? '');

    expect(rows.length).toBe(3);
    expect(rows[0]).toContain('Taking away');
    expect(rows[0]).toContain('Failed');
    expect(rows[1]).toContain('Adding to ten');
    expect(rows[1]).toContain('Needs review');
    expect(rows[2]).toContain('Nothing on today');
    expect(rows[2]).toContain('1B');

    // Each lesson line is a way *in*, in her own namespace — she changes nothing from here (DR2).
    expect(
      within(card as HTMLElement)
        .getByRole('link', { name: /Taking away/ })
        .getAttribute('href'),
    ).toBe('/coordinator/lessons/l-2');
    expect(within(card as HTMLElement).queryAllByRole('button')).toEqual([]);
  });
});
