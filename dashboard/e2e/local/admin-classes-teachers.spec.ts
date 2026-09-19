import { type Locator, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
// `ADMIN` is the shared one. This file used to keep its own copy, with its own `env()` beside
// it, and read the password *eagerly* at module scope — so a shell without `E2E_ADMIN_PASSWORD`
// failed Playwright's **collection** of the whole directory rather than this one file's tests,
// and the error named the wrong thing. The shared account reads it through a getter.
import { ADMIN, expect, shoot, test } from './env';

/**
 * N1.2's acceptance (`docs/teacher-flow.md` §10 step 1), against the built bundle and a local
 * API on H2 with `SEED_SCHOOL=false` — the fixture is `e2e/seed/seed.mjs`'s two schools, and
 * everything this suite needs beyond it is created through the screens themselves.
 *
 * What it proves, in order: an Admin creates 1A and 1B (British, Grade 1); creates Sara (Math,
 * British) and is shown her temporary password once; assigns her to both sections; and a second
 * Math teacher offered 1A is **refused by the picker with Sara's name on the checkbox**, which
 * is the whole point of N1.1's unique constraint reaching the person making the mistake.
 */
const SHOTS = resolve(process.cwd(), '../docs/screenshots/dashboard-n1.2');

/** The rail in Admin order: Home, Classes, Teachers, … — by position, so it reads in either language. */
const CLASSES_ITEM = 1;
const TEACHERS_ITEM = 2;

/** Unique per run: the suite creates rows and the H2 database outlives a single test. */
const RUN = Date.now().toString(36).slice(-4).toUpperCase();
const SARA = `sara.${RUN}@alnoor.test`;
const OMAR = `omar.${RUN}@alnoor.test`;

async function signInAsAdmin(page: Page): Promise<void> {
  await page.goto('sign-in');
  await page.evaluate(() => localStorage.clear());
  await page.goto('sign-in');
  await page.getByLabel('Email').fill(ADMIN.email);
  await page.getByLabel('Password').fill(ADMIN.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  const skip = page.getByRole('button', { name: 'Skip' });
  await skip.waitFor({ state: 'visible', timeout: 10_000 });
  await skip.click();
  await expect(page.getByRole('dialog').first()).toBeHidden();
}

async function openClasses(page: Page): Promise<void> {
  await page.getByRole('navigation').getByRole('link', { name: 'Classes' }).click();
  await expect(page.getByRole('heading', { name: 'Classes' })).toBeVisible();
}

async function openTeachers(page: Page): Promise<void> {
  await page.getByRole('navigation').getByRole('link', { name: 'Teachers' }).click();
  await expect(page.getByRole('heading', { name: 'Teachers' })).toBeVisible();
}

/** Creates one section through the dialog and waits for it to appear in the table. */
async function createClass(page: Page, name: string): Promise<void> {
  await page.getByRole('button', { name: 'Create class' }).last().click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Curriculum').selectOption('british');
  await dialog.getByLabel('Grade').selectOption('1');
  await dialog.getByRole('textbox', { name: 'Class name', exact: true }).fill(name);
  await dialog.getByRole('button', { name: 'Create' }).click();
  await expect(page.getByRole('cell', { name, exact: true })).toBeVisible();
}

/**
 * Ticks a checkbox the way a person does — by its label.
 *
 * `hq-checkbox` keeps the real `<input>` in the DOM but visually hidden behind the drawn box, so
 * a click aimed at the input is intercepted by that box; the label is both the accessible name
 * and the real hit target.
 */
function tick(scope: Locator, label: string | RegExp): Locator {
  return scope.locator('label.check', { hasText: label });
}

/** Adds a teacher and returns nothing: the password band is asserted by the caller. */
async function createTeacher(page: Page, fullName: string, email: string): Promise<void> {
  await page.getByRole('button', { name: 'Add teacher' }).last().click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('textbox', { name: 'Full name', exact: true }).fill(fullName);
  await dialog.getByRole('textbox', { name: 'Email', exact: true }).fill(email);
  await tick(dialog, 'Math').click();
  await dialog.getByLabel('Curriculum').selectOption('british');
  await dialog.getByRole('button', { name: 'Save' }).click();
}

/**
 * Local only (N2.5). This file creates classes and a teacher on every run, and there is no
 * `DELETE /admin/classes/{id}` or `/admin/teachers/{id}` to take them back — the rest of the
 * suite cleans up after itself, and this one cannot. Against a local H2 database that is a fresh
 * start every time; against QA's shared, never-reset database it would leave a `1A<run>`, a
 * `1B<run>` and a `sara.<run>@…` behind on every deploy, for good.
 */
test.skip(
  !!process.env['E2E_BASE_URL'],
  'creates classes and teachers that no endpoint can delete — local only, never against a shared database',
);

test.describe.configure({ mode: 'serial' });

test('an Admin creates 1A and 1B, and each gets its own join code', async ({ page }) => {
  await signInAsAdmin(page);
  await openClasses(page);

  await createClass(page, `1A${RUN}`);
  await createClass(page, `1B${RUN}`);

  const codes = await page.locator('code').allTextContents();
  const mine = codes.filter((code) => code.trim().length > 0);
  expect(new Set(mine).size).toBe(mine.length);
});

test('creating a teacher shows her temporary password exactly once', async ({ page }) => {
  await signInAsAdmin(page);
  await openTeachers(page);

  await createTeacher(page, 'Sara', SARA);

  const band = page.locator('[data-hq-temp-password]');
  await expect(band).toBeVisible();
  expect((await band.textContent())?.trim().length ?? 0).toBeGreaterThan(6);
  await expect(page.getByText(/shown once and cannot be read again/)).toBeVisible();

  // Leaving the screen is enough to lose it — nothing writes it down.
  await openClasses(page);
  await openTeachers(page);
  await expect(page.locator('[data-hq-temp-password]')).toHaveCount(0);
});

test('Sara takes both sections, and a second Math teacher for 1A is refused with her name', async ({
  page,
}) => {
  await signInAsAdmin(page);
  await openTeachers(page);

  await page.getByRole('button', { name: `Actions for Sara` }).first().click();
  await page.getByRole('menuitem', { name: 'Assignments' }).click();
  const picker = page.getByRole('dialog');
  await tick(picker, `1A${RUN} · British`).click();
  await tick(picker, `1B${RUN} · British`).click();
  await picker.getByRole('button', { name: 'Save assignments' }).click();

  await expect(page.getByText(`1A${RUN} · Math`)).toBeVisible();
  await expect(page.getByText(`1B${RUN} · Math`)).toBeVisible();

  // A second Math teacher, offered the same section.
  await createTeacher(page, 'Omar', OMAR);
  await expect(page.locator('[data-hq-temp-password]')).toBeVisible();

  await page.getByRole('button', { name: 'Actions for Omar' }).first().click();
  await page.getByRole('menuitem', { name: 'Assignments' }).click();
  const second = page.getByRole('dialog');
  await expect(tick(second, `1A${RUN} · British`).getByRole('checkbox')).toBeDisabled();
  await expect(second.getByText('Taught by Sara').first()).toBeVisible();
  // 1B is Sara's too, so nothing this run created is left for Omar to take.
  await expect(tick(second, `1B${RUN} · British`).getByRole('checkbox')).toBeDisabled();
});

test('the screenshot set, EN and AR', async ({ page }) => {
  await mkdir(SHOTS, { recursive: true });
  await signInAsAdmin(page);

  // One pass per language rather than one per screen: switching back and forth mid-pass is what
  // makes a screenshot suite flaky, and `dir` is the only honest signal that the switch landed.
  for (const language of ['en', 'ar'] as const) {
    await page.evaluate((lang) => localStorage.setItem('hq.language', lang), language);
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('dir', language === 'ar' ? 'rtl' : 'ltr');

    await page.getByRole('navigation').getByRole('link').nth(CLASSES_ITEM).click();
    await shoot(page, `${SHOTS}/01-classes-${language}.png`, page.getByRole('table'));

    await page.getByRole('navigation').getByRole('link').nth(TEACHERS_ITEM).click();
    await shoot(page, `${SHOTS}/02-teachers-${language}.png`, page.getByRole('table'));

    // The picker open, which is the screen this package exists for.
    await page.getByRole('button', { name: /Sara/ }).first().click();
    await page.getByRole('menuitem').first().click();
    await shoot(page, `${SHOTS}/03-assignment-picker-${language}.png`, page.getByRole('dialog'));
    await page.keyboard.press('Escape');
  }

  await page.evaluate(() => localStorage.setItem('hq.language', 'en'));
});
