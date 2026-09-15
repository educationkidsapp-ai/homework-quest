import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderHq } from '../../../testing/render';
import { NavComponent, type NavItem } from './nav.component';

const items: readonly NavItem[] = [
  { id: 'home', label: 'Home' },
  { id: 'schools', label: 'Schools' },
  { id: 'lessons', label: 'All lessons', badge: 3 },
];

describe('hq-nav', () => {
  it('names itself and marks the active item as the current page', async () => {
    await renderHq(NavComponent, {
      inputs: { items, active: 'schools', label: 'Admin' },
      providers: [provideRouter([])],
    });

    expect(screen.getByRole('navigation', { name: 'Admin' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Schools' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('button', { name: 'Home' })).not.toHaveAttribute('aria-current');
  });

  it('moves the active item on click', async () => {
    const { fixture } = await renderHq(NavComponent, {
      inputs: { items, active: 'schools', label: 'Admin' },
      providers: [provideRouter([])],
    });

    await userEvent.click(screen.getByRole('button', { name: /All lessons/ }));

    expect(fixture.componentInstance.active()).toBe('lessons');
  });

  it('positions a single rule rather than one border per item', async () => {
    const { fixture } = await renderHq(NavComponent, {
      inputs: { items, active: 'schools', label: 'Admin' },
      providers: [provideRouter([])],
    });

    const rules = (fixture.nativeElement as HTMLElement).querySelectorAll('.nav__rule');
    expect(rules).toHaveLength(1);
    expect((rules[0] as HTMLElement).style.getPropertyValue('--hq-nav-rule-opacity')).toBe('1');
  });
});
