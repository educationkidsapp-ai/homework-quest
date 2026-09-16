import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderHq } from '../../../testing/render';
import { PhonePreviewComponent } from './phone-preview.component';
import { StopPreviewComponent } from './stop-preview.component';
import { SEED_STOPS } from './stop.fixtures';
import { STOP_TYPES } from './stop.model';

/**
 * The rules every child screen keeps (`docs/design.md` §7). They are the reason the preview exists
 * at all: a teacher must be able to see that the screen their class meets never tells a child they
 * failed. Asserted over the rendered text of all twenty-two types, not spot-checked.
 */

/** A red ✗ in any of its usual shapes. */
const REJECTION_GLYPHS = /[✗✘❌✖×]/u;
/** A clock: "0:30", "30 seconds left", "time's up". */
const COUNTDOWN = /\b\d{1,2}:\d{2}\b|\bseconds?\b|\btimer\b|\btime'?s up\b/iu;

describe('child-mode rules', () => {
  it.each(STOP_TYPES)('the %s stop shows no score, no cross and no clock', async (type) => {
    const { container } = await renderHq(StopPreviewComponent, {
      inputs: { stop: SEED_STOPS[type] },
    });

    const text = container.textContent ?? '';

    expect(text).not.toContain('%');
    expect(text).not.toMatch(REJECTION_GLYPHS);
    expect(text).not.toMatch(COUNTDOWN);
  });

  it.each(STOP_TYPES)('everything tappable on the %s stop is a button', async (type) => {
    const { container } = await renderHq(StopPreviewComponent, {
      inputs: { stop: SEED_STOPS[type] },
    });

    // No div-with-a-click-handler: a child using a switch or a keyboard reaches every target.
    expect(container.querySelectorAll('[tabindex]')).toHaveLength(0);
    expect(container.querySelectorAll('button:not([type])')).toHaveLength(0);
  });

  it('dims a wrong tile and disables it, and never takes it off the screen', async () => {
    await renderHq(StopPreviewComponent, { inputs: { stop: SEED_STOPS.choice } });
    const wrong = screen.getByRole('button', { name: /For a party/ });

    await userEvent.click(wrong);

    expect(screen.getByRole('button', { name: /For a party/ })).toBe(wrong);
    expect(wrong).toBeDisabled();
    expect(screen.getByRole('button', { name: /To help Mummy feel better/ })).toBeEnabled();
  });

  it('keeps right picks lit while the child keeps going on a multi-select', async () => {
    await renderHq(StopPreviewComponent, { inputs: { stop: SEED_STOPS.multiSelect } });

    await userEvent.click(screen.getByRole('button', { name: 'carrot' }));
    await userEvent.click(screen.getByRole('button', { name: 'fish' }));
    await userEvent.click(screen.getByRole('button', { name: /Check/ }));

    expect(screen.getByRole('button', { name: 'fish' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'carrot' })).toBeInTheDocument();
    expect(screen.getByText('1 more to tap')).toBeInTheDocument();
  });

  it('shows progress as stars, never as a figure out of a hundred', async () => {
    const { container } = await renderHq(PhonePreviewComponent, {
      inputs: { label: 'Child app preview', stop: SEED_STOPS.choice, stars: 3 },
    });

    expect(screen.getByLabelText('3 of 7 stars')).toBeInTheDocument();
    expect(container.textContent ?? '').not.toContain('%');
  });

  it('offers the read-aloud button on the practice frame', async () => {
    await renderHq(PhonePreviewComponent, {
      inputs: { label: 'Child app preview', stop: SEED_STOPS.choice },
    });

    expect(screen.getByRole('button', { name: 'Read it to me' })).toBeInTheDocument();
  });

  it('says what to do when no stop is selected yet', async () => {
    await renderHq(PhonePreviewComponent, {
      inputs: { label: 'Child app preview', placeholder: 'Pick a stop to see it here.' },
    });

    expect(screen.getByText('Pick a stop to see it here.')).toBeInTheDocument();
  });
});
