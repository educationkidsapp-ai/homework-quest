import { request, type Locator, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import {
  API,
  dayFromNow,
  expect,
  removeLessonsOfThisRun,
  RUN,
  SARA,
  setScheme,
  shoot,
  signInAsSara,
  signInForToken,
  test,
} from './env';

/**
 * N2.4b's acceptance (`docs/teacher-flow.md` Step 5, Step 8 and §10 steps 3–4).
 *
 * Sara creates a lesson on 1A, publishes it to 1A **and** 1B from the sheet, sees both classes'
 * calendars turn to Published, unpublishes with the 10 s Undo, deletes a draft, and uploads the
 * same file twice to earn the "Analyzed before · 0 tokens" badge.
 *
 * Everything runs on her own token, so an operation that fell back to `/admin/**` would fail
 * here rather than pass: `POST /teacher/lessons`, `POST …/publish` with `classIds`,
 * `PATCH …/{id}` and `DELETE …/{id}` are all teacher routes as of N2.4b-api.
 *
 * Needs the API on H2 with the **one-school seed** — Sara's 1A British and 1B British are the
 * sibling pair the sheet exists for:
 *
 *     SPRING_PROFILES_ACTIVE=h2 SEED_SCHOOL=true SEED_STAFF_PASSWORD="$E2E_STAFF_PASSWORD" \
 *       ADMIN_EMAIL=… ADMIN_PASSWORD=… LLM_PROVIDER=fake PORT=18080 java -jar server/target/server.jar
 *     cd dashboard && pnpm build --configuration=production && pnpm e2e:local lesson-publish
 *
 * It creates lessons on days well ahead, on a stretch that shifts per run, so it is safe to run
 * against one H2 database.
 */
const SHOTS = resolve(process.cwd(), '../docs/screenshots/dashboard-n2.4b');
const ONE_PAGE_PDF = resolve(process.cwd(), 'e2e/fixtures/one-page.pdf');

const TITLE = `Counting on ${RUN}`;
const DRAFT_TITLE = `Scratch ${RUN}`;

/**
 * Days well ahead, and a different stretch of them on every run: a class holds one lesson per
 * day, so a second run against the same H2 database must not land on the first run's cells. Far
 * enough out to be empty, near enough that the calendar test is a few "Next month" hops.
 */
const BASE = 150 + (Math.floor(Date.now() / 1000) % 60);
const PUBLISH_DAY = dayFromNow(BASE);
const MOVED_DAY = dayFromNow(BASE + 1);
const DRAFT_DAY = dayFromNow(BASE + 2);

let classId = '';
let siblingName = '';
let lessonUrl = '';
/** Sara's own token, for the one lesson this file has to create outside the screens (below). */
let saraToken = '';

/** Her two sections, read with her own token — the pair the publish sheet is about. */
test.beforeAll(async () => {
  const token = await signInForToken(SARA);
  const api = await request.newContext({ baseURL: API });
  const classes = await api.get('/teacher/classes', { headers: { Authorization: `Bearer ${token}` } });
  const rows = (await classes.json()) as { classId: string; className: string; subject: string }[];
  const math = rows.filter((row) => row.subject === 'math');
  const own = math.find((row) => /1A/i.test(row.className));
  const sibling = math.find((row) => /1B/i.test(row.className));
  expect(
    own && sibling,
    `needs 1A and 1B Math among ${rows.map((r) => `${r.className}/${r.subject}`).join(', ')}`,
  ).toBeTruthy();
  classId = own!.classId;
  siblingName = sibling!.className;
  saraToken = token;
  await api.dispose();
});

/**
 * A manual lesson on her 1A section, through the screen rather than the API: the point of the
 * package is that `New lesson` now creates through `POST /teacher/lessons`.
 */
async function createManualLesson(page: Page, title: string, date: string): Promise<string> {
  const query = new URLSearchParams({ classId, subject: 'math', date });
  await page.goto(`teacher/lessons/new?${query.toString()}`);
  await expect(page.getByRole('heading', { name: 'New lesson' })).toBeVisible();
  await page.getByLabel('Title').fill(title);
  await page.getByRole('button', { name: /Write it yourself/ }).click();
  await page.getByRole('button', { name: 'Create and write the questions' }).click();
  await expect(page).toHaveURL(/\/teacher\/lessons\/[0-9a-f-]+/, { timeout: 20_000 });
  return new URL(page.url()).pathname.replace(/^\/dashboard\//, '');
}

/**
 * Ticks a checkbox the way a person does — by its label.
 *
 * `hq-checkbox` keeps the real `<input>` in the DOM but visually hidden behind the drawn box, so
 * a click aimed at the input is intercepted by that box; the label is the real hit target.
 */
function tick(scope: Locator, label: string | RegExp): Locator {
  return scope.locator('label.check', { hasText: label });
}

/**
 * A lesson still at `draft`, through her own token.
 *
 * The screens cannot produce one: "Write it yourself" lands on `review` immediately and a PDF
 * goes to `uploading` the moment the file is chosen. A draft is the state a create leaves behind
 * when no source has been attached yet, and it is the only state — with `error` — the server
 * lets a teacher delete (409 otherwise), so the delete case needs one.
 */
async function createDraft(title: string, date: string): Promise<string> {
  const api = await request.newContext({ baseURL: API });
  const created = await api.post('/teacher/lessons', {
    headers: { Authorization: `Bearer ${saraToken}` },
    data: { classId, subject: 'math', date, source: 'pdf', title },
  });
  expect(created.ok(), `could not create a draft: ${created.status()}`).toBeTruthy();
  const lesson = (await created.json()) as { id: string; status: string };
  expect(lesson.status).toBe('draft');
  await api.dispose();
  return `teacher/lessons/${lesson.id}`;
}

/** One stop is all "ready to publish" takes for a manual lesson (`publishReady`). */
async function addOneStop(page: Page): Promise<void> {
  await page.getByRole('button', { name: '+ Add stop' }).click();
  await page.getByRole('menuitem', { name: 'Multiple choice', exact: true }).click();
  await expect(page.getByRole('listbox', { name: 'Stops' }).getByRole('option')).toHaveCount(1, {
    timeout: 20_000,
  });
}

/** True once the server has finished reading the pages — whatever the page is painting. */
async function analyzedStatusOf(lessonId: string): Promise<boolean> {
  const api = await request.newContext({ baseURL: API });
  try {
    const lesson = await api.get(`/teacher/lessons/${lessonId}`, {
      headers: { Authorization: `Bearer ${saraToken}` },
    });
    if (!lesson.ok()) return false;
    const status = ((await lesson.json()) as { status: string }).status;
    return status !== 'draft' && status !== 'uploading' && status !== 'analyzing';
  } finally {
    await api.dispose();
  }
}

test.describe.configure({ mode: 'serial' });

test('Sara writes a lesson on 1A and publishes it to 1A and 1B from the sheet', async ({ page }) => {
  test.setTimeout(120_000);
  await signInAsSara(page);
  lessonUrl = await createManualLesson(page, TITLE, PUBLISH_DAY);
  await addOneStop(page);

  // §4: class and subject are facts of the lesson, not fields — and the day is a field only
  // while the lesson is unpublished.
  await expect(page.getByText(/1A.*·.*Math/)).toBeVisible();
  await expect(page.getByLabel('Lesson day')).toHaveValue(PUBLISH_DAY);

  await page.getByRole('button', { name: 'Publish', exact: true }).click();

  const sheet = page.getByRole('dialog');
  await expect(sheet).toBeVisible();
  // Her own class is the title, never a checkbox: "Publish to 1A on …".
  await expect(sheet.getByRole('heading', { name: /^Publish to 1A/ })).toBeVisible();
  const sibling = tick(sheet, siblingName);
  await expect(sibling).toBeVisible();
  await sibling.click();
  await expect(sheet.getByRole('checkbox')).toBeChecked();
  await sheet.getByRole('button', { name: 'Publish', exact: true }).click();

  // One link per copy, named after its class. 1B's copy is a different lesson id.
  await expect(page.getByText('Published. Each class has its own copy:')).toBeVisible({ timeout: 20_000 });
  const ownCopy = page.getByRole('link', { name: '1A British', exact: true });
  const siblingCopy = page.getByRole('link', { name: siblingName, exact: true });
  await expect(ownCopy).toBeVisible();
  await expect(siblingCopy).toBeVisible();
  expect(await siblingCopy.getAttribute('href')).not.toBe(await ownCopy.getAttribute('href'));

  // Published: the day is fixed, Delete is gone and Unpublish is the way back (§8).
  await expect(page.getByLabel('Lesson day')).toBeHidden();
  await expect(page.getByRole('button', { name: /^Actions for/ })).toBeHidden();
  await expect(page.getByRole('button', { name: 'Unpublish' })).toBeVisible();
});

test('both calendars show the lesson as published, each as its own copy', async ({ page }) => {
  await signInAsSara(page);

  for (const className of ['1A British', siblingName]) {
    await page.goto('teacher/classes');
    await page.getByRole('link', { name: `${className} · Math · British`, exact: true }).click();
    const grid = page.getByRole('grid');
    await expect(grid).toBeVisible();

    // The calendar opens on this month; the lesson is months out, so walk forward to its cell.
    // `:not(.cal__cell--outside)` matters: a month's grid also draws the neighbouring months'
    // spill days, and those carry the date but never the lesson.
    const cell = page.locator(`[data-date="${PUBLISH_DAY}"]:not(.cal__cell--outside)`);
    // Far enough out that this is a dozen hops, not two: the day has to be one no earlier run
    // has taken, and the calendar only ever opens on this month. Each hop is done when the
    // grid's own `aria-label` — the month it is showing — has changed.
    for (let hop = 0; hop < 14 && (await cell.count()) === 0; hop += 1) {
      const showing = await grid.getAttribute('aria-label');
      await page.getByRole('button', { name: 'Next month' }).click();
      await expect(grid).not.toHaveAttribute('aria-label', showing ?? '');
    }
    await expect(cell, `${className}'s calendar never reached ${PUBLISH_DAY}`).toHaveCount(1);
    // Its own copy, its own id — the cell links to the lesson *this* class was given.
    await expect(cell).toContainText(TITLE);
    await expect(cell).toContainText('Published');
  }
});

test('Unpublish is immediate and the 10 s Undo puts it back', async ({ page }) => {
  await signInAsSara(page);
  await page.goto(lessonUrl);
  await expect(page.getByRole('heading', { level: 1, name: TITLE })).toBeVisible({ timeout: 15_000 });

  await page.getByRole('button', { name: 'Unpublish' }).click();
  await expect(page.getByText('Lesson unpublished.')).toBeVisible({ timeout: 20_000 });
  // Unpublished again: the day is a field once more.
  await expect(page.getByLabel('Lesson day')).toBeVisible();

  await page.getByRole('button', { name: 'Undo' }).click();
  await expect(page.getByRole('button', { name: 'Unpublish' })).toBeVisible({ timeout: 20_000 });
});

test('an unpublished lesson moves day, and the move survives a reload', async ({ page }) => {
  test.setTimeout(120_000);
  await signInAsSara(page);
  await createManualLesson(page, DRAFT_TITLE, DRAFT_DAY);

  const day = page.getByLabel('Lesson day');
  await expect(day).toHaveValue(DRAFT_DAY);
  await day.fill(MOVED_DAY);
  // Optimistic, then confirmed by the lesson the PATCH answers with.
  await expect(day).toHaveValue(MOVED_DAY);
  await page.reload();
  await expect(page.getByLabel('Lesson day')).toHaveValue(MOVED_DAY, { timeout: 20_000 });

  // It has been written, so it is not deletable — Unpublish, not Delete, is the way back (§8),
  // and a `review` lesson would be a 409 from `DELETE /teacher/lessons/{id}`.
  await expect(page.getByRole('button', { name: /^Actions for/ })).toBeHidden();
});

test('a draft is deleted from the overflow menu behind a red band', async ({ page }) => {
  await signInAsSara(page);
  const draft = await createDraft(`${DRAFT_TITLE} draft`, dayFromNow(BASE + 3));
  await page.goto(draft);

  await page.getByRole('button', { name: /^Actions for/ }).click();
  await page.getByRole('menuitem', { name: 'Delete' }).click();
  const band = page.getByRole('alert', { name: 'Delete this lesson?' });
  await band.getByRole('button', { name: 'Delete', exact: true }).click();

  await expect(page).toHaveURL(/\/teacher\/lessons(\?|$)/, { timeout: 20_000 });
});

test('the same file uploaded twice is badged "Analyzed before · 0 tokens"', async ({ page }) => {
  test.setTimeout(180_000);
  await signInAsSara(page);

  // The first upload pays for the analysis; the second is a cache hit on the file's hash. On a
  // shared database the first one is often a hit too — either way, what this proves is that the
  // second lesson says so.
  for (const pass of [1, 2]) {
    const query = new URLSearchParams({ classId, subject: 'math', date: dayFromNow(BASE + 5 + pass) });
    await page.goto(`teacher/lessons/new?${query.toString()}`);
    await page.getByLabel('Title').fill(`${TITLE} cached ${pass}`);
    await page.getByRole('button', { name: /Upload a PDF/ }).click();
    await page.getByLabel('Drop a PDF here').setInputFiles(ONE_PAGE_PDF);
    await page.getByRole('button', { name: 'Create and read the PDF' }).click();
    await expect(page).toHaveURL(/\/teacher\/lessons\/[0-9a-f-]+/, { timeout: 30_000 });
    // The next pass may only hash the file once this one has stored its analysis. Waiting for
    // "Reading the pages" to *disappear* is the wrong wait and passed by accident: a lesson is
    // `uploading` for its first seconds, so the text is not on the page yet and `toBeHidden`
    // returns at once — under the local `fake` provider the analysis was over by the time it
    // mattered, against QA's real model it was not. The status the server reports is the fact.
    await expect
      .poll(() => analyzedStatusOf(new URL(page.url()).pathname.split('/').pop()!), {
        timeout: 150_000,
        intervals: [2_000],
      })
      .toBe(true);
  }

  await expect(page.getByText('Analyzed before · 0 tokens')).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText('Analyzed before · 0 tokens')).toHaveAttribute('title', /saved \d+ tokens/);
});

/**
 * The `ar` deep-reload regression (N2.4b fold-in): with Arabic stored, a hard reload of a lesson
 * used to paint the rail from `screens.ts` before `ar.json` landed, so it read `nav.thisWeek`.
 * `provideLanguage()` waits for the bundle, so there is no such frame — held back here on
 * purpose, because on this Mac the file arrives too fast for the bug to have ever shown.
 */
test('a hard reload with `ar` stored never paints a raw translation key', async ({ page }) => {
  await signInAsSara(page);
  await page.evaluate(() => localStorage.setItem('hq.language', 'ar'));
  await page.route('**/assets/i18n/ar.json', async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 1500));
    await route.continue();
  });

  await page.goto(lessonUrl);
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
  await expect(page.locator('hq-nav')).toBeVisible({ timeout: 20_000 });
  const rail = await page.locator('hq-nav a').allTextContents();
  expect(rail.length).toBeGreaterThan(0);
  expect(rail.some((item) => /^(nav|lessons|week)\./.test(item.trim()))).toBe(false);

  await page.evaluate(() => localStorage.setItem('hq.language', 'en'));
});

test('the screenshot set, EN and AR', async ({ page }) => {
  test.setTimeout(180_000);
  await mkdir(SHOTS, { recursive: true });
  await signInAsSara(page);

  // Both drafts are written first, in English: `createManualLesson` drives the screens by their
  // English labels, and the AR pass would be looking for a heading that is no longer there.
  const sheetDrafts: Record<'en' | 'ar', string> = { en: '', ar: '' };
  for (const [index, language] of (['en', 'ar'] as const).entries()) {
    sheetDrafts[language] = await createManualLesson(
      page,
      `${TITLE} sheet ${language}`,
      dayFromNow(BASE + 8 + index),
    );
    await addOneStop(page);
  }

  for (const language of ['en', 'ar'] as const) {
    await page.evaluate((lang) => localStorage.setItem('hq.language', lang), language);

    // The sheet is a CDK overlay panel with nothing opaque behind it, which is exactly the
    // thing dark mode gets wrong if a floating panel is given the card surface — so it is
    // photographed in both schemes, not only in both languages.
    for (const scheme of ['light', 'dark'] as const) {
      const suffix = scheme === 'dark' ? `${language}-dark` : language;

      await page.goto(lessonUrl);
      await expect(page.locator('html')).toHaveAttribute('dir', language === 'ar' ? 'rtl' : 'ltr');
      await setScheme(page, scheme);
      await shoot(
        page,
        `${SHOTS}/01-published-lesson-${suffix}.png`,
        page.getByRole('heading', { level: 1, name: TITLE }),
      );

      await page.goto(sheetDrafts[language]);
      await setScheme(page, scheme);
      // The footer's last control is the primary one — Publish, whatever it is called here.
      await page.locator('[page-footer]').getByRole('button').last().click();
      await shoot(page, `${SHOTS}/02-publish-sheet-${suffix}.png`, page.getByRole('dialog'));
      await page.keyboard.press('Escape');
    }
    await setScheme(page, 'light');
  }

  await page.evaluate(() => localStorage.setItem('hq.language', 'en'));
});

/** The lessons this file wrote, off the shared database again (`env.ts`). */
test.afterAll(removeLessonsOfThisRun);
