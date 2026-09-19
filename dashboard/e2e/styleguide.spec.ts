import { expect, test, type Page } from '@playwright/test';
import { shoot } from './local/env';

const SHOTS = '../docs/screenshots/dashboard-p1.0';
/** The four frames the T1 PR is reviewed from: both languages against both schemes. */
const THEME_SHOTS = '../docs/screenshots/theme-t1';
/** T4's: the same four frames once the T3 kit's variants are on the page. */
const T4_SHOTS = '../docs/screenshots/theme-t4';

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

  /**
   * T4: the T3 kit's own variants, which the guide had no frame for.
   *
   * Every one of these shipped in #81 and none of them was drawn here, so the light/dark pair
   * below proved the *foundations* and nothing built on them. The assertions are deliberately
   * about presence and count rather than about pixels — a screenshot is the record, and a
   * count is what fails when a variant quietly stops rendering.
   */
  for (const scheme of ['light', 'dark'] as const) {
    test(`the T3 kit's variants, ${scheme}`, async ({ page }) => {
      await openStyleguide(page, 'en', scheme);

      // Badges: five tones, twice over — soft and solid (styles.scss:204).
      await expect(page.locator('.hq-badge')).toHaveCount(10);
      await expect(page.locator('.hq-badge--solid')).toHaveCount(5);

      // Cards: the default frame, `nested`, `flush`, and one with neither title nor eyebrow.
      await expect(page.locator('hq-card.card--nested')).toHaveCount(1);
      await expect(page.locator('hq-card.card--flush').first()).toBeVisible();

      // Buttons: the 44 × 44 icon variant, which no feature screen uses, and the block one.
      await expect(page.locator('.btn--icon')).toHaveCount(2);
      await expect(page.locator('.btn--block')).toHaveCount(1);

      // Tabs: the underline set and T3's chips, the same contract twice.
      await expect(page.locator('hq-tabs')).toHaveCount(2);

      // Progress: §3's three thresholds plus `plain`, so the rule is visible in one frame.
      for (const tone of ['good', 'mid', 'look', 'plain']) {
        await expect(page.locator(`.bar--${tone}`).first()).toBeVisible();
      }

      // The input keycap and the table's cell tile, both new in T3.
      await expect(page.locator('.field__keycap')).toHaveCount(1);
      await expect(page.locator('.hq-cell__tile').first()).toBeVisible();

      await shoot(page, `${T4_SHOTS}/styleguide-en-${scheme}.png`, page.getByRole('heading', { level: 1 }), {
        fullPage: true,
      });
    });
  }

  /**
   * T5.2: the contrast row, and the one role allowed to be under it.
   *
   * The guide measures every role that is ever set as text against the card it is drawn on,
   * composited, in the scheme showing — which is the only way to get a true number for the two
   * that are `color-mix`ed from a school's accent at runtime, and for the dark card, whose
   * surface is translucent. The assertion is the *list* of roles under 4.5:1 rather than a
   * floor, so a new one appearing is a failure and the known exemption stays named.
   */
  for (const scheme of ['light', 'dark'] as const) {
    test(`every role set as text clears AA, ${scheme}`, async ({ page }) => {
      await openStyleguide(page, 'en', scheme);

      const rows = page.locator('.sg__contrast-role');
      expect(await rows.count(), 'the contrast row measured nothing').toBeGreaterThan(5);

      const under = await page
        .locator(".sg__contrast-role[data-hq-aa='false']")
        .evaluateAll((elements) => elements.map((el) => el.getAttribute('data-hq-role') ?? '?'));

      // `ink-muted` is the placeholder and disabled ink, which WCAG exempts and which has to
      // read as unavailable next to `ink-soft`. Everything else is body copy or a label.
      expect(under, `roles under AA as text in ${scheme}`).toEqual(['--hq-color-ink-muted']);
    });
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
