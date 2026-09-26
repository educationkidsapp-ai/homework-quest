import { request, type Page } from '@playwright/test';
import {
  addStopThroughForm,
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
 * E5's acceptance (`docs/teacher-flow.md`, "Adding Level 2, Level 3 and Again").
 *
 * Sara writes Level 1 by hand and then asks the assistant for Level 2 — `POST
 * /teacher/lessons/{id}/plays/2/generate`. Nothing about the way back is new: the job is one
 * ledger step, so the editor's own `/status` poll fills the tab in, and this spec is really
 * about the three things only the dashboard can get wrong — the tab saying which level is being
 * written, the level actually appearing in it, and `exists` turning into the Replace band rather
 * than a dead end.
 *
 * Teacher routes throughout: an operation that fell back to `/admin/**` would fail on her token.
 * The server runs on H2 with `SEED_SCHOOL=true` and `LLM_PROVIDER=fake` (see `README.md`).
 */
const TITLE = `Sharing sweets ${RUN}`;
const DATE = schoolDayFromNow(360 + (Math.floor(Date.now() / 1000) % 40));
let classId = '';
let lessonUrl = '';

test.beforeAll(async () => {
  const token = await signInForToken(SARA);
  const api = await request.newContext({ baseURL: API });
  const classes = await api.get('/teacher/classes', { headers: { Authorization: `Bearer ${token}` } });
  const rows = (await classes.json()) as { classId: string; className: string; subject: string }[];
  const mine = rows.find((row) => /1A British/i.test(row.className) && row.subject === 'math');
  expect(mine, 'no 1A British Math section for Sara').toBeTruthy();
  classId = mine!.classId;
  await api.dispose();
});

test.afterAll(removeLessonsOfThisRun);

test.describe.configure({ mode: 'serial' });

async function openTheLesson(page: Page): Promise<void> {
  await signInAsSara(page);
  await page.goto(lessonUrl);
  await expect(page.getByRole('heading', { level: 1, name: TITLE })).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('[data-hq-add-stop-trigger] button')).toBeVisible({ timeout: 15_000 });
}

function levelTab(page: Page, name: string) {
  return page.getByRole('tab', { name: new RegExp(name) });
}

function stopRows(page: Page) {
  return page.getByRole('listbox', { name: 'Stops' }).getByRole('option');
}

test('Sara writes three questions in Level 1', async ({ page }) => {
  test.setTimeout(240_000);
  await signInAsSara(page);

  const query = new URLSearchParams({
    classId,
    curriculum: 'british',
    grade: '1',
    subject: 'math',
    date: DATE,
  });
  await page.goto(`teacher/lessons/new?${query.toString()}`);
  await page.getByLabel('Title').fill(TITLE);
  await page.getByRole('button', { name: /Write it yourself/ }).click();
  await page.getByRole('button', { name: 'Create and write the questions' }).click();
  await expect(page).toHaveURL(/\/teacher\/lessons\/[0-9a-f-]+/, { timeout: 20_000 });
  lessonUrl = new URL(page.url()).pathname.replace(/^\/dashboard\//, '');

  // The chooser's starter stop is already there; two more make the three the assistant reads.
  await page.getByRole('button', { name: 'Cancel' }).click();
  await addStopThroughForm(page, {
    title: 'Six sweets, two friends',
    question: 'Six sweets shared between two friends — how many each?',
    type: 'choice',
  });
  await addStopThroughForm(page, {
    title: 'Ten sweets, five friends',
    question: 'Ten sweets shared between five friends — how many each?',
    type: 'choice',
  });
  await expect(stopRows(page)).toHaveCount(3);
});

test('she asks the assistant for Level 2 and it fills that tab', async ({ page }) => {
  test.setTimeout(300_000);
  await openTheLesson(page);

  // An empty level's tab opens (D27) rather than being greyed out, and the card behind it is
  // where E5 lives. The source is `manual`, but the button no longer asks about the source.
  await levelTab(page, 'Level 2').click();
  await expect(page.getByText(/This level is empty/)).toBeVisible();
  await page.getByRole('button', { name: 'Let the assistant write it' }).click();

  // The tab says which level is being written straight away — the `JobRef` carries no steps, so
  // a page that waited for the ledger would show "does not exist yet" over a level in flight.
  await expect(page.getByText('The assistant is writing Level 2…')).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole('list', { name: /pipeline/i })).toBeVisible();

  // …and then the poll fills it in, with no reload and nothing else pressed.
  await expect(stopRows(page)).not.toHaveCount(0, { timeout: 240_000 });
  await expect(page.getByText('The assistant is writing Level 2…')).toBeHidden();
  await expect(page.getByText(/This level is empty/)).toBeHidden();

  // Level 1 is untouched: the assistant writes *one* level, and hers is the one it reads.
  await levelTab(page, 'Level 1').click();
  await expect(stopRows(page)).toHaveCount(3);
});

test('asking again for a level that has questions asks her first', async ({ page }) => {
  test.setTimeout(300_000);
  await openTheLesson(page);
  await levelTab(page, 'Level 2').click();
  const before = await stopRows(page).count();
  expect(before).toBeGreaterThan(0);

  await page.getByRole('button', { name: 'Rewrite this level with the assistant' }).click();
  await expect(page.getByText('Replace this level?')).toBeVisible();
  await page.getByRole('button', { name: 'Replace', exact: true }).click();

  await expect(page.getByText('The assistant is writing Level 2…')).toBeVisible({ timeout: 20_000 });
  await expect(page.getByText('The assistant is writing Level 2…')).toBeHidden({ timeout: 240_000 });
  await expect(stopRows(page)).not.toHaveCount(0);
});
