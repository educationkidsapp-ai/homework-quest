import { expect, test, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';

/**
 * P3.2b's acceptance, against the built bundle and a seeded local API.
 *
 * `e2e/seed/seed.mjs` publishes one lesson per school, both `british/1` (Al Noor's is
 * `math`, Green Valley's `english`), so a single curriculum/grade choice on the chooser is
 * enough to prove both the Admin's cross-school view and the school switcher's narrowing —
 * and, on the Teacher's own `/teacher/lessons`, that the tenant filter keeps Green Valley's
 * row out entirely rather than the dashboard merely not showing it.
 */
const SHOTS = resolve(process.cwd(), '../docs/screenshots/dashboard-p3.2b');

const ADMIN = {
  email: process.env['E2E_ADMIN_EMAIL'] ?? 'admin@quest.local',
  password: env('E2E_ADMIN_PASSWORD'),
};
const TEACHER_A = { email: 'teacher.a@alnoor.test', password: env('E2E_STAFF_PASSWORD') };

const AL_NOOR_LESSON = 'Counting by 2s — Al Noor';
const GREEN_VALLEY_LESSON = 'The sh sound — Green Valley';

function env(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set — see playwright.local.config.ts`);
  return value;
}

async function signIn(page: Page, who: { email: string; password: string }): Promise<void> {
  await page.evaluate(() => localStorage.clear());
  await page.goto('sign-in');
  await page.getByLabel('Email').fill(who.email);
  await page.getByLabel('Password').fill(who.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  const skip = page.getByRole('button', { name: 'Skip' });
  await skip.waitFor({ state: 'visible', timeout: 10_000 });
  await skip.click();
  await expect(page.getByRole('dialog').first()).toBeHidden();
}

/**
 * Both seeded lessons are `british/1`, so this one choice is the whole chooser for this suite.
 * By `<option>` value rather than its translated label, so this works in either language.
 */
async function chooseBritishGradeOne(page: Page): Promise<void> {
  await page.locator('select').nth(0).selectOption({ value: 'british' });
  await page.locator('select').nth(1).selectOption({ value: '1' });
}

test.beforeEach(async ({ page }) => {
  await page.goto('sign-in');
  await page.evaluate(() => localStorage.clear());
});

test('an Admin sees both schools’ lessons, and the switcher narrows to one', async ({ page }) => {
  await signIn(page, ADMIN);
  await page.getByRole('navigation').getByRole('link', { name: 'All lessons' }).click();
  await expect(page.getByRole('heading', { name: 'All lessons' })).toBeVisible();

  await chooseBritishGradeOne(page);
  await expect(page.getByRole('link', { name: AL_NOOR_LESSON })).toBeVisible();
  await expect(page.getByRole('link', { name: GREEN_VALLEY_LESSON })).toBeVisible();
  // The Admin's own column — a screen a teacher never sees.
  await expect(page.getByRole('columnheader', { name: 'School' })).toBeVisible();

  await page.getByRole('button', { name: 'School' }).click();
  await page.getByRole('menuitem', { name: /Al Noor School/ }).click();

  await expect(page.getByRole('link', { name: AL_NOOR_LESSON })).toBeVisible();
  await expect(page.getByRole('link', { name: GREEN_VALLEY_LESSON })).toHaveCount(0);
});

test('Teacher A sees only her own school’s lesson on My lessons', async ({ page }) => {
  await signIn(page, TEACHER_A);
  await page.getByRole('navigation').getByRole('link', { name: 'My lessons' }).click();
  await expect(page.getByRole('heading', { name: 'My lessons' })).toBeVisible();

  await chooseBritishGradeOne(page);
  await expect(page.getByRole('link', { name: AL_NOOR_LESSON })).toBeVisible();
  await expect(page.getByRole('link', { name: GREEN_VALLEY_LESSON })).toHaveCount(0);
  // Not her school's screen to have — the tenant filter, not a hidden column.
  await expect(page.getByRole('columnheader', { name: 'School' })).toHaveCount(0);
});

test('the screenshot set, EN and AR', async ({ page }) => {
  await mkdir(SHOTS, { recursive: true });

  await signIn(page, ADMIN);
  await page.getByRole('navigation').getByRole('link', { name: 'All lessons' }).click();
  await chooseBritishGradeOne(page);
  await expect(page.getByRole('link', { name: AL_NOOR_LESSON })).toBeVisible();
  await page.waitForTimeout(400);
  await page.screenshot({ path: `${SHOTS}/01-admin-all-lessons-en.png` });

  // Arabic from a cold sign-in, not a live switch or a reload of an already-restored session:
  // a page title, this screen's table headers and the shell's nav labels are all built by a
  // TypeScript `computed()` that reads Transloco imperatively (`activeLang()` + `.translate()`)
  // rather than through the `| transloco` pipe. `langChanges$` fires the instant the language
  // is set, before its catalogue has actually loaded, and — unlike the pipe — a `computed()`
  // never gets a second chance to run once the fetch lands, so it freezes on whatever
  // `.translate()` happened to return at that instant (a real, separately-flagged gap). A live
  // switch or a token-refresh reload re-renders the already-mounted shell within a few
  // milliseconds — faster than the local JSON can round-trip — so it reproduces every time. A
  // fresh sign-in does not: it costs a deliberately slow bcrypt check (see the server's own
  // `POST /auth/sign-in` timings) before the shell exists at all, which is enough of a head
  // start for the catalogue to have already loaded by the time anything reads it.
  await page.evaluate(() => {
    localStorage.clear();
    localStorage.setItem('hq.language', 'ar');
  });
  await page.goto('sign-in');
  await page.locator('input[type="email"]').fill(ADMIN.email);
  await page.locator('input[type="password"]').fill(ADMIN.password);
  await page.locator('button[type="submit"]').click();
  const skip = page.getByRole('button', { name: 'تخطٍّ' });
  await skip.waitFor({ state: 'visible', timeout: 10_000 });
  await skip.click();

  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
  await page.getByRole('navigation').getByRole('link', { name: 'كل الدروس' }).click();
  await expect(page.getByRole('heading', { name: 'كل الدروس' })).toBeVisible();
  await chooseBritishGradeOne(page);
  await expect(page.getByRole('link', { name: AL_NOOR_LESSON })).toBeVisible();
  await page.waitForTimeout(400);
  await page.screenshot({ path: `${SHOTS}/01-admin-all-lessons-ar.png` });
});
