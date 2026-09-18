import { expect, test } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { ADMIN, SARA, settled, signIn } from './env';

/**
 * P3.1's acceptance — the shell itself: branding, the Content-Security-Policy, where each role
 * lands, the guards on rail and router, the keyboard contract and the screenshot set.
 *
 * Rewritten for **one school** (N2.5). It used to sign in as `teacher.a@alnoor.test` and
 * `manager.a@alnoor.test` from `e2e/seed/seed.mjs` and to drive the Admin's school switcher; QA
 * has neither — its seed is the one school of `server/.../seed/*.csv`, and D13 keeps
 * `multiSchool` off, so there is no switcher to drive and the three school screens are hidden by
 * the flag. The accounts are now the Admin and Sara Al Harbi, whose Home is This week.
 *
 * The Managerial half went with the switcher: nothing seeds a MANAGERIAL user, so the two tests
 * that needed one (the flag-gated Complaints refusal and the management Home screenshot) have no
 * subject until a seed grows one.
 */
const SHOTS = resolve(process.cwd(), '../docs/screenshots/dashboard-p3.1');

/** The Home's big numbers, scoped — the platform's name contains ordinary words too. */
function cards(page: import('@playwright/test').Page) {
  return page.locator('.home__cards');
}

test.beforeEach(async ({ page }) => {
  await page.goto('sign-in');
  await page.evaluate(() => localStorage.clear());
});

test('the sign-in page is branded from PlatformSettings, not from a literal', async ({ page }) => {
  await page.goto('sign-in');

  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  // §A: the name comes from the database. Whatever it is, the tab title is the same string.
  // `textContent`, not `innerText`: the brand line is upper-cased by CSS, and the title is the
  // string itself.
  const name = ((await page.locator('.auth__name').textContent()) ?? '').trim();
  expect(name.length).toBeGreaterThan(0);
  await expect(page).toHaveTitle(name);
});

test('the built bundle runs clean under the API’s Content-Security-Policy', async ({ page }) => {
  // A violation is reported to the console as a security error, and an inline script or an
  // off-origin fetch would show up here — with the page rendering unstyled, which is what makes
  // this worth asserting rather than eyeballing.
  const violations: string[] = [];
  page.on('console', (message) => {
    if (/Content Security Policy|Refused to/i.test(message.text())) violations.push(message.text());
  });

  await signIn(page, SARA);
  await expect(page.getByRole('heading', { level: 1, name: 'This week' })).toBeVisible();
  // The stylesheet applied: the rail has its width, so no CSP-blocked onload handler.
  await expect(page.getByRole('navigation')).toHaveCSS('flex-grow', '0');

  expect(violations).toEqual([]);
});

test('each role signs in and lands on its own Home', async ({ page }) => {
  await signIn(page, ADMIN);
  await expect(page).toHaveURL(/\/dashboard\/admin$/);
  await expect(page.getByRole('heading', { name: /^Hello,/ })).toBeVisible();
  await expect(cards(page).locator('hq-card').first()).toBeVisible();

  // D13: a teacher's Home *is* This week, so `/teacher` redirects rather than drawing a landing.
  await signIn(page, SARA);
  await expect(page).toHaveURL(/\/dashboard\/teacher\/week$/);
  await expect(page.getByRole('heading', { level: 1, name: 'This week' })).toBeVisible();
});

test('a wrong password is a band on the page, not a toast and not a sign-out', async ({ page }) => {
  await page.goto('sign-in');
  await page.getByLabel('Email').fill(SARA.email);
  await page.getByLabel('Password').fill('definitely-not-it');
  await page.getByRole('button', { name: 'Sign in' }).click();

  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page).toHaveURL(/\/dashboard\/sign-in/);
});

test('EN/AR flips dir and the whole dashboard without a reload', async ({ page }) => {
  await signIn(page, SARA);
  await expect(page.getByRole('heading', { level: 1, name: 'This week' })).toBeVisible();

  // Pin the document so a reload would be visible as the marker disappearing.
  await page.evaluate(() => ((window as unknown as { __hqMark?: boolean }).__hqMark = true));
  await expect(page.locator('html')).toHaveAttribute('dir', 'ltr');

  await page.getByRole('button', { name: /Sara/ }).click();
  await page.getByRole('menuitem', { name: 'العربية' }).click();

  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
  // The rail came from `ar.json`, not from the key table. Asserted as "the Arabic words are
  // there" rather than "no `nav.` is there": the bundle is fetched, so the second reads true for
  // a frame while the first simply waits for it.
  await expect(page.locator('hq-nav').getByRole('link', { name: 'هذا الأسبوع' })).toBeVisible();
  await expect(page.locator('hq-nav').getByRole('link', { name: 'فصولي' })).toBeVisible();
  expect(await page.evaluate(() => (window as unknown as { __hqMark?: boolean }).__hqMark)).toBe(true);
});

test('the Admin rail carries every screen §6 gives the Admin on one school', async ({ page }) => {
  await signIn(page, ADMIN);
  const rail = page.getByRole('navigation');

  for (const item of [
    'Home',
    'Classes',
    'Teachers',
    'Users',
    'Feature flags',
    'All lessons',
    'Platform usage',
    'Platform settings',
  ])
    await expect(rail.getByRole('link', { name: item })).toBeVisible();

  // D13: one school, so no switcher and none of the three screens behind `multiSchool`.
  await expect(page.getByRole('button', { name: 'School' })).toHaveCount(0);
  await expect(rail.getByRole('link', { name: 'Schools' })).toHaveCount(0);
  await page.goto('admin/schools');
  await expect(page).toHaveURL(/\/dashboard\/(admin|not-found)$/);
});

test('a teacher is offered nothing that belongs to the Admin', async ({ page }) => {
  await signIn(page, SARA);
  const rail = page.getByRole('navigation');
  await expect(rail).toBeVisible();

  // §4: her rail is This week · My classes, and nothing else renders.
  await expect(rail.getByRole('list').getByRole('link')).toHaveCount(2);
  for (const hers of ['This week', 'My classes'])
    await expect(rail.getByRole('link', { name: hers })).toBeVisible();
  for (const admin of ['Users', 'Feature flags', 'All lessons', 'Platform settings'])
    await expect(rail.getByRole('link', { name: admin })).toHaveCount(0);

  // And the URL is closed too, not just the menu.
  await page.goto('admin');
  await expect(page).toHaveURL(/\/dashboard\/teacher\/week$/);
});

/**
 * The hole the review found: the rail hid `/admin/users` from a teacher and the route let her in
 * by typing the URL. Rail and router now come from one table, so both doors are the same door.
 */
test('a permission-gated URL refuses a teacher who types it', async ({ page }) => {
  await signIn(page, SARA);
  await expect(page.getByRole('heading', { level: 1, name: 'This week' })).toBeVisible();

  for (const url of ['admin/users', 'admin/settings', 'admin/flags', 'admin/usage']) {
    await page.goto(url);
    // `roleGuard` turns the area away first; either way she never reaches the screen.
    await expect(page).toHaveURL(/\/dashboard\/(teacher\/week|no-access)$/);
  }
});

test('a cold start on a guarded bookmark is let through, not bounced', async ({ page }) => {
  await signIn(page, ADMIN);
  await page.goto('admin/users');
  await expect(page).toHaveURL(/\/dashboard\/admin\/users$/);

  // The real test: reload that URL, so every guard runs before any answer has arrived.
  await page.reload();
  await expect(page).toHaveURL(/\/dashboard\/admin\/users$/);
  await expect(page.getByRole('heading', { name: 'Coming soon' })).toBeVisible();
});

test('the tour is modal in fact, not just in its attributes', async ({ page }) => {
  await page.goto('sign-in');
  await page.getByLabel('Email').fill(SARA.email);
  await page.getByLabel('Password').fill(SARA.password);
  await page.getByRole('button', { name: 'Sign in' }).click();

  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible({ timeout: 30_000 });
  // Focus starts on the step's title, so a screen reader reads the step it is on.
  await expect(dialog.locator('h2')).toBeFocused();

  // And Tab stays inside: four presses cannot reach the rail behind the spotlight.
  for (let press = 0; press < 4; press++) await page.keyboard.press('Tab');
  expect(await dialog.evaluate((node) => node.contains(document.activeElement))).toBe(true);

  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
});

test('a route change moves focus to the new screen and announces it', async ({ page }) => {
  await signIn(page, ADMIN);
  await expect(page.getByRole('heading', { name: /^Hello,/ })).toBeVisible();

  await page.getByRole('navigation').getByRole('link', { name: 'Users' }).click();
  await expect(page).toHaveURL(/\/dashboard\/admin\/users$/);

  // Focus is on the new screen's heading, not left on the link that was clicked.
  await expect(page.locator('main h1')).toBeFocused();
  // …and the title reached the live region, so the change is perceivable without a page load.
  await expect(page.locator('[aria-live]')).toContainText('Coming soon');
});

test('? opens the shortcut sheet and Esc closes it', async ({ page }) => {
  await signIn(page, SARA);
  await expect(page.getByRole('heading', { level: 1, name: 'This week' })).toBeVisible();

  await page.keyboard.press('Shift+Slash');
  await expect(page.getByRole('dialog', { name: 'Keyboard shortcuts' })).toBeVisible();

  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog', { name: 'Keyboard shortcuts' })).toBeHidden();
});

test('the screenshot set, EN and AR', async ({ page }) => {
  test.setTimeout(120_000);
  await mkdir(SHOTS, { recursive: true });

  await page.goto('sign-in');
  await settled(page);
  await page.screenshot({ path: `${SHOTS}/01-sign-in-en.png` });

  for (const [name, who, marker] of [
    ['02-admin-home', ADMIN, /^Hello,/],
    ['03-teacher-home', SARA, /^This week$/],
  ] as const) {
    await signIn(page, who);
    await expect(page.getByRole('heading', { level: 1, name: marker })).toBeVisible();
    await settled(page); // the count-up has finished, so the shot is never mid-animation
    await page.screenshot({ path: `${SHOTS}/${name}-en.png` });

    await page.evaluate(() => localStorage.setItem('hq.language', 'ar'));
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
    await settled(page);
    await page.screenshot({ path: `${SHOTS}/${name}-ar.png` });
    await page.evaluate(() => localStorage.setItem('hq.language', 'en'));
  }

  await page.goto('sign-in');
  await page.evaluate(() => localStorage.clear());
  await page.evaluate(() => localStorage.setItem('hq.language', 'ar'));
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
  await settled(page);
  await page.screenshot({ path: `${SHOTS}/01-sign-in-ar.png` });
});
