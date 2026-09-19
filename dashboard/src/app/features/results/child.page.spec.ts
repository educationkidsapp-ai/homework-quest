import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { screen, within } from '@testing-library/angular';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { BASE_PATH, type ChildReport } from '../../api';
import { TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { FlagService } from '../../core/flags/flag.service';
import { ChildPage } from './child.page';

const REPORT: ChildReport = {
  childId: 'ch-2',
  name: 'Zain Lutfi',
  classId: 'c-1a',
  className: '1A British',
  levels: [{ subject: 'math', band: 'secure', levelScore: 64, trend: 'up', lessons: 6 }],
  trend: [
    {
      lessonId: 'l-1',
      title: 'Counting to ten',
      date: '2026-09-07',
      score: 50,
      band: 'developing',
      released: true,
    },
  ],
  comments: [
    { lessonId: 'l-1', lessonTitle: 'Counting to ten', comment: 'Much better this week', markedAt: 200 },
  ],
  work: [],
};

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([{ path: '**', children: [] }]),
  { provide: BASE_PATH, useValue: '' },
  {
    provide: ActivatedRoute,
    useValue: {
      paramMap: of(convertToParamMap({ childId: 'ch-2' })),
      snapshot: { paramMap: convertToParamMap({ childId: 'ch-2' }) } as ActivatedRoute['snapshot'],
    },
  },
];

async function settle(): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
}

async function renderChild(flags: Record<string, boolean> = { gradebook: true }) {
  await renderHq(ChildPage, { providers });
  const backend = TestBed.inject(HttpTestingController);

  TestBed.inject(SessionStore).set({ token: 'access-1', refreshToken: 'refresh-1' });
  TestBed.inject(AuthService).loadMe().subscribe();
  backend.expectOne('/me').flush(TEACHER_USER);
  TestBed.tick();
  // The page reads no permission of its own — the route's `canGuard` holds `results.read` — so
  // `/me/permissions` is never asked for here, only the school's flags.
  TestBed.inject(FlagService).flags();
  await settle();
  backend.expectOne('/schools/school-a/flags').flush(flags);
  await settle();

  backend.expectOne('/teacher/children/ch-2').flush(REPORT);
  await settle();
  return backend;
}

describe('the child page', () => {
  it('leads with the band and the direction for each subject', async () => {
    await renderChild();

    expect(screen.getByRole('heading', { name: 'Zain Lutfi' })).toBeInTheDocument();
    expect(screen.getAllByText('Secure').length).toBeGreaterThan(0);
    expect(screen.getByText('Going up')).toBeInTheDocument();
    expect(screen.getByText('64')).toBeInTheDocument();
  });

  it('puts the chart’s numbers in a table as well as in the picture', async () => {
    await renderChild();

    const table = screen.getByRole('table', { name: 'Every released score, oldest first' });
    expect(within(table).getByRole('rowheader', { name: 'Counting to ten' })).toBeInTheDocument();
    expect(within(table).getByText('50')).toBeInTheDocument();
  });

  it('shows the comments, saying which one the parent sees', async () => {
    await renderChild();

    expect(screen.getByText('Much better this week')).toBeInTheDocument();
    expect(screen.getByText('Seen by the parent')).toBeInTheDocument();
  });

  it('says so when there is no saved work rather than leaving a hole', async () => {
    await renderChild();

    expect(screen.getByText('Nothing saved yet.')).toBeInTheDocument();
  });
});
