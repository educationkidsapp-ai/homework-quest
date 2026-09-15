import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { renderHq } from '../../../testing/render';
import { InputComponent } from './input.component';

describe('hq-input', () => {
  it('labels the control and writes typed text back through the model', async () => {
    const { fixture } = await renderHq(InputComponent, { inputs: { label: 'School name' } });

    const field = screen.getByLabelText(/School name/);
    await userEvent.type(field, 'Greenfield');

    expect(fixture.componentInstance.value()).toBe('Greenfield');
  });

  it('describes the field with its hint', async () => {
    await renderHq(InputComponent, {
      inputs: { label: 'School code', hint: 'Six characters.' },
    });

    expect(screen.getByLabelText(/School code/)).toHaveAccessibleDescription('Six characters.');
  });

  it('announces an error and marks the control invalid', async () => {
    await renderHq(InputComponent, {
      inputs: { label: 'School code', error: 'That code is already taken.' },
    });

    expect(screen.getByRole('alert')).toHaveTextContent('That code is already taken.');
    expect(screen.getByLabelText(/School code/)).toHaveAttribute('aria-invalid', 'true');
  });

  it('ignores Enter unless enterSubmit is set', async () => {
    const enterSubmitted = vi.fn();
    await renderHq(InputComponent, {
      inputs: { label: 'Search', enterSubmit: false },
      on: { enterSubmitted },
    });

    await userEvent.type(screen.getByLabelText(/Search/), 'maths{Enter}');

    expect(enterSubmitted).not.toHaveBeenCalled();
  });

  it('emits the current value on Enter when enterSubmit is set', async () => {
    const enterSubmitted = vi.fn();
    await renderHq(InputComponent, {
      inputs: { label: 'Search', enterSubmit: true },
      on: { enterSubmitted },
    });

    await userEvent.type(screen.getByLabelText(/Search/), 'maths{Enter}');

    expect(enterSubmitted).toHaveBeenCalledWith('maths');
  });
});
