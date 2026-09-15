import { screen } from '@testing-library/angular';
import { describe, expect, it } from 'vitest';
import { renderHq } from '../../../testing/render';
import { ProgressBarComponent } from './progress-bar.component';

describe('hq-progress-bar', () => {
  it('reports a determinate value', async () => {
    await renderHq(ProgressBarComponent, { inputs: { value: 64, label: 'Upload progress' } });

    const bar = screen.getByRole('progressbar', { name: 'Upload progress' });
    expect(bar).toHaveAttribute('aria-valuenow', '64');
    expect(bar).toHaveAttribute('aria-valuemax', '100');
  });

  it('omits the value entirely when indeterminate', async () => {
    await renderHq(ProgressBarComponent, { inputs: { value: null, label: 'Reading slides' } });

    const bar = screen.getByRole('progressbar', { name: 'Reading slides' });
    expect(bar).not.toHaveAttribute('aria-valuenow');
  });

  it('clamps a value outside the range instead of overflowing', async () => {
    const { fixture } = await renderHq(ProgressBarComponent, {
      inputs: { value: 140, label: 'Upload progress' },
    });

    const fill = (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>('.bar__fill');
    expect(fill?.style.inlineSize).toBe('100%');
  });
});
