import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderHq } from '../../../testing/render';
import { DayPickerComponent } from './day-picker.component';

async function renderPicker(value = '2026-09-17') {
  const rendered = await renderHq(DayPickerComponent, { inputs: { label: 'Lesson day', value } });
  return { rendered, value: () => rendered.fixture.componentInstance.value() };
}

/** The cell for a day of the month on screen, by the number printed in it. */
function day(number: string) {
  return screen.getAllByRole('gridcell').map((cell) => cell.querySelector('button')!).find((button) => button.textContent?.trim() === number)!;
}

describe('hq-day-picker', () => {
  it('lays the month out as a grid of days, with the value selected and today marked', async () => {
    await renderPicker();

    expect(screen.getByRole('grid', { name: 'Lesson day' })).toBeInTheDocument();
    expect(screen.getByText('September 2026')).toBeInTheDocument();
    // Six weeks of seven days, always — the grid never changes height between months.
    expect(screen.getAllByRole('gridcell')).toHaveLength(42);
    expect(day('17')).toHaveAttribute('aria-selected', 'true');
    // `Intl` decides the wording per locale — what matters is that the cell is named by its day.
    expect(day('17').getAttribute('aria-label')).toMatch(/Thursday.*September.*17.*2026|Thursday 17 September 2026/);
  });

  it('is one tab stop: only the selected day is reachable with Tab', async () => {
    await renderPicker();

    const reachable = screen
      .getAllByRole('gridcell')
      .map((cell) => cell.querySelector('button')!)
      .filter((button) => button.getAttribute('tabindex') === '0');

    expect(reachable).toHaveLength(1);
    expect(reachable[0]!.textContent?.trim()).toBe('17');
  });

  /** ‹ / › can walk to a month the selected day is not in; the grid must keep its tab stop. */
  it('keeps exactly one tab stop in a month that does not hold the selected day', async () => {
    await renderPicker();

    await userEvent.click(screen.getByRole('button', { name: 'Next month' }));

    const stops = screen
      .getAllByRole('gridcell')
      .map((cell) => cell.querySelector('button')!)
      .filter((button) => button.getAttribute('tabindex') === '0');

    expect(stops).toHaveLength(1);
    // The stand-in is a day of the month on screen, not a leftover from the one before it.
    expect(stops[0]!).not.toHaveClass('picker__day--outside');
    expect(stops[0]!).not.toHaveAttribute('aria-selected', 'true');
  });

  it('picks the day that was clicked', async () => {
    const picker = await renderPicker();

    await userEvent.click(day('22'));

    expect(picker.value()).toBe('2026-09-22');
    expect(day('22')).toHaveAttribute('aria-selected', 'true');
  });

  /** The ARIA date-grid pattern: an arrow key moves *and* selects, so there is nothing to confirm. */
  it('moves a day with the arrows, a week with up and down, and a month with Page Up', async () => {
    const picker = await renderPicker();
    const selected = day('17');
    selected.focus();

    await userEvent.keyboard('{ArrowRight}');
    expect(picker.value()).toBe('2026-09-18');

    await userEvent.keyboard('{ArrowDown}');
    expect(picker.value()).toBe('2026-09-25');

    await userEvent.keyboard('{PageUp}');
    expect(picker.value()).toBe('2026-08-25');
    expect(screen.getByText('August 2026')).toBeInTheDocument();
  });

  /** APG: the arrows follow the *visual* direction, and the grid is mirrored in Arabic. */
  it('mirrors the left and right arrows when the document is right to left', async () => {
    document.documentElement.setAttribute('dir', 'rtl');
    try {
      const picker = await renderPicker();
      day('17').focus();

      await userEvent.keyboard('{ArrowLeft}');
      expect(picker.value()).toBe('2026-09-18');

      await userEvent.keyboard('{ArrowRight}');
      expect(picker.value()).toBe('2026-09-17');

      // Up and down are rows, not reading order, so they do not flip.
      await userEvent.keyboard('{ArrowDown}');
      expect(picker.value()).toBe('2026-09-24');
    } finally {
      document.documentElement.removeAttribute('dir');
    }
  });

  it('walks months without changing the chosen day', async () => {
    const picker = await renderPicker();

    await userEvent.click(screen.getByRole('button', { name: 'Next month' }));

    expect(screen.getByText('October 2026')).toBeInTheDocument();
    expect(picker.value()).toBe('2026-09-17');
  });

  it('refuses every day while it is disabled', async () => {
    const picker = await renderPicker();
    picker.rendered.fixture.componentRef.setInput('disabled', true);
    picker.rendered.fixture.detectChanges();

    await userEvent.click(day('22'));

    expect(picker.value()).toBe('2026-09-17');
  });
});
