import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BASE_PATH } from '../../api';
import { renderHq } from '../../../testing/render';
import { ClassCalendarComponent } from './class-calendar.component';
import { calendarCells } from './classes.models';

const providers: (Provider | EnvironmentProviders)[] = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([]),
  { provide: BASE_PATH, useValue: '' },
];

/**
 * March 2026 as the server answers it: 1 March is a Sunday, and the school teaches Sunday to
 * Thursday — so Friday and Saturday come back `schoolDay: false`, which is the only place this
 * screen learns the school's week from.
 */
const MARCH = {
  classId: 'c-1a',
  year: 2026,
  month: 3,
  gaps: 0,
  days: Array.from({ length: 31 }, (_, index) => {
    const date = new Date(Date.UTC(2026, 2, index + 1));
    const weekday = date.getUTCDay();
    return { date: date.toISOString().slice(0, 10), schoolDay: weekday !== 5 && weekday !== 6 };
  }),
};

async function renderMonth() {
  await renderHq(ClassCalendarComponent, {
    providers,
    inputs: {
      classId: 'c-1a',
      curriculum: 'british',
      grade: 1,
      subject: 'math',
      year: 2026,
      month: 3,
      cells: calendarCells(MARCH),
      gaps: 0,
      loading: false,
    },
  });
  const backend = TestBed.inject(HttpTestingController);
  backend.expectOne('/platform-settings').flush({ timezone: 'UTC' });
  await Promise.resolve();
  TestBed.tick();
}

/** U1 item 6 — the Calendar tab. */
describe('the class calendar', () => {
  beforeEach(() => {
    localStorage.clear();
    // Tuesday 10 March 2026: the 3rd is behind her, the 17th is ahead.
    vi.setSystemTime(new Date('2026-03-10T09:00:00Z'));
  });
  afterEach(() => vi.useRealTimers());

  it('draws only the school’s teaching days — the vacation columns are not there at all', async () => {
    await renderMonth();

    const headers = screen.getAllByRole('columnheader').map((cell) => cell.textContent?.trim());
    expect(headers).toEqual(['Sun', 'Mon', 'Tue', 'Wed', 'Thu']);
    expect(screen.queryByRole('columnheader', { name: 'Fri' })).toBeNull();
    expect(screen.queryByRole('columnheader', { name: 'Sat' })).toBeNull();
  });

  it('keeps the month’s own navigation, one month at a time', async () => {
    await renderMonth();

    expect(screen.getByRole('button', { name: 'Previous month' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Next month' })).toBeTruthy();
    expect(screen.getByText('March 2026')).toBeTruthy();
  });

  it('offers a + on a school day still ahead of her', async () => {
    await renderMonth();

    const add = screen.getByRole('link', { name: 'Add a lesson on Tuesday, March 17' });
    const href = add.getAttribute('href') ?? '';
    expect(href).toContain('/teacher/lessons/new');
    expect(href).toContain('date=2026-03-17');
  });

  it('offers nothing at all on a day that has gone, and says why', async () => {
    await renderMonth();

    expect(screen.queryByRole('link', { name: 'Add a lesson on Tuesday, March 3' })).toBeNull();
    const past = screen.getByLabelText('Tuesday, March 3 has passed, so nothing can be added to it.');
    // Not a link and not a button: there is nothing to click, so a click cannot do anything.
    expect(past.tagName).toBe('SPAN');
    expect(past.getAttribute('title')).toBe('This day has passed — lessons are planned ahead.');
  });

  it('counts today as open — a lesson for this afternoon is ordinary', async () => {
    await renderMonth();

    expect(screen.getByRole('link', { name: 'Add a lesson on Tuesday, March 10' })).toBeTruthy();
  });
});
