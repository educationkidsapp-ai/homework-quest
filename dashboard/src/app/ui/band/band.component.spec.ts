import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { renderHq } from '../../../testing/render';
import { BandComponent } from './band.component';

describe('hq-band', () => {
  it('is hidden until it is opened', async () => {
    const { rerender } = await renderHq(BandComponent, { inputs: { open: false } });

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

    await rerender({ inputs: { open: true, title: 'Publishing failed' } });

    expect(screen.getByRole('alert')).toHaveTextContent('Publishing failed');
  });

  it('actually hides — not just out of the accessibility tree — when closed', async () => {
    // The band's own `.band { display: flex }` rule out-cascades the `hidden` attribute's
    // UA-level `display: none` (regression: the band stayed visually laid out while closed).
    const { container, rerender } = await renderHq(BandComponent, { inputs: { open: true, title: 'x' } });
    const band = container.querySelector('.band') as HTMLElement;
    expect(getComputedStyle(band).display).not.toBe('none');

    await rerender({ inputs: { open: false, title: 'x' } });
    expect(getComputedStyle(band).display).toBe('none');
  });

  it('is an alert for errors and a status for notices', async () => {
    const { rerender } = await renderHq(BandComponent, {
      inputs: { open: true, variant: 'error', title: 'Publishing failed' },
    });
    expect(screen.getByRole('alert')).toBeInTheDocument();

    await rerender({ inputs: { open: true, variant: 'notice', title: 'Read-only' } });
    expect(screen.getByRole('status')).toBeInTheDocument();
  });

  it('confirms a destructive action from inside the band', async () => {
    const confirmed = vi.fn();
    await renderHq(BandComponent, {
      inputs: { open: true, variant: 'confirm', confirmLabel: 'Delete school' },
      on: { confirmed },
    });

    await userEvent.click(screen.getByRole('button', { name: 'Delete school' }));

    expect(confirmed).toHaveBeenCalledTimes(1);
  });

  it('can be dismissed', async () => {
    const dismissed = vi.fn();
    await renderHq(BandComponent, { inputs: { open: true }, on: { dismissed } });

    await userEvent.click(screen.getByRole('button', { name: 'Dismiss' }));

    expect(dismissed).toHaveBeenCalledTimes(1);
  });
});
