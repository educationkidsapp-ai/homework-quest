import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { screen, waitFor } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PATH } from '../api';
import { renderHq } from '../../testing/render';
import { TEACHER_USER } from '../../testing/fixtures';
import { AuthService } from '../core/auth/auth.service';
import { SidebarService } from '../core/shell/sidebar.service';
import { DarkModeService } from '../core/theme/dark-mode.service';
import { ShellHeaderComponent } from './shell-header.component';

const providers = [
  provideHttpClient(),
  provideHttpClientTesting(),
  provideRouter([]),
  { provide: BASE_PATH, useValue: '' },
];

/**
 * Signs someone in through the real service and the real `GET /me`, rather than writing to a
 * private signal: the header's whole job is to render what that response says.
 */
function signIn(): void {
  TestBed.inject(AuthService).loadMe().subscribe();
  TestBed.inject(HttpTestingController)
    .expectOne((request) => request.url.endsWith('/me'))
    .flush(TEACHER_USER);
}

describe('hq-shell-header', () => {
  beforeEach(() => localStorage.clear());

  it('offers the rail, the language, the scheme and the account, and nothing else', async () => {
    await renderHq(ShellHeaderComponent, { providers });
    signIn();

    expect(screen.getByRole('button', { name: 'Show or hide the menu' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Language' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Dark mode' })).toBeInTheDocument();
    // D13: one school, so no switcher — it is Admin-only *and* behind `multiSchool`.
    expect(screen.queryByRole('button', { name: 'School' })).not.toBeInTheDocument();
  });

  it('keeps the scheme toggle a pressed button with the attribute the screenshots drive', async () => {
    const { fixture } = await renderHq(ShellHeaderComponent, { providers });
    const toggle = screen.getByRole('button', { name: 'Dark mode' });

    expect(toggle).toHaveAttribute('data-hq-scheme-toggle');
    expect(toggle).toHaveAttribute('aria-pressed', 'false');

    await userEvent.click(toggle);
    fixture.detectChanges();

    expect(TestBed.inject(DarkModeService).isDark()).toBe(true);
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
  });

  it('hands the burger to the sidebar so closing the drawer can give focus back', async () => {
    const { fixture } = await renderHq(ShellHeaderComponent, { providers });
    const sidebar = TestBed.inject(SidebarService);

    await userEvent.click(screen.getByRole('button', { name: 'Show or hide the menu' }));
    fixture.detectChanges();

    // On this viewport (JSDOM has no `matchMedia`, so the rail is a column) the button
    // collapses rather than opening a drawer, and says so.
    expect(sidebar.collapsed()).toBe(true);
    expect(screen.getByRole('button', { name: 'Show or hide the menu' })).toHaveAttribute(
      'aria-expanded',
      'false',
    );
  });

  /**
   * The user dropdown is a CDK menu, which is the whole reason it is one: `role="menu"`, a
   * roving tab index, Home/End and typeahead, Escape, and focus back on the trigger are the
   * platform's contract rather than four hand-written keydown handlers.
   */
  describe('the user dropdown', () => {
    it('names whoever is signed in, and offers Profile and Sign out', async () => {
      const { fixture } = await renderHq(ShellHeaderComponent, { providers });
      signIn();
      fixture.detectChanges();

      await userEvent.click(screen.getByRole('button', { name: /Ms Sara/ }));

      const menu = await screen.findByRole('menu', { name: 'Your account' });
      expect(menu).toHaveTextContent('Ms Sara');
      expect(menu).toHaveTextContent('Teacher');
      expect(menu).toHaveTextContent('sara@alnoor.test');
      expect(screen.getByRole('menuitem', { name: 'Profile' })).toHaveAttribute('href', '/profile');
      expect(screen.getByRole('menuitem', { name: 'Sign out' })).toBeInTheDocument();
    });

    /**
     * Opened from the keyboard, the first row takes focus and the rest are off the tab order —
     * which is what makes the arrow keys, and not Tab, the way through the panel. Closing it
     * puts focus back on the trigger rather than dropping it on `<body>`.
     *
     * The arrow walk and Escape both need a layout engine to prove: JSDOM has none, so the
     * CDK's interactivity checker finds nothing focusable and its overlay never sees the key —
     * the same gap `tour.component.spec.ts` documents. `e2e/local/shell.spec.ts` drives both in
     * a real browser.
     */
    it('is a roving tab stop, and hands focus back when it closes', async () => {
      const { fixture } = await renderHq(ShellHeaderComponent, { providers });
      signIn();
      fixture.detectChanges();
      const trigger = screen.getByRole('button', { name: /Ms Sara/ });

      trigger.focus();
      await userEvent.keyboard('{Enter}');
      await screen.findByRole('menu', { name: 'Your account' });
      await waitFor(() => expect(screen.getByRole('menuitem', { name: 'Profile' })).toHaveFocus());

      expect(screen.getByRole('menuitem', { name: 'Profile' })).toHaveAttribute('tabindex', '0');
      expect(screen.getByRole('menuitem', { name: 'Sign out' })).toHaveAttribute('tabindex', '-1');
      expect(trigger).toHaveAttribute('aria-expanded', 'true');

      await userEvent.click(trigger);
      await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument());
      expect(trigger).toHaveFocus();
      expect(trigger).toHaveAttribute('aria-expanded', 'false');
    });

    it('switches the language from its own control, not from inside the account menu', async () => {
      const { fixture } = await renderHq(ShellHeaderComponent, { providers });
      signIn();
      fixture.detectChanges();

      await userEvent.click(screen.getByRole('button', { name: 'Language' }));

      const menu = await screen.findByRole('menu', { name: 'Language' });
      expect(menu).toHaveTextContent('English');
      expect(menu).toHaveTextContent('العربية');
      expect(screen.getByRole('menuitem', { name: 'English' })).toHaveAttribute('aria-current', 'true');
    });
  });
});
