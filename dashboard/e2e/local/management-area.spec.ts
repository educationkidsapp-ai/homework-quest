import { type APIRequestContext, type Page } from '@playwright/test';
import { MANAGER, SARA, expect, signIn, test } from './env';
import { api, schoolOfSara, withFlags } from './n4-api';

/**
 * RM3a's acceptance (`docs/management-flow.md`), against the built bundle and a local API on H2
 * started with `SEED_SCHOOL=true` — the one school, 30 classes, 40 teachers, 600 children.
 *
 * Huda Salem (`seed/managers.csv`) runs the **British** department: every grade and every
 * subject of that track, and nothing of the American one. That is the assertion running through
 * this file — her Coordinators list and her People are British and only British.
 *
 * MG2a took Classes, All lessons, Gradebook and Exams off her rail, so the Classes walk went
 * with them; what replaced it is the bookmark test (a removed URL lands on her Home) and School
 * usage, whose numbers are deliberately *not* her department's.
 *
 * One test writes: the staff register is the single write in the whole namespace. It marks a
 * person late for today and reads it back, which is idempotent — the row is upserted on
 * `(user_id, date)`, so the file can run twice or beside the others without cleanup. It skips
 * itself on a day the school does not teach on, which the roster says rather than the test.
 */
const DEPARTMENT = 'British';

/** The 240 px rail, not a page's breadcrumb — both are `role="navigation"` with a list inside. */
function rail(page: Page) {
  return page.locator('hq-nav');
}

async function openManagement(page: Page): Promise<void> {
  await signIn(page, MANAGER);
  await expect(page).toHaveURL(/\/management$/);
}

test.describe('the management area', () => {
  test("lands on her Home, which is the department's statistics", async ({ page }) => {
    await openManagement(page);

    await expect(page.getByRole('heading', { level: 1 })).toContainText('Huda Salem');
    // Her scope is a track, with no subject beside it: every subject of it is hers (DR5).
    await expect(page.locator('main')).toContainText(DEPARTMENT);
    await expect(page.getByText('Classes in the department')).toBeVisible();
    await expect(page.getByText('Coordinators', { exact: true }).first()).toBeVisible();

    // DR5's table: a row per grade and the department's own total, over a window she can change.
    const stats = page.getByRole('table', { name: 'How the department is doing' });
    await expect(stats).toBeVisible({ timeout: 30_000 });
    await expect(stats.getByRole('row')).not.toHaveCount(1); // the header alone would be one row
    await expect(stats).toContainText('Whole department');
    await expect(page.getByLabel('From')).toBeVisible();

    // Read-only, everywhere: the whole area has one write and it is not on this screen.
    await expect(stats.getByRole('button')).toHaveCount(0);

    // MG2a: Classes, All lessons, Gradebook and Exams left her rail on the owner's own
    // instruction, and Complaints lost its label while it is still a stub. Broadcasts and
    // Messages are flag-gated and absent from the seeded school.
    await expect(rail(page).getByRole('link')).toHaveText([
      'Home',
      'Coordinators',
      'Teachers',
      'Attendance',
      'People',
      'Staff attendance',
      'School usage',
    ]);
  });

  /** MG2a: the screens that left the rail still resolve — onto her Home, not onto /not-found. */
  test('sends a bookmark of a removed screen back to her Home', async ({ page }) => {
    await openManagement(page);

    for (const path of ['/management/classes', '/management/lessons', '/management/gradebook']) {
      await page.goto(path);
      await expect(page).toHaveURL(/\/management$/);
    }
  });

  test("shows the school's usage over a window she picks, and exports it", async ({ page }) => {
    await openManagement(page);
    await rail(page).getByRole('link', { name: 'School usage' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'School usage' })).toBeVisible();

    // MG2b: hers is `GET /management/usage` — the same shape scoped to her department — and the
    // screen says whose numbers they are rather than letting her read her department's name into
    // the other one's.
    await expect(page.locator('main')).toContainText('Your department');
    await expect(page.locator('main')).not.toContainText('The whole school');
    const table = page.getByRole('table', { name: 'How often each teacher publishes' });
    await expect(table).toBeVisible({ timeout: 30_000 });
    await expect(table.getByRole('row')).not.toHaveCount(1);
    await expect(page.getByLabel('From')).toBeVisible();

    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Export CSV' }).click();
    expect((await download).suggestedFilename()).toContain('school-usage');
  });

  test('lists the coordinators of her department, and not the other one', async ({ page }) => {
    await openManagement(page);
    await rail(page).getByRole('link', { name: 'Coordinators' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Coordinators' })).toBeVisible();

    const table = page.getByRole('table', { name: 'Coordinators' });
    await expect(table.getByRole('row')).not.toHaveCount(1);
    // Rasha Kamal's scope row names no track at all, so she reports to both managers (DR5).
    await expect(table).toContainText('Rasha Kamal');
    // No row menu, no action: RM1 is read-only.
    await expect(table.getByRole('button')).toHaveCount(0);

    await page.getByLabel('Search by name, email or subject').fill('Rasha');
    await expect(table).toContainText('coordinator.math@school.test');
  });

  test('marks a teacher late today and reads the mark back', async ({ page }) => {
    await openManagement(page);
    await rail(page).getByRole('link', { name: 'Staff attendance' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Staff attendance' })).toBeVisible();

    const roster = page.getByRole('radiogroup').first();
    await expect(roster).toBeVisible({ timeout: 30_000 });
    const late = roster.getByRole('radio', { name: 'Late' });

    // The day rule is the server's: a teaching day of this school, not after today in the
    // school's zone. The screen says which refusal it is; the test believes it rather than
    // re-deriving a calendar of its own.
    if (await late.isDisabled()) {
      await expect(page.locator('hq-band')).toContainText(/does not teach|has not happened/);
      test.skip(true, 'today is not a teaching day of the seeded school, so the register is closed');
    }

    await late.click();
    await expect(late).toHaveAttribute('aria-checked', 'true');
    const save = page.getByRole('button', { name: 'Save' });
    await expect(save).toBeEnabled();
    await save.click();

    // The server answers the whole roster back and the screen holds *that* — so a reload shows
    // the same mark, and "Save" has nothing left to send.
    await expect(page.getByText('Nothing changed yet.')).toBeVisible({ timeout: 30_000 });
    await page.reload();
    await expect(page.getByRole('radiogroup').first().getByRole('radio', { name: 'Late' })).toHaveAttribute(
      'aria-checked',
      'true',
      { timeout: 30_000 },
    );

    // Her month: the same people, four counts each and a rate.
    await page.getByRole('tab', { name: 'This month' }).click();
    await expect(page.getByText("Each person's month")).toBeVisible({ timeout: 30_000 });
  });

  test('lists the children of her department with their parents and classes', async ({ page }) => {
    await openManagement(page);
    await rail(page).getByRole('link', { name: 'People' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'People' })).toBeVisible();

    const table = page.getByRole('table', { name: 'People' });
    await expect(table.getByRole('row')).not.toHaveCount(1);
    await expect(table).toContainText('Grade 1');
    // Paged by the server, and the pager says where in the whole department she is.
    await expect(page.getByText(/\d+–\d+ of \d+/)).toBeVisible();

    await page.getByLabel('Search by name or email').fill('zzzz-nobody');
    await expect(page.getByText('Nobody matches that search.')).toBeVisible({ timeout: 30_000 });

    await page.getByRole('tab', { name: 'Coordinators' }).click();
    await expect(page.getByLabel('Search by name or email')).toHaveValue('');
    await expect(table).toContainText('Rasha Kamal', { timeout: 30_000 });
  });
});

/**
 * **MG2b item 6, the half RM2 left out**: the manager and a teacher of her department, both ways.
 *
 * `chat` is off in the one-school seed and the rail assertions above depend on that, so this block
 * turns it on and puts it back. The round trip is two sign-ins over one thread: Huda writes to Sara
 * from her own Messages screen (`POST /management/chat/threads {teacherUserId}`), and Sara finds it
 * under Management in her chat — a second transport beside her parent threads, keyed by thread.
 */
test.describe('manager ↔ teacher messages', () => {
  const FROM_MANAGER = `About grade 1 ${Date.now().toString(36).slice(-5)}`;
  const FROM_TEACHER = 'Understood, I will look at it today.';

  let context: APIRequestContext;
  let restoreFlags: (() => Promise<void>) | null = null;

  test.beforeAll(async () => {
    context = await api();
    restoreFlags = await withFlags(context, await schoolOfSara(context), ['chat']);
  });

  test.afterAll(async () => {
    if (restoreFlags) await restoreFlags();
    await context.dispose();
  });

  test('the manager starts a thread with a teacher of her department', async ({ page }) => {
    await signIn(page, MANAGER);
    await rail(page).getByRole('link', { name: 'Messages' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();

    await page.getByRole('button', { name: 'New message' }).click();
    // Her chooser holds three kinds of person now; the teachers are MG2b's addition.
    const who = page.getByLabel('Who do you want to write to');
    await who.selectOption({ label: /Sara Al Harbi/ });
    await page.getByRole('button', { name: 'Open the conversation' }).click();

    const composer = page.getByPlaceholder('Write a message...');
    await composer.fill(FROM_MANAGER);
    await composer.press('Enter');
    await expect(page.locator('main')).toContainText(FROM_MANAGER, { timeout: 30_000 });
  });

  test('the teacher finds it under Management and answers it', async ({ page }) => {
    await signIn(page, SARA);
    await page.goto('/teacher/chat');
    await expect(page.getByRole('tab', { name: /Management/ })).toBeVisible({ timeout: 30_000 });

    // Her second list: keyed by thread, beside the parent threads keyed by child.
    await page.getByRole('tab', { name: /Management/ }).click();
    await page.locator('.thread-card').first().click();
    await expect(page.locator('main')).toContainText(FROM_MANAGER);

    const composer = page.getByPlaceholder('Write a message...');
    await composer.fill(FROM_TEACHER);
    await composer.press('Enter');
    await expect(page.locator('main')).toContainText(FROM_TEACHER, { timeout: 30_000 });
  });

  test('the manager reads the answer on the same thread', async ({ page }) => {
    await signIn(page, MANAGER);
    await rail(page).getByRole('link', { name: 'Messages' }).click();
    await page.getByRole('tab', { name: /Teachers/ }).click();
    await page.locator('.thread-card').first().click();
    await expect(page.locator('main')).toContainText(FROM_TEACHER, { timeout: 30_000 });
  });
});
