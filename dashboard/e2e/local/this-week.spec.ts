import { expect, request, test, type Locator, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';

/**
 * N2.2's acceptance (`docs/teacher-flow.md` §4 step 2), against the built bundle and a local API
 * on H2 started with `SEED_SCHOOL=true` — the one school, 30 classes, 40 teachers, 600 children.
 *
 * Sara Al Harbi (`seed/assignments.csv`) teaches **1A British** and **3A British**, both Math.
 * That is deliberate in the seed and inconvenient here: the drag-to-copy rule is "another class
 * of the same grade, curriculum and subject", and no seeded teacher has two such sections —
 * 1B–1E British Math are each somebody else's. So `test.beforeAll` creates one more Grade 1
 * British section through the Admin API and gives it to Sara, which is exactly what an Admin
 * would do, and leaves the seed alone.
 */
const API = process.env['HQ_API'] ?? 'http://localhost:18080';
const SHOTS = resolve(process.cwd(), '../docs/screenshots/dashboard-n2.2');

const ADMIN = {
  email: process.env['E2E_ADMIN_EMAIL'] ?? 'admin@quest.local',
  password: env('E2E_ADMIN_PASSWORD'),
};
const SARA = { email: 'sara.al-harbi@school.test', password: env('E2E_STAFF_PASSWORD') };

/** Unique per run: the H2 database outlives a single test file. */
const RUN = Date.now().toString(36).slice(-4).toUpperCase();
const SIBLING = `1Z${RUN} British`;

function env(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set — see playwright.local.config.ts`);
  return value;
}

/** Sara's second Grade 1 British Math section, created the way an Admin creates one. */
test.beforeAll(async () => {
  const api = await request.newContext({ baseURL: API });
  const signIn = await api.post('/admin/auth/sign-in', { data: ADMIN });
  expect(signIn.ok()).toBeTruthy();
  const token = ((await signIn.json()) as { token: string }).token;
  const auth = { Authorization: `Bearer ${token}` };

  const created = await api.post('/admin/classes', {
    headers: auth,
    data: { curriculum: 'british', grade: 1, name: SIBLING },
  });
  expect(created.ok()).toBeTruthy();
  const classId = ((await created.json()) as { id: string }).id;

  const teachers = await api.get('/admin/teachers', { headers: auth });
  const sara = ((await teachers.json()) as { userId: string; email: string; assignments: unknown[] }[]).find(
    (row) => row.email === SARA.email,
  );
  expect(sara, 'Sara is missing — start the server with SEED_SCHOOL=true').toBeTruthy();

  // Her seeded two, and nothing an earlier run of this file left behind: a second leftover
  // sibling row would make "the sibling row" ambiguous, which is the one thing the drop needs.
  const kept = (sara!.assignments as { classId: string; className: string; subject: string }[])
    .filter((assignment) => !/^1Z/i.test(assignment.className))
    .map((assignment) => ({ classId: assignment.classId, subject: assignment.subject }));
  const saved = await api.put(`/admin/teachers/${sara!.userId}/assignments`, {
    headers: auth,
    data: { assignments: [...kept, { classId, subject: 'math' }] },
  });
  expect(saved.ok()).toBeTruthy();
  await api.dispose();
});

async function signInAsSara(page: Page): Promise<void> {
  await page.goto('sign-in');
  await page.evaluate(() => localStorage.clear());
  await page.goto('sign-in');
  await page.getByLabel('Email').fill(SARA.email);
  await page.getByLabel('Password').fill(SARA.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  const skip = page.getByRole('button', { name: 'Skip' });
  await skip.waitFor({ state: 'visible', timeout: 15_000 });
  await skip.click();
  await expect(page.getByRole('dialog').first()).toBeHidden();
}

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

  // Her three rows, grouped by grade — 1A and the sibling under Grade 1, 3A under Grade 3.
  await expect(page.getByText('Grade 1', { exact: true })).toBeVisible();
  await expect(rowOf(page, '1A British')).toBeVisible();
  await expect(rowOf(page, SIBLING)).toBeVisible();
  await expect(rowOf(page, '3A British')).toBeVisible();

  // A route outside her scope is a redirect home, not a screen full of red bands.
  await page.goto('admin/classes');
  await expect(page).toHaveURL(/\/teacher\/week/);
});

test('the + on an empty cell opens the editor already knowing the class, subject and day', async ({
  page,
}) => {
  await signInAsSara(page);

  const plus = rowOf(page, '1A British')
    .getByRole('link', { name: /^Add a lesson for 1A British/ })
    .first();
  const label = (await plus.getAttribute('aria-label')) ?? '';
  await plus.click();

  await expect(page).toHaveURL(/lessons\/new\?.*classId=/);
  await expect(page.getByRole('heading', { name: 'New lesson' })).toBeVisible();
  await expect(page.getByLabel('Curriculum')).toHaveValue('british');
  await expect(page.getByLabel('Grade')).toHaveValue('1');
  await expect(page.getByLabel('Subject')).toHaveValue('math');
  // The day the `+` was on, not today.
  const day = new URL(page.url()).searchParams.get('date') ?? '';
  expect(day).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  expect(label).toContain('1A British');
});

test('a manual lesson created from the + appears as a card in that cell', async ({ page }) => {
  await signInAsSara(page);

  await rowOf(page, '1A British')
    .getByRole('link', { name: /^Add a lesson for 1A British/ })
    .first()
    .click();
  await expect(page.getByRole('heading', { name: 'New lesson' })).toBeVisible();

  await page.getByLabel('Title').fill(`Sorting ${RUN}`);
  await page.getByRole('button', { name: /Write it yourself/ }).click();
  await page.getByRole('button', { name: 'Create and write the questions' }).click();
  await expect(page).toHaveURL(/\/teacher\/lessons\/[0-9a-f-]+/, { timeout: 15_000 });

  await page.getByRole('navigation').getByRole('link', { name: 'This week' }).click();
  await expect(rowOf(page, '1A British').getByText(`Sorting ${RUN}`)).toBeVisible();
});

test('dragging the card to another day moves the lesson', async ({ page }) => {
  await signInAsSara(page);

  const row = rowOf(page, '1A British');
  const card = row.getByText(`Sorting ${RUN}`);
  await expect(card).toBeVisible();
  const before = await cellIndexOf(row, `Sorting ${RUN}`);

  // The next empty cell in the same row.
  const empty = row.getByRole('link', { name: /^Add a lesson for 1A British/ }).last();
  const target = (await empty.getAttribute('aria-label')) ?? '';
  await dragTo(page, card, empty);

  await expect(row.getByRole('link', { name: target })).toHaveCount(0);
  const after = await cellIndexOf(row, `Sorting ${RUN}`);
  expect(after).not.toBe(before);

  // And it stuck: a reload reads the server back.
  await page.reload();
  await expect(rowOf(page, '1A British').getByText(`Sorting ${RUN}`)).toBeVisible({ timeout: 15_000 });
  expect(await cellIndexOf(rowOf(page, '1A British'), `Sorting ${RUN}`)).toBe(after);
});

test('dropping the card on the sibling row offers a copy, and the copy appears there', async ({ page }) => {
  await signInAsSara(page);

  const source = rowOf(page, '1A British').getByText(`Sorting ${RUN}`);
  await expect(source).toBeVisible();
  const index = await cellIndexOf(rowOf(page, '1A British'), `Sorting ${RUN}`);
  const target = rowOf(page, SIBLING).getByRole('gridcell').nth(index);
  await dragTo(page, source, target);

  const band = page.getByRole('alert').filter({ hasText: 'Copy this lesson?' });
  await expect(band).toBeVisible();
  await expect(band).toContainText(SIBLING);
  await page.getByRole('button', { name: 'Copy', exact: true }).click();

  await expect(rowOf(page, SIBLING).getByText(`Sorting ${RUN}`)).toBeVisible({ timeout: 15_000 });
  // 3A is another grade: it never becomes a drop target, so nothing landed there.
  await expect(rowOf(page, '3A British').getByText(`Sorting ${RUN}`)).toHaveCount(0);
});

test('the summary strip names the class and the day of every gap', async ({ page }) => {
  await signInAsSara(page);

  const strip = page.getByRole('region', { name: 'This week at a glance' });
  await expect(strip).toBeVisible();
  // Every school day 3A has nothing on, each named by class and by day rather than counted.
  const gaps = strip.getByText(/3A British has no lesson (Sunday|Monday|Tuesday|Wednesday|Thursday)/);
  expect(await gaps.count()).toBeGreaterThan(0);
  await expect(gaps.first()).toBeVisible();
});

test('the screenshot set, EN and AR', async ({ page }) => {
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
      .getByRole('button', { name: new RegExp(`Sorting ${RUN}`) })
      .first()
      .click();
    await expect(page.getByRole('menu').first()).toBeVisible();
    await page.waitForTimeout(400);
    await page.screenshot({ path: `${SHOTS}/02-card-menu-${language}.png` });
    await page.keyboard.press('Escape');
  }

  await page.evaluate(() => localStorage.setItem('hq.language', 'en'));
});

/** Which column of a row holds a card, counted over the row's own `gridcell`s. */
async function cellIndexOf(row: Locator, text: string): Promise<number> {
  const cells = row.getByRole('gridcell');
  const count = await cells.count();
  for (let index = 0; index < count; index += 1) {
    if ((await cells.nth(index).textContent())?.includes(text)) return index;
  }
  return -1;
}
