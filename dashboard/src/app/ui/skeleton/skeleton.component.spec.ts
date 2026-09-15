import { screen } from '@testing-library/angular';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHq } from '../../../testing/render';
import { SkeletonComponent } from './skeleton.component';

describe('hq-skeleton', () => {
  beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
  afterEach(() => vi.useRealTimers());

  it('stays invisible for the first 300 ms so a fast response never flashes', async () => {
    const { fixture } = await renderHq(SkeletonComponent, {
      inputs: { loading: true, label: 'Loading lessons' },
    });

    expect(screen.queryByRole('status')).not.toBeInTheDocument();

    await vi.advanceTimersByTimeAsync(299);
    fixture.detectChanges();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();

    await vi.advanceTimersByTimeAsync(2);
    fixture.detectChanges();
    expect(screen.getByRole('status')).toHaveTextContent('Loading lessons');
  });

  it('disappears as soon as loading ends', async () => {
    const { fixture, rerender } = await renderHq(SkeletonComponent, {
      inputs: { loading: true, label: 'Loading lessons' },
    });

    await vi.advanceTimersByTimeAsync(400);
    fixture.detectChanges();
    expect(screen.getByRole('status')).toBeInTheDocument();

    await rerender({ inputs: { loading: false, label: 'Loading lessons' } });
    fixture.detectChanges();
    await fixture.whenStable();

    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});
