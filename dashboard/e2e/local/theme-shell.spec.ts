import { expect, test, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { setLanguage, setScheme, shoot, signInAsSara } from './env';

/**
 * The rail's rendered width, polled rather than measured once: `transition: width .3s ease` is
 * the spec's, so the box on the frame after the click is still the old one.
 */
async function expectRailWidth(page: Page, width: number): Promise<void> {
  await expect
    .poll(async () => (await page.locator('hq-nav .nav__panel').boundingBox())?.width, {
      timeout: 5_000,
    })
    .toBe(width);
}

/**
 * T2's acceptance — the shell itself against `docs/prompts/tailadmin-spec.md` §2, §3 and §5:
 * the 290/90 px sidebar, the off-canvas drawer under 1024 px, the header's controls and the
 * user dropdown's keyboard contract, in both languages and both schemes.
 *
 * The screenshot matrix is the other half. Three widths, because this is the package that gave
 * the shell a second and a third presentation and a picture at 1366 px alone would prove none
 * of it: 1366 (the reference viewport), 768 with the drawer shut and open, and 375. Each in
 * English and Arabic, light and dark, on This week and on a class page.
 */
const SHOTS = resolve(process.cwd(), '../docs/screenshots/theme-t2');

const WIDE = { width: 1366, height: 768 };
const TABLET = { width: 768, height: 1024 };
const PHONE = { width: 375, height: 812 };

/** The rail, not the page's breadcrumb: both are `role="navigation"` on the class page. */
function rail(page: Page) {
  return page.locator('hq-nav');
}

function burger(page: Page) {
  return page.locator('[data-hq-sidebar-toggle]');
}

/** 1A British's class page, reached the way she reaches it. */
async function openClass(page: Page): Promise<void> {
  await rail(page)
    .getByRole('link', { name: /My classes|فصولي/ })
    .click();
  await page.locator('hq-card').first().getByRole('link').first().click();
  await expect(page.getByRole('grid')).toBeVisible();
}

test.describe('the sidebar', () => {
  test('collapses to 90 px, remembers it, and keeps every item named', async ({ page }) => {
    await page.setViewportSize(WIDE);
    await signInAsSara(page);
    const panel = page.locator('hq-nav .nav__panel');

    await expectRailWidth(page, 290);
    // Expanded, the item is named by the text you can read; nothing repeats it in an
    // `aria-label`, which would put "My classes" in reach of a form field's `getByLabel`.
    const week = rail(page).getByRole('link', { name: 'This week' });
    await expect(week).not.toHaveAttribute('aria-label', /./);

    await burger(page).click();
    await expect(panel).toHaveClass(/is-collapsed/);
    await expectRailWidth(page, 90);
    // The label is hidden, not removed: the item is still named for a screen reader, and the
    // `title` gives a pointer the same word.
    await expect(week).toHaveAttribute('title', 'This week');
    await expect(week.locator('.nav__label')).toBeHidden();

    // Per viewer, in this browser — a reload comes back to the rail she left.
    await page.reload();
    await expect(page.locator('hq-nav .nav__panel')).toHaveClass(/is-collapsed/);

    // TailAdmin's hover-expand, over the content rather than shoving it sideways.
    await page.locator('hq-nav .nav__panel').hover();
    await expect(page.locator('hq-nav .nav__panel')).not.toHaveClass(/is-collapsed/);

    await burger(page).click();
    await expect(page.locator('hq-nav .nav__panel')).not.toHaveClass(/is-collapsed/);
  });

  test('is a drawer under 1024 px: scrim, Escape, and focus back on the burger', async ({ page }) => {
    await page.setViewportSize(TABLET);
    await signInAsSara(page);
    const host = page.locator('hq-nav');

    await expect(host).toHaveClass(/hq-nav--drawer/);
    await expect(host).not.toHaveClass(/hq-nav--open/);

    await burger(page).click();
    await expect(host).toHaveClass(/hq-nav--open/);
    // The page behind a drawer does not scroll.
    await expect(page.locator('body')).toHaveCSS('overflow', 'hidden');

    // Tab is trapped: five presses from inside cannot reach the header behind the scrim.
    for (let press = 0; press < 5; press++) await page.keyboard.press('Tab');
    expect(
      await host.evaluate((node) => node.contains(document.activeElement)),
      'Tab walked out of a drawer that claims to be modal',
    ).toBe(true);

    await page.keyboard.press('Escape');
    await expect(host).not.toHaveClass(/hq-nav--open/);
    await expect(burger(page)).toBeFocused();
    await expect(page.locator('body')).not.toHaveCSS('overflow', 'hidden');

    // And the scrim closes it too, because a tap beside a drawer means "not this".
    await burger(page).click();
    await expect(host).toHaveClass(/hq-nav--open/);
    await page.locator('hq-nav .nav__scrim').click({ position: { x: 700, y: 400 } });
    await expect(host).not.toHaveClass(/hq-nav--open/);
  });

  test('sits on the inline-start edge in Arabic, and slides in from there', async ({ page }) => {
    await page.setViewportSize(WIDE);
    await signInAsSara(page);
    await setLanguage(page, 'ar');

    // RTL: the rail is on the right, so its box starts past the middle of the viewport.
    const panel = await page.locator('hq-nav .nav__panel').boundingBox();
    expect(panel?.x).toBeGreaterThan(WIDE.width / 2);

    await page.setViewportSize(TABLET);
    await burger(page).click();
    await expect(page.locator('hq-nav')).toHaveClass(/hq-nav--open/);
    const drawer = await page.locator('hq-nav .nav__panel').boundingBox();
    expect(drawer?.x).toBeGreaterThan(TABLET.width / 2);

    await page.keyboard.press('Escape');
    await setLanguage(page, 'en');
  });
});

test.describe('the header', () => {
  test('names whoever is signed in, and Escape closes the account menu', async ({ page }) => {
    await page.setViewportSize(WIDE);
    await signInAsSara(page);

    const trigger = page.getByRole('button', { name: /Sara/ });
    await trigger.click();
    const menu = page.getByRole('menu', { name: 'Your account' });
    await expect(menu).toBeVisible();
    await expect(menu).toContainText('Teacher');
    await expect(menu).toContainText('sara.al-harbi@school.test');

    // The arrow keys walk the rows — which is what makes Tab not the way through a menu.
    await page.keyboard.press('ArrowDown');
    await expect(page.getByRole('menuitem', { name: 'Show me around' })).toBeFocused();

    await page.keyboard.press('Escape');
    await expect(menu).toBeHidden();
    await expect(trigger).toBeFocused();
  });

  test('keeps the scheme toggle and switches the language from its own control', async ({ page }) => {
    await page.setViewportSize(WIDE);
    await signInAsSara(page);

    await setScheme(page, 'dark');
    await expect(page.locator('html')).toHaveClass(/dark/);
    await setScheme(page, 'light');

    await setLanguage(page, 'ar');
    await expect(rail(page).getByRole('link', { name: 'هذا الأسبوع' })).toBeVisible();
    await setLanguage(page, 'en');
  });
});

/**
 * The set. One sign-in, then every combination is a viewport change, a language switch and a
 * scheme switch on a page that is already loaded — the barrier in `shoot` is what makes that
 * safe, because it waits for this screen's own marker rather than for a sign of life.
 */
test('the screenshot set: two screens, two languages, two schemes, three widths', async ({ page }) => {
  test.setTimeout(300_000);
  await mkdir(SHOTS, { recursive: true });
  await page.setViewportSize(WIDE);
  await signInAsSara(page);

  const screens = [
    {
      name: 'week',
      open: async () => void (await page.goto('teacher/week')),
      marker: () => page.getByRole('grid'),
    },
    { name: 'class', open: () => openClass(page), marker: () => page.getByRole('grid') },
  ] as const;

  for (const screen of screens) {
    for (const language of ['en', 'ar'] as const) {
      await page.setViewportSize(WIDE);
      await setLanguage(page, language);
      await screen.open();

      for (const scheme of ['light', 'dark'] as const) {
        await setScheme(page, scheme);
        const tag = `${screen.name}-${language}-${scheme}`;

        await page.setViewportSize(WIDE);
        await shoot(page, `${SHOTS}/${tag}-1366.png`, screen.marker());

        // 768: the rail is a drawer, so both states are worth a frame — the one a teacher
        // lands on, and the one the burger opens.
        await page.setViewportSize(TABLET);
        await shoot(page, `${SHOTS}/${tag}-768.png`, screen.marker());
        await burger(page).click();
        await expect(page.locator('hq-nav')).toHaveClass(/hq-nav--open/);
        await shoot(page, `${SHOTS}/${tag}-768-drawer.png`, page.locator('hq-nav .nav__panel'));
        await page.keyboard.press('Escape');
        await expect(page.locator('hq-nav')).not.toHaveClass(/hq-nav--open/);

        await page.setViewportSize(PHONE);
        await shoot(page, `${SHOTS}/${tag}-375.png`, screen.marker());
      }

      await page.setViewportSize(WIDE);
      await setScheme(page, 'light');
    }
  }

  await setLanguage(page, 'en');
});
