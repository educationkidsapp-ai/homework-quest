import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderHq } from '../../../testing/render';
import { TabsComponent, type Tab } from './tabs.component';

const tabs: readonly Tab[] = [
  { id: 'overview', label: 'Overview' },
  { id: 'users', label: 'Users', badge: 12 },
  { id: 'theme', label: 'Theme' },
];

describe('hq-tabs', () => {
  it('marks one tab selected and keeps only that tab in the tab order', async () => {
    await renderHq(TabsComponent, { inputs: { tabs, selected: 'overview', label: 'School settings' } });

    expect(screen.getByRole('tab', { name: /Overview/ })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: /Users/ })).toHaveAttribute('tabindex', '-1');
  });

  it('selects on click', async () => {
    const { fixture } = await renderHq(TabsComponent, {
      inputs: { tabs, selected: 'overview', label: 'School settings' },
    });

    await userEvent.click(screen.getByRole('tab', { name: /Theme/ }));

    expect(fixture.componentInstance.selected()).toBe('theme');
  });

  it('moves with the arrow keys and wraps, per the ARIA tabs pattern', async () => {
    const { fixture } = await renderHq(TabsComponent, {
      inputs: { tabs, selected: 'overview', label: 'School settings' },
    });

    screen.getByRole('tab', { name: /Overview/ }).focus();
    await userEvent.keyboard('{ArrowRight}');
    expect(fixture.componentInstance.selected()).toBe('users');

    await userEvent.keyboard('{ArrowLeft}{ArrowLeft}');
    expect(fixture.componentInstance.selected()).toBe('theme');

    await userEvent.keyboard('{Home}');
    expect(fixture.componentInstance.selected()).toBe('overview');
  });
});
