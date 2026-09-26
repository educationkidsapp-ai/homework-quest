import { type Page } from '@playwright/test';
import { COORDINATOR, expect, signIn, test } from './env';

/**
 * R6's acceptance (`docs/coordinator-flow.md`): her three record screens and the child page,
 * against the built bundle and a local API on H2 started with `SEED_SCHOOL=true`.
 *
 * Rasha Kamal supervises Math on both tracks; Sara Al Harbi's 1A/1B British carry the seeded
 * published lesson "Counting to ten", and `AttemptSeed` puts attempts and a register behind it.
 *
 * Every screen here is a teacher's component reused read-only, so each test asserts both halves:
 * that the numbers arrived (she is reading real rows, not an empty state) and that no control
 * which would change them was drawn. Nothing here writes, so the file needs no cleanup.
 */
const SEEDED_LESSON = 'Counting to ten';
const SEEDED_CLASS = '1A British';

function rail(page: Page) {
  return page.locator('hq-nav');
}

async function openScreen(page: Page, name: string): Promise<void> {
  await signIn(page, COORDINATOR);
  await expect(page).toHaveURL(/\/coordinator$/);
  await rail(page).getByRole('link', { name, exact: true }).click();
}

/** Her class picker names the label the same way the Classes screen does, so pick by value. */
async function pickSeededClass(page: Page): Promise<void> {
  const picker = page.getByLabel('Class');
  const value = await picker.locator('option', { hasText: SEEDED_CLASS }).first().getAttribute('value');
  await picker.selectOption(value ?? '');
}

test.describe('the coordinator’s records', () => {
  test('reads a register for one of her Math sections, and cannot mark it', async ({ page }) => {
    await openScreen(page, 'Attendance');
    await expect(page.getByRole('heading', { level: 1, name: 'Attendance' })).toBeVisible();
    await pickSeededClass(page);

    // The matrix: her days across the top and the roster down the side.
    const table = page.getByRole('table', { name: 'Attendance' });
    await expect(table).toBeVisible({ timeout: 30_000 });
    await expect(table.getByRole('row')).not.toHaveCount(1);
    await expect(table).toContainText('Present');

    // Read-only (DR2): no pill to press, no note to type, no Save, no date to shift.
    await expect(page.getByRole('button', { name: 'Save Attendance' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Mark all present' })).toHaveCount(0);
    await expect(page.locator('textarea')).toHaveCount(0);
    await expect(page.locator('input[type="date"][aria-label="Select date"]')).toHaveCount(0);
    // The one thing she may take away with her.
    await expect(page.getByRole('button', { name: 'Export CSV' })).toBeVisible();
  });

  test('shows the seeded lesson as a gradebook column, with no way to mark it', async ({ page }) => {
    await openScreen(page, 'Gradebook');
    await expect(page.getByRole('heading', { level: 1, name: 'Gradebook' })).toBeVisible();
    await pickSeededClass(page);

    const grid = page.getByRole('table', { name: 'Gradebook' });
    await expect(grid).toContainText(SEEDED_LESSON, { timeout: 30_000 });

    // Neither export exists in her namespace, and no square opens an override.
    await expect(page.getByRole('button', { name: 'Export CSV' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Export Excel' })).toHaveCount(0);
    await expect(page.locator('[data-hq-gb-cell]:not([disabled])')).toHaveCount(0);
  });

  test('opens a child’s report from a gradebook row', async ({ page }) => {
    await openScreen(page, 'Gradebook');
    await pickSeededClass(page);

    const grid = page.getByRole('table', { name: 'Gradebook' });
    await expect(grid).toContainText(SEEDED_LESSON, { timeout: 30_000 });
    // The first name in the first column — a row header, not a cell.
    const name = grid.getByRole('rowheader').getByRole('link').first();
    const label = (await name.textContent())?.trim() ?? '';
    await name.click();

    await expect(page).toHaveURL(/\/coordinator\/children\//);
    await expect(page.getByRole('heading', { level: 1 })).toContainText(label);
    // Her copy of the report: the crumb goes back to her Classes screen, not the teacher's.
    await expect(page.locator('main')).not.toContainText('/teacher/');
  });

  test('reaches a lesson’s results from her read-only lesson page', async ({ page }) => {
    await openScreen(page, 'All lessons');
    await page.getByLabel('Status').selectOption('published');
    const row = page.getByRole('link', { name: SEEDED_LESSON }).first();
    await expect(row).toBeVisible({ timeout: 30_000 });
    await row.click();

    // The link is behind `gradebook` and her own `coordinator.results.read`, and it lands in her
    // area rather than on the teacher's route, which `roleGuard` would refuse.
    await page.getByRole('link', { name: 'Results', exact: true }).click();
    await expect(page).toHaveURL(/\/coordinator\/lessons\/[^/]+\/results$/);
    await expect(page.locator('main')).toContainText('Class average');

    // Read-only: no release toggle, no marking, no export.
    await expect(page.locator('[data-hq-release]')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Export CSV' })).toHaveCount(0);
    await expect(page.locator('hq-mark-panel')).toHaveCount(0);
  });

  test('lists the class’s exams, with no New exam and no settings', async ({ page }) => {
    await openScreen(page, 'Exams');
    await expect(page.getByRole('heading', { level: 1, name: 'Exams' })).toBeVisible();
    await pickSeededClass(page);

    // Either the seed gave this section an exam, or the tab says so plainly. Both are correct
    // answers about her school; what must not appear is a control she cannot use.
    const list = page.locator('hq-exams-tab');
    await expect(list).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole('link', { name: 'New exam' })).toHaveCount(0);
    await expect(page.locator('hq-exam-settings-card')).toHaveCount(0);
  });
});
