import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { BASE_PATH, type Gradebook, type LessonResults } from '../../api';
import { TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { FlagService } from '../../core/flags/flag.service';
import { GradebookComponent } from './gradebook.component';

const PERMISSIONS = {
  role: 'TEACHER',
  permissions: ['teacher.week', 'results.read', 'results.write'],
  readOnly: false,
};

const BOOK: Gradebook = {
  classId: 'c-1a',
  className: '1A British',
  needsMarking: 1,
  lessons: [
    {
      lessonId: 'l-1',
      title: 'Counting to ten',
      date: '2026-09-07',
      type: 'homework',
      released: true,
      classAverage: 71,
      needsMarking: 0,
    },
  ],
  children: [
    {
      childId: 'ch-1',
      name: 'Amina Al Amin',
      average: 90,
      band: 'exceeding',
      trend: 'up',
      cells: [{ lessonId: 'l-1', attempted: true, autoScore: 90, score: 90, band: 'exceeding' }],
    },
    {
      childId: 'ch-2',
      name: 'Zain Lutfi',
      average: 60,
      band: 'secure',
      trend: 'down',
      cells: [
        { lessonId: 'l-1', attempted: true, autoScore: 52, teacherScore: 60, score: 60, band: 'secure' },
      ],
    },
  ],
};

/** The one lesson the cell editor reads before it writes, so the parent comment survives. */
const RESULTS: LessonResults = {
  lessonId: 'l-1',
  children: [
    { childId: 'ch-1', name: 'Amina Al Amin' },
    { childId: 'ch-2', name: 'Zain Lutfi', comment: 'Much better this week', teacherScore: 60 },
  ],
};

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

async function renderGradebook(flags: Record<string, boolean> = { gradebook: true, openStopMarking: true }) {
  await renderHq(GradebookComponent, {
    providers,
    inputs: { classId: 'c-1a', className: '1A British' },
  });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(TEACHER_USER);
  TestBed.tick();
  backend.expectOne('/me/permissions').flush(PERMISSIONS);
  TestBed.inject(FlagService).flags();
  await settle();
  backend.expectOne('/schools/school-a/flags').flush(flags);
  await settle();

  backend.expectOne((request) => request.url === '/teacher/classes/c-1a/gradebook').flush(BOOK);
  await settle();
  return backend;
}

function cellOf(name: string) {
  const row = screen.getAllByRole('row').find((candidate) => candidate.textContent?.includes(name));
  return within(row!);
}

describe('the gradebook grid', () => {
  it('asks for the eight weeks behind today', async () => {
    await renderHq(GradebookComponent, {
      providers,
      inputs: { classId: 'c-1a', className: '1A British' },
    });
    const backend = TestBed.inject(HttpTestingController);
    await settle();

    const asked = backend.expectOne((request) => request.url === '/teacher/classes/c-1a/gradebook');
    const from = asked.request.params.get('from');
    const to = asked.request.params.get('to');
    expect(from).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect((Date.parse(to!) - Date.parse(from!)) / 86_400_000).toBe(56);
  });

  it('never carries a band on colour alone — every square has its word and its score', async () => {
    await renderGradebook();

    const amina = cellOf('Amina Al Amin');
    expect(amina.getAllByText('Ex').length).toBeGreaterThan(0);
    expect(amina.getAllByText('Exceeding').length).toBeGreaterThan(0);
    expect(amina.getAllByText('90').length).toBeGreaterThan(0);
  });

  it('keeps the automatic score beside an override, struck through', async () => {
    await renderGradebook();

    const zain = cellOf('Zain Lutfi');
    expect(zain.getAllByText('60').length).toBeGreaterThan(0);
    expect(zain.getByText('52')).toBeInTheDocument();
  });

  it('puts the class average of every lesson on its own row', async () => {
    await renderGradebook();

    const averages = screen.getAllByRole('row')[1]!;
    expect(within(averages).getByText('71')).toBeInTheDocument();
  });

  it('keeps the parent comment when only the score is overridden', async () => {
    const backend = await renderGradebook();

    await userEvent.click(cellOf('Zain Lutfi').getAllByRole('button')[0]!);
    await settle();
    // The editor reads the lesson once, for the comment the grid does not carry.
    backend.expectOne('/teacher/lessons/l-1/results').flush(RESULTS);
    await settle();

    // The field's accessible name carries the library's "Optional" marker after it.
    const override = screen.getByLabelText(/^Score override/);
    await userEvent.clear(override);
    await userEvent.type(override, '75');
    await settle();
    await userEvent.click(screen.getByRole('button', { name: 'Apply' }));
    await settle();

    const saved = backend.expectOne('/teacher/marks');
    expect(saved.request.body).toEqual({
      marks: [{ lessonId: 'l-1', childId: 'ch-2', score: 75, comment: 'Much better this week' }],
    });
  });

  it('is read-only for a school without the marking flag', async () => {
    await renderGradebook({ gradebook: true, openStopMarking: false });

    expect(cellOf('Zain Lutfi').getAllByRole('button')[0]!).toBeDisabled();
  });
});
