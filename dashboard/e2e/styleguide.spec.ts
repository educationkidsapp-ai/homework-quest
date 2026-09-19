import { expect, test, type Page } from '@playwright/test';
import { shoot } from './local/env';

const SHOTS = '../docs/screenshots/dashboard-p1.0';
/** The four frames the T1 PR is reviewed from: both languages against both schemes. */
const THEME_SHOTS = '../docs/screenshots/theme-t1';

/**
 * The screenshots attached to the P1.0 PR.
 *
 * Motion is reduced before every capture so the images are deterministic: with animations
 * running, the step strip's arc and the count-up figure differ between runs.
 */
async function openStyleguide(
  page: Page,
  language: 'en' | 'ar',
  scheme: 'light' | 'dark' = 'light',
): Promise<void> {
  // Both are read before the app boots — the language by `provideLanguage`, the scheme by
  // `applyStoredColorScheme` in `main.ts` — so they have to be in storage before the first
  // navigation rather than clicked afterwards.
  await page.addInitScript(
    ({ lang, theme }) => {
      window.localStorage.setItem('hq.language', lang);
      window.localStorage.setItem('theme', theme);
    },
    { lang: language, theme: scheme },
  );
  await page.goto('styleguide');
  await page.getByRole('heading', { level: 1 }).waitFor();
  await page.getByRole('switch').first().click();
  await expect(page.locator('html')).toHaveAttribute('data-hq-reduced-motion', 'true');
  await page.evaluate(() => document.fonts.ready);
}

test.describe('styleguide', () => {
  test('renders every component in English', async ({ page }) => {
    await openStyleguide(page, 'en');

    await expect(page.locator('html')).toHaveAttribute('dir', 'ltr');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Component styleguide');
    await expect(page.getByRole('table', { name: 'Lessons' })).toBeVisible();

    await page.screenshot({ path: `${SHOTS}/styleguide-en.png`, fullPage: true });
  });

  test('renders every component in Arabic, right to left', async ({ page }) => {
    await openStyleguide(page, 'ar');

    await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('دليل المكوّنات');

    await page.screenshot({ path: `${SHOTS}/styleguide-ar.png`, fullPage: true });
  });

  for (const language of ['en', 'ar'] as const) {
    for (const scheme of ['light', 'dark'] as const) {
      test(`foundations in ${language}, ${scheme}`, async ({ page }) => {
        await openStyleguide(page, language, scheme);

        const dark = await page.evaluate(() => document.documentElement.classList.contains('dark'));
        expect(dark, `the ${scheme} scheme was not applied before the first paint`).toBe(scheme === 'dark');

        await shoot(
          page,
          `${THEME_SHOTS}/styleguide-${language}-${scheme}.png`,
          page.getByRole('heading', { level: 1 }),
          {
            fullPage: true,
          },
        );
      });
    }
  }

  // The halo comes from the global `:focus-visible` and the outline from each component's
  // `focus-ring` mixin. They are different properties, which is the only reason the global one
  // survives a component stylesheet — worth a test, because the day a component sets its own
  // box-shadow the ring disappears silently from that control alone.
  test('gives a focused control both halves of the ring', async ({ page }) => {
    await openStyleguide(page, 'en');

    // A text field rather than a button: Chrome matches `:focus-visible` on a text field
    // however it was focused, while a button only matches it after a keyboard interaction.
    const field = page.getByLabel('School name');
    await field.focus();

    await expect(field).toHaveCSS('box-shadow', /rgba\(70, 95, 255/);
    await expect(field).toHaveCSS('outline-width', '3px');
  });

  test('opens the shortcuts sheet with ?', async ({ page }) => {
    await openStyleguide(page, 'en');

    await page.keyboard.press('Shift+Slash');

    await expect(page.getByRole('dialog', { name: 'Keyboard shortcuts' })).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/shortcuts-en.png` });
  });
});
