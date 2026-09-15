import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { renderHq } from '../../../testing/render';
import { ButtonComponent } from './button.component';

describe('hq-button', () => {
  it('emits pressed when clicked', async () => {
    const pressed = vi.fn();
    await renderHq(ButtonComponent, {
      inputs: { variant: 'primary' },
      on: { pressed },
    });

    await userEvent.click(screen.getByRole('button'));

    expect(pressed).toHaveBeenCalledTimes(1);
  });

  it('is disabled and silent while loading, and says so', async () => {
    const pressed = vi.fn();
    await renderHq(ButtonComponent, { inputs: { loading: true }, on: { pressed } });

    const button = screen.getByRole('button');
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByText('Loading')).toBeInTheDocument();

    await userEvent.click(button);
    expect(pressed).not.toHaveBeenCalled();
  });

  it('does not emit when disabled', async () => {
    const pressed = vi.fn();
    await renderHq(ButtonComponent, { inputs: { disabled: true }, on: { pressed } });

    await userEvent.click(screen.getByRole('button'));

    expect(pressed).not.toHaveBeenCalled();
  });
});
