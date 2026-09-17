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

  it('stays on the placeholder — not the first real option — until something is chosen', async () => {
    // Regression: a `disabled` placeholder option is deselected by the browser the moment a
    // second render pass applies it, and the `<select>` falls back to its first *enabled*
    // option — "Math" would show pre-selected while `value()` was still `''`. See the comment
    // on the placeholder `<option>` in select.component.ts.
    const subjects: readonly SelectOption[] = [
      { value: 'math', label: 'Math' },
      { value: 'english', label: 'English' },
    ];
    const { fixture } = await renderHq(SelectComponent, {
      inputs: { label: 'Subject', options: subjects, placeholder: 'Choose a subject', value: '' },
    });
    fixture.detectChanges();

    expect(screen.getByLabelText('Subject')).toHaveValue('');
  });
});
