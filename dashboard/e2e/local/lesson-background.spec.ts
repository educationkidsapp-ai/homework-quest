import { request } from '@playwright/test';
import { resolve } from 'node:path';
import {
  API,
  expect,
  removeLessonsOfThisRun,
  RUN,
  SARA,
  schoolDayFromNow,
  signInAsSara,
  signInForToken,
  test,
} from './env';

/**
 * E3 §1–§3, as Sara meets them: she starts a lesson, walks away from it, and the dashboard
 * tells her when it is done.
 *
 * What this proves that no unit test can: the chain outlives the page that started it (she
 * leaves mid-upload and the lesson still reaches `review`), the list keeps saying what a row is
 * doing while she is on it, and the bell is fed by the server rather than by the screen she
 * happens to have open — she is on the list the whole time, and the badge still lands.
 *
 * Runs against the local H2 server on the one-school seed with `LLM_PROVIDER=fake`. That fake
 * pipeline stops at `needs_review` first (the same place the real one does), so both
 * notifications are asserted, in the order the server writes them.
 */
const ONE_PAGE_PDF = resolve(process.cwd(), 'e2e/fixtures/one-page.pdf');
const TITLE = `Background ${RUN}`;
/** A day this run owns: a class holds one lesson per day, and the database is never reset. */
const DATE = schoolDayFromNow(500 + (Math.floor(Date.now() / 1000) % 60));

let classId = '';
let saraToken = '';
let lessonId = '';

test.beforeAll(async () => {
  saraToken = await signInForToken(SARA);
  const api = await request.newContext({ baseURL: API });
  try {
    const classes = await api.get('/teacher/classes', {
      headers: { Authorization: `Bearer ${saraToken}` },
    });
    const rows = (await classes.json()) as { classId: string; className: string; subject: string }[];
    const mine = rows.find((row) => /1A British/i.test(row.className) && row.subject === 'math');
    expect(mine, 'the seed should give Sara a 1A British Math section').toBeTruthy();
    classId = mine!.classId;
  } finally {
    await api.dispose();
  }
});

test.afterAll(async () => {
  await removeLessonsOfThisRun();
});

test.describe.configure({ mode: 'serial' });

test('she sends the upload to the background and the list keeps working', async ({ page }) => {
  test.setTimeout(240_000);
  await signInAsSara(page);

  const query = new URLSearchParams({ classId, subject: 'math', date: DATE });
  await page.goto(`teacher/lessons/new?${query.toString()}`);
  await expect(page.getByRole('heading', { name: 'New lesson' })).toBeVisible();
  await page.getByLabel('Title').fill(TITLE);
  await page.getByRole('button', { name: /Upload a PDF/ }).click();
  await page.getByLabel('Drop a PDF here').setInputFiles(ONE_PAGE_PDF);
  await page.getByRole('button', { name: 'Create and read the PDF' }).click();

  // The progress card, and the way out of it that E3 added.
  const background = page.getByRole('button', { name: 'Work in background (return to Lessons)' });
  await expect(background).toBeVisible({ timeout: 60_000 });
  await background.click();

  // The list, and her lesson on it, mid-pipeline. This is the navigation the old chain would
  // have undone a minute later by pulling her onto the lesson page.
  await expect(page).toHaveURL(/\/teacher\/lessons(\?|$)/, { timeout: 30_000 });
  const row = page.getByRole('row').filter({ hasText: TITLE });
  await expect(row).toBeVisible({ timeout: 60_000 });
  await expect(row).toContainText(/Reading the pages|Generating|Needs review/, { timeout: 60_000 });

  // The lesson exists and is the one she named — read from the server, not from the row.
  const api = await request.newContext({ baseURL: API });
  try {
    const hers = await api.get('/teacher/lessons', {
      headers: { Authorization: `Bearer ${saraToken}` },
    });
    const mine = ((await hers.json()) as { id: string; title?: string }[]).find(
      (lesson) => lesson.title === TITLE,
    );
    expect(mine, 'the chain she walked away from never created the lesson').toBeTruthy();
    lessonId = mine!.id;
  } finally {
    await api.dispose();
  }

  // She stays on the list. The bell is fed by the socket and by `/me/notifications`, so the
  // badge arrives without her opening the lesson at all.
  const bell = page.getByRole('button', { name: 'Notifications' });
  await expect(bell).toContainText(/[1-9]/, { timeout: 120_000 });

  await bell.click();
  const first = page.getByRole('menuitem').filter({ hasText: /Skills to confirm|Questions ready/ }).first();
  await expect(first).toBeVisible();
  await expect(first).toContainText('Skills to confirm');
  await first.click();

  // The link the server put on the notification is a dashboard path, and it opens her lesson.
  await expect(page).toHaveURL(new RegExp(`/teacher/lessons/${lessonId}`), { timeout: 30_000 });
});

test('confirming the skills leaves her free again, and the bell says when it is ready', async ({
  page,
}) => {
  test.setTimeout(240_000);
  await signInAsSara(page);
  await page.goto(`teacher/lessons/${lessonId}`);

  const confirm = page.getByRole('button', { name: 'Make the quest' });
  await expect(confirm, 'the analyze/skills steps never finished').toBeVisible({ timeout: 120_000 });
  await confirm.click();

  // Back to the list while it generates — the whole point of E3 is that this is allowed.
  await page.goto('teacher/lessons');
  const bell = page.getByRole('button', { name: 'Notifications' });
  await bell.click();
  await expect(
    page.getByRole('menuitem').filter({ hasText: 'Questions ready' }).first(),
    'the lesson never reached `review`, or the bell never heard about it',
  ).toBeVisible({ timeout: 180_000 });

  await page.getByRole('menuitem').filter({ hasText: 'Questions ready' }).first().click();
  await expect(page).toHaveURL(new RegExp(`/teacher/lessons/${lessonId}`), { timeout: 30_000 });
  await expect(page.getByRole('heading', { name: 'Levels' })).toBeVisible({ timeout: 60_000 });
});
