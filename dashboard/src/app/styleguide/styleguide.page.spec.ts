import { provideRouter } from '@angular/router';
import { screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { renderHq } from '../../testing/render';
import { StyleguidePage } from './styleguide.page';

describe('styleguide', () => {
  // LanguageService persists the choice, so one test's switch would otherwise
  // decide the next test's language.
  beforeEach(() => localStorage.clear());

  it('renders the component library in English', async () => {
    await renderHq(StyleguidePage, { providers: [provideRouter([])] });

    expect(screen.getByRole('heading', { level: 1, name: 'Component styleguide' })).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Lessons' })).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Admin' })).toBeInTheDocument();
  });

  it('switches the whole page to Arabic and flips the direction', async () => {
    const { fixture } = await renderHq(StyleguidePage, { providers: [provideRouter([])] });

    await userEvent.click(screen.getByRole('button', { name: 'العربية' }));
    fixture.detectChanges();
    await fixture.whenStable();

    expect(document.documentElement.dir).toBe('rtl');
    expect(screen.getByRole('heading', { level: 1, name: 'دليل المكوّنات' })).toBeInTheDocument();
  });

  it('turns motion off for the whole document from one switch', async () => {
    const { fixture } = await renderHq(StyleguidePage, { providers: [provideRouter([])] });

    await userEvent.click(screen.getByRole('switch', { name: 'Reduce motion' }));
    fixture.detectChanges();

    expect(document.documentElement.dataset['hqReducedMotion']).toBe('true');
  });
});
