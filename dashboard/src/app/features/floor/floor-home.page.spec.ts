import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../../api';
import { renderHq } from '../../../testing/render';
import { FlagService } from '../../core/flags/flag.service';
import { FloorHomePage } from './floor-home.page';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([{ path: '**', children: [] }]),
  { provide: BASE_PATH, useValue: '' },
  // Both of her flags are on: `floorSupervisor`, which her whole area carries, and
  // `staffAttendance`, which the board inside the page carries. The one case where they differ is
  // the server's own answer — `FloorHome.board` is null — and that is what the second test is.
  { provide: FlagService, useValue: { isOn: () => true, refresh: () => undefined, ready: () => true } },
];

const ME = {
  userId: 'u-mona',
  displayName: 'Mona Saeed',
  email: 'floor2@alnoor.test',
  // Two floors, so the subtitle is the join of both — and so the one assertion about it cannot
  // be satisfied by a class row that happens to name the same floor.
  floors: [
    { id: 'f-2', name: 'Floor 2', academy: 'british' },
    { id: 'f-3', name: 'Floor 3', academy: 'british' },
  ],
  classes: 2,
  teachers: 3,
  children: 48,
};

/** What `GET /staff-attendance/board` answers her: her floor's teachers, scope word `REPORTS`. */
const BOARD = {
  day: '2026-10-15',
  scope: 'REPORTS',
  counts: { present: 2, late: 1, notArrived: 0, remote: 0, noCheckOut: 0 },
  rows: [
    {
      kind: 'USER',
      id: 'u-tala',
      fullName: 'Tala Nasser',
      role: 'TEACHER',
      category: 'TEACHER',
      academies: ['british'],
      status: 'LATE',
      checkIn: '07:20',
      lateMinutes: 20,
      mode: 'onsite',
      noCheckOut: false,
    },
  ],
};

const CLASSES = [
  {
    floorId: 'f-2',
    floorName: 'Floor 2',
    classId: 'c-1a',
    className: '1A',
    curriculum: 'british',
    grade: 1,
    children: 24,
    present: 22,
    absent: 1,
    late: 1,
    excused: 0,
    notMarked: 0,
  },
  {
    floorId: 'f-2',
    floorName: 'Floor 2',
    classId: 'c-1b',
    className: '1B',
    curriculum: 'british',
    grade: 1,
    children: 24,
    present: 0,
    absent: 0,
    late: 0,
    excused: 0,
    notMarked: 24,
  },
];

async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

async function renderHome(home: Record<string, unknown>) {
  const rendered = await renderHq(FloorHomePage, { providers });
  const backend = TestBed.inject(HttpTestingController);

  backend.expectOne('/floor/home').flush(home);
  backend.expectOne('/floor/me').flush(ME);
  await settle(rendered);
  return { rendered, backend };
}

describe('the floor supervisor’s Home', () => {
  beforeEach(() => localStorage.clear());

  /**
   * The two blocks, in the order she asks them in: her floor's staff this morning, then her
   * floor's children. The board is feature 17's own component, so what is asserted here is that
   * it is *drawn and fed* — its five cards and its filtering have their own spec.
   */
  it('draws the staff board over the children’s attendance of each class', async () => {
    const { rendered, backend } = await renderHome({ day: '2026-10-15', board: BOARD, classes: CLASSES });
    // The board reads the live endpoint itself: it refreshes every thirty seconds and owns its
    // filters, and `FloorHome.board` is what decides whether the block exists at all.
    backend.expectOne('/staff-attendance/board').flush(BOARD);
    await settle(rendered);

    expect(screen.getByRole('heading', { name: 'Staff attendance' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Children’s attendance' })).toBeInTheDocument();
    // Her floors are the subtitle, from `/floor/me`.
    expect(screen.getByText('Floor 2 · Floor 3')).toBeInTheDocument();
    // A row per class of her floor, with the floor it is on and the register's numbers on it.
    expect(screen.getByRole('cell', { name: /1A/ })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: /1B/ })).toBeInTheDocument();
    // The one number she acts on: a whole class nobody has marked.
    expect(screen.getByRole('columnheader', { name: 'Not marked' })).toBeInTheDocument();
    expect(screen.getAllByRole('cell', { name: '24' }).length).toBeGreaterThan(0);
    // The board's own rows are on the page, so the block is genuinely drawn rather than stubbed.
    expect(screen.getByText('Tala Nasser')).toBeInTheDocument();
  });

  /**
   * `board` is null when the school takes no staff attendance (`staffAttendance` off, server
   * side). The children's block is the whole page then — not an empty board, and not a red band:
   * nothing has failed.
   */
  it('leaves the staff block out when the server sends no board', async () => {
    const { backend } = await renderHome({ day: '2026-10-15', board: null, classes: CLASSES });

    expect(screen.queryByRole('heading', { name: 'Staff attendance' })).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Children’s attendance' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: /1A/ })).toBeInTheDocument();
    // And the board was never asked for, which is the point: the component is not on the page.
    backend.expectNone('/staff-attendance/board');
  });

  it('says a floor with no classes has none, rather than drawing an empty table', async () => {
    await renderHome({ day: '2026-10-15', board: null, classes: [] });

    expect(screen.getByText('No classes on your floor yet.')).toBeInTheDocument();
  });

  it('puts a red band and Try again over a read that failed, not an empty state', async () => {
    const rendered = await renderHq(FloorHomePage, { providers });
    const backend = TestBed.inject(HttpTestingController);

    backend
      .expectOne('/floor/home')
      .flush({ code: 'server_error', message: 'nope' }, { status: 500, statusText: 'Server Error' });
    backend.expectOne('/floor/me').flush(ME);
    await settle(rendered);

    expect(screen.getByText('That did not work')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
    expect(screen.queryByText('No classes on your floor yet.')).not.toBeInTheDocument();
  });
});
