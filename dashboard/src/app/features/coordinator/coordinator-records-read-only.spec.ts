import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import { describe, expect, it } from 'vitest';
import { COORDINATOR_USER, TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { BASE_PATH } from '../../api';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { FlagService } from '../../core/flags/flag.service';
import { ClassAttendanceComponent } from '../classes/class-attendance.component';
import { ExamsTabComponent } from '../exams/exams-tab.component';
import { GradebookComponent } from '../results/gradebook.component';

/** R2: the three read keys she holds for R6, and not one that writes. */
const COORDINATOR_PERMISSIONS = {
  role: 'COORDINATOR',
  permissions: [
    'coordinator.read',
    'coordinator.lesson.read',
    'coordinator.attendance.read',
    'coordinator.results.read',
    'coordinator.exams.read',
  ],
  readOnly: false,
};

/** The teacher who owns the class — the negative control's account. */
const TEACHER_PERMISSIONS = {
  role: 'TEACHER',
  permissions: ['teacher.week', 'lesson.read', 'lesson.write', 'results.read', 'results.write'],
  readOnly: false,
};

const FLAGS_ON = { gradebook: true, exams: true, openStopMarking: true };

const GRADEBOOK = {
  classId: 'c-1a',
  className: '1A British',
  needsMarking: 0,
  lessons: [{ lessonId: 'l-1', title: 'Counting to ten', date: '2026-09-14', classAverage: 62 }],
  children: [
    {
      childId: 'ch-1',
      name: 'Omar',
      average: 62,
      band: 'mid',
      cells: [
        { lessonId: 'l-1', attempted: true, score: 62, band: 'mid', autoScore: 62, teacherScore: null },
      ],
    },
  ],
};

const HOUR = 60 * 60 * 1000;

/** One exam already sat. R3's `ExamRow` carries `sat`/`roster`/`needsMarking`; `ExamSettings` does not. */
function examRow(now: number, withCounts: boolean) {
  return {
    examId: 'e-open',
    title: 'Mid-term',
    classId: 'c-1a',
    className: '1A British',
    opensAt: now - HOUR,
    closesAt: now + HOUR,
    level: 'mixed',
    releaseMode: 'manual',
    status: 'published',
    released: false,
    ...(withCounts ? { sat: 18, roster: 24, needsMarking: 3 } : {}),
  };
}

/** Two days of a register, as `GET /coordinator/classes/{id}/attendance?from&to` answers it. */
const DAYS = [
  {
    date: '2026-09-14',
    students: [{ childId: 'ch-1', childName: 'Omar', status: 'PRESENT', notes: 'On time' }],
  },
  { date: '2026-09-15', students: [{ childId: 'ch-1', childName: 'Omar', status: 'ABSENT' }] },
];

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([]),
  { provide: BASE_PATH, useValue: '' },
];

async function settle(): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
}

/**
 * Answers whatever the screen asked for, in whatever order it asked — the same helper the Exams
 * tab's own spec uses, and for the same reason: `*hqCan` inside `*hqFeature` means
 * `/me/permissions` is not requested until the flags have landed.
 */
async function answer(
  backend: HttpTestingController,
  replies: Record<string, object | unknown[]>,
): Promise<void> {
  for (let round = 0; round < 6; round += 1) {
    for (const [url, body] of Object.entries(replies)) {
      for (const request of backend.match((candidate) => candidate.url === url)) request.flush(body);
    }
    await settle();
  }
}

async function signIn(mine: boolean): Promise<HttpTestingController> {
  const backend = TestBed.inject(HttpTestingController);
  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  TestBed.inject(FlagService).flags();
  await answer(backend, {
    '/me': mine ? COORDINATOR_USER : TEACHER_USER,
    '/me/permissions': mine ? COORDINATOR_PERMISSIONS : TEACHER_PERMISSIONS,
    '/schools/school-a/flags': FLAGS_ON,
    '/platform-settings': { timezone: 'UTC' },
  });
  return backend;
}

/**
 * R6: the teacher's three record components, reused read-only (`docs/coordinator-flow.md`).
 *
 * What each test pins is the pair a reused component can get wrong: **which namespace** it reads
 * (her `/coordinator/**`, never the teacher's, which answers 404 for her) and **which controls**
 * it draws (none that writes, hidden rather than disabled). Every absence has a teacher rendering
 * beside it that asserts the same thing present, because an assertion that a control is missing
 * passes just as well when its label was never right.
 */
describe('the coordinator’s record screens', () => {
  describe('the gradebook', () => {
    async function renderGradebook(mine: boolean) {
      await renderHq(GradebookComponent, {
        providers,
        inputs: { classId: 'c-1a', className: '1A British', readOnly: mine },
      });
      const backend = await signIn(mine);
      await answer(backend, {
        [`${mine ? '/coordinator/classes/c-1a/results' : '/teacher/classes/c-1a/gradebook'}`]: GRADEBOOK,
      });
      return backend;
    }

    it('reads her own namespace and draws the grid', async () => {
      const backend = await renderGradebook(true);

      expect(screen.getByText('Counting to ten')).toBeInTheDocument();
      expect(screen.getByText('Omar')).toBeInTheDocument();
      // Never the teacher's: `/teacher/classes/{id}/gradebook` is scoped to her own assignments.
      backend.expectNone((request) => request.url.startsWith('/teacher/'));
    });

    it('offers no export and opens no cell editor', async () => {
      await renderGradebook(true);

      // Both exports are teacher-namespace routes, so a button for either would be a 403.
      expect(screen.queryByRole('button', { name: 'Export CSV' })).toBeNull();
      expect(screen.queryByRole('button', { name: 'Export Excel' })).toBeNull();
      // The square is still a square — it is simply not a way in to an override.
      for (const cell of document.querySelectorAll('[data-hq-gb-cell]'))
        expect(cell.hasAttribute('disabled')).toBe(true);
    });

    it('sends a child’s name to her own copy of the report', async () => {
      await renderGradebook(true);

      expect(screen.getByRole('link', { name: 'Omar' }).getAttribute('href')).toBe(
        '/coordinator/children/ch-1',
      );
    });

    it('gives the teacher both exports, an editable cell and her own child link', async () => {
      await renderGradebook(false);

      expect(screen.getByRole('button', { name: 'Export CSV' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Export Excel' })).toBeInTheDocument();
      const cells = [...document.querySelectorAll('[data-hq-gb-cell]')];
      expect(cells.some((cell) => !cell.hasAttribute('disabled'))).toBe(true);
      expect(screen.getByRole('link', { name: 'Omar' }).getAttribute('href')).toBe('/teacher/children/ch-1');
    });
  });

  describe('the exams list', () => {
    async function renderExams(mine: boolean) {
      const now = Date.now();
      await renderHq(ExamsTabComponent, {
        providers,
        inputs: { classId: 'c-1a', className: '1A British', readOnly: mine },
      });
      const backend = await signIn(mine);
      await answer(backend, {
        [`${mine ? '/coordinator/classes/c-1a/exams' : '/teacher/classes/c-1a/exams'}`]: [examRow(now, mine)],
        '/teacher/exams/e-open/results': { examId: 'e-open', roster: 24, sat: 18, needsMarking: 3 },
      });
      return backend;
    }

    it('lists her exams in one request, counts and all', async () => {
      const backend = await renderExams(true);

      expect(screen.getByText('Mid-term')).toBeInTheDocument();
      // The count came off the row itself: no per-exam results fan-out, and nothing asked of
      // `/teacher/**` — which would answer 404 for her anyway.
      expect(screen.getByText('18 of 24')).toBeInTheDocument();
      backend.expectNone((request) => request.url.startsWith('/teacher/'));
    });

    it('offers no New exam, and opens the results in her area', async () => {
      await renderExams(true);

      expect(screen.queryByRole('link', { name: 'New exam' })).toBeNull();
      expect(screen.getByRole('link', { name: '18 of 24' }).getAttribute('href')).toBe(
        '/coordinator/exams/e-open/results',
      );
      expect(screen.getByRole('link', { name: 'Mid-term' }).getAttribute('href')).toBe(
        '/coordinator/lessons/e-open',
      );
    });

    it('gives the teacher New exam and her own two links', async () => {
      await renderExams(false);

      expect(screen.getByRole('link', { name: 'New exam' })).toBeInTheDocument();
      expect(screen.getByRole('link', { name: '18 of 24' }).getAttribute('href')).toBe(
        '/teacher/exams/e-open/results',
      );
    });
  });

  describe('the attendance table', () => {
    it('draws the range with no way to mark it', async () => {
      await renderHq(ClassAttendanceComponent, {
        providers,
        inputs: {
          classId: 'c-1a',
          className: '1A British',
          readOnly: true,
          days: DAYS,
          childBase: '/coordinator/children',
        },
      });
      await settle();

      // Both days are columns, and the child's two marks are her totals.
      expect(screen.getByText('09-14')).toBeInTheDocument();
      expect(screen.getByText('09-15')).toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'Omar' }).getAttribute('href')).toBe(
        '/coordinator/children/ch-1',
      );
      // Not one control that would write, and no note anybody could type into.
      expect(screen.queryByRole('button', { name: 'Save Attendance' })).toBeNull();
      expect(screen.queryByRole('button', { name: 'Mark all present' })).toBeNull();
      expect(document.querySelectorAll('textarea').length).toBe(0);
      expect(document.querySelectorAll('input[type="date"]').length).toBe(0);
    });

    it('gives the teacher the pills, the notes and Save', async () => {
      await renderHq(ClassAttendanceComponent, {
        providers,
        inputs: { classId: 'c-1a', className: '1A British' },
      });
      const backend = TestBed.inject(HttpTestingController);
      await answer(backend, {
        [`/teacher/classes/c-1a/attendance`]: {
          classId: 'c-1a',
          className: '1A British',
          date: '2026-09-15',
          students: [{ childId: 'ch-1', childName: 'Omar', status: 'NOT_MARKED' }],
          totalCount: 1,
          presentCount: 0,
          absentCount: 0,
          lateCount: 0,
          excusedCount: 0,
          attendanceRate: 100,
        },
      });

      expect(screen.getByRole('button', { name: 'Save Attendance' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Mark all present' })).toBeInTheDocument();
      expect(document.querySelectorAll('textarea').length).toBe(1);
    });
  });
});
