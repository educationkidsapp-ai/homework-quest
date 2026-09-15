import { screen } from '@testing-library/angular';
import { describe, expect, it } from 'vitest';
import { renderHq } from '../../../testing/render';
import { CardComponent } from './card.component';

describe('hq-card', () => {
  it('renders its heading and projected body', async () => {
    await renderHq(CardComponent, {
      inputs: { title: 'Lessons published', eyebrow: 'This week' },
      // Projected content is what a card is for, so the spec has to project something.
      componentProperties: {},
    });

    expect(screen.getByRole('heading', { name: 'Lessons published' })).toBeInTheDocument();
    expect(screen.getByText('This week')).toBeInTheDocument();
  });

  it('omits the header entirely when there is nothing to put in it', async () => {
    await renderHq(CardComponent);

    expect(screen.queryByRole('heading')).not.toBeInTheDocument();
  });
});
