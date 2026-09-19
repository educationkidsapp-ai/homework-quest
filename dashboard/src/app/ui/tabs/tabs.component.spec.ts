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

  /**
   * The hover-versus-selected rule, asserted as specificity rather than as a colour.
   *
   * `.tabs--chips .tabs__tab:hover:not(:disabled)` is (0,4,0) and
   * `.tabs--chips .tabs__tab[aria-selected='true']` is (0,3,0), so hover wins and a selected
   * tab under the pointer takes the *hover* background while keeping the selected ink — which
   * on chips was white on `#f9fafb`, 1.05:1 (§4.4b of `docs/reports/tailadmin-restyle.md`), and
   * on the underline variant cost the selected tab its accent. Both variants now exclude the
   * selected state from their hover rule.
   *
   * Read out of the stylesheet the component actually emitted, because the defect is in the
   * cascade and not in anything a rendered element says about itself: jsdom will not hover, and
   * a test that asserted a colour would pass on the broken rule.
   */
  it('never lets hover override the selected tab, in either variant', async () => {
    await renderHq(TabsComponent, { inputs: { tabs, selected: 'overview', label: 'School settings' } });

    const hoverRules = [...document.styleSheets]
      .flatMap((sheet) => {
        try {
          return [...sheet.cssRules];
        } catch {
          return [];
        }
      })
      .filter((rule): rule is CSSStyleRule => 'selectorText' in rule)
      .map((rule) => rule.selectorText)
      .filter((selector) => selector.includes('.tabs__tab') && selector.includes(':hover'));

    expect(hoverRules.length, 'no tab hover rule was emitted at all').toBeGreaterThan(1);
    for (const selector of hoverRules) {
      // Serialized by the browser, so the attribute's quotes are its choice and not ours.
      expect(selector, `${selector} outranks the selected rule`).toMatch(
        /:not\(\[aria-selected=["']true["']\]\)/,
      );
    }
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
