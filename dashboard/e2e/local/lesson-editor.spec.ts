import { request, type Locator, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import {
  ADMIN,
  API,
  expect,
  removeLessonsOfThisRun,
  RUN,
  SARA,
  schoolDayFromNow,
  setLanguage,
  setScheme,
  shoot,
  signIn,
  signInAsSara,
  signInForToken,
  test,
} from './env';

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
const SHOTS = resolve(process.cwd(), '../docs/screenshots/dashboard-n2.4');
/** CR5's own set: the editor as a teacher sees it, and as an Admin in debug view does. */
const CR5_SHOTS = resolve(process.cwd(), '../docs/screenshots/cr5');
const SQUARE_PNG = resolve(process.cwd(), 'e2e/fixtures/square.png');
const TITLE = `Sorting shapes ${RUN}`;

/**
 * A day far out, and a *different* one on every run: a class holds one lesson per day, and QA's
 * database is shared and never reset, so a fixed day would be taken by the run before. The
 * lesson is created from the `lessons/new` route with the class in the query — the same link
 * "Add today's lesson" builds — rather than from a `+` on the calendar, because whether a `+`
 * is there at all depends on what the week already holds, and this file should not care.
 */
const DATE = schoolDayFromNow(300 + (Math.floor(Date.now() / 1000) % 60));
let classId = '';

/** Sara's own 1A British Math section, read with her token — never the Admin's. */
test.beforeAll(async () => {
  const token = await signInForToken(SARA);
  const api = await request.newContext({ baseURL: API });
  const classes = await api.get('/teacher/classes', { headers: { Authorization: `Bearer ${token}` } });
  const rows = (await classes.json()) as { classId: string; className: string; subject: string }[];
  const mine = rows.find((row) => /1A British/i.test(row.className) && row.subject === 'math');
  expect(
    mine,
    `no 1A British Math among ${rows.map((r) => `${r.className}/${r.subject}`).join(', ')}`,
  ).toBeTruthy();
  classId = mine!.classId;
  await api.dispose();
});

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

/** CR2's form, which replaced the twenty-two-template menu. */
export function addStopForm(page: Page): Locator {
  return page.locator('[data-hq-add-stop]');
}

/**
 * Adds one stop through the form and waits for the assistant to finish writing it.
 *
 * E4a: the sheet closes on the click and the row appears at once, so the count grows immediately
 * and the *content* lands a moment later — this helper waits for both, because the tests below
 * read the prose of the stops it made.
 *
 * The labels are scoped to the form because the stop editor behind the dialog has a "Title" of
 * its own; `type` is the stop type's value, which is what the `<optgroup>`ed select carries.
 */
async function addStop(page: Page, type: string, title: string, question: string): Promise<void> {
  const before = await stopRows(page).count();
  await page.getByRole('button', { name: '+ Add stop' }).click();
  const form = addStopForm(page);
  await expect(form).toBeVisible();
  await form.getByLabel('Title', { exact: true }).fill(title);
  await form.getByLabel('Type', { exact: true }).selectOption(type);
  // E4b: these tests are about the prose the assistant writes, so the five types that would
  // otherwise ask for their fields are switched back to her words.
  const toWords = form.getByRole('button', { name: 'Let the assistant write it from my words' });
  if ((await toWords.count()) > 0) await toWords.click();
  await form.getByLabel('Question / what the child does', { exact: true }).fill(question);
  await page.getByRole('button', { name: 'Save the question' }).click();
  await expect(stopRows(page)).toHaveCount(before + 1, { timeout: 30_000 });
  await expect(stopRows(page).filter({ hasText: title }).first()).toBeVisible();
  await expect(page.getByText('The assistant is writing…')).toHaveCount(0, { timeout: 120_000 });
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
  // The one sleep left here, and deliberately so: `scrollIntoView` is smooth, and CDK reads the
  // handle's bounding box at mousedown. There is no DOM signal for "the scroll has stopped", and
  // a box read mid-scroll starts the drag from the wrong place.
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

  const query = new URLSearchParams({
    classId,
    curriculum: 'british',
    grade: '1',
    subject: 'math',
    date: DATE,
  });
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

test('she adds three kinds of stop from the one form', async ({ page }) => {
  test.setTimeout(180_000);
  await openTheLesson(page);

  // CR2: the twenty-two-template menu is gone; the five groups are the type select's headings.
  await page.getByRole('button', { name: '+ Add stop' }).click();
  await expect(page.getByRole('menuitem')).toHaveCount(0);
  const types = addStopForm(page).getByLabel('Type', { exact: true });
  for (const group of ['Information', 'One answer', 'Several answers', 'Open answer', 'Exit']) {
    await expect(types.locator(`optgroup[label="${group}"]`)).toHaveCount(1);
  }
  await page.getByRole('button', { name: 'Cancel' }).click();

  // The starter stop the "Write it yourself" chooser already made, plus the three below.
  const before = await stopRows(page).count();
  await addStop(page, 'readPage', 'Read the page', 'Read page one of the book together.');
  await addStop(
    page,
    'choice',
    'Pick the answer',
    'Ask which shape has three sides.\n\nOptions:\n- a triangle (correct)\n- a circle',
  );
  await addStop(page, 'exitTicket', 'Three last questions', 'Three quick questions about shapes.');
  await expect(stopRows(page)).toHaveCount(before + 3);
});

/** CR5: the editor's own surface. The label is the teacher's, not a data format's. */
function proseField(page: Page): Locator {
  return editor(page).getByLabel('This stop, in your words');
}

/**
 * CR5's acceptance: **no element on this page reads as JSON**.
 *
 * Asserted on the text of every element that has no element children, so a `{` nested three
 * divs deep still counts, and the Raw JSON panel is checked by name as well — it is an Admin's,
 * behind debug view, and a teacher must not be able to reach it at all.
 */
async function expectNoJsonOnThePage(page: Page): Promise<void> {
  await expect(page.locator('[data-hq-raw-json]')).toHaveCount(0);
  await expect(page.getByLabel('The whole stop')).toHaveCount(0);
  const jsonish = await page.evaluate(() =>
    [...document.querySelectorAll('body *')]
      .filter((el) => el.children.length === 0)
      .map((el) => (el.textContent ?? '').trim())
      .filter((text) => /^[{[]/.test(text) || /"[A-Za-z]+"\s*:/.test(text)),
  );
  expect(jsonish, `these elements read as JSON: ${jsonish.join(' | ')}`).toEqual([]);
}

test('she rewrites a stop in her own words, and no JSON is anywhere on the page', async ({ page }) => {
  test.setTimeout(120_000);
  await openTheLesson(page);

  await stopRows(page).filter({ hasText: 'Pick the answer' }).click();

  // Her own words, rendered as a page rather than a document. Since CR2 every stop in a
  // hand-written lesson is saved *from* text, so `stops.text` is set and there is nothing left
  // for `StopText.describe` to render here — that path is what a *generated* lesson's stops
  // still take, and it is the same prose view either way.
  const prose = proseField(page);
  await expect(prose).toHaveValue(/Ask which shape has three sides/);
  await expect(editor(page).locator('[data-hq-stop-prose] li').first()).toBeVisible();
  await expectNoJsonOnThePage(page);

  // The fake provider answers Prompt D with the stop as stored, taking `title` and `speak` from
  // the first two lines — so what comes back names itself, and the stop list is the proof.
  const title = `Which shape ${RUN}`;
  await prose.fill(
    `${title}\nPick the shape with three sides.\n\nOptions:\n- a triangle (correct)\n- a circle`,
  );
  const save = page.getByRole('button', { name: 'Save the stop' });
  await expect(save).toBeEnabled();
  await save.click();

  await expect(stopRows(page).filter({ hasText: title })).toBeVisible({ timeout: 30_000 });

  // Re-opened, the prose is the prose she saved — the server stored both halves, so nothing
  // was re-described and nothing churned.
  await page.reload();
  await expect(page.getByRole('button', { name: '+ Add stop' })).toBeVisible({ timeout: 15_000 });
  await stopRows(page).filter({ hasText: title }).click();
  await expect(proseField(page)).toHaveValue(new RegExp(`^${title}`));
  await expectNoJsonOnThePage(page);
});

test('an Admin in debug view gets the Raw JSON panel, and a teacher never does', async ({ page }) => {
  test.setTimeout(120_000);
  await signIn(page, ADMIN);
  await page.goto(lessonUrl.replace('teacher/', 'admin/'));
  await expect(page.getByRole('button', { name: '+ Add stop' })).toBeVisible({ timeout: 20_000 });
  await stopRows(page).first().click();

  // Shut by default, even for her: teacher view is what everybody opens on.
  await expect(page.locator('[data-hq-raw-json]')).toHaveCount(0);

  await page.locator('[data-hq-tour="profile"]').click();
  await page.getByRole('menuitem', { name: 'Show the raw JSON' }).click();

  const panel = page.locator('[data-hq-raw-json]');
  await expect(panel).toBeVisible();
  await panel.locator('summary').click();
  await expect(editor(page).getByLabel('The whole stop')).toHaveValue(/"type":/);
  // The read side-channel is not part of the document the server validates.
  await expect(editor(page).getByLabel('The whole stop')).not.toHaveValue(/teacherText/);
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

  // CR5: the confirmation a teacher gets is the description, re-rendered from the new JSON —
  // `StopText.describe` names an attached picture on its own line. There is no JSON to read.
  await expect(proseField(page)).toHaveValue(/Picture:/, { timeout: 15_000 });

  // And the crop is actually drawn. `/media/pages/{id}` wants a bearer, which an `<img src>`
  // cannot send, so every picture in this editor used to answer 401 and draw nothing
  // (`docs/reports/tailadmin-restyle.md` §4.1). `hqPageImage` fetches the bytes by id through
  // the generated client the interceptor decorates and paints them; `data-hq-media` is what it
  // says it is showing, and `naturalWidth` is the browser saying it decoded real pixels.
  const crop = page.locator('hq-phone-preview img[data-hq-media]').first();
  await expect(crop).toHaveAttribute('data-hq-media', 'ready', { timeout: 20_000 });
  expect(await crop.evaluate((img) => (img as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);
});

test('she asks for the other levels, then edits the parent panel that comes with them', async ({ page }) => {
  // Local only. This is the parent-panel editor's test; the generation in front of it is just
  // how a panel comes to exist. Under the local `fake` provider that costs seconds — against QA
  // it is the real model writing three levels, an Again variant and a panel, which took longer
  // than this whole file's budget and put the post-deploy job back near its 20-minute cap. The
  // pipeline itself is proved on QA by `teacher-flow.spec.ts`, which is written to wait for it.
  test.skip(
    !!process.env['E2E_BASE_URL'],
    'the real model writes the levels too slowly to belong in the QA budget — teacher-flow.spec.ts covers the pipeline there',
  );
  test.setTimeout(240_000);
  await openTheLesson(page);

  await page
    .getByLabel('What this lesson is about')
    .fill('Sorting two-dimensional shapes by their number of sides.');
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

test('screenshots: the editor in English and in Arabic, light and dark', async ({ page }) => {
  test.setTimeout(180_000);
  await mkdir(SHOTS, { recursive: true });
  await mkdir(CR5_SHOTS, { recursive: true });
  await openTheLesson(page);
  await stopRows(page).first().click();
  await expect(proseField(page)).toBeVisible();
  // The element, not its English label: the same barrier has to hold for the Arabic frame below.
  await shoot(page, resolve(SHOTS, 'lesson-editor-en.png'), editor(page), { fullPage: true });
  await shoot(page, resolve(CR5_SHOTS, 'stop-editor-teacher-en.png'), editor(page), { fullPage: true });

  // Dark is the screen most worth photographing here: the editor is the one place a phone
  // preview sits inside a dashboard card, and the two schemes meet on the same page.
  await setScheme(page, 'dark');
  await shoot(page, resolve(SHOTS, 'lesson-editor-en-dark.png'), editor(page), { fullPage: true });
  await shoot(page, resolve(CR5_SHOTS, 'stop-editor-teacher-en-dark.png'), editor(page), { fullPage: true });
  await setScheme(page, 'light');

  // Through the header's language control, the way a teacher switches — not by writing
  // localStorage. T2 moved it out of the account menu.
  await setLanguage(page, 'ar');
  await shoot(page, resolve(SHOTS, 'lesson-editor-ar.png'), editor(page), { fullPage: true });
  await shoot(page, resolve(CR5_SHOTS, 'stop-editor-teacher-ar.png'), editor(page), { fullPage: true });

  await setScheme(page, 'dark');
  await shoot(page, resolve(SHOTS, 'lesson-editor-ar-dark.png'), editor(page), { fullPage: true });
  await setScheme(page, 'light');
});

test('screenshots: the same editor as an Admin in debug view', async ({ page }) => {
  test.setTimeout(180_000);
  await mkdir(CR5_SHOTS, { recursive: true });
  await signIn(page, ADMIN);
  await page.goto(lessonUrl.replace('teacher/', 'admin/'));
  await expect(page.getByRole('button', { name: '+ Add stop' })).toBeVisible({ timeout: 20_000 });
  await stopRows(page).first().click();

  await page.locator('[data-hq-tour="profile"]').click();
  await page.getByRole('menuitem', { name: 'Show the raw JSON' }).click();
  await page.locator('[data-hq-raw-json] summary').click();
  await expect(editor(page).getByLabel('The whole stop')).toBeVisible();
  await shoot(page, resolve(CR5_SHOTS, 'stop-editor-admin-debug-en.png'), editor(page), { fullPage: true });

  await setLanguage(page, 'ar');
  await shoot(page, resolve(CR5_SHOTS, 'stop-editor-admin-debug-ar.png'), editor(page), { fullPage: true });
});

/** The lessons this file wrote, off the shared database again (`env.ts`). */
test.afterAll(removeLessonsOfThisRun);
