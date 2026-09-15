import { screen } from '@testing-library/angular';
import { describe, expect, it } from 'vitest';
import { renderHq } from '../../../testing/render';
import { PhoneFrameComponent } from './phone-frame.component';

describe('hq-phone-frame', () => {
  it('names the preview region so it is reachable and understandable', async () => {
    await renderHq(PhoneFrameComponent, {
      inputs: { label: 'Parent app preview', caption: 'Parent mode' },
    });

    const screenRegion = screen.getByRole('group', { name: 'Parent app preview' });
    expect(screenRegion).toHaveAttribute('tabindex', '0');
    expect(screen.getByText('Parent mode')).toBeInTheDocument();
  });

  it('scrolls its own content rather than the page', async () => {
    const { fixture } = await renderHq(PhoneFrameComponent, {
      inputs: { label: 'Parent app preview' },
    });

    const screenRegion = (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>('.phone__screen');
    expect(screenRegion).not.toBeNull();
    expect(getComputedStyle(screenRegion!).overflow).toBe('auto');
  });
});
