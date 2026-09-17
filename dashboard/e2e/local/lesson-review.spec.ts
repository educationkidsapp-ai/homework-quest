import { expect, test, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';

/**
 * P3.2d's acceptance: the PDF lesson P3.2c's suite creates, carried all the way through the
 * pipeline — skills confirmation, the three levels + Again, the phone preview, and publish.
 *
 * The fake LLM provider (`LLM_PROVIDER=fake` in the README) answers fast but not instantly, so
 * every wait below is generous rather than a fixed sleep: the dashboard's own 2.5 s poll is
 * what actually moves the screen along, and this suite waits on what the poll produces.
 */
const ADMIN = {
  email: process.env['E2E_ADMIN_EMAIL'] ?? 'admin@quest.local',
  password: env('E2E_ADMIN_PASSWORD'),
};

const ONE_PAGE_PDF = resolve(process.cwd(), 'e2e/fixtures/one-page.pdf');
const SHOTS = resolve(process.cwd(), '../docs/screenshots/dashboard-p3.2d');

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

test.beforeEach(async ({ page }) => {
  await page.goto('sign-in');
  await page.evaluate(() => localStorage.clear());
});

test('a PDF lesson goes from upload through skills, the levels and the preview, to published', async ({
  page,
}) => {
  test.setTimeout(180_000); // the pipeline runs for real, one 2.5 s poll at a time
  await signIn(page, ADMIN);
  await page.getByRole('button', { name: 'School' }).click();
  await page.getByRole('menuitem', { name: /Al Noor School/ }).click();

  await page.getByRole('navigation').getByRole('link', { name: 'All lessons' }).click();
  await page.getByRole('button', { name: 'New lesson' }).click();
  await expect(page.getByRole('heading', { name: 'New lesson' })).toBeVisible();
  await page.getByLabel('Curriculum').selectOption({ value: 'british' });
  await page.getByLabel('Grade').selectOption({ value: '1' });
  await page.getByLabel('Subject').selectOption({ value: 'math' });
  await page.getByRole('button', { name: /Upload a PDF/ }).click();
  await page.getByLabel('Drop a PDF here').setInputFiles(ONE_PAGE_PDF);
  const create = page.getByRole('button', { name: 'Create and read the PDF' });
  await expect(create).toBeEnabled();
  await create.click();

  await expect(page).toHaveURL(/\/admin\/lessons\/(?!new\b)[^/?]+/);
  const lessonId = new URL(page.url()).pathname.split('/').pop();

  // --- needs_review: the model asked what the skills are, and the dashboard polled to it.
  const confirmSkills = page.getByRole('button', { name: 'Make the quest' });
  await expect(confirmSkills).toBeVisible({ timeout: 60_000 });
  // The fake LLM extracts at least one skill, kept by default — nothing to edit to publish.
  await confirmSkills.click();

  // --- review: three levels, the Again variant and the parent panel are all written.
  await expect(page.getByRole('heading', { name: 'Levels' })).toBeVisible({ timeout: 90_000 });
  await expect(page.getByText('Ready to review', { exact: false })).toBeVisible();

  // --- a stop, selected, drives the pinned preview.
  const stopList = page.locator('.lesson__stop-list');
  const firstStop = stopList.locator('.lesson__stop-item').first();
  await expect(firstStop).toBeVisible();
  const stopTitle = (await firstStop.locator('.lesson__stop-title').textContent())?.trim();
  await firstStop.click();
  expect(stopTitle).toBeTruthy();
  await expect(page.locator('.phone__caption')).toHaveText(stopTitle ?? '');

  // --- publish: red confirm band, then the notice.
  await page.getByRole('button', { name: 'Publish' }).click();
  const band = page.getByRole('alert').filter({ hasText: 'Publish this lesson?' });
  await expect(band).toBeVisible();
  await band.getByRole('button', { name: 'Publish' }).click();
  await expect(
    page.getByText('Published — every child on the course sees the island on its day.'),
  ).toBeVisible({ timeout: 15_000 });

  // --- the list agrees. Matched by id, not by title: the fake LLM has given the lesson a real
  // title by now (`Counting by 2s`, same content the seed uses), not the "Untitled" it had at
  // creation. (`getByRole('navigation')` alone is ambiguous here — the lesson page's own
  // breadcrumb is a `nav` too, with an "All lessons" link of its own.)
  await page.getByRole('navigation', { name: 'Admin' }).getByRole('link', { name: 'All lessons' }).click();
  await page.locator('select').nth(0).selectOption({ value: 'british' });
  await page.locator('select').nth(1).selectOption({ value: '1' });
  const row = page.getByRole('row').filter({ has: page.locator(`a[href$="/${lessonId}"]`) });
  await expect(row.getByText('Published')).toBeVisible({ timeout: 10_000 });
});

test('the screenshot set, EN and AR — needs_review and review', async ({ page }) => {
  test.setTimeout(180_000);
  await mkdir(SHOTS, { recursive: true });

  await signIn(page, ADMIN);
  await page.getByRole('button', { name: 'School' }).click();
  await page.getByRole('menuitem', { name: /Al Noor School/ }).click();
  await page.getByRole('navigation').getByRole('link', { name: 'All lessons' }).click();
  await page.getByRole('button', { name: 'New lesson' }).click();
  await expect(page.getByRole('heading', { name: 'New lesson' })).toBeVisible();
  await page.getByLabel('Curriculum').selectOption({ value: 'british' });
  await page.getByLabel('Grade').selectOption({ value: '1' });
  await page.getByLabel('Subject').selectOption({ value: 'math' });
  await page.getByRole('button', { name: /Upload a PDF/ }).click();
  await page.getByLabel('Drop a PDF here').setInputFiles(ONE_PAGE_PDF);
  await page.getByRole('button', { name: 'Create and read the PDF' }).click();
  await expect(page).toHaveURL(/\/admin\/lessons\/(?!new\b)[^/?]+/);
  const lessonId = new URL(page.url()).pathname.split('/').pop();

  const confirmSkills = page.getByRole('button', { name: 'Make the quest' });
  await expect(confirmSkills).toBeVisible({ timeout: 60_000 });
  await page.waitForTimeout(300);
  await page.screenshot({ path: `${SHOTS}/01-needs-review-en.png` });
  await confirmSkills.click();

  await expect(page.getByRole('heading', { name: 'Levels' })).toBeVisible({ timeout: 90_000 });
  await page.waitForTimeout(300);
  await page.screenshot({ path: `${SHOTS}/02-review-en.png` });

  // Arabic from a cold sign-in — see the matching comment in lessons.spec.ts for why not a
  // live switch. Reached by clicking through (All lessons → the row), not a hard `page.goto`
  // straight to the deep link: a cold reload with `ar` already in `localStorage` leaves some
  // of the shell's translations stale (tracked separately — not this page's bug, see the list
  // page's own `?` sheet for the same symptom).
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

  await page.getByRole('button', { name: 'المدرسة' }).click();
  await page.getByRole('menuitem', { name: /Al Noor School/ }).click();
  await page.getByRole('navigation', { name: 'المشرف' }).getByRole('link', { name: 'كل الدروس' }).click();
  await page.locator('select').nth(0).selectOption({ value: 'british' });
  await page.locator('select').nth(1).selectOption({ value: '1' });
  await page.locator(`a[href$="/${lessonId}"]`).click();

  await expect(page.getByRole('heading', { name: 'المستويات' })).toBeVisible({ timeout: 15_000 });
  await page.waitForTimeout(300);
  await page.screenshot({ path: `${SHOTS}/02-review-ar.png` });
});
