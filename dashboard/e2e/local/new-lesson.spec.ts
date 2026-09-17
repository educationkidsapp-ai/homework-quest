import { expect, test, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';

/**
 * P3.2c's acceptance, against the built bundle and a seeded local API.
 *
 * Teacher A (`e2e/seed/seed.mjs`) has one curriculum (British) and one subject (math), so the
 * chooser only leaves her Grade to pick — she teaches both 1 and 2. The Admin has no lesson
 * pipeline claim of her own; she reaches the wizard from All lessons' primary action, the way
 * §6 screen 8 opens it, and picks a school in the header first.
 */
const ADMIN = {
  email: process.env['E2E_ADMIN_EMAIL'] ?? 'admin@quest.local',
  password: env('E2E_ADMIN_PASSWORD'),
};
const TEACHER_A = { email: 'teacher.a@alnoor.test', password: env('E2E_STAFF_PASSWORD') };

const ONE_PAGE_PDF = resolve(process.cwd(), 'e2e/fixtures/one-page.pdf');
const SHOTS = resolve(process.cwd(), '../docs/screenshots/dashboard-p3.2c');

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

test('teacher A creates a manual lesson and lands on the lesson route', async ({ page }) => {
  await signIn(page, TEACHER_A);
  await page.getByRole('navigation').getByRole('link', { name: 'New lesson' }).click();
  await expect(page.getByRole('heading', { name: 'New lesson' })).toBeVisible();

  // Curriculum (one option) and Subject (one option) are pre-filled; Grade (two) is not.
  await expect(page.getByLabel('Curriculum')).toHaveValue('british');
  await page.getByLabel('Grade').selectOption({ value: '1' });
  await expect(page.getByLabel('Subject')).toHaveValue('math');

  await page.getByRole('button', { name: /Write it yourself/ }).click();
  const create = page.getByRole('button', { name: 'Create and write the questions' });
  await expect(create).toBeEnabled();
  await create.click();

  // The lesson detail page is the P3.1 stub until P3.2d; landing there, off /lessons/new, with
  // the notice band from the wizard, is what this test can prove today.
  await expect(page).toHaveURL(/\/teacher\/lessons\/(?!new\b)[^/?]+/);
  await expect(page.getByText('Lesson created — write its questions below.')).toBeVisible();
});

test('an admin creates a PDF lesson and it appears in the list as running or needing review', async ({ page }) => {
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
  await expect(page.getByText('Lesson created — its pipeline starts now.')).toBeVisible();

  // Back on the list, the row this just created (no title given) is mid-pipeline or already
  // waiting on the admin (the fake LLM provider finishes fast) — never still "Draft", which
  // would mean the upload or analyze call never fired.
  await page.getByRole('navigation').getByRole('link', { name: 'All lessons' }).click();
  await page.locator('select').nth(0).selectOption({ value: 'british' });
  await page.locator('select').nth(1).selectOption({ value: '1' });
  const row = page.getByRole('row').filter({ hasText: 'Untitled lesson' }).first();
  await expect(row).toBeVisible();
  await expect(
    row.getByText(/Uploading|Reading the pages|Needs review|Generating|Ready to review|Failed/),
  ).toBeVisible();
});

test('the screenshot set, EN and AR — step 1 (chooser) and step 2 (the full form)', async ({ page }) => {
  await mkdir(SHOTS, { recursive: true });

  await signIn(page, TEACHER_A);
  await page.getByRole('navigation').getByRole('link', { name: 'New lesson' }).click();
  await expect(page.getByRole('heading', { name: 'New lesson' })).toBeVisible();
  await page.waitForTimeout(300);
  await page.screenshot({ path: `${SHOTS}/01-new-lesson-step1-en.png` });

  await page.getByLabel('Grade').selectOption({ value: '1' });
  await expect(page.getByRole('button', { name: /Upload a PDF/ })).toBeVisible();
  await page.waitForTimeout(300);
  await page.screenshot({ path: `${SHOTS}/02-new-lesson-step2-en.png` });

  // Arabic from a cold sign-in — see the matching comment in lessons.spec.ts for why not a
  // live switch or a reload.
  await page.evaluate(() => {
    localStorage.clear();
    localStorage.setItem('hq.language', 'ar');
  });
  await page.goto('sign-in');
  await page.locator('input[type="email"]').fill(TEACHER_A.email);
  await page.locator('input[type="password"]').fill(TEACHER_A.password);
  await page.locator('button[type="submit"]').click();
  const skip = page.getByRole('button', { name: 'تخطٍّ' });
  await skip.waitFor({ state: 'visible', timeout: 10_000 });
  await skip.click();
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');

  await page.getByRole('navigation').getByRole('link', { name: 'درس جديد' }).click();
  await expect(page.getByRole('heading', { name: 'درس جديد' })).toBeVisible();
  await page.waitForTimeout(300);
  await page.screenshot({ path: `${SHOTS}/01-new-lesson-step1-ar.png` });

  await page.getByLabel('الصف').selectOption({ value: '1' });
  await expect(page.getByRole('button', { name: /رفع ملف PDF/ })).toBeVisible();
  await page.waitForTimeout(300);
  await page.screenshot({ path: `${SHOTS}/02-new-lesson-step2-ar.png` });
});
