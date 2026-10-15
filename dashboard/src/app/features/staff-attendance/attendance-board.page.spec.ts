import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { screen, within } from '@testing-library/angular';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { type AttendanceBoard, type BoardRow, BASE_PATH } from '../../api';
import { renderHq } from '../../../testing/render';
import { FlagService } from '../../core/flags/flag.service';
import { StaffAttendancePage } from '../management/staff-attendance.page';
import { AttendanceBoardPage } from './attendance-board.page';
import {
  CARDS,
  NO_FILTER,
  REFRESH_MS,
  academiesOf,
  cardFromStatus,
  cardMatches,
  countOf,
  filterRows,
  toggleCard,
  withBom,
} from './attendance-board.models';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([{ path: '**', children: [] }]),
  { provide: BASE_PATH, useValue: '' },
];

function row(id: string, fullName: string, status: string, extra: Partial<BoardRow> = {}): BoardRow {
  return {
    kind: 'USER',
    id,
    fullName,
    role: 'TEACHER',
    category: 'TEACHER',
    academies: ['british'],
    status,
    noCheckOut: false,
    ...extra,
  };
}

/** One row per status a card counts, a no-check-out flag on a present row, and an absent worker. */
const ROWS: BoardRow[] = [
  row('u-tala', 'تالا المعلمة', 'LATE', { checkIn: '07:20', lateMinutes: 20, mode: 'onsite' }),
  row('u-amy', 'Amy American', 'PRESENT', { academies: ['american'], checkIn: '06:58', mode: 'onsite' }),
  row('u-lina', 'Lina Lead', 'PRESENT', { checkIn: '07:00', mode: 'onsite', noCheckOut: true }),
  row('u-rami', 'Rami Remote', 'REMOTE', { checkIn: '07:02', mode: 'remote' }),
  row('u-nour', 'Nour', 'NOT_ARRIVED'),
  {
    ...row('w-walid', 'Walid Worker', 'ABSENT', { academies: [] }),
    kind: 'WORKER',
    role: 'WORKER',
    category: 'WORKER',
  },
];

const BOARD: AttendanceBoard = {
  day: '2026-10-18',
  timeZone: 'Asia/Riyadh',
  scope: 'SCHOOL',
  counts: { present: 2, late: 1, notArrived: 1, remote: 1, noCheckOut: 1 },
  rows: ROWS,
};

async function settle(rendered: { fixture: { detectChanges: () => void } }): Promise<void> {
  await Promise.resolve();
  TestBed.tick();
  rendered.fixture.detectChanges();
}

async function renderBoard(board: AttendanceBoard = BOARD) {
  const rendered = await renderHq(AttendanceBoardPage, { providers });
  const backend = TestBed.inject(HttpTestingController);
  TestBed.tick();
  backend.expectOne('/staff-attendance/board').flush(board);
  await settle(rendered);
  return { rendered, backend };
}

function names(): string[] {
  const table = screen.getByRole('table', { name: "Today's staff attendance" });
  return within(table)
    .queryAllByRole('button')
    .map((button) => button.textContent?.trim() ?? '');
}

describe('the board, without Angular', () => {
  it('shows exactly the rows each card counted', () => {
    for (const card of CARDS)
      expect(`${card}:${filterRows(ROWS, { ...NO_FILTER, card }).length}`).toBe(
        `${card}:${countOf(BOARD.counts, card)}`,
      );
  });

  it('keeps no check-out a flag, so a present row can also carry it', () => {
    const lina = ROWS.find((r) => r.id === 'u-lina')!;
    expect(cardMatches('present', lina)).toBe(true);
    expect(cardMatches('noCheckOut', lina)).toBe(true);
  });

  it('combines search, status, academy and category, and a second tap clears the card', () => {
    expect(filterRows(ROWS, { ...NO_FILTER, search: 'تالا' }).map((r) => r.id)).toEqual(['u-tala']);
    expect(filterRows(ROWS, { ...NO_FILTER, search: 'LEAD' }).map((r) => r.id)).toEqual(['u-lina']);
    expect(filterRows(ROWS, { ...NO_FILTER, academy: 'american' }).map((r) => r.id)).toEqual(['u-amy']);
    expect(filterRows(ROWS, { ...NO_FILTER, category: 'WORKER' }).map((r) => r.id)).toEqual(['w-walid']);
    expect(filterRows(ROWS, { ...NO_FILTER, status: 'ABSENT' }).map((r) => r.id)).toEqual(['w-walid']);
    const on = toggleCard(NO_FILTER, 'late');
    expect(on.card).toBe('late');
    expect(toggleCard(on, 'late').card).toBeNull();
    expect(academiesOf(ROWS)).toEqual(['american', 'british']);
  });

  /** Feature 21: the four statuses the "What needs me today?" Home links with, and nothing else. */
  it('reads a ?status= into a card, and refuses anything that is not one', () => {
    expect(cardFromStatus('late')).toBe('late');
    expect(cardFromStatus('notArrived')).toBe('notArrived');
    expect(cardFromStatus('remote')).toBe('remote');
    expect(cardFromStatus('present')).toBe('present');
    // A flag a present row also carries, counted by no card on that Home.
    expect(cardFromStatus('noCheckOut')).toBeNull();
    expect(cardFromStatus('LATE')).toBeNull();
    expect(cardFromStatus('')).toBeNull();
    expect(cardFromStatus(null)).toBeNull();
  });

  it('puts the byte-order mark back on the CSV so Excel reads Arabic names', () => {
    expect(withBom('الاسم,الفئة\n').charCodeAt(0)).toBe(0xfeff);
    expect(withBom('\uFEFFx')).toBe('\uFEFFx');
  });
});

describe('Staff attendance board', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => vi.useRealTimers());

  it('draws the five cards with the server counts and every row', async () => {
    await renderBoard();
    expect(screen.getByRole('button', { name: /^1\s*Late$/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^2\s*Present$/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^1\s*No check-out$/ })).toBeInTheDocument();
    expect(names()).toHaveLength(ROWS.length);
    expect(names()).toContain('تالا المعلمة');
  });

  it('filters the table to a card when it is tapped, and back when it is tapped again', async () => {
    const { rendered } = await renderBoard();
    const late = screen.getByRole('button', { name: /^1\s*Late$/ });
    late.click();
    await settle(rendered);
    expect(late).toHaveAttribute('aria-pressed', 'true');
    expect(names()).toEqual(['تالا المعلمة']);

    screen.getByRole('button', { name: /^2\s*Present$/ }).click();
    await settle(rendered);
    expect(names()).toEqual(['Amy American', 'Lina Lead']);

    screen.getByRole('button', { name: /^2\s*Present$/ }).click();
    await settle(rendered);
    expect(names()).toHaveLength(ROWS.length);
  });

  it('asks again every 30 seconds, so a new check-in appears without a reload', async () => {
    vi.useFakeTimers();
    const { rendered, backend } = await renderBoard();
    expect(names()).not.toContain('Sami New');

    vi.advanceTimersByTime(REFRESH_MS);
    TestBed.tick();
    backend
      .expectOne('/staff-attendance/board')
      .flush({ ...BOARD, rows: [...ROWS, row('u-sami', 'Sami New', 'PRESENT', { checkIn: '09:01' })] });
    await settle(rendered);
    expect(names()).toContain('Sami New');
    backend.expectNone('/staff-attendance/board');
  });

  /**
   * Feature 21: she tapped "2 Late" on her Home, so this is the two rows that number counted —
   * the card is pressed and the filter is clearable from here like any other.
   */
  it('lands filtered to the card named by ?status=, and clears when it is tapped again', async () => {
    const rendered = await renderHq(AttendanceBoardPage, {
      providers: [
        ...providers,
        {
          provide: ActivatedRoute,
          useValue: { queryParamMap: of(convertToParamMap({ status: 'late' })) },
        },
      ],
    });
    const backend = TestBed.inject(HttpTestingController);
    TestBed.tick();
    backend.expectOne('/staff-attendance/board').flush(BOARD);
    await settle(rendered);

    const late = screen.getByRole('button', { name: /^1\s*Late$/ });
    expect(late).toHaveAttribute('aria-pressed', 'true');
    expect(names()).toEqual(['تالا المعلمة']);

    late.click();
    await settle(rendered);
    expect(names()).toHaveLength(ROWS.length);
  });

  /** No `status` in the link is no filter — the whole board, as it has always been. */
  it('ignores a status it does not know', async () => {
    const rendered = await renderHq(AttendanceBoardPage, {
      providers: [
        ...providers,
        {
          provide: ActivatedRoute,
          useValue: { queryParamMap: of(convertToParamMap({ status: 'noCheckOut' })) },
        },
      ],
    });
    const backend = TestBed.inject(HttpTestingController);
    TestBed.tick();
    backend.expectOne('/staff-attendance/board').flush(BOARD);
    await settle(rendered);

    expect(names()).toHaveLength(ROWS.length);
  });

  it('tells a teacher who leads nobody so, rather than drawing an empty table', async () => {
    await renderBoard({
      ...BOARD,
      scope: 'REPORTS',
      rows: [],
      counts: { present: 0, late: 0, notArrived: 0, remote: 0, noCheckOut: 0 },
    });
    expect(screen.getByText('Nobody reports to you yet')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });
});

describe("the manager's Staff attendance page while the switch is on", () => {
  it('opens on the board and has no daily marking tab', async () => {
    const rendered = await renderHq(StaffAttendancePage, {
      providers: [
        ...providers,
        { provide: FlagService, useValue: { isOn: () => true, refresh: () => undefined } },
      ],
    });
    const backend = TestBed.inject(HttpTestingController);
    TestBed.tick();
    backend.expectOne('/staff-attendance/board').flush(BOARD);
    await settle(rendered);

    const tabs = screen.getAllByRole('tab').map((tab) => tab.textContent?.trim());
    expect(tabs).toEqual(['Today', 'Monthly report', 'Marked by hand']);
    expect(screen.queryByRole('radiogroup')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument();
    backend.expectNone((request) => request.url.startsWith('/management/staff-attendance'));
  });
});
