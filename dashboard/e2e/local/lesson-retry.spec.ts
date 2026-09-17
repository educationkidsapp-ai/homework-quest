import { expect, test, type Page } from '@playwright/test';
import { resolve } from 'node:path';

/**
 * The pipeline actually breaking, and "Retry and continue" actually mending it — needs the API
 * started with `-Dquest.pipeline.fail-once-at=generate_L2` (README's step 1), which fails that
 * step exactly once per lesson. Skipped unless `E2E_FAIL_ONCE_AT` says the server was started
 * that way, since the happy-path suite's server was not.
 */
const ADMIN = {
  email: process.env['E2E_ADMIN_EMAIL'] ?? 'admin@quest.local',
  password: env('E2E_ADMIN_PASSWORD'),
};

const ONE_PAGE_PDF = resolve(process.cwd(), 'e2e/fixtures/one-page.pdf');

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

test.skip(process.env['E2E_FAIL_ONCE_AT'] !== '1', 'needs the API started with quest.pipeline.fail-once-at=generate_L2');

test.beforeEach(async ({ page }) => {
  await page.goto('sign-in');
  await page.evaluate(() => localStorage.clear());
});

test('retries a lesson past an injected failure at generate_L2', async ({ page }) => {
  test.setTimeout(180_000);
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

  const confirmSkills = page.getByRole('button', { name: 'Make the quest' });
  await expect(confirmSkills).toBeVisible({ timeout: 60_000 });
  await confirmSkills.click();

  // generate_L2 fails once: the pipeline lands on `error`, not `review`.
  await expect(page.getByText('Failed', { exact: false }).first()).toBeVisible({ timeout: 60_000 });
  const retry = page.getByRole('button', { name: 'Retry and continue' });
  await expect(retry).toBeVisible();
  await retry.click();

  // `failed.add(lessonId + ":" + step)` in `LessonPipeline.java` only fails a step once per
  // lesson, so the retry runs the real pipeline through to review.
  await expect(page.getByRole('heading', { name: 'Levels' })).toBeVisible({ timeout: 90_000 });
  await expect(page.getByText('Ready to review', { exact: false })).toBeVisible();
});
