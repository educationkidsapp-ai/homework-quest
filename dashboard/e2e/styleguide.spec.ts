import { expect, test, type Page } from '@playwright/test';

const SHOTS = '../docs/screenshots/dashboard-p1.0';

/**
 * The screenshots attached to the P1.0 PR.
 *
 * Motion is reduced before every capture so the images are deterministic: with animations
 * running, the step strip's arc and the count-up figure differ between runs.
 */
async function openStyleguide(page: Page, language: 'en' | 'ar'): Promise<void> {
  await page.addInitScript((lang) => {
    window.localStorage.setItem('hq.language', lang);
  }, language);
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

  test('opens the shortcuts sheet with ?', async ({ page }) => {
    await openStyleguide(page, 'en');

    await page.keyboard.press('Shift+Slash');

    await expect(page.getByRole('dialog', { name: 'Keyboard shortcuts' })).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/shortcuts-en.png` });
  });
});
