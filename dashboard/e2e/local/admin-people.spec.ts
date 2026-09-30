import { type Page } from '@playwright/test';
import { ADMIN, RUN, expect, signIn, test } from './env';

/**
 * MA2 (the owner's admin list of 2026-09-30, items 3–5): the Admin's people pages, against the
 * built bundle and a local API on H2 with `SEED_SCHOOL=true`.
 *
 * What it proves, in order: her rail now carries the four new rows; a **worker** is created with a
 * job and a number and no password anywhere; a **coordinator** is created with a subject and is
 * shown a one-time password once; and a **child** is admitted with a parent login, the success
 * sheet saying which of `parentCreated` / `passwordApplied` happened.
 *
 * **Why this runs locally at all.** A parent's login lives in Firebase Auth, and the `h2` profile
 * sets `quest.auth.fake: true` (`server/src/main/resources/application.yml`), which swaps
 * `quest.server.auth.ParentAccounts` for the in-memory stand-in — no network, no credentials, and
 * `POST /admin/children` answers 201 rather than the 503 a misconfigured deployment would.
 */

/** Unique per run: the H2 database outlives a single test. */
const WORKER = `Mona ${RUN}`;
const COORDINATOR = `Hoda ${RUN}`;
const COORDINATOR_EMAIL = `hoda.${RUN.toLowerCase()}@alnoor.test`;
const CHILD = `Hala ${RUN}`;
const PARENT_EMAIL = `ahmed.${RUN.toLowerCase()}@example.com`;
/** Eight characters is the server's floor; this is comfortably over it and not the address. */
const PARENT_PASSWORD = `Sunflower-${RUN}`;

async function open(page: Page, item: string): Promise<void> {
  await page.getByRole('navigation').getByRole('link', { name: item, exact: true }).click();
}

test.describe('the Admin’s people pages', () => {
  test.beforeEach(async ({ page }) => {
    await signIn(page, ADMIN);
  });

  test('her rail carries Coordinators, Managers, Workers and Children & parents', async ({ page }) => {
    const rail = page.getByRole('navigation');
    for (const item of ['Coordinators', 'Managers', 'Workers', 'Children & parents'])
      await expect(rail.getByRole('link', { name: item, exact: true })).toBeVisible();
  });

  test('creates a worker, with a job and a number and no account at all', async ({ page }) => {
    await open(page, 'Workers');
    await expect(page.getByRole('heading', { name: 'Workers' })).toBeVisible();

    await page.getByRole('button', { name: 'Add worker' }).last().click();
    await page.getByLabel('Full name').fill(WORKER);
    await page.getByLabel('Job').fill('Nurse');
    await page.getByLabel(/^Mobile/).fill('0501234567');
    await page.getByRole('button', { name: 'Save' }).click();

    await expect(page.getByRole('cell', { name: WORKER })).toBeVisible();
    await expect(page.getByRole('cell', { name: 'Nurse' }).first()).toBeVisible();
    // Nothing was minted, so nothing is shown once: no password band anywhere on the screen.
    await expect(page.locator('[data-hq-temp-password]')).toHaveCount(0);
  });

  test('creates a coordinator and shows her one-time password once', async ({ page }) => {
    await open(page, 'Coordinators');
    await expect(page.getByRole('heading', { name: 'Coordinators' })).toBeVisible();

    await page.getByRole('button', { name: 'Add coordinator' }).last().click();
    await page.getByLabel('Full name').fill(COORDINATOR);
    await page.getByLabel('Email').fill(COORDINATOR_EMAIL);
    await page.getByLabel('Subject').selectOption('math');
    await page.getByRole('button', { name: 'Save' }).click();

    const password = page.locator('[data-hq-temp-password]');
    await expect(password).toBeVisible();
    await expect(password).not.toBeEmpty();
    await expect(page.getByText(/shown once and cannot be read again/)).toBeVisible();
    await expect(page.getByRole('cell', { name: COORDINATOR })).toBeVisible();

    // Leaving the screen loses it: "shown once" has to mean once.
    await open(page, 'Home');
    await open(page, 'Coordinators');
    await expect(page.locator('[data-hq-temp-password]')).toHaveCount(0);
  });

  test('admits a child and creates the login her parent signs in with', async ({ page }) => {
    await open(page, 'Children & parents');
    await expect(page.getByRole('heading', { name: 'Children & parents' })).toBeVisible();

    await page.getByRole('button', { name: 'Admit a child' }).last().click();
    await page.getByLabel("Child's name").fill(CHILD);
    // The section select is empty until both are answered, and offers only matching sections.
    await page.getByLabel('Curriculum').selectOption('british');
    await page.getByLabel('Grade').selectOption('1');
    const section = page.getByLabel('Class');
    await expect(section).toBeEnabled();
    await section.selectOption({ index: 1 });

    await page.getByLabel("Parent's name").fill(`Ahmed ${RUN}`);
    await page.getByLabel("Parent's email").fill(PARENT_EMAIL);
    await page.getByLabel(/^Parent's mobile/).fill('0501002030');
    await page.getByLabel('Password for the parent').fill(PARENT_PASSWORD);
    await page.getByRole('button', { name: 'Admit', exact: true }).click();

    // The success sheet, and the one sentence that decides whether that password is worth
    // anything. A brand-new address, so it is the login this call minted.
    const verdict = page.locator('[data-hq-password-applied]');
    await expect(verdict).toBeVisible();
    await expect(verdict).toHaveText('The password you typed is the one that now works.');
    await expect(page.getByText('A new parent account was created for her family.')).toBeVisible();

    await page.getByRole('button', { name: 'Close' }).first().click();
    await page.getByLabel(/^Find a child or a parent/).fill(CHILD);
    await expect(page.getByRole('cell', { name: CHILD })).toBeVisible();
    await expect(page.getByRole('cell', { name: PARENT_EMAIL })).toBeVisible();
  });

  /**
   * A second child of the same family: one account, and the password typed the second time is
   * **not** applied — which is exactly the case the sheet exists to say out loud.
   *
   * Follows the test above, which is what creates the address this one reuses. The local config is
   * `fullyParallel: false` with one worker, so the order in this file is the order they run in.
   */
  test('reuses an address the school already has, and says the password was not applied', async ({
    page,
  }) => {
    await open(page, 'Children & parents');
    await page.getByRole('button', { name: 'Admit a child' }).last().click();
    await page.getByLabel("Child's name").fill(`${CHILD} II`);
    await page.getByLabel('Curriculum').selectOption('british');
    await page.getByLabel('Grade').selectOption('1');
    await page.getByLabel('Class').selectOption({ index: 1 });
    await page.getByLabel("Parent's name").fill(`Ahmed ${RUN}`);
    await page.getByLabel("Parent's email").fill(PARENT_EMAIL);
    await page.getByLabel('Password for the parent').fill(`Different-${RUN}`);
    await page.getByRole('button', { name: 'Admit', exact: true }).click();

    await expect(page.getByText(/already had an account here/)).toBeVisible();
    await expect(page.locator('[data-hq-password-applied]')).toHaveText(/was not applied/);
  });
});
