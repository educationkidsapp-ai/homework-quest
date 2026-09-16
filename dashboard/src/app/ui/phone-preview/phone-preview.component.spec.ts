import { screen } from '@testing-library/angular';
import { describe, expect, it } from 'vitest';
import { renderHq } from '../../../testing/render';
import { PhonePreviewComponent } from './phone-preview.component';
import { SEED_STOPS, SEED_THEME } from './stop.fixtures';

describe('hq-phone-preview', () => {
  it('names the device frame it draws the child app in', async () => {
    await renderHq(PhonePreviewComponent, {
      inputs: { label: 'Child app preview', stop: SEED_STOPS.sequence },
    });

    expect(screen.getByRole('group', { name: 'Child app preview' })).toBeInTheDocument();
  });

  it('switches between the three screens', async () => {
    const { rerender } = await renderHq(PhonePreviewComponent, {
      inputs: { label: 'Child app preview', stop: SEED_STOPS.sequence },
    });
    expect(screen.getByLabelText('missing number')).toBeInTheDocument();

    // `partialUpdate`: without it the helper deletes the inputs a rerender leaves out.
    await rerender({
      partialUpdate: true,
      inputs: {
        screen: 'map',
        theme: SEED_THEME,
        islands: [{ id: 'wed', label: 'Wednesday', state: 'today' }],
      },
    });
    expect(screen.getByRole('button', { name: 'Wednesday, today' })).toBeInTheDocument();

    await rerender({ partialUpdate: true, inputs: { screen: 'wrongAnswer' } });
    expect(screen.getByText('Start at 6 and jump 2.')).toBeInTheDocument();
  });

  it('carries the subject down to the stop, so the world palette follows the lesson', async () => {
    const { fixture } = await renderHq(PhonePreviewComponent, {
      inputs: { label: 'Child app preview', subject: 'math', stop: SEED_STOPS.count },
    });

    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-hq-screen]')).toHaveAttribute('data-subject', 'math');
    expect(host.querySelector('hq-stop-preview')).toHaveAttribute('data-subject', 'math');
  });

  it('resolves a page image for the stop that carries one', async () => {
    await renderHq(PhonePreviewComponent, {
      inputs: {
        label: 'Child app preview',
        stop: { ...SEED_STOPS.trueFalse, imageId: 'page-3' },
        images: [{ id: 'page-3', url: 'https://example.test/page-3.png', description: 'page 3' }],
      },
    });

    expect(screen.getByRole('img', { name: 'page 3' })).toBeInTheDocument();
  });
});
