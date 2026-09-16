import { screen } from '@testing-library/angular';
import { describe, expect, it } from 'vitest';
import { renderHq } from '../../../testing/render';
import { PhonePreviewComponent } from './phone-preview.component';
import { SEED_STOPS } from './stop.fixtures';
import { WrongAnswerPreviewComponent } from './wrong-answer.preview';

describe('hq-wrong-answer-preview', () => {
  it("shows the stop's own hint and a way back into the question", async () => {
    await renderHq(WrongAnswerPreviewComponent, { inputs: { stop: SEED_STOPS.choice } });

    expect(screen.getByText('Mummy has a cold. What helps when you are ill?')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Try again/ })).toBeInTheDocument();
  });

  it('draws the number line for a numeric question', async () => {
    await renderHq(WrongAnswerPreviewComponent, { inputs: { stop: SEED_STOPS.sequence } });

    const line = screen.getByRole('list', { name: 'Number line' });

    expect(line).toBeInTheDocument();
    // 0 … 10, with the counting-by-2s marks lit
    expect(screen.getAllByRole('listitem')).toHaveLength(11);
  });

  it('leaves the number line out when the question was not a numeric one', async () => {
    await renderHq(WrongAnswerPreviewComponent, { inputs: { stop: SEED_STOPS.sound } });

    expect(screen.queryByRole('list', { name: 'Number line' })).not.toBeInTheDocument();
  });

  it('carries no cross, no score and no clock', async () => {
    const { container } = await renderHq(WrongAnswerPreviewComponent, {
      inputs: { stop: SEED_STOPS.compare },
    });

    const text = container.textContent ?? '';

    expect(text).not.toContain('%');
    expect(text).not.toMatch(/[✗✘❌✖×]/u);
    expect(text).not.toMatch(/\bseconds?\b|\btimer\b/iu);
  });

  it('falls back to encouragement for a stop that has no hint', async () => {
    await renderHq(WrongAnswerPreviewComponent, {
      inputs: { stop: SEED_STOPS.move, fallbackHint: 'Have another look.' },
    });

    expect(screen.getByText('Have another look.')).toBeInTheDocument();
  });

  it('is the wrongAnswer screen of the phone preview, over the question it came from', async () => {
    await renderHq(PhonePreviewComponent, {
      inputs: { label: 'Child app preview', screen: 'wrongAnswer', stop: SEED_STOPS.choice },
    });

    // the question is still there, with its tiles
    expect(screen.getByText('Why do Alan and Daddy make soup?')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Try again/ })).toBeInTheDocument();
  });
});
