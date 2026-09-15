import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHq } from '../../../testing/render';
import { UndoStripComponent } from './undo-strip.component';

describe('hq-undo-strip', () => {
  beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
  afterEach(() => vi.useRealTimers());

  it('offers Undo while it is open', async () => {
    const undone = vi.fn();
    await renderHq(UndoStripComponent, {
      inputs: { open: true, message: 'Lesson deleted' },
      on: { undone },
    });

    expect(screen.getByRole('status')).toHaveTextContent('Lesson deleted');
    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));

    expect(undone).toHaveBeenCalledTimes(1);
  });

  it('commits the change when the countdown runs out', async () => {
    const expired = vi.fn();
    await renderHq(UndoStripComponent, {
      inputs: { open: true, message: 'Lesson deleted', durationMs: 1000 },
      on: { expired },
    });

    await vi.advanceTimersByTimeAsync(900);
    expect(expired).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(200);
    expect(expired).toHaveBeenCalledTimes(1);
  });

  it('shows nothing when closed', async () => {
    await renderHq(UndoStripComponent, { inputs: { open: false, message: 'Lesson deleted' } });

    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});
