import { expect, request, test, type Locator, type Page } from '@playwright/test';
import { resolve } from 'node:path';
import {
  API,
  OMAR,
  RUN,
  SARA,
  dayFromNow,
  removeLessonsOfThisRun,
  signIn,
  signInAsSara,
  signInForToken,
} from './env';

/**
 * `docs/teacher-flow.md` §10 steps 2–4 and 8, end to end, as the teacher — and N1.3's isolation
 * on top of it. This is the file the QA job exists for: it runs against the deployed API on the
 * one-school seed, so what it proves is what a teacher will actually meet.
 *
 *   step 2  she signs in and This week is her two rows, and only hers
 *   step 3  the `+` on 1A opens New lesson knowing the class, subject and day; a PDF goes up,
 *           the step strip runs to the end, she edits a Level 2 stop, previews as a child, and
 *           publishes to 1A *and* 1B; both calendars show the copy each class was given
 *   step 8  Omar cannot read her class or her lesson, by API or by URL
 *
 * Idempotent against a shared database that is never reset: every title carries `RUN`, every
 * lesson lands on a stretch of days chosen per run, and the only things deleted are the ones
 * this file created. Nothing here writes to the seed.
 *
 *     E2E_BASE_URL=<api> E2E_ADMIN_EMAIL=… E2E_ADMIN_PASSWORD=… E2E_STAFF_PASSWORD=… pnpm e2e:qa
 *     # or, on the local H2 one-school seed, see e2e/local/README.md
 *     pnpm e2e:local teacher-flow
 */
const ONE_PAGE_PDF = resolve(process.cwd(), 'e2e/fixtures/one-page.pdf');

const TITLE = `Teacher flow ${RUN}`;
/** Far enough out that the cell is empty, and a different stretch on every run. */
const BASE = 60 + (Math.floor(Date.now() / 1000) % 60);
const LESSON_DAY = dayFromNow(BASE);

/** Filled by `beforeAll` from her own token — the two sections the seed gives her. */
let saraToken = '';
let omarToken = '';
let ownClassId = '';
let ownClassName = '';
let siblingName = '';
let siblingClassId = '';
/** The lesson step 3 builds, carried through the tests below. */
let lessonPath = '';
let lessonId = '';

interface TeacherClass {
  readonly classId: string;
  readonly className: string;
  readonly subject: string;
}

test.beforeAll(async () => {
  saraToken = await signInForToken(SARA);
  omarToken = await signInForToken(OMAR);

  const api = await request.newContext({ baseURL: API });
  const response = await api.get('/teacher/classes', {
    headers: { Authorization: `Bearer ${saraToken}` },
  });
  expect(response.ok(), `GET /teacher/classes: HTTP ${response.status()}`).toBeTruthy();
  const rows = (await response.json()) as TeacherClass[];
  await api.dispose();

  // N1.3, seed presence: exactly her two Math sections, nobody else's. An assignment leak shows
  // up here first, before any screen has had a chance to hide it.
  expect(
    rows.map((row) => `${row.className} · ${row.subject}`).sort(),
    'the seed should give Sara exactly 1A British and 1B British, both math',
  ).toEqual(['1A British · math', '1B British · math']);

  ownClassId = rows.find((row) => /1A/i.test(row.className))!.classId;
  ownClassName = rows.find((row) => /1A/i.test(row.className))!.className;
  siblingName = rows.find((row) => /1B/i.test(row.className))!.className;
  siblingClassId = rows.find((row) => /1B/i.test(row.className))!.classId;
});

/** The row for one class on This week — the grid's own `rowgroup` per assignment. */
function rowOf(page: Page, className: string): Locator {
  return page.getByRole('row').filter({ hasText: className });
}

function stopRows(page: Page): Locator {
  return page.getByRole('listbox', { name: 'Stops' }).getByRole('option');
}

/**
 * Walks a class calendar forward until the lesson's month is on screen.
 *
 * `:not(.cal__cell--outside)` matters: a month's grid also draws its neighbours' spill days, and
 * those carry the date attribute but never the lesson.
 */
async function calendarCell(page: Page, className: string, day: string): Promise<Locator> {
  await page.goto('teacher/classes');
  await page.getByRole('link', { name: `${className} · Math · British`, exact: true }).click();
  await expect(page.getByRole('grid')).toBeVisible();
  const cell = page.locator(`[data-date="${day}"]:not(.cal__cell--outside)`);
  for (let hop = 0; hop < 8 && (await cell.count()) === 0; hop += 1) {
    await page.getByRole('button', { name: 'Next month' }).click();
    await page.waitForTimeout(250);
  }
  await expect(cell, `${className}'s calendar never reached ${day}`).toHaveCount(1);
  return cell;
}

interface PipelineStep {
  readonly step: string;
  readonly status: string;
  readonly errorCode?: string;
  readonly errorMessage?: string;
}

/**
 * `review` once the whole strip is done, otherwise the status — and, when a step has failed, that
 * step, its code and what the model was told it got wrong. The string is what `expect.poll`
 * prints, so a red QA run is readable without opening a trace.
 */
async function pipelineEnd(id: string): Promise<string> {
  const api = await request.newContext({ baseURL: API });
  try {
    const response = await api.get(`/teacher/lessons/${id}`, {
      headers: { Authorization: `Bearer ${saraToken}` },
    });
    if (!response.ok()) return `HTTP ${response.status()}`;
    const lesson = (await response.json()) as { status: string; steps?: PipelineStep[] };
    const failed = (lesson.steps ?? []).find((step) => step.status === 'error');
    if (failed) return `${failed.step} ${failed.errorCode ?? 'failed'}: ${failed.errorMessage ?? ''}`;
    return lesson.status;
  } finally {
    await api.dispose();
  }
}

test.describe.configure({ mode: 'serial' });

test('step 2 — she signs in and This week is her two sections, and nothing else', async ({ page }) => {
  await signInAsSara(page);

  await expect(page).toHaveURL(/\/teacher\/week/);
  await expect(page.getByRole('heading', { level: 1, name: 'This week' })).toBeVisible();
  await expect(page.getByRole('grid', { name: 'This week' })).toBeVisible();

  await expect(rowOf(page, ownClassName)).toBeVisible();
  await expect(rowOf(page, siblingName)).toBeVisible();

  // The rail holds the two screens a teacher has, and no Admin area behind a typed URL.
  const rail = page.getByRole('navigation');
  await expect(rail.getByRole('list').getByRole('link')).toHaveCount(2);
  await page.goto('admin/classes');
  await expect(page).toHaveURL(/\/teacher\/week/);
});

test('step 3a — the + on 1A opens New lesson already knowing the class, subject and day', async ({
  page,
}) => {
  await signInAsSara(page);

  const plus = rowOf(page, ownClassName)
    .getByRole('link', { name: new RegExp(`^Add a lesson for ${ownClassName}`) })
    .first();
  const label = (await plus.getAttribute('aria-label')) ?? '';
  await plus.click();

  await expect(page).toHaveURL(/lessons\/new\?.*classId=/);
  await expect(page.getByRole('heading', { name: 'New lesson' })).toBeVisible();
  // N2.4b: the class is the picker, fixed to the one the `+` named — the Admin's
  // curriculum/grade/subject trio cannot tell 1A from 1B.
  await expect(page.getByLabel('Class')).toHaveValue(`${ownClassId}::math`);
  await expect(page.getByLabel('Class')).toBeDisabled();
  expect(new URL(page.url()).searchParams.get('date')).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  expect(label).toContain(ownClassName);
});

test('step 3b — a PDF goes up and the step strip runs to the end', async ({ page }) => {
  // The pipeline is a real model on QA, one 2.5 s poll at a time. Generous per step, and the
  // file is created on a day this run owns rather than on the `+`'s day, which a previous run
  // may already have filled (a class holds one lesson per day).
  test.setTimeout(600_000);
  await signInAsSara(page);

  const query = new URLSearchParams({ classId: ownClassId, subject: 'math', date: LESSON_DAY });
  await page.goto(`teacher/lessons/new?${query.toString()}`);
  await expect(page.getByRole('heading', { name: 'New lesson' })).toBeVisible();
  await page.getByLabel('Title').fill(TITLE);
  await page.getByRole('button', { name: /Upload a PDF/ }).click();
  await page.getByLabel('Drop a PDF here').setInputFiles(ONE_PAGE_PDF);
  const create = page.getByRole('button', { name: 'Create and read the PDF' });
  await expect(create).toBeEnabled();
  await create.click();

  await expect(page).toHaveURL(/\/teacher\/lessons\/[0-9a-f-]+/, { timeout: 60_000 });
  lessonPath = new URL(page.url()).pathname.replace(/^\/dashboard\//, '');
  lessonId = lessonPath.split('/').pop() ?? '';

  // The strip is the teacher's only window on a pipeline that takes minutes (§3).
  await expect(page.getByRole('list', { name: 'Lesson pipeline' })).toBeVisible({ timeout: 60_000 });

  // needs_review: the model asks what the skills are; the ones it found are kept by default.
  const confirmSkills = page.getByRole('button', { name: 'Make the quest' });
  await expect(confirmSkills, 'the analyze/skills steps never finished').toBeVisible({
    timeout: 240_000,
  });
  await confirmSkills.click();

  // `generating`: Level 1 lands first and the Levels card appears with it, so neither the heading
  // nor a tab is the end of the strip. The end is the server saying `review` — L1, L2, L3, Again
  // and the parent panel all written — and each of those is a model call on QA, so the wait is
  // minutes. Waited on the API rather than on the screen so that a pipeline that *fails* names
  // the step and the model's own message in the CI log, instead of leaving a tab disabled and a
  // report that says only "expected enabled, received disabled".
  await expect
    .poll(() => pipelineEnd(lessonId), { timeout: 420_000, intervals: [5_000] })
    .toBe('review');

  await expect(page.getByRole('heading', { name: 'Levels' })).toBeVisible({ timeout: 60_000 });
  await expect(page.getByRole('tab', { name: 'Level 2' })).toBeEnabled({ timeout: 60_000 });
});

test('step 3c — she edits a Level 2 stop and the list keeps the new title', async ({ page }) => {
  test.setTimeout(180_000);
  await signInAsSara(page);
  await page.goto(lessonPath);
  await expect(page.getByRole('heading', { name: 'Levels' })).toBeVisible({ timeout: 60_000 });

  const levelTwo = page.getByRole('tab', { name: 'Level 2' });
  await expect(levelTwo).toBeEnabled({ timeout: 60_000 });
  await levelTwo.click();
  const stop = stopRows(page).first();
  await expect(stop).toBeVisible({ timeout: 30_000 });
  await stop.click();

  const editor = page.locator('hq-stop-editor');
  const title = editor.getByLabel('Title');
  await expect(title).toBeVisible({ timeout: 30_000 });
  const rewritten = `Level 2 stop ${RUN}`;
  await title.fill(rewritten);
  const save = page.getByRole('button', { name: 'Save the stop' });
  await expect(save).toBeEnabled({ timeout: 15_000 });
  await save.click();

  await expect(stopRows(page).filter({ hasText: rewritten })).toBeVisible({ timeout: 30_000 });
  // The pinned phone follows the selection, so the edit is visible where a child would see it.
  await expect(page.locator('.phone__caption')).toHaveText(rewritten);
});

test('step 3d — Preview as child opens the player, which says the player is coming', async ({ page }) => {
  await signInAsSara(page);
  await page.goto(lessonPath);

  const preview = page.getByRole('link', { name: 'Preview as child' });
  await expect(preview).toBeVisible({ timeout: 60_000 });
  await preview.click();

  // N3 builds the player; until then the route is an honest placeholder, not a 404 (§8).
  await expect(page).toHaveURL(new RegExp(`/player/gallery\\?lesson=${lessonId}`));
  await expect(page.getByRole('heading', { name: 'Preview as child' })).toBeVisible();
  await page.getByRole('button', { name: 'Back to the lesson' }).click();
  await expect(page).toHaveURL(new RegExp(lessonId));
});

test('step 3e — she publishes to 1A and 1B, and each class gets its own copy', async ({ page }) => {
  test.setTimeout(180_000);
  await signInAsSara(page);
  await page.goto(lessonPath);
  await expect(page.getByRole('heading', { name: 'Levels' })).toBeVisible({ timeout: 60_000 });

  await page.getByRole('button', { name: 'Publish', exact: true }).click();
  const sheet = page.getByRole('dialog');
  await expect(sheet).toBeVisible();
  // Her own class is the sheet's title, never a checkbox — the sibling is the choice.
  await expect(sheet.getByRole('heading', { name: new RegExp(`^Publish to ${ownClassName}`) })).toBeVisible();
  // `hq-checkbox` hides the real input behind the drawn box, so the label is the hit target.
  await sheet.locator('label.check', { hasText: siblingName }).click();
  await expect(sheet.getByRole('checkbox')).toBeChecked();
  await sheet.getByRole('button', { name: 'Publish', exact: true }).click();

  await expect(page.getByText('Published. Each class has its own copy:')).toBeVisible({ timeout: 60_000 });
  const own = page.getByRole('link', { name: ownClassName, exact: true });
  const sibling = page.getByRole('link', { name: siblingName, exact: true });
  await expect(own).toBeVisible();
  await expect(sibling).toBeVisible();
  expect(await sibling.getAttribute('href')).not.toBe(await own.getAttribute('href'));
  await expect(page.getByRole('button', { name: 'Unpublish' })).toBeVisible();
});

test('step 3f — both class calendars show the lesson as published', async ({ page }) => {
  test.setTimeout(180_000);
  await signInAsSara(page);

  for (const className of [ownClassName, siblingName]) {
    const cell = await calendarCell(page, className, LESSON_DAY);
    await expect(cell).toContainText(TITLE);
    await expect(cell).toContainText('Published');
  }
});

/**
 * N1.3 / §10 step 8. Omar teaches 3A and 3B; Sara's 1A and the lesson above are not his, by API
 * or by URL. Run on its own token through `request` rather than through a page, because what is
 * being proved is the server's answer — a dashboard that merely did not draw a link would pass a
 * screen-only test and still leak the row to anything that asked.
 */
test('step 8 — another teacher is refused her class and her lesson, by API and by URL', async ({
  page,
}) => {
  const auth = (token: string) => ({ Authorization: `Bearer ${token}` });
  const api = await request.newContext({ baseURL: API });

  // Hers, to prove the route works at all before its refusal means anything.
  const mine = await api.get(`/teacher/classes/${encodeURIComponent(ownClassId)}/students`, {
    headers: auth(saraToken),
  });
  expect(mine.status(), 'Sara should be able to read her own roster').toBe(200);

  for (const path of [
    `/teacher/classes/${encodeURIComponent(ownClassId)}/students`,
    `/teacher/classes/${encodeURIComponent(ownClassId)}/calendar?month=${LESSON_DAY.slice(0, 7)}`,
    `/teacher/lessons/${lessonId}`,
  ]) {
    const refused = await api.get(path, { headers: auth(omarToken) });
    expect([403, 404], `${path} answered Omar ${refused.status()}`).toContain(refused.status());
  }

  // And her own list is only ever her own sections — by `classId`, which is the field the row
  // actually carries (`className` is null there; it is the calendar that names a class).
  const lessons = await api.get('/teacher/lessons', { headers: auth(saraToken) });
  const rows = (await lessons.json()) as { classId?: string }[];
  expect(rows.length, 'her list should at least hold the lesson this run published').toBeGreaterThan(0);
  const foreign = rows.filter((row) => row.classId !== ownClassId && row.classId !== siblingClassId);
  expect(
    foreign.map((row) => row.classId),
    'GET /teacher/lessons leaked a class Sara does not teach',
  ).toEqual([]);
  await api.dispose();

  // The dashboard agrees: Omar typing her class URL is put back on his own screen.
  await signIn(page, OMAR);
  await page.goto(`teacher/classes/${encodeURIComponent(ownClassId)}`);
  await expect(page.getByRole('heading', { level: 1, name: `${ownClassName} · Math` })).toBeHidden();
  await expect(page).toHaveURL(/\/teacher\/(week|classes)(\?|$|\/)/, { timeout: 30_000 });
});

/** What this run created, taken back off the shared database (`env.ts` says how, and why not as Sara). */
test.afterAll(removeLessonsOfThisRun);
