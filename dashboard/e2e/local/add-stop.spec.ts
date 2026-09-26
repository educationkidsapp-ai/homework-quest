import { request, type Locator, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import {
  API,
  expect,
  RUN,
  SARA,
  schoolDayFromNow,
  setLanguage,
  setScheme,
  shoot,
  signInAsSara,
  signInForToken,
  test,
} from './env';

/**
 * CR2's acceptance: "Add stop" is one simple form. E4a's: it is also fast.
 *
 * Sara opens it, presses Save on an empty form and is told what is missing *under the fields*,
 * fills the three required ones, saves, and the question she wrote is the stop that is now
 * selected in the editor — in her own words, read back by CR5's prose view. The twenty-two
 * -template menu is gone, and nothing in this file can reach it.
 *
 * E4a adds the three things the owner asked for: the sheet is already open when a hand-written
 * lesson opens, five questions go in one after another without anybody waiting on the model, and a
 * question the assistant refuses stays on its row with Retry beside it.
 *
 * The server runs on H2 with `SEED_SCHOOL=true` and `LLM_PROVIDER=fake` (see `README.md`): the
 * fake turns the text back into the stop it was given, keeping the first line as the title and
 * the next as what Pip says, which is what makes a Save assertable without a model.
 */
const SHOTS = resolve(process.cwd(), '../docs/screenshots/cr2');
const TITLE = `Shapes and sides ${RUN}`;
const DATE = schoolDayFromNow(360 + (Math.floor(Date.now() / 1000) % 60));

let classId = '';
let lessonUrl = '';

test.describe.configure({ mode: 'serial' });

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

function form(page: Page): Locator {
  return page.locator('[data-hq-add-stop]');
}

function stopRows(page: Page): Locator {
  return page.getByRole('listbox', { name: 'Stops' }).getByRole('option');
}

function proseField(page: Page): Locator {
  return page.locator('hq-stop-editor').getByLabel('This stop, in your words');
}

async function openTheLesson(page: Page): Promise<void> {
  await signInAsSara(page);
  await page.goto(lessonUrl);
  await expect(page.getByRole('button', { name: '+ Add stop' })).toBeVisible({ timeout: 20_000 });
}

/**
 * By attribute, not by name: the screenshot pass opens this form in Arabic too, and the
 * button's accessible name is translated — `setScheme` and `setLanguage` are located the same
 * way, for the same reason.
 */
async function openTheForm(page: Page): Promise<Locator> {
  await page.locator('[data-hq-add-stop-trigger] button').click();
  const fields = form(page);
  await expect(fields).toBeVisible();
  return fields;
}

test('Sara writes a lesson by hand to add questions to', async ({ page }) => {
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
  await page.getByLabel('Title').fill(TITLE);
  await page.getByRole('button', { name: /Write it yourself/ }).click();
  await page.getByRole('button', { name: 'Create and write the questions' }).click();

  await expect(page).toHaveURL(/\/teacher\/lessons\/[0-9a-f-]+/, { timeout: 20_000 });
  lessonUrl = new URL(page.url()).pathname.replace(/^\/dashboard\//, '');
  await expect(page.getByRole('button', { name: '+ Add stop' })).toBeVisible();

  // E4a: the lesson opens on the sheet. The next thing she is going to do is write a question,
  // and the empty Level 1 behind it says nothing else.
  await expect(form(page)).toBeVisible();
  await expect(page.getByRole('dialog', { name: 'Add a question' })).toBeVisible();
  // Three plain steps where a hand-written lesson's empty pipeline strip used to be.
  await expect(page.getByText('3. Questions')).toBeVisible();

  await page.keyboard.press('Escape');
  await expect(form(page)).toBeHidden();
});

test('the form is one card, one column, five fields — and no template menu', async ({ page }) => {
  test.setTimeout(120_000);
  await openTheLesson(page);
  const fields = await openTheForm(page);

  // The dialog is the platform's: a real `role="dialog"`, with the focus trap that comes with it.
  await expect(page.getByRole('dialog', { name: 'Add a question' })).toBeVisible();
  await expect(page.getByRole('menuitem')).toHaveCount(0);

  await expect(fields.getByLabel('Title', { exact: true })).toBeVisible();
  await expect(fields.getByLabel('Question / what the child does', { exact: true })).toBeVisible();
  await expect(fields.getByLabel('Type', { exact: true })).toBeVisible();
  await expect(fields.getByRole('combobox', { name: /^Picture/ })).toBeVisible();
  await expect(fields.getByText('Parent tip', { exact: true })).toBeVisible();
  // No wizard, no tabs, nothing folded away — inside the form; the lesson page's own level
  // tabs are behind it and are not this screen's.
  await expect(page.getByRole('dialog', { name: 'Add a question' }).getByRole('tablist')).toHaveCount(0);
  await expect(fields.locator('details')).toHaveCount(0);
  // One primary action.
  await expect(page.getByRole('button', { name: 'Save the question' })).toBeVisible();

  await page.getByRole('button', { name: 'Cancel' }).click();
  await expect(fields).toBeHidden();
});

test('an empty Save is refused under the fields, not in a toast', async ({ page }) => {
  test.setTimeout(120_000);
  await openTheLesson(page);
  const fields = await openTheForm(page);

  await page.getByRole('button', { name: 'Save the question' }).click();

  await expect(fields.getByText('Give this question a title.')).toBeVisible();
  await expect(fields.getByText('Write what the child should do.')).toBeVisible();
  await expect(fields.getByText('Choose the kind of question this is.')).toBeVisible();
  await expect(page.getByText('Question added')).toHaveCount(0);

  // Dirty now (the Save filled nothing, but the errors are up): Escape asks before it throws
  // the words away.
  await fields.getByLabel('Title', { exact: true }).fill('Half a thought');
  await page.keyboard.press('Escape');
  await expect(page.getByText('Throw this question away?')).toBeVisible();
  await page.getByRole('button', { name: 'Throw it away' }).click();
  await expect(fields).toBeHidden();
});

/**
 * E4b: written out in fields, a choice question is saved by the browser in one request.
 *
 * The assertion the owner asked for is the negative one: `/from-text` is never called, so no model
 * runs, nothing says "the assistant is writing", and the stop is complete in the list and playable
 * in the phone the moment the POST answers.
 */
test('a choice question written out saves in one request, and asks the model nothing', async ({
  page,
}) => {
  test.setTimeout(120_000);
  await openTheLesson(page);
  const before = await stopRows(page).count();

  let creates = 0;
  let models = 0;
  page.on('request', (request) => {
    if (request.method() === 'POST' && /\/plays\/[^/]+\/stops$/.test(request.url())) creates++;
    if (/\/from-text$/.test(request.url())) models++;
  });

  const fields = await openTheForm(page);
  const title = `Sides of a shape ${RUN}`;
  const question = `Which shape has three sides ${RUN}?`;
  await fields.getByLabel('Title', { exact: true }).fill(title);
  await fields.getByLabel('Type', { exact: true }).selectOption('choice');
  // The paragraph for the assistant is replaced by the question's own fields.
  await expect(fields.getByLabel('Question / what the child does', { exact: true })).toHaveCount(0);
  await fields.getByLabel('Question', { exact: true }).fill(question);
  for (const [index, label] of ['Triangle', 'Circle', 'Square', 'Hexagon'].entries()) {
    await fields.getByLabel(`Answer ${index + 1}`, { exact: true }).fill(label);
  }
  await fields.getByLabel('Which answer is right', { exact: true }).selectOption('0');

  const posted = page.waitForRequest(
    (request) => request.method() === 'POST' && /\/plays\/[^/]+\/stops$/.test(request.url()),
  );
  await page.getByRole('button', { name: 'Save the question' }).click();
  await posted;

  await expect(fields).toBeHidden();
  await expect(page.getByText('Question added')).toBeVisible();
  await expect(stopRows(page)).toHaveCount(before + 1);
  const added = stopRows(page).filter({ hasText: title }).first();
  await expect(added).toHaveAttribute('aria-selected', 'true');
  // It plays: the question and its four tiles are in the phone, not a template's placeholders.
  const phone = page.locator('hq-phone-preview');
  await expect(phone.getByText(question)).toBeVisible();
  await expect(phone.getByText('Hexagon')).toBeVisible();
  await expect(page.getByText('The assistant is writing…')).toHaveCount(0);

  expect(creates).toBe(1);
  expect(models).toBe(0);
});

test('a saved question becomes the selected stop, in her own words', async ({ page }) => {
  test.setTimeout(180_000);
  await openTheLesson(page);
  const before = await stopRows(page).count();
  const fields = await openTheForm(page);

  const title = `Which shape has three sides ${RUN}`;
  await fields.getByLabel('Title', { exact: true }).fill(title);
  await fields.getByLabel('Type', { exact: true }).selectOption('choice');
  // E4b: `choice` opens on its fields, and this is the teacher who would rather describe it.
  await page.getByRole('button', { name: 'Let the assistant write it from my words' }).click();
  await fields
    .getByLabel('Question / what the child does', { exact: true })
    .fill('Pip says: show a triangle, a circle and a square, and ask which one has three sides.');
  await page.getByRole('button', { name: 'Save the question' }).click();

  // The sheet is shut and the toast is up before the model has been asked anything — E4a's whole
  // point. The row is already in the list, saying who is working on it.
  await expect(fields).toBeHidden();
  await expect(page.getByText('Question added')).toBeVisible();
  await expect(stopRows(page)).toHaveCount(before + 1);

  const added = stopRows(page).filter({ hasText: title }).first();
  await expect(added).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByText('The assistant is writing…')).toHaveCount(0, { timeout: 120_000 });
  // CR5's prose view, carrying what she wrote rather than a JSON document.
  await expect(proseField(page)).toHaveValue(new RegExp(`^${title}`), { timeout: 20_000 });
  await expect(proseField(page)).toHaveValue(/three sides/);
});

test('five questions in a row, without waiting on the sheet once', async ({ page }) => {
  test.setTimeout(240_000);
  await openTheLesson(page);
  const before = await stopRows(page).count();
  const fields = await openTheForm(page);

  // "Save and add another" keeps the sheet, keeps the type, empties her words and puts the caret
  // back in Title — so this loop is exactly what a teacher's hands do.
  for (let i = 1; i <= 5; i++) {
    await fields.getByLabel('Title', { exact: true }).fill(`Count to ${i} ${RUN}`);
    await fields
      .getByLabel('Question / what the child does', { exact: true })
      .fill(`Ask the child to count ${i} carrots out loud.`);
    if (i === 1) await fields.getByLabel('Type', { exact: true }).selectOption('count');
    await page.getByRole('button', { name: 'Save and add another' }).click();
    // Nothing is awaited but the form emptying itself: no modal to wait on, no model either.
    await expect(fields.getByLabel('Title', { exact: true })).toHaveValue('');
    await expect(fields.getByLabel('Type', { exact: true })).toHaveValue('count');
  }

  await page.keyboard.press('Escape');
  await expect(fields).toBeHidden();
  await expect(stopRows(page)).toHaveCount(before + 5, { timeout: 30_000 });
  // They finish in the background, in their own time.
  await expect(page.getByText('The assistant is writing…')).toHaveCount(0, { timeout: 180_000 });
  await expect(stopRows(page).filter({ hasText: `Count to 5 ${RUN}` })).toHaveCount(1);
});

/**
 * A refusal keeps the question and offers Retry.
 *
 * `SampleLlmClient` has no "fail this one" hook — `quest.pipeline.fail-once-at` is a *pipeline*
 * step hook and `from-text` is not a pipeline step — so the refusal is injected in the browser:
 * the first `from-text` of this test is answered with the server's own 422 body, and the retry is
 * let through to the real endpoint, which the fake then answers.
 */
test('a question the assistant refuses stays on its row, and Retry sends the same words', async ({
  page,
}) => {
  test.setTimeout(180_000);
  await openTheLesson(page);

  let refused = 0;
  await page.route('**/stops/*/from-text', async (route) => {
    if (refused++ === 0) {
      await route.fulfill({
        status: 422,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'rephrase', message: "Couldn't save, please rephrase." }),
      });
      return;
    }
    await route.continue();
  });

  const title = `Draw the shape ${RUN}`;
  const fields = await openTheForm(page);
  await fields.getByLabel('Title', { exact: true }).fill(title);
  await fields.getByLabel('Type', { exact: true }).selectOption('choice');
  await page.getByRole('button', { name: 'Let the assistant write it from my words' }).click();
  await fields
    .getByLabel('Question / what the child does', { exact: true })
    .fill('Pip says: draw a triangle and say how many sides it has.');
  await page.getByRole('button', { name: 'Save the question' }).click();
  await expect(fields).toBeHidden();

  // The stop is hers and it is still here — the old form deleted it behind a closing dialog.
  const row = stopRows(page).filter({ hasText: title }).first();
  await expect(row).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText("Couldn't save, please rephrase.").first()).toBeVisible();

  await page.getByRole('button', { name: 'Retry' }).click();
  await expect(page.getByText("Couldn't save, please rephrase.")).toHaveCount(0, { timeout: 120_000 });
  await expect(page.getByText('The assistant is writing…')).toHaveCount(0, { timeout: 120_000 });
  await expect(stopRows(page).filter({ hasText: title })).toHaveCount(1);
});

/**
 * One frame, with the form shut again afterwards.
 *
 * The language and scheme controls live in the header, and a modal `<dialog>` makes the rest of
 * the document inert — so every frame sets the page up first and opens the form last.
 */
async function frame(
  page: Page,
  name: string,
  set: { readonly lang: 'en' | 'ar'; readonly scheme: 'light' | 'dark'; readonly width: number },
): Promise<void> {
  await page.setViewportSize({ width: set.width, height: set.width < 768 ? 812 : 768 });
  await setLanguage(page, set.lang);
  await setScheme(page, set.scheme);
  const fields = await openTheForm(page);
  await shoot(page, resolve(SHOTS, name), fields);
  await page.keyboard.press('Escape');
  await expect(fields).toBeHidden();
}

test('screenshots: the form in English and Arabic, at 1366 and at 375', async ({ page }) => {
  test.setTimeout(300_000);
  await mkdir(SHOTS, { recursive: true });
  await openTheLesson(page);

  await frame(page, 'add-stop-en-1366.png', { lang: 'en', scheme: 'light', width: 1366 });
  await frame(page, 'add-stop-en-1366-dark.png', { lang: 'en', scheme: 'dark', width: 1366 });
  await frame(page, 'add-stop-ar-1366.png', { lang: 'ar', scheme: 'light', width: 1366 });
  // Under 768 px the same dialog is a full-screen sheet.
  await frame(page, 'add-stop-en-375.png', { lang: 'en', scheme: 'light', width: 375 });
  await frame(page, 'add-stop-ar-375.png', { lang: 'ar', scheme: 'light', width: 375 });

  await page.setViewportSize({ width: 1366, height: 768 });
  await setLanguage(page, 'en');
  await setScheme(page, 'light');
});
