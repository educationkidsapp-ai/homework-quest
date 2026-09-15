import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderHq } from '../../../testing/render';
import { SelectComponent, type SelectOption } from './select.component';

const options: readonly SelectOption[] = [
  { value: 'american', label: 'American' },
  { value: 'british', label: 'British' },
  { value: 'ib', label: 'IB', disabled: true },
];

describe('hq-select', () => {
  it('renders every option and writes the chosen value back', async () => {
    const { fixture } = await renderHq(SelectComponent, {
      inputs: { label: 'Curriculum', options },
    });

    const select = screen.getByLabelText('Curriculum');
    expect(screen.getAllByRole('option')).toHaveLength(3);

    await userEvent.selectOptions(select, 'british');

    expect(fixture.componentInstance.value()).toBe('british');
  });

  it('keeps a disabled option unselectable', async () => {
    await renderHq(SelectComponent, { inputs: { label: 'Curriculum', options } });

    expect(screen.getByRole('option', { name: 'IB' })).toBeDisabled();
  });
});
