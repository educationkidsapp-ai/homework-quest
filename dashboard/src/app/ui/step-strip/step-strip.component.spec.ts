import { screen, within } from '@testing-library/angular';
import { describe, expect, it } from 'vitest';
import { renderHq } from '../../../testing/render';
import { StepStripComponent, type PipelineStep, type StepState } from './step-strip.component';

const stateLabels: Record<StepState, string> = {
  pending: 'pending',
  running: 'in progress',
  done: 'done',
  error: 'failed',
};

const steps: readonly PipelineStep[] = [
  { id: 'upload', label: 'Upload', state: 'done' },
  { id: 'read', label: 'Read slides', state: 'running' },
  { id: 'generate', label: 'Write questions', state: 'pending' },
  { id: 'publish', label: 'Publish', state: 'error', detail: 'No text layer found' },
];

describe('hq-step-strip', () => {
  it('lists every step with its state in text, not only in the animation', async () => {
    await renderHq(StepStripComponent, { inputs: { steps, label: 'Lesson pipeline', stateLabels } });

    const list = screen.getByRole('list', { name: 'Lesson pipeline' });
    const items = within(list).getAllByRole('listitem');

    expect(items).toHaveLength(4);
    expect(items[0]).toHaveTextContent('Upload');
    expect(items[0]).toHaveTextContent('done');
    expect(items[1]).toHaveTextContent('in progress');
    expect(items[3]).toHaveTextContent('failed');
  });

  it('shows the failure detail so the user knows what to fix', async () => {
    await renderHq(StepStripComponent, { inputs: { steps, label: 'Lesson pipeline', stateLabels } });

    expect(screen.getByText('No text layer found')).toBeInTheDocument();
  });
});
