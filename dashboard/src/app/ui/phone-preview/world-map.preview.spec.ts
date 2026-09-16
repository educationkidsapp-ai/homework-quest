import { screen } from '@testing-library/angular';
import { describe, expect, it } from 'vitest';
import { renderHq } from '../../../testing/render';
import { SEED_THEME } from './stop.fixtures';
import { MapIsland, WorldMapPreviewComponent } from './world-map.preview';

const ISLANDS: readonly MapIsland[] = [
  { id: 'mon', label: 'Monday', state: 'done', stars: 7 },
  { id: 'tue', label: 'Tuesday', state: 'done', stars: 5 },
  { id: 'wed', label: 'Wednesday', state: 'today' },
  { id: 'thu', label: 'Thursday', state: 'locked' },
];

describe('hq-world-map-preview', () => {
  it("marks today's island as the one to go to", async () => {
    await renderHq(WorldMapPreviewComponent, { inputs: { islands: ISLANDS } });

    const today = screen.getByRole('button', { name: 'Wednesday, today' });

    expect(today).toHaveAttribute('aria-current', 'step');
    expect(today).toHaveAttribute('data-hq-island', 'today');
  });

  it('shows the stars a finished island earned', async () => {
    await renderHq(WorldMapPreviewComponent, { inputs: { islands: ISLANDS } });

    expect(screen.getByRole('button', { name: 'Monday, done, 7 stars' })).toHaveAttribute(
      'data-hq-island',
      'done',
    );
    expect(screen.getByRole('button', { name: 'Tuesday, done, 5 stars' })).toBeInTheDocument();
  });

  it('leaves a locked island asleep, with Pip asleep on it', async () => {
    const { container } = await renderHq(WorldMapPreviewComponent, { inputs: { islands: ISLANDS } });

    const locked = screen.getByRole('button', { name: 'Thursday, still asleep' });

    expect(locked).toHaveAttribute('data-hq-island', 'locked');
    expect(locked.querySelector('hq-pip')).toHaveAttribute('data-hq-pose', 'sleeping');
    // no "0 of 7" and no cross on a day the child has not reached
    expect(container.textContent ?? '').not.toContain('%');
  });

  it('with no lesson yet, puts Pip asleep on a raft and says who can add one', async () => {
    await renderHq(WorldMapPreviewComponent, {
      inputs: {
        islands: [],
        emptyMessage: 'No quest today yet. Ask a grown-up to add today’s lesson.',
      },
    });

    expect(screen.getByRole('img', { name: 'Pip is asleep on a raft' })).toBeInTheDocument();
    expect(screen.getByText('No quest today yet. Ask a grown-up to add today’s lesson.')).toBeInTheDocument();
    expect(screen.queryAllByRole('button')).toHaveLength(0);
  });

  it('names the pot the lesson fills, and the child', async () => {
    await renderHq(WorldMapPreviewComponent, {
      inputs: { islands: ISLANDS, theme: SEED_THEME, childName: 'Alan' },
    });

    expect(screen.getByText('Hot soup for Mummy')).toBeInTheDocument();
    expect(screen.getByText('Hi Alan!')).toBeInTheDocument();
  });
});
