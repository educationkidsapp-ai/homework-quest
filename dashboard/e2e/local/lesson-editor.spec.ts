import { expect, request, test, type Locator, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';

/**
 * N2.4a's acceptance (`docs/teacher-flow.md` §4 steps 4–8): Sara writes a lesson by hand.
 *
 * She adds three kinds of stop from the grouped menu, rewrites one in the inline editor,
 * reorders the list by dragging, attaches a picture, and finally asks the model for the other
 * levels — which is what gives her a parent panel to edit. Everything runs against the
 * `/teacher/**` aliases: this file signs in as a teacher and never as the Admin, so an
 * operation that quietly fell back to `/admin/**` would fail on her token rather than pass.
 *
 * The server runs on H2 with `SEED_SCHOOL=true` and `LLM_PROVIDER=fake` (see `README.md`).
 */
const API = process.env['HQ_API'] ?? 'http://localhost:18080';
const SHOTS = resolve(process.cwd(), '../docs/screenshots/dashboard-n2.4');
const SQUARE_PNG = resolve(process.cwd(), 'e2e/fixtures/square.png');
const SARA = { email: 'sara.al-harbi@school.test', password: env('E2E_STAFF_PASSWORD') };

/** Unique per run: the H2 database outlives a single test file. */
const RUN = Date.now().toString(36).slice(-4).toUpperCase();
const TITLE = `Sorting shapes ${RUN}`;

/**
 * A day far enough out that no earlier run has taken it. The lesson is created from the
 * `lessons/new` route with the class in the query — the same link the Home's "Add today's
 * lesson" builds — rather than from a `+` on the calendar, because whether a `+` is there at
 * all depends on what previous runs left in the week, and this file should not.
 */
const DATE = nextYear();
let classId = '';

function nextYear(): string {
  const day = new Date();
  day.setUTCFullYear(day.getUTCFullYear() + 1);
  return day.toISOString().slice(0, 10);
}

function env(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set — see playwright.local.config.ts`);
  return value;
}

/** Sara's own 1A British Math section, read with her token — never the Admin's. */
test.beforeAll(async () => {
  const api = await request.newContext({ baseURL: API });
  const signIn = await api.post('/admin/auth/sign-in', { data: SARA });
  expect(signIn.ok(), 'Sara could not sign in — is the server seeded with SEED_STAFF_PASSWORD?').toBeTruthy();
  const token = ((await signIn.json()) as { token: string }).token;

  const classes = await api.get('/teacher/classes', { headers: { Authorization: `Bearer ${token}` } });
  const rows = (await classes.json()) as { classId: string; className: string; subject: string }[];
  const mine = rows.find((row) => /1A British/i.test(row.className) && row.subject === 'math');
  expect(mine, `no 1A British Math among ${rows.map((r) => `${r.className}/${r.subject}`).join(', ')}`).toBeTruthy();
  classId = mine!.classId;
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
  await skip.waitFor({ state: 'visible', timeout: 30_000 });
  await skip.click();
  await expect(page.getByRole('dialog').first()).toBeHidden();
}

/** The one lesson this file writes, created once and carried through every test below. */
async function openTheLesson(page: Page): Promise<void> {
  await signInAsSara(page);
  await page.goto(lessonUrl);
  await expect(page.getByRole('heading', { level: 1, name: TITLE })).toBeVisible({ timeout: 15_000 });
  // The card renders after the lesson resolves; acting before it reads an empty screen.
  await expect(page.getByRole('button', { name: '+ Add stop' })).toBeVisible({ timeout: 15_000 });
}

let lessonUrl = '';

/**
 * Scoped to the listbox on purpose: the editor's picture `<select>` renders `<option>`s, which
 * carry the same ARIA role, and an unscoped `getByRole('option')` counts those as stops.
 */
function stopRows(page: Page): Locator {
  return page.getByRole('listbox', { name: 'Stops' }).getByRole('option');
}

function editor(page: Page): Locator {
  return page.locator('hq-stop-editor');
}

/** Adds one stop and waits for the list to grow — "+ Add stop" reloads the lesson behind it. */
async function addStop(page: Page, menuItem: string, expectedTitle: string): Promise<void> {
  const before = await stopRows(page).count();
  await page.getByRole('button', { name: '+ Add stop' }).click();
  await page.getByRole('menuitem', { name: menuItem, exact: true }).click();
  await expect(stopRows(page)).toHaveCount(before + 1, { timeout: 20_000 });
  await expect(stopRows(page).filter({ hasText: expectedTitle }).first()).toBeVisible();
}

/** A row reads "3\nThree last questions\nExit ticket"; the middle line is the stop's title. */
function titleOf(row: string): string {
  return row.split('\n')[1] ?? row;
}

/** CDK's drag needs real pointer movement — one mousedown/mouseup never crosses the threshold. */
async function dragHandle(page: Page, handle: Locator, target: Locator): Promise<void> {
  // Centred, not merely "in view": the page's sticky footer sits over the bottom of the
  // viewport, and a handle scrolled to just-visible ends up underneath it, where the mousedown
  // lands on the footer and no drag ever starts.
  await handle.evaluate((element) => element.scrollIntoView({ block: 'center' }));
  await page.waitForTimeout(300);
  const from = await handle.boundingBox();
  const to = await target.boundingBox();
  expect(from && to).toBeTruthy();
  const startX = from!.x + from!.width / 2;
  const startY = from!.y + from!.height / 2;
  // Just inside the target's top edge, not its centre: the rows shift as the placeholder moves,
  // so aiming at where the middle of row one *was* can land back in the original slot.
  const endX = to!.x + to!.width / 2;
  const endY = to!.y + 4;

  await page.mouse.move(startX, startY);
  await page.mouse.down();
  await page.mouse.move(startX, startY - 10, { steps: 5 });
  await page.mouse.move(endX, endY - 10, { steps: 20 });
  await page.mouse.move(endX, endY, { steps: 5 });
  await page.mouse.up();
}

test.describe.configure({ mode: 'serial' });

test('Sara writes a lesson by hand and it opens on the editor', async ({ page }) => {
  test.setTimeout(120_000);
  await signInAsSara(page);

  const query = new URLSearchParams({ classId, curriculum: 'british', grade: '1', subject: 'math', date: DATE });
  await page.goto(`teacher/lessons/new?${query.toString()}`);

  await expect(page.getByRole('heading', { name: 'New lesson' })).toBeVisible();
  // N2.4b: a teacher authors into a *section*, so the one picker is the class the `+` named and
  // it is fixed — the curriculum/grade/subject trio is the Admin's, and cannot tell 1A from 1B.
  await expect(page.getByLabel('Class')).toHaveValue(`${classId}::math`);
  await expect(page.getByLabel('Class')).toBeDisabled();
  await page.getByLabel('Title').fill(TITLE);
  await page.getByRole('button', { name: /Write it yourself/ }).click();
  await page.getByRole('button', { name: 'Create and write the questions' }).click();

  await expect(page).toHaveURL(/\/teacher\/lessons\/[0-9a-f-]+/, { timeout: 20_000 });
  lessonUrl = new URL(page.url()).pathname.replace(/^\/dashboard\//, '');

  // A manual lesson never ran the pipeline, so there is no step strip to explain.
  await expect(page.getByRole('list', { name: /pipeline/i })).toBeHidden();
  await expect(page.getByRole('button', { name: '+ Add stop' })).toBeVisible();
});

test('she adds three kinds of stop from the grouped menu', async ({ page }) => {
  test.setTimeout(120_000);
  await openTheLesson(page);

  await page.getByRole('button', { name: '+ Add stop' }).click();
  // The menu is grouped exactly as the old admin panel grouped it.
  for (const group of ['Information', 'One answer', 'Several answers', 'Open answer', 'Exit']) {
    await expect(page.getByText(group, { exact: true })).toBeVisible();
  }
  await page.keyboard.press('Escape');

  // The starter stop the "Write it yourself" chooser already made, plus the three below.
  const before = await stopRows(page).count();
  await addStop(page, 'Read a page', 'Read the page');
  await addStop(page, 'Multiple choice', 'Pick the answer');
  await addStop(page, 'Exit ticket', 'Three last questions');
  await expect(stopRows(page)).toHaveCount(before + 3);
});

test('she rewrites a stop in the inline editor, and the schema holds her to the contract', async ({ page }) => {
  test.setTimeout(120_000);
  await openTheLesson(page);

  await stopRows(page).filter({ hasText: 'Pick the answer' }).click();
  const json = editor(page).getByLabel('The whole stop');
  // `toHaveValue`, not `toContainText`: a textarea's text node is its *initial* markup, and
  // this one is bound to a signal, so its text content is empty however full the field looks.
  await expect(json).toHaveValue(/"type": "choice"/);

  // A document the server would refuse: Save goes off and the error names the missing field.
  const valid = (await json.inputValue()).trim();
  const broken = JSON.parse(valid) as Record<string, unknown>;
  delete broken['question'];
  await json.fill(JSON.stringify(broken, null, 2));
  const save = page.getByRole('button', { name: 'Save the stop' });
  await expect(page.getByRole('alert')).toContainText('question', { timeout: 15_000 });
  await expect(save).toBeDisabled();

  await json.fill(valid);
  await editor(page).getByLabel('Title').fill(`Which shape ${RUN}`);
  await expect(save).toBeEnabled({ timeout: 15_000 });
  await save.click();

  await expect(stopRows(page).filter({ hasText: `Which shape ${RUN}` })).toBeVisible({ timeout: 15_000 });
});

test('she reorders the stops by dragging the handle', async ({ page }) => {
  test.setTimeout(120_000);
  await openTheLesson(page);

  const before = await stopRows(page).allInnerTexts();
  expect(before.length).toBeGreaterThanOrEqual(3);

  // The second row to the top, not the last one: `SchemaValidator` keeps the exit ticket at the
  // end, so dragging it up is a 400 the page rolls back from — a different test than this one.
  const handles = page.getByRole('button', { name: /^Reorder / });
  await dragHandle(page, handles.nth(1), stopRows(page).nth(0));

  // Polled, not slept on: the 250 ms settle is followed by the PUT and a reload, and the row
  // that matters is the one the server gave back rather than the optimistic paint.
  await expect
    .poll(async () => titleOf((await stopRows(page).allInnerTexts())[0] ?? ''), { timeout: 20_000 })
    .toBe(titleOf(before[1]!));

  const after = await stopRows(page).allInnerTexts();
  expect(after).toHaveLength(before.length);
  expect(titleOf(after[1]!)).toBe(titleOf(before[0]!));
});

test('she attaches a picture and puts it on the stop', async ({ page }) => {
  test.setTimeout(120_000);
  await openTheLesson(page);

  await stopRows(page).first().click();
  await page.getByLabel('Attach a picture').setInputFiles(SQUARE_PNG);
  await expect(page.getByText(/Picture uploaded/)).toBeVisible({ timeout: 20_000 });

  const picker = editor(page).getByLabel('Picture', { exact: false }).first();
  await picker.selectOption({ index: 1 });
  const save = page.getByRole('button', { name: 'Save the stop' });
  await expect(save).toBeEnabled({ timeout: 15_000 });
  await save.click();

  await expect(editor(page).getByLabel('The whole stop')).toHaveValue(/"imageId"/, { timeout: 15_000 });
});

test('she asks for the other levels, then edits the parent panel that comes with them', async ({ page }) => {
  test.setTimeout(240_000);
  await openTheLesson(page);

  await page.getByLabel('What this lesson is about').fill('Sorting two-dimensional shapes by their number of sides.');
  await page.getByRole('button', { name: 'Generate the levels' }).click();

  // The pipeline runs for real behind the fake provider; the page's own 2.5 s poll moves it on,
  // and the tabs for Levels 2 and 3 light up before the panel does.
  await expect(page.getByRole('tab', { name: 'Level 2' })).toBeEnabled({ timeout: 120_000 });
  const panelSave = page.getByRole('button', { name: 'Save the parent panel' });
  await expect(panelSave).toBeVisible({ timeout: 60_000 });

  // Each panel field carries its section in the label, so the first objective is addressable.
  const objective = page.getByLabel("What they'll learn 1 — English");
  await objective.fill(`Sort shapes by sides ${RUN}`);
  await expect(panelSave).toBeEnabled();
  await panelSave.click();
  await expect(page.getByText('Parent panel saved.')).toBeVisible({ timeout: 20_000 });

  // Leaving with an unsaved edit is a red band on the page, never a browser dialog.
  await objective.fill('Unsaved');
  await page.getByRole('navigation').getByRole('link', { name: 'This week' }).click();
  await expect(page.getByText('Leave without saving?')).toBeVisible();
  await page.getByRole('button', { name: 'Leave anyway' }).click();
  await expect(page).toHaveURL(/\/teacher\/week/);
});

test('screenshots: the editor in English and in Arabic', async ({ page }) => {
  test.setTimeout(120_000);
  await mkdir(SHOTS, { recursive: true });
  await openTheLesson(page);
  await stopRows(page).first().click();
  await expect(editor(page).getByLabel('The whole stop')).toBeVisible();
  await page.screenshot({ path: resolve(SHOTS, 'lesson-editor-en.png'), fullPage: true });

  // Through the account menu, the way a teacher switches — not by writing localStorage.
  await page.getByRole('button', { name: /Sara/ }).click();
  await page.getByRole('menuitem', { name: 'العربية' }).click();
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
  await page.waitForTimeout(500);
  await page.screenshot({ path: resolve(SHOTS, 'lesson-editor-ar.png'), fullPage: true });
});
