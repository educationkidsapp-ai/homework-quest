import { screen, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHq } from '../../../testing/render';
import { LessonsCalendarComponent } from './lessons-calendar.component';

describe('hq-lessons-calendar', () => {
  afterEach(() => vi.unstubAllEnvs());

  it('numbers a day by its UTC date, not the runner’s local one', async () => {
    // Every other date computed here is UTC (`Date.UTC`, `getUTCDay`, `setUTCDate`) precisely
    // so a Gulf-week school day and a lesson's own date line up regardless of where this runs;
    // a `.getDate()` on the cell would read the runner's local calendar day instead; UTC
    // midnight on the 8th is still the 7th in New York, which is exactly what would expose it.
    vi.stubEnv('TZ', 'America/New_York');

    await renderHq(LessonsCalendarComponent, {
      inputs: {
        curriculum: 'british',
        grade: 1,
        year: 2020,
        month: 1,
        days: [{ date: '2020-01-08', math: true, english: false }],
        loading: false,
      },
    });

    const lessonDay = screen.getByRole('grid').querySelector('[data-date="2020-01-08"]');
    expect(lessonDay?.querySelector('.calendar__day')?.textContent?.trim()).toBe('8');
  });


  it('dots the days a subject was published on and flags a school day with nothing as a gap', async () => {
    // January 2020: the 8th is a Wednesday (a lesson), the 9th a Thursday (a gap — a school
    // day with nothing published), the 10th a Friday (not a school day, so no gap label even
    // though nothing was published either).
    await renderHq(LessonsCalendarComponent, {
      inputs: {
        curriculum: 'british',
        grade: 1,
        year: 2020,
        month: 1,
        days: [{ date: '2020-01-08', math: true, english: false }],
        loading: false,
      },
    });

    const grid = screen.getByRole('grid');
    const lessonDay = grid.querySelector('[data-date="2020-01-08"]');
    expect(lessonDay?.querySelector('.calendar__dot--math')).toBeTruthy();

    const gapDay = grid.querySelector('[data-date="2020-01-09"]');
    expect(within(gapDay as HTMLElement).getByText('No lesson')).toBeInTheDocument();

    const weekendDay = grid.querySelector('[data-date="2020-01-10"]');
    expect(within(weekendDay as HTMLElement).queryByText('No lesson')).not.toBeInTheDocument();
  });

  it('shows a skeleton instead of the grid while loading', async () => {
    await renderHq(LessonsCalendarComponent, {
      inputs: { curriculum: 'british', grade: 1, year: 2020, month: 1, days: [], loading: true },
    });

    expect(screen.queryByRole('grid')).not.toBeInTheDocument();
  });

  it('asks for the next or previous month rather than computing dates itself', async () => {
    const monthChange = vi.fn();
    await renderHq(LessonsCalendarComponent, {
      inputs: { curriculum: 'british', grade: 1, year: 2020, month: 1, days: [], loading: false },
      on: { monthChange },
    });

    await userEvent.click(screen.getByRole('button', { name: 'Next month' }));
    expect(monthChange).toHaveBeenCalledWith({ year: 2020, month: 2 });

    await userEvent.click(screen.getByRole('button', { name: 'Previous month' }));
    expect(monthChange).toHaveBeenCalledWith({ year: 2019, month: 12 });
  });
});
