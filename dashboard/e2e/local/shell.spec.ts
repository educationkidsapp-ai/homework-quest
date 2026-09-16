import { expect, test, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';

/**
 * P3.1's acceptance, against the built bundle and a seeded local API.
 *
 * Every account here comes from `e2e/seed/seed.mjs` (Al Noor = school A, Green Valley = B).
 * Passwords come from the environment and are never printed: a failure says which variable is
 * missing, not what it contains.
 */
// Playwright transpiles specs to CommonJS, so `import.meta` is out; the runner's working
// directory is `dashboard/`, which is the stable anchor here.
const SHOTS = resolve(process.cwd(), '../docs/screenshots/dashboard-p3.1');

const ADMIN = {
  email: process.env['E2E_ADMIN_EMAIL'] ?? 'admin@quest.local',
  password: env('E2E_ADMIN_PASSWORD'),
};
const TEACHER = { email: 'teacher.a@alnoor.test', password: env('E2E_STAFF_PASSWORD') };
const MANAGER = { email: 'manager.a@alnoor.test', password: env('E2E_STAFF_PASSWORD') };

function env(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set — see playwright.local.config.ts`);
  return value;
}

/**
 * Signs in from a clean slate.
 *
 * The session is dropped *before* navigating, because `/sign-in` bounces an already-signed-in
 * browser to its Home — which is the right behaviour and would otherwise make the second
 * sign-in in a test hang on a field that is not there.
 */
async function signIn(page: Page, who: { email: string; password: string }): Promise<void> {
  await page.evaluate(() => localStorage.clear());
  await page.goto('sign-in');
  await page.getByLabel('Email').fill(who.email);
  await page.getByLabel('Password').fill(who.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await dismissTour(page);
}

/**
 * The four-step tour runs on a role's first sign-in in a browser, and these tests clear
 * localStorage before each one — so every sign-in here is a first sign-in. Skipping it is part
 * of the flow, not a workaround: the spotlight is modal by design and would intercept clicks.
 */
async function dismissTour(page: Page): Promise<void> {
  const skip = page.getByRole('button', { name: 'Skip' });
  await skip.waitFor({ state: 'visible', timeout: 10_000 });
  await skip.click();
  await expect(page.getByRole('dialog').first()).toBeHidden();
}

/** The three big numbers, scoped — "Schools" is also a word in the platform's own name. */
function cards(page: Page) {
  return page.locator('.home__cards');
}

/** The dashboard is one bundle: clear the session between tests rather than restart anything. */
test.beforeEach(async ({ page }) => {
  await page.goto('sign-in');
  await page.evaluate(() => localStorage.clear());
});

test('the sign-in page is branded from PlatformSettings, not from a literal', async ({ page }) => {
  await page.goto('sign-in');

  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  // §A: the name comes from the database. Whatever it is, the tab title is the same string.
  // `textContent`, not `innerText`: the brand line is upper-cased by CSS, and the tab title is
  // the string itself.
  const name = ((await page.locator('.auth__name').textContent()) ?? '').trim();
  expect(name.length).toBeGreaterThan(0);
  await expect(page).toHaveTitle(name);
});

test('the built bundle runs clean under the API’s Content-Security-Policy', async ({ page }) => {
  // The static server sets the same policy the API will. A violation is reported to the console
  // as a security error, and an inline script or an off-origin fetch would show up here — with
  // the page rendering unstyled, which is what makes this worth asserting rather than eyeballing.
  const violations: string[] = [];
  page.on('console', (message) => {
    if (/Content Security Policy|Refused to/i.test(message.text())) violations.push(message.text());
  });

  await signIn(page, MANAGER);
  await expect(page.getByRole('heading', { name: /^Hello,/ })).toBeVisible();
  // The stylesheet applied: the 240 px rail has its width, so no CSP-blocked onload handler.
  await expect(page.getByRole('navigation')).toHaveCSS('flex-grow', '0');

  expect(violations).toEqual([]);
});

test('each role signs in and lands on its own Home', async ({ page }) => {
  await signIn(page, ADMIN);
  await expect(page).toHaveURL(/\/dashboard\/admin$/);
  await expect(page.getByRole('heading', { name: /^Hello,/ })).toBeVisible();
  await expect(cards(page).getByText('Schools', { exact: true })).toBeVisible();

  await signIn(page, TEACHER);
  await expect(page).toHaveURL(/\/dashboard\/teacher$/);
  await expect(page.getByText('Your classes')).toBeVisible();

  await signIn(page, MANAGER);
  await expect(page).toHaveURL(/\/dashboard\/management$/);
  await expect(page.getByText('Active families')).toBeVisible();
});

test('a wrong password is a band on the page, not a toast and not a sign-out', async ({ page }) => {
  await page.goto('sign-in');
  await page.getByLabel('Email').fill(TEACHER.email);
  await page.getByLabel('Password').fill('definitely-not-it');
  await page.getByRole('button', { name: 'Sign in' }).click();

  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page).toHaveURL(/\/dashboard\/sign-in/);
});

test('EN/AR flips dir and the whole dashboard without a reload', async ({ page }) => {
  await signIn(page, TEACHER);
  await expect(page.getByRole('heading', { name: /^Hello,/ })).toBeVisible();

  // Pin the document so a reload would be visible as the marker disappearing.
  await page.evaluate(() => ((window as unknown as { __hqMark?: boolean }).__hqMark = true));
  await expect(page.locator('html')).toHaveAttribute('dir', 'ltr');

  await page.getByRole('button', { name: /Ms Sara|teacher\.a/ }).click();
  await page.getByRole('menuitem', { name: 'العربية' }).click();

  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
  await expect(page.getByText('فصولك')).toBeVisible();
  expect(await page.evaluate(() => (window as unknown as { __hqMark?: boolean }).__hqMark)).toBe(true);
});

test('the Admin rail carries every screen §6 gives the Admin', async ({ page }) => {
  await signIn(page, ADMIN);
  const rail = page.getByRole('navigation');

  for (const item of [
    'Home',
    'Schools',
    'Users',
    'Feature flags',
    'All lessons',
    'Platform usage',
    'Platform settings',
  ])
    await expect(rail.getByRole('link', { name: item })).toBeVisible();
});

test('the Admin school switcher scopes every screen to one school', async ({ page }) => {
  await signIn(page, ADMIN);
  await expect(page.getByRole('button', { name: 'School' })).toHaveText('All schools');

  await page.getByRole('button', { name: 'School' }).click();
  await page.getByRole('menuitem', { name: /Al Noor School/ }).click();

  await expect(page.getByRole('button', { name: 'School' })).toHaveText(/Al Noor School/);
  // `GET /me/home` answers for the school in scope: one school, not two.
  await expect(cards(page).locator('hq-card').first()).toContainText('1');

  await page.getByRole('button', { name: 'School' }).click();
  await page.getByRole('menuitem', { name: 'All schools' }).click();
  await expect(cards(page).locator('hq-card').first()).toContainText('2');
});

test('a teacher is offered nothing that belongs to the Admin', async ({ page }) => {
  await signIn(page, TEACHER);
  await expect(page.getByRole('navigation')).toBeVisible();

  const rail = page.getByRole('navigation');
  for (const hers of ['My lessons', 'New lesson', 'My students'])
    await expect(rail.getByRole('link', { name: hers })).toBeVisible();
  for (const admin of ['Schools', 'Users', 'Feature flags', 'All lessons', 'Platform settings'])
    await expect(rail.getByRole('link', { name: admin })).toHaveCount(0);

  // And the URL is closed too, not just the menu.
  await page.goto('admin');
  await expect(page).toHaveURL(/\/dashboard\/teacher$/);
});

/**
 * The hole the review found: the rail hid `/admin/users` from a teacher and the route let her
 * in by typing the URL. Rail and router now come from one table, so both doors are the same
 * door — and these are the exact addresses the review named.
 */
test('a permission-gated URL refuses a teacher who types it', async ({ page }) => {
  await signIn(page, TEACHER);
  await expect(page.getByRole('heading', { name: /^Hello,/ })).toBeVisible();

  for (const url of ['admin/users', 'admin/settings', 'admin/flags', 'admin/usage']) {
    await page.goto(url);
    // `roleGuard` turns the area away first; either way she never reaches the screen.
    await expect(page).toHaveURL(/\/dashboard\/(teacher|no-access)$/);
  }
});

test('a permission-gated URL inside the right area refuses too, and says why', async ({ page }) => {
  // A Managerial user is in her own area for /management/**, so the role guard lets her past
  // and the permission guard is the only thing standing between her and a screen she may not
  // open. Complaints is flag-gated (off in the seed), which is a different answer again.
  await signIn(page, MANAGER);
  await expect(page.getByRole('heading', { name: /^Hello,/ })).toBeVisible();

  await page.goto('management/complaints');
  await expect(page).toHaveURL(/\/dashboard\/not-found$/);
  await expect(page.getByRole('heading', { name: 'Nothing here' })).toBeVisible();
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
  await page.evaluate(() => localStorage.clear());
  await page.goto('sign-in');
  await page.getByLabel('Email').fill(TEACHER.email);
  await page.getByLabel('Password').fill(TEACHER.password);
  await page.getByRole('button', { name: 'Sign in' }).click();

  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
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
  await signIn(page, MANAGER);
  await expect(page.getByRole('heading', { name: /^Hello,/ })).toBeVisible();

  await page.keyboard.press('Shift+Slash');
  await expect(page.getByRole('dialog', { name: 'Keyboard shortcuts' })).toBeVisible();

  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog', { name: 'Keyboard shortcuts' })).toBeHidden();
});

test('the screenshot set, EN and AR', async ({ page }) => {
  await mkdir(SHOTS, { recursive: true });

  await page.goto('sign-in');
  await page.waitForTimeout(400);
  await page.screenshot({ path: `${SHOTS}/01-sign-in-en.png` });

  for (const [name, who, marker] of [
    ['02-admin-home', ADMIN, 'Children'],
    ['03-teacher-home', TEACHER, 'Your classes'],
    ['04-management-home', MANAGER, 'Active families'],
  ] as const) {
    await signIn(page, who);
    await expect(page.getByText(marker, { exact: true }).first()).toBeVisible();
    await page.waitForTimeout(800); // let the count-up settle so the shot is not mid-animation
    await page.screenshot({ path: `${SHOTS}/${name}-en.png` });

    await page.evaluate(() => localStorage.setItem('hq.language', 'ar'));
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
    await page.waitForTimeout(800);
    await page.screenshot({ path: `${SHOTS}/${name}-ar.png` });
    await page.evaluate(() => localStorage.setItem('hq.language', 'en'));
  }

  await page.goto('sign-in');
  await page.evaluate(() => localStorage.clear());
  await page.evaluate(() => localStorage.setItem('hq.language', 'ar'));
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
  await page.waitForTimeout(400);
  await page.screenshot({ path: `${SHOTS}/01-sign-in-ar.png` });
});
