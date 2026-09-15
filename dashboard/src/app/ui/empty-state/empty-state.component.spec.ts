import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { renderHq } from '../../../testing/render';
import { EmptyStateComponent } from './empty-state.component';

describe('hq-empty-state', () => {
  it('says what is missing and offers the one action that fixes it', async () => {
    const action = vi.fn();
    await renderHq(EmptyStateComponent, {
      inputs: { message: 'No schools yet.', actionLabel: 'New school' },
      on: { action },
    });

    expect(screen.getByText('No schools yet.')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'New school' }));

    expect(action).toHaveBeenCalledTimes(1);
  });

  it('shows no action when there is nothing the user can do here', async () => {
    await renderHq(EmptyStateComponent, { inputs: { message: 'Nothing to review.' } });

    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});
