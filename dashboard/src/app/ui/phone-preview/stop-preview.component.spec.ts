import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderHq } from '../../../testing/render';
import { StopPreviewComponent } from './stop-preview.component';
import { SEED_STOPS } from './stop.fixtures';
import { STOP_TYPES, StopType } from './stop.model';

/**
 * Every stop type renders its own screen.
 *
 * The table below is keyed by `StopType`, so a type added to the contract fails to compile here
 * until somebody says what its screen must show; and the run is parametrised over `STOP_TYPES`, so
 * a type that silently stops rendering fails rather than quietly disappearing from the suite.
 */
const ASSERTIONS: Readonly<Record<StopType, () => void>> = {
  readPage: () => {
    expect(screen.getByText('Page 2')).toBeInTheDocument();
    expect(screen.getByText('They find peas too!')).toBeInTheDocument();
    // the tap task, over the picture
    expect(screen.getByRole('button', { name: 'carrot' })).toBeInTheDocument();
    expect(screen.getByText(/Tap the vegetables in the fridge\./)).toBeInTheDocument();
  },
  storyPieces: () => {
    for (const piece of ['title', 'genre', 'characters', 'setting', 'plot', 'problem']) {
      expect(screen.getByRole('button', { name: new RegExp(piece) })).toBeInTheDocument();
    }
  },
  wordCards: () => {
    expect(screen.getByText('fridge')).toBeInTheDocument();
    expect(screen.getByText('A cold box that keeps food fresh.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Next/ })).toBeInTheDocument();
  },
  move: () => {
    expect(screen.getByRole('button', { name: /Sniff the yummy soup!/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Carry a bowl very carefully!/ })).toBeInTheDocument();
  },
  explain: () => {
    expect(screen.getByText('Counting by 2s means we jump two each time!')).toBeInTheDocument();
    expect(screen.getByText('Start at 6')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Show me another/ })).toBeInTheDocument();
  },
  choice: () => {
    expect(screen.getByText('Why do Alan and Daddy make soup?')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /To help Mummy feel better/ })).toBeInTheDocument();
  },
  trueFalse: () => {
    expect(screen.getByText('Daddy chops the vegetables.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /True/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /False/ })).toBeInTheDocument();
  },
  sequence: () => {
    expect(screen.getByLabelText('missing number')).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '8' })).toBeInTheDocument();
  },
  count: () => {
    expect(screen.getAllByRole('group', { name: 'group of 2 shoe' })).toHaveLength(3);
    expect(screen.getByRole('button', { name: '6' })).toBeInTheDocument();
  },
  compare: () => {
    expect(screen.getByLabelText('number 8')).toBeInTheDocument();
    expect(screen.getByLabelText('number 6')).toBeInTheDocument();
    expect(screen.getByLabelText('missing sign')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '>' })).toBeInTheDocument();
  },
  sound: () => {
    expect(screen.getByRole('img', { name: 'ship' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'sh' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'ch' })).toBeInTheDocument();
  },
  word: () => {
    expect(screen.getByRole('button', { name: /Listen/ })).toBeInTheDocument();
    for (const option of ['shop', 'chop', 'stop']) {
      expect(screen.getByRole('button', { name: option })).toBeInTheDocument();
    }
  },
  readTap: () => {
    expect(screen.getByText('sheep')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Say sheep' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'fish' })).toBeInTheDocument();
  },
  multiSelect: () => {
    expect(screen.getByText('Tap two vegetables from the soup.')).toBeInTheDocument();
    expect(screen.getByText('2 more to tap')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'carrot' })).toBeInTheDocument();
  },
  selectAll: () => {
    expect(screen.getByText('Tap every word that starts with sh.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'shell' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Check/ })).toBeInTheDocument();
  },
  match: () => {
    expect(screen.getByText('Match the word to the picture.')).toBeInTheDocument();
    // the word and its picture, one on each side
    expect(screen.getAllByRole('button', { name: 'carrot' })).toHaveLength(2);
    expect(screen.getAllByRole('button', { name: 'spoon' })).toHaveLength(2);
    expect(screen.getAllByRole('listitem')).toHaveLength(8);
  },
  order: () => {
    expect(screen.getByRole('button', { name: 'slot 1: empty' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /The soup cooks in the pot\./ })).toBeInTheDocument();
  },
  trace: () => {
    expect(screen.getByLabelText('trace S')).toBeInTheDocument();
    expect(screen.getByText('Start at the top and curve like a snake.')).toBeInTheDocument();
  },
  retell: () => {
    expect(screen.getByText('Tell the story in your own words.')).toBeInTheDocument();
    expect(screen.getByText('Mummy is in bed…')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /I told it!/ })).toBeInTheDocument();
  },
  openAnswer: () => {
    expect(screen.getByText('Think of another way Alan could help Mummy.')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'drawing canvas' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Record/ })).toBeInTheDocument();
  },
  writeSentence: () => {
    expect(screen.getByText('Alan and Daddy make ___ for Mummy.')).toBeInTheDocument();
    for (const option of ['soup', 'cake', 'tea']) {
      expect(screen.getByRole('button', { name: option })).toBeInTheDocument();
    }
  },
  exitTicket: () => {
    expect(screen.getByLabelText('Question 1 of 3')).toBeInTheDocument();
    expect(screen.getByText('Who has a cold?')).toBeInTheDocument();
  },
};

describe('hq-stop-preview', () => {
  it.each(STOP_TYPES)('renders the %s stop', async (type) => {
    await renderHq(StopPreviewComponent, { inputs: { stop: SEED_STOPS[type] } });

    ASSERTIONS[type]();
  });

  it('tags the rendered stop with its type, so a screenshot names what it shows', async () => {
    const { fixture } = await renderHq(StopPreviewComponent, {
      inputs: { stop: SEED_STOPS.compare },
    });

    expect(fixture.nativeElement).toHaveAttribute('data-hq-stop-type', 'compare');
  });

  it('walks an exit ticket through its questions', async () => {
    await renderHq(StopPreviewComponent, { inputs: { stop: SEED_STOPS.exitTicket } });

    await userEvent.click(screen.getByRole('button', { name: 'Next question' }));

    expect(screen.getByText('Tap two vegetables from the soup.')).toBeInTheDocument();
    expect(screen.getByLabelText('Question 2 of 3')).toBeInTheDocument();
  });

  it('shows the picture a stop carries above the stop itself', async () => {
    await renderHq(StopPreviewComponent, {
      inputs: {
        stop: { ...SEED_STOPS.choice, imageId: 'page-7' },
        images: [{ id: 'page-7', url: 'https://example.test/page-7.png', description: 'page 7' }],
      },
    });

    expect(screen.getByRole('img', { name: 'page 7' })).toHaveAttribute(
      'src',
      'https://example.test/page-7.png',
    );
  });

  it('dims a wrong tile and leaves it where it was', async () => {
    await renderHq(StopPreviewComponent, { inputs: { stop: SEED_STOPS.sound } });

    await userEvent.click(screen.getByRole('button', { name: 'ch' }));

    expect(screen.getByRole('button', { name: 'ch' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'sh' })).toBeEnabled();
  });
});
