import { type Page } from '@playwright/test';
import { COORDINATOR, expect, signIn, test } from './env';

/**
 * R5's acceptance (`docs/coordinator-flow.md`), against the built bundle and a local API on H2
 * started with `SEED_SCHOOL=true` — the one school, 30 classes, 40 teachers, 600 children.
 *
 * Rasha Kamal (`seed/coordinators.csv`) is the **Math** coordinator with no curriculum on her
 * scope row, so she supervises Math on both tracks and every grade. Sara Al Harbi
 * (`seed/assignments.csv`) teaches 1A British and 1B British Math, and `AttemptSeed` publishes
 * one lesson — "Counting to ten", six days ago — into each of them, which is the seeded published
 * lesson the calendar test looks for.
 *
 * Nothing here creates, changes or removes anything: the whole point of the role is that it
 * cannot (DR2), so the file needs no cleanup and can run in any order beside the others.
 */
const SEEDED_LESSON = 'Counting to ten';

/** The 240 px rail, not a page's breadcrumb — both are `role="navigation"` with a list inside. */
function rail(page: Page) {
  return page.locator('hq-nav');
}

/** Six days back, as `YYYY-MM-DD`: where `AttemptSeed` dated the published lesson. */
function seededLessonDate(): string {
  const day = new Date();
  day.setDate(day.getDate() - 6);
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${day.getFullYear()}-${pad(day.getMonth() + 1)}-${pad(day.getDate())}`;
}

async function openCoordinator(page: Page): Promise<void> {
  await signIn(page, COORDINATOR);
  await expect(page).toHaveURL(/\/coordinator$/);
}

test.describe('the coordinator area', () => {
  test('lands on her Home, which says what she is responsible for', async ({ page }) => {
    await openCoordinator(page);

    await expect(page.getByRole('heading', { level: 1 })).toContainText('Rasha Kamal');
    // Her scope, from `GET /coordinator/me`: one subject, no curriculum, so both tracks (DR1).
    await expect(page.locator('main')).toContainText('Math · both tracks');
    await expect(page.getByText('Classes in scope')).toBeVisible();
    await expect(page.getByText('What needs you')).toBeVisible();

    // Her rail is these four and nothing else: no This week, no My classes, no Attendance (R6).
    await expect(rail(page).getByRole('link')).toHaveText(['Home', 'Teachers', 'Classes', 'All lessons']);
  });

  test('lists the teachers of her subject, read-only, and searches them', async ({ page }) => {
    await openCoordinator(page);
    await rail(page).getByRole('link', { name: 'Teachers' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Teachers' })).toBeVisible();

    const table = page.getByRole('table', { name: 'Teachers' });
    await expect(table.getByRole('row')).not.toHaveCount(1); // the header alone would be one row
    await expect(table).toContainText('Sara Al Harbi');

    // Read-only: no row menu, no action button anywhere on the table (DR2).
    await expect(table.getByRole('button')).toHaveCount(0);

    await page.getByLabel('Search by name, email or class').fill('Sara');
    await expect(table).toContainText('sara.al-harbi@school.test');
    await expect(table).not.toContainText('Khalid Jaber');
  });

  test('shows her Math sections only, and the seeded lesson on the month', async ({ page }) => {
    await openCoordinator(page);
    await rail(page).getByRole('link', { name: 'Classes' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Classes' })).toBeVisible();

    const sections = page.locator('hq-card').first().getByRole('listitem');
    await expect(sections.first()).toBeVisible();
    // Every section in scope carries her subject. English's 1A British is Fatima's, not hers.
    for (const text of await sections.allTextContents()) expect(text).toContain('Math');
    await expect(page.locator('hq-card').first()).toContainText('1A British');

    // The month of the one section the seed published into. The lesson is six days old, which in
    // the first week of a month is the month before — so the grid is paged back when it is.
    // By value rather than by label: the option reads "1A British · Grade 1 · Math · British", and
    // naming the whole of that in the test would be a second copy of how the label is built.
    const filter = page.getByLabel('Calendar for');
    const value = await filter.locator('option', { hasText: '1A British' }).first().getAttribute('value');
    await filter.selectOption(value ?? '');
    const date = seededLessonDate();
    if (!date.startsWith(new Date().toISOString().slice(0, 7))) {
      await page.getByRole('button', { name: 'Previous month' }).click();
    }
    const cell = page.locator(`[data-date="${date}"]`);
    await expect(cell).toContainText(SEEDED_LESSON, { timeout: 30_000 });

    // No add affordances anywhere on her month (DR2): the teacher's `+` is not drawn for her.
    await expect(page.locator('.cal__add')).toHaveCount(0);

    await cell.getByRole('link').first().click();
    await expect(page).toHaveURL(/\/coordinator\/lessons\//);
  });

  test('opens a lesson she may read and not change', async ({ page }) => {
    await openCoordinator(page);
    await rail(page).getByRole('link', { name: 'All lessons' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'All lessons' })).toBeVisible();

    await page.getByLabel('Status').selectOption('published');
    const row = page.getByRole('link', { name: SEEDED_LESSON }).first();
    await expect(row).toBeVisible({ timeout: 30_000 });
    await row.click();

    await expect(page.getByRole('heading', { level: 1 })).toContainText(SEEDED_LESSON);
    // The lesson page in read-only mode: the questions are there to read and nothing writes.
    for (const name of ['Publish', 'Unpublish', 'Add a question', 'Save', 'Delete the lesson'])
      await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0);
    await expect(page.locator('hq-stop-editor')).toHaveCount(0);
    await expect(page.locator('hq-parent-panel-editor')).toHaveCount(0);
    await expect(page.locator('input[type="file"]')).toHaveCount(0);
  });
});
