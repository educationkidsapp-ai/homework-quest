import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderHq } from '../../../testing/render';
import { ToggleComponent } from './toggle.component';

describe('hq-toggle', () => {
  it('is a switch that reports its state', async () => {
    const { fixture } = await renderHq(ToggleComponent, { inputs: { label: 'Complaints' } });

    const toggle = screen.getByRole('switch', { name: 'Complaints' });
    expect(toggle).toHaveAttribute('aria-checked', 'false');

    await userEvent.click(toggle);

    expect(toggle).toHaveAttribute('aria-checked', 'true');
    expect(fixture.componentInstance.checked()).toBe(true);
  });

  it('flips with the keyboard', async () => {
    const { fixture } = await renderHq(ToggleComponent, { inputs: { label: 'Complaints' } });

    screen.getByRole('switch').focus();
    await userEvent.keyboard('{Enter}');

    expect(fixture.componentInstance.checked()).toBe(true);
  });

  it('stays put when disabled', async () => {
    const { fixture } = await renderHq(ToggleComponent, {
      inputs: { label: 'Three levels', disabled: true },
    });

    await userEvent.click(screen.getByRole('switch'));

    expect(fixture.componentInstance.checked()).toBe(false);
  });
});
