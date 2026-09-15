import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderHq } from '../../../testing/render';
import { CheckboxComponent } from './checkbox.component';

describe('hq-checkbox', () => {
  it('toggles through the label and updates the model', async () => {
    const { fixture } = await renderHq(CheckboxComponent, {
      inputs: { label: 'Send an invite email' },
    });

    const checkbox = screen.getByRole('checkbox', { name: /Send an invite email/ });
    expect(checkbox).not.toBeChecked();

    await userEvent.click(checkbox);

    expect(checkbox).toBeChecked();
    expect(fixture.componentInstance.checked()).toBe(true);
  });

  it('exposes its hint as the accessible description', async () => {
    await renderHq(CheckboxComponent, {
      inputs: { label: 'Send an invite email', hint: 'Valid for seven days.' },
    });

    expect(screen.getByRole('checkbox')).toHaveAccessibleDescription('Valid for seven days.');
  });

  it('cannot be toggled when disabled', async () => {
    const { fixture } = await renderHq(CheckboxComponent, {
      inputs: { label: 'Locked', disabled: true },
    });

    await userEvent.click(screen.getByRole('checkbox'));

    expect(fixture.componentInstance.checked()).toBe(false);
  });
});
