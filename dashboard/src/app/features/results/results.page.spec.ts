import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { screen, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH, type LessonResults } from '../../api';
import { TEACHER_USER } from '../../../testing/fixtures';
import { renderHq } from '../../../testing/render';
import { AuthService } from '../../core/auth/auth.service';
import { SessionStore } from '../../core/auth/session.store';
import { FlagService } from '../../core/flags/flag.service';
import { UndoService } from '../../core/undo/undo.service';
import { ResultsPage } from './results.page';

const PERMISSIONS = {
  role: 'TEACHER',
  permissions: ['teacher.week', 'results.read', 'results.write'],
  readOnly: false,
};

const RESULTS: LessonResults = {
  lessonId: 'l-1',
  classId: 'c-1a',
  className: '1A British',
  title: 'Counting to ten',
  date: '2026-09-14',
  released: true,
  releasedAt: 1_757_000_000_000,
  classAverage: 71,
  played: 2,
  needsMarking: 1,
  stops: [
    { stopId: 's1', title: 'How many carrots?', type: 'choice', level: 1, open: false },
    { stopId: 's3', title: 'Tell the story back', type: 'retell', level: 1, open: true },
  ],
  children: [
    {
      childId: 'ch-1',
      name: 'Amina Al Amin',
      attempted: true,
      levelReached: 1,
      scoredLevel: 1,
      autoScore: 90,
      score: 90,
      band: 'exceeding',
      needsMarking: 0,
      stops: [
        { stopId: 's1', attempted: true, stars: 3, score: 100, needsMarking: false },
        { stopId: 's3', attempted: true, stars: 2, score: 70, markStars: 2, needsMarking: false },
      ],
    },
    {
      childId: 'ch-2',
      name: 'Zain Lutfi',
      attempted: true,
      levelReached: 1,
      scoredLevel: 1,
      autoScore: 52,
      score: 52,
      band: 'developing',
      needsMarking: 1,
      stops: [
        { stopId: 's1', attempted: true, stars: 3, score: 100, needsMarking: false },
        { stopId: 's3', attempted: true, needsMarking: true, workUrl: 'https://api.test/media/child/m-9' },
      ],
    },
  ],
};

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([{ path: '**', children: [] }]),
  { provide: BASE_PATH, useValue: '' },
  {
    provide: ActivatedRoute,
    useValue: {
      paramMap: of(convertToParamMap({ id: 'l-1' })),
      snapshot: { paramMap: convertToParamMap({ id: 'l-1' }) } as ActivatedRoute['snapshot'],
    },
  },
];

async function settle(): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
}

async function renderResults(flags: Record<string, boolean> = { gradebook: true, openStopMarking: true }) {
  await renderHq(ResultsPage, { providers });
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

  backend.expectOne('/teacher/lessons/l-1/results').flush(RESULTS);
  await settle();
  return backend;
}

/** The row's own toggle: the child's name is the control that opens her marking panel. */
async function openPanel(name: string): Promise<void> {
  await userEvent.click(screen.getByRole('button', { name: new RegExp(name) }));
  await settle();
}

describe('the Results page', () => {
  let backend: HttpTestingController;

  beforeEach(async () => {
    backend = await renderResults();
  });

  it('puts the four numbers she came for above the table', () => {
    expect(screen.getByText('2 of 2')).toBeInTheDocument();
    expect(screen.getByText('71')).toBeInTheDocument();
    // "Released Sep 4" rather than a bare "Released": when is half the answer.
    expect(screen.getByText(/^Released \w/)).toBeInTheDocument();
  });

  it('says who is still waiting for a mark, and filters down to them', async () => {
    // Two heading rows now — the level group above the column labels — and two children.
    expect(screen.getAllByRole('row')).toHaveLength(4);

    await userEvent.click(screen.getByRole('checkbox', { name: 'Needs marking only' }));
    await settle();

    const rows = screen.getAllByRole('row');
    expect(rows).toHaveLength(3);
    expect(within(rows[2]!).getByText('Zain Lutfi')).toBeInTheDocument();
  });

  it('sends one request for the whole panel, and offers it back for ten seconds', async () => {
    await openPanel('Zain Lutfi');

    await userEvent.click(screen.getByRole('radio', { name: '2 of 3 stars' }));
    await settle();
    await userEvent.click(screen.getByRole('button', { name: 'Save marks' }));
    await settle();

    const saved = backend.expectOne('/teacher/marks');
    expect(saved.request.method).toBe('PUT');
    expect(saved.request.body).toEqual({
      marks: [{ lessonId: 'l-1', childId: 'ch-2', stopId: 's3', stars: 2 }],
    });
    saved.flush([]);
    await settle();

    backend.expectOne('/teacher/lessons/l-1/results').flush(RESULTS);
    await settle();

    // The strip itself lives in the shell; what the page owes it is the offer and the way back.
    const offer = TestBed.inject(UndoService).offer();
    expect(offer?.message).toBe('Marks saved for Zain Lutfi.');

    TestBed.inject(UndoService).undo();
    await settle();
    const undone = backend.expectOne('/teacher/marks');
    expect(undone.request.body).toEqual({
      marks: [{ lessonId: 'l-1', childId: 'ch-2', stopId: 's3' }],
    });
  });

  it('refuses to send a panel nobody touched', async () => {
    await openPanel('Amina Al Amin');

    expect(screen.getByRole('button', { name: 'Save marks' })).toBeDisabled();
  });

  it('asks before taking a released score back off the parents', async () => {
    await userEvent.click(screen.getByRole('switch', { name: 'Released' }));
    await settle();

    expect(screen.getByText(/Parents will stop seeing/)).toBeInTheDocument();
    backend.expectNone('/teacher/lessons/l-1/release');

    await userEvent.click(screen.getByRole('button', { name: 'Withdraw' }));
    await settle();

    const withdrawn = backend.expectOne('/teacher/lessons/l-1/release');
    expect(withdrawn.request.body).toEqual({ released: false });
    withdrawn.flush({ lessonId: 'l-1', released: false, children: 2 });
    await settle();
    backend.expectOne('/teacher/lessons/l-1/results').flush({ ...RESULTS, released: false });
    await settle();

    expect(screen.getByRole('switch', { name: 'Released' })).toHaveAttribute('aria-checked', 'false');
  });

  it('releases again without asking — nothing is taken away', async () => {
    await userEvent.click(screen.getByRole('switch', { name: 'Released' }));
    await settle();
    await userEvent.click(screen.getByRole('button', { name: 'Withdraw' }));
    await settle();
    backend
      .expectOne('/teacher/lessons/l-1/release')
      .flush({ lessonId: 'l-1', released: false, children: 2 });
    await settle();
    backend
      .expectOne('/teacher/lessons/l-1/results')
      .flush({ ...RESULTS, released: false, releasedAt: undefined });
    await settle();

    await userEvent.click(screen.getByRole('switch', { name: 'Released' }));
    await settle();

    const released = backend.expectOne('/teacher/lessons/l-1/release');
    expect(released.request.body).toEqual({ released: true });
  });
});

describe('a school without the marking flag', () => {
  it('reads the marks and cannot change them', async () => {
    await renderResults({ gradebook: true, openStopMarking: false });

    await openPanel('Zain Lutfi');

    expect(screen.queryByRole('button', { name: 'Save marks' })).toBeNull();
    expect(screen.getByRole('radio', { name: '2 of 3 stars' })).toBeDisabled();
  });
});
