import { expect, test, type Locator, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { RUN, removeLessonsOfThisRun, signInAsSara } from './env';

/**
 * N2.2's acceptance (`docs/teacher-flow.md` §4 step 2): the week grid, the `+`, the drag that
 * moves a lesson and the drop that copies it onto a sibling section.
 *
 * Rewritten for the one-school seed as it stands (N2.5). It used to create a `1Z… British`
 * section through the Admin API in `beforeAll` and assign it to Sara, because no seeded teacher
 * then had two sections of the same grade *and* subject and the drag-to-copy rule needs one.
 * `seed/assignments.csv` now gives Sara **1A British** and **1B British**, both Math, so the
 * sibling is seeded and this file creates no classes at all — which matters against QA, where
 * the database is shared and never reset, and where an extra section would also change what
 * `teacher-flow.spec.ts` asserts she teaches.
 *
 * What it does create — one lesson, plus the copy the drop makes — it deletes again in
 * `afterAll`, so the week has the same empty cells for the next run.
 */
const SHOTS = resolve(process.cwd(), '../docs/screenshots/dashboard-n2.2');

const OWN = '1A British';
const SIBLING = '1B British';
const TITLE = `Sorting ${RUN}`;

/** The row of the grid for one class, by its `rowheader`. */
function rowOf(page: Page, className: string): Locator {
  return page.getByRole('row').filter({ has: page.getByRole('rowheader', { name: new RegExp(className) }) });
}

/**
 * CDK's drag needs real pointer movement — a single mousedown/mouseup never crosses the start
 * threshold — so the gesture is driven step by step rather than with `dragTo`.
 */
async function dragTo(page: Page, card: Locator, target: Locator): Promise<void> {
  const from = await card.boundingBox();
  const to = await target.boundingBox();
  expect(from && to).toBeTruthy();
  await page.mouse.move(from!.x + from!.width / 2, from!.y + from!.height / 2);
  await page.mouse.down();
  await page.mouse.move(from!.x + from!.width / 2 + 20, from!.y + from!.height / 2, { steps: 5 });
  await page.mouse.move(to!.x + to!.width / 2, to!.y + to!.height / 2, { steps: 15 });
  await page.mouse.move(to!.x + to!.width / 2, to!.y + to!.height / 2 + 2, { steps: 3 });
  await page.mouse.up();
  // The 250 ms settle, then the optimistic paint.
  await page.waitForTimeout(600);
}

/** Which column of a row holds a card, counted over the row's own `gridcell`s. */
async function cellIndexOf(row: Locator, text: string): Promise<number> {
  const cells = row.getByRole('gridcell');
  const count = await cells.count();
  for (let index = 0; index < count; index += 1) {
    if ((await cells.nth(index).textContent())?.includes(text)) return index;
  }
  return -1;
}

test.describe.configure({ mode: 'serial' });

test('signing in lands on This week, and the rail holds nothing else she cannot use', async ({ page }) => {
  await signInAsSara(page);

  await expect(page).toHaveURL(/\/teacher\/week/);
  await expect(page.getByRole('heading', { level: 1, name: 'This week' })).toBeVisible();
  await expect(page.getByRole('grid', { name: 'This week' })).toBeVisible();

  const rail = page.getByRole('navigation');
  await expect(rail.getByRole('list').getByRole('link')).toHaveCount(2);
  await expect(rail.getByRole('link', { name: 'This week' })).toBeVisible();
  await expect(rail.getByRole('link', { name: 'My classes' })).toBeVisible();

  // Her two sections, grouped by grade — both Grade 1, which is what makes them siblings.
  await expect(page.getByText('Grade 1', { exact: true })).toBeVisible();
  await expect(rowOf(page, OWN)).toBeVisible();
  await expect(rowOf(page, SIBLING)).toBeVisible();

  // A route outside her scope is a redirect home, not a screen full of red bands.
  await page.goto('admin/classes');
  await expect(page).toHaveURL(/\/teacher\/week/);
});

test('the + on an empty cell opens the editor already knowing the class, subject and day', async ({
  page,
}) => {
  await signInAsSara(page);

  const plus = rowOf(page, OWN)
    .getByRole('link', { name: new RegExp(`^Add a lesson for ${OWN}`) })
    .first();
  const label = (await plus.getAttribute('aria-label')) ?? '';
  await plus.click();

  await expect(page).toHaveURL(/lessons\/new\?.*classId=/);
  await expect(page.getByRole('heading', { name: 'New lesson' })).toBeVisible();
  // N2.4b: a teacher authors into a *section*, so the one picker is the class the `+` named and
  // it is fixed. The Admin's curriculum/grade/subject trio is gone from her screen — it could
  // not tell 1A from 1B, which is the whole point of the row the `+` was on.
  await expect(page.getByLabel('Class')).toHaveValue(/1a british::math$/i);
  await expect(page.getByLabel('Class')).toBeDisabled();
  await expect(page.getByLabel('Curriculum')).toHaveCount(0);
  // The day the `+` was on, not today.
  const day = new URL(page.url()).searchParams.get('date') ?? '';
  expect(day).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  expect(label).toContain(OWN);
});

test('a manual lesson created from the + appears as a card in that cell', async ({ page }) => {
  await signInAsSara(page);

  await rowOf(page, OWN)
    .getByRole('link', { name: new RegExp(`^Add a lesson for ${OWN}`) })
    .first()
    .click();
  await expect(page.getByRole('heading', { name: 'New lesson' })).toBeVisible();

  await page.getByLabel('Title').fill(TITLE);
  await page.getByRole('button', { name: /Write it yourself/ }).click();
  await page.getByRole('button', { name: 'Create and write the questions' }).click();
  await expect(page).toHaveURL(/\/teacher\/lessons\/[0-9a-f-]+/, { timeout: 30_000 });

  await page.getByRole('navigation').getByRole('link', { name: 'This week' }).click();
  await expect(rowOf(page, OWN).getByText(TITLE)).toBeVisible();
});

test('dragging the card to another day moves the lesson', async ({ page }) => {
  await signInAsSara(page);

  const row = rowOf(page, OWN);
  const card = row.getByText(TITLE);
  await expect(card).toBeVisible();
  const before = await cellIndexOf(row, TITLE);

  // The next empty cell in the same row.
  const empty = row.getByRole('link', { name: new RegExp(`^Add a lesson for ${OWN}`) }).last();
  const target = (await empty.getAttribute('aria-label')) ?? '';
  await dragTo(page, card, empty);

  await expect(row.getByRole('link', { name: target })).toHaveCount(0);
  const after = await cellIndexOf(row, TITLE);
  expect(after).not.toBe(before);

  // And it stuck: a reload reads the server back.
  await page.reload();
  await expect(rowOf(page, OWN).getByText(TITLE)).toBeVisible({ timeout: 15_000 });
  expect(await cellIndexOf(rowOf(page, OWN), TITLE)).toBe(after);
});

test('dropping the card on the sibling row offers a copy, and the copy appears there', async ({ page }) => {
  await signInAsSara(page);

  const source = rowOf(page, OWN).getByText(TITLE);
  await expect(source).toBeVisible();
  const index = await cellIndexOf(rowOf(page, OWN), TITLE);
  const target = rowOf(page, SIBLING).getByRole('gridcell').nth(index);
  await dragTo(page, source, target);

  const band = page.getByRole('alert').filter({ hasText: 'Copy this lesson?' });
  await expect(band).toBeVisible();
  await expect(band).toContainText(SIBLING);
  await page.getByRole('button', { name: 'Copy', exact: true }).click();

  await expect(rowOf(page, SIBLING).getByText(TITLE)).toBeVisible({ timeout: 15_000 });
});

test('the summary strip names the class and the day of every gap', async ({ page }) => {
  await signInAsSara(page);

  const strip = page.getByRole('region', { name: 'This week at a glance' });
  await expect(strip).toBeVisible();
  // Every school day a section has nothing on, named by class and by day rather than counted.
  const gaps = strip.getByText(
    new RegExp(`1[AB] British has no lesson (Sunday|Monday|Tuesday|Wednesday|Thursday)`),
  );
  expect(await gaps.count()).toBeGreaterThan(0);
  await expect(gaps.first()).toBeVisible();
});

test('the screenshot set, EN and AR', async ({ page }) => {
  test.setTimeout(120_000);
  await mkdir(SHOTS, { recursive: true });
  await signInAsSara(page);

  for (const language of ['en', 'ar'] as const) {
    await page.evaluate((lang) => localStorage.setItem('hq.language', lang), language);
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('dir', language === 'ar' ? 'rtl' : 'ltr');
    await expect(page.getByRole('grid')).toBeVisible();
    // Long enough for the summary strip's `listStagger` to finish (30 ms apart, 250 ms each).
    const strip = page.getByRole('region', { name: /at a glance|باختصار/ });
    await expect(strip).toBeVisible();
    await page.waitForTimeout(1500);
    await page.screenshot({ path: `${SHOTS}/01-this-week-${language}.png` });

    // The summary strip sits under the fold behind the sticky footer, so it gets its own frame.
    await strip.scrollIntoViewIfNeeded();
    await page.waitForTimeout(300);
    await page.screenshot({ path: `${SHOTS}/03-summary-${language}.png` });
    await page.mouse.wheel(0, -800);
    await page.waitForTimeout(300);

    // The card's menu open — the keyboard twin of the drag.
    await page
      .getByRole('button', { name: new RegExp(TITLE) })
      .first()
      .click();
    await expect(page.getByRole('menu').first()).toBeVisible();
    await page.waitForTimeout(400);
    await page.screenshot({ path: `${SHOTS}/02-card-menu-${language}.png` });
    await page.keyboard.press('Escape');
  }

  await page.evaluate(() => localStorage.setItem('hq.language', 'en'));
});

/**
 * The week back the way it was found.
 *
 * This file's cells are *this* week's, and there are only five of them per section: without this
 * a second run against the same database would find the row full and have no `+` left to press.
 */
test.afterAll(removeLessonsOfThisRun);
