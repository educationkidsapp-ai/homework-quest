import { type APIRequestContext, type Locator, type Page } from '@playwright/test';
import { mkdir, stat } from 'node:fs/promises';
import { resolve } from 'node:path';
import {
  OMAR,
  RUN,
  SARA,
  expect,
  setLanguage,
  setScheme,
  shoot,
  signIn,
  signInAsSara,
  signInForToken,
  teacherClasses,
  test,
} from './env';
import {
  adminHeaders,
  api,
  attempt,
  bearer,
  createChild,
  islandOf,
  joinCodeOf,
  paperOf,
  parentOf,
  publishExam,
  publishHomework,
  removeChild,
  schoolOfSara,
  staff,
  upload,
  withFlags,
  type Parent,
} from './n4-api';

/**
 * N4.5 — `docs/teacher-flow.md` §10 **steps 5 to 8**, walked once, end to end, with a child who
 * really plays.
 *
 * What the earlier files leave open is the middle of the chain. `results.spec.ts` (N4.2) reads the
 * numbers `seed/attempts.csv` wrote straight into the tables, and `exams.spec.ts` (N4.4) stops at
 * a class that is entirely absent because nobody can sit an exam. Both were right for their
 * packages, and between them nothing has ever proved that an attempt made **through the API the
 * app uses** arrives on the Results page, moves when the teacher marks it, reaches the parent on
 * release and disappears on withdrawal — or that §8's one-sitting rule holds against a second
 * upload.
 *
 * So this file drives the child's side over the API (`n4-api.ts` says why, and how) and the
 * teacher's side through the screens:
 *
 * | Step | Here |
 * | --- | --- |
 * | 5 | a published homework, played by a child of 1A, on the Results page **within 5 s** (wall time, asserted); the retell marked 2★ with a comment; the score moving 100 → 94; the parent's `GET /children/{id}/progress` gaining and losing it with the release |
 * | 6 | the 1A gradebook carrying the same 94 and its band, and the child page carrying the band and the chart point |
 * | 7 | an exam whose window is open now, sat once through the API, a second upload refused `409 exam_already_taken`; the results page's score, percent, band and time; the distribution; the per-question difficulty; CSV, XLSX and the per-child PDF all non-empty; an absent child re-opened once and the second re-opening refused |
 * | 8 | Omar — another teacher's section — refused the results, the gradebook and the exam routes |
 *
 * **Local only.** The child's half needs `quest.auth.fake` (the `h2` profile), and QA is the
 * owner's acceptance environment where this package writes nothing at all: `E2E_BASE_URL` skips
 * the whole file.
 *
 * Idempotent: every title carries `RUN`, the child is this run's own, and `afterAll` takes back
 * the lesson, the exam, the child and the four flags.
 */
const SHOTS = resolve(process.cwd(), '../docs/screenshots/n4.5');

const SECTION = '1A British';
const FLAGS = ['gradebook', 'openStopMarking', 'exams', 'teacher.rosterEdit'] as const;
const HOMEWORK = `N4.5 homework ${RUN}`;
const EXAM = `N4.5 exam ${RUN}`;
const CHILD = `Nour N45 ${RUN}`;
const COMMENT = `Lovely retelling — ${RUN}`;

/** The seed's own week, as `Date.getUTCDay()` numbers it: Sunday to Thursday. */
const TEACHING = new Set([0, 1, 2, 3, 4]);

/**
 * Today, or the next day the school teaches on.
 *
 * Today rather than a future day on purpose: the child plays the lesson the moment it is
 * published, and the gradebook's default window ends today (`GradingService.DEFAULT_WINDOW_DAYS`),
 * so a lesson dated next week would be published, played and then invisible in the grid the same
 * package asserts.
 */
function todayOrNextTeachingDay(): string {
  const day = new Date();
  while (!TEACHING.has(day.getUTCDay())) day.setUTCDate(day.getUTCDate() + 1);
  return day.toISOString().slice(0, 10);
}

let context: APIRequestContext;
let restoreFlags: () => Promise<void> = async () => {};
let classId = '';
let schoolId = '';
let lessonId = '';
let lessonStops: readonly string[] = [];
let examId = '';
let childId = '';
let parent: Parent;

test.skip(
  !!process.env['E2E_BASE_URL'],
  'N4.5 writes a child, a lesson and an exam: the local H2 seed only — QA is read-only for this package',
);
test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  context = await api();
  const section = (await teacherClasses()).find((row) => row.className === SECTION);
  expect(section, `Sara should teach ${SECTION} (seed/assignments.csv)`).toBeTruthy();
  classId = section!.classId;
  schoolId = await schoolOfSara(context);
  restoreFlags = await withFlags(context, schoolId, FLAGS);

  parent = parentOf(`n45-${RUN.toLowerCase()}`);
  childId = await createChild(context, parent, CHILD, await joinCodeOf(context, classId));

  const homework = await publishHomework(context, {
    classId,
    title: HOMEWORK,
    date: todayOrNextTeachingDay(),
  });
  lessonId = homework.lessonId;
  lessonStops = homework.stopIds;
});

test.afterAll(async () => {
  const headers = await staff();
  const admin = await adminHeaders();
  for (const id of [examId, lessonId].filter(Boolean)) {
    await context.post(`/teacher/lessons/${id}/unpublish`, { headers });
    await context.delete(`/admin/lessons/${id}`, { headers: admin });
  }
  if (childId) await removeChild(context, parent, childId);
  await restoreFlags();
  await context.dispose();
});

/** Her row in a results or gradebook table. */
function rowOf(page: Page, name: string) {
  return page.getByRole('row').filter({ hasText: name });
}

async function openResults(page: Page): Promise<void> {
  await page.goto(`teacher/lessons/${lessonId}/results`);
  await expect(page.getByRole('heading', { name: HOMEWORK })).toBeVisible({ timeout: 15_000 });
}

test('step 5 — a published homework is auto-released, and the child is not on it yet', async ({ page }) => {
  // §7's rule, at the moment it applies: publishing a homework releases it to the parents, which
  // is the half of the release round trip nothing else in the suite goes through the publish path
  // for (the seeded homework of `results.spec.ts` is written straight into the tables).
  const results = await context.get(`/teacher/lessons/${lessonId}/results`, { headers: await staff() });
  expect(results.ok(), `GET results: HTTP ${results.status()}`).toBeTruthy();
  const body = (await results.json()) as { released: boolean; played: number };
  expect(body.released, 'a homework releases itself on publish (§7)').toBe(true);
  expect(body.played, 'nobody has played it yet').toBe(0);

  // The child's device can see it, which is what makes the next test's upload a play rather than
  // a write: the island is hers, on her map, in the state the player reads.
  const island = await islandOf(context, parent, childId, lessonId);
  expect(island, `${CHILD} should see ${HOMEWORK} on her map`).toBeTruthy();

  await signInAsSara(page);
  await openResults(page);
  // She is on the page already — the results table is the class register, not a list of players
  // (`GradingService.rosterFor`) — with nothing in her score column yet.
  await expect(rowOf(page, CHILD).locator('[data-hq-score]')).toHaveText('—');
});

test('step 5 — her attempt is on the Results page within 5 seconds', async ({ page }) => {
  await signInAsSara(page);
  await openResults(page);

  const paper = await paperOf(context, parent, childId, lessonId);
  expect(paper.stops.map((stop) => stop.id)).toEqual([...lessonStops]);

  // Four single-answer stops right on the first try, and the retell answered but unmarked: §7's
  // arithmetic makes that 100 with one stop waiting for the teacher.
  const started = Date.now();
  const ack = await upload(
    context,
    parent,
    childId,
    paper.stops.map((stop, index) => attempt({ lessonId, stopId: stop.id, at: started + index })),
  );
  expect(ack.status, `POST attempts: HTTP ${ack.status}`).toBe(200);
  expect(ack.accepted).toBe(paper.stops.length);

  await page.reload();
  const row = rowOf(page, CHILD);
  await expect(row.locator('[data-hq-score]')).toHaveText('100', { timeout: 10_000 });
  const elapsed = Date.now() - started;
  // Recorded, not only asserted: "within 5 s" is a number the verification report quotes.
  test.info().annotations.push({ type: 'timing', description: `attempt → Results page: ${elapsed} ms` });
  console.log(`n4 step 5: the attempt reached the Results page in ${elapsed} ms`);
  expect(elapsed, `§10 step 5: the attempt reached the Results page in ${elapsed} ms`).toBeLessThan(5_000);

  await expect(row.getByText('to mark')).toBeVisible();
});

/**
 * **A defect, marked expected-to-fail so the run stays honest about it.**
 *
 * Marking from the Results page does not move the score on any lesson with more than one level —
 * which, since publishing generates Levels 2 and 3, is every published lesson.
 *
 * `GET /teacher/lessons/{id}/results` sends the **column list** from the lesson's *top* level
 * (`GradingService.java:96-100`: `stopsByLevel.getOrDefault(top, …)`) while each child's own stops
 * come from the level she actually played (`Scoring.java:82`, scoredLevel). The dashboard joins the
 * two by stop id (`dashboard/src/app/features/results/results.models.ts:74,90`), so for a child who
 * played Level 1 of a three-level lesson *nothing matches*: every per-stop cell reads "not
 * attempted", and the mark panel — whose open stops are the row's (`mark-panel.component.ts:119`) —
 * offers the **Level 3** retell. `PUT /teacher/marks` then stores 2★ against a stop the child never
 * answered: 200, no complaint, and her score stays where it was.
 *
 * Observed here: the child plays `…:1:0:…`, the columns are `…:3:0:…`, the saved mark is
 * `…:3:0:…`, and the score stays 100 where §7's arithmetic says 94. It has gone unnoticed because
 * the only marking covered until now was `results.spec.ts` on `seed/attempts.csv`, whose homework
 * has exactly one level, where top and scored level are the same play.
 *
 * When it is fixed this test passes and Playwright fails the run for an unexpected pass — which is
 * the point: the fix removes the annotation, not the test.
 */
test('step 5 — marking the retell from the Results page moves her score', async ({ page }) => {
  test.fail(true, 'known defect: the Results columns are the top level, the child played Level 1');
  await signInAsSara(page);
  await openResults(page);

  const row = rowOf(page, CHILD);
  const score = row.locator('[data-hq-score]');
  await expect(score).toHaveText('100');

  await row.getByRole('button', { name: new RegExp(CHILD) }).click();
  await page.getByRole('radio', { name: '2 of 3 stars' }).click();
  await page.getByRole('button', { name: 'Save marks' }).click();
  await expect(page.getByText(`Marks saved for ${CHILD}.`)).toBeVisible({ timeout: 15_000 });

  // Four correct stops at 100 and a two-star retell at 70: (400 + 70) / 5 = 94.
  await expect(score).toHaveText('94', { timeout: 15_000 });
});

test('step 5 — the mark moves the score, and the parent gains and loses it with the release', async ({
  page,
}) => {
  // The mark is written on the stop **she answered**, which is what the screen above should have
  // sent and does not. Everything after it — the parent's screen, the gradebook, her page — is
  // about the score moving, so the chain is kept on correct data rather than on the defect.
  const retell = lessonStops[lessonStops.length - 1]!;
  const marked = await context.put('/teacher/marks', {
    headers: await staff(),
    data: {
      marks: [
        { lessonId, childId, stopId: retell, stars: 2 },
        { lessonId, childId, comment: COMMENT },
      ],
    },
  });
  expect(marked.ok(), `PUT /teacher/marks: HTTP ${marked.status()}`).toBeTruthy();

  await signInAsSara(page);
  await openResults(page);
  const score = rowOf(page, CHILD).locator('[data-hq-score]');
  await expect(score).toHaveText('94', { timeout: 15_000 });

  // The parent, on the app's own endpoint. The lesson released itself on publish, so this is the
  // first thing she sees rather than something the teacher has had to turn on.
  await expect
    .poll(async () => (await releasedResults()).find((result) => result.lessonId === lessonId), {
      message: 'the parent should see the released score and the comment',
      timeout: 15_000,
    })
    .toMatchObject({ score: 94, band: 'exceeding', comment: COMMENT });

  // Withdrawn — destructive, so the screen asks in the red band before it takes anything away.
  const toggle = page.locator('[data-hq-release] [role="switch"]').first();
  await expect(toggle).toHaveAttribute('aria-checked', 'true');
  await toggle.click();
  await expect(page.getByText(/Parents will stop seeing/)).toBeVisible();
  await page.getByRole('button', { name: 'Withdraw' }).click();
  await expect(toggle).toHaveAttribute('aria-checked', 'false', { timeout: 15_000 });
  // Polled, not read once: the switch draws the server's answer, and the parent's own read is a
  // second request against a transaction that has only just committed.
  await expect
    .poll(async () => (await releasedResults()).map((result) => result.lessonId), {
      message: 'a withdrawn result must leave the parent',
      timeout: 15_000,
    })
    .not.toContain(lessonId);

  // And back, with nothing to confirm: giving a parent a score takes nothing away.
  await toggle.click();
  await expect(toggle).toHaveAttribute('aria-checked', 'true', { timeout: 15_000 });
  await expect
    .poll(async () => (await releasedResults()).map((result) => result.lessonId), {
      message: 'releasing again gives the parent the score back',
      timeout: 15_000,
    })
    .toContain(lessonId);
});

/** What the parent's app shows on her progress screen — §7's released results, and only those. */
async function releasedResults(): Promise<readonly { lessonId: string; score: number; band: string; comment?: string }[]> {
  const response = await context.get(`/children/${childId}/progress`, { headers: bearer(parent.token) });
  expect(response.ok(), `GET progress: HTTP ${response.status()}`).toBeTruthy();
  return ((await response.json()) as { results?: { lessonId: string; score: number; band: string; comment?: string }[] })
    .results ?? [];
}

test('step 6 — the gradebook and the child page carry the same score and band', async ({ page }) => {
  await signInAsSara(page);
  await page.goto(`teacher/classes/${encodeURIComponent(classId)}?tab=gradebook`);

  const grid = page.getByRole('table', { name: 'Gradebook' });
  await expect(grid).toBeVisible({ timeout: 15_000 });
  const row = grid.getByRole('row').filter({ hasText: CHILD });
  const cell = row.locator('[data-hq-gb-cell]').filter({ hasText: '94' }).first();
  await expect(cell).toBeVisible({ timeout: 15_000 });
  await expect(cell.locator('[data-band="exceeding"]')).toBeVisible();

  // Her name is the door to her own page (§4 step 9).
  await row.getByRole('link', { name: CHILD }).click();
  await expect(page.getByRole('heading', { name: CHILD })).toBeVisible({ timeout: 15_000 });

  // The band, and the chart's own point — the SVG is `aria-hidden`, so the assertion is on the
  // table beside it, which carries the same rows.
  await expect(page.locator('[data-band="exceeding"]').first()).toBeVisible();
  const chart = page.getByRole('table').filter({ hasText: HOMEWORK });
  await expect(chart.getByRole('row').filter({ hasText: HOMEWORK }).first()).toBeVisible();
});

test('step 7 — the exam is sat once, and the second sitting is refused', async ({ page }) => {
  test.setTimeout(120_000);
  const exam = await publishExam(context, { classId, title: EXAM, minutes: 30 });
  examId = exam.examId;

  // §8: the island exists only inside the window, and it carries the window that makes it a test.
  const island = await islandOf(context, parent, childId, examId);
  expect(island, 'the exam island should be on her map while the window is open').toBeTruthy();
  expect(island?.examWindow, 'an exam island carries its window').toBeTruthy();

  const paper = await paperOf(context, parent, childId, examId);
  expect(paper.type).toBe('exam');
  expect(paper.hintsOff, '§8: no hints in an exam').toBe(true);
  expect(paper.numbersOff, '§8: no numbers in an exam').toBe(true);

  // She is interrupted after two questions and comes back to the same sitting — §8's "the attempt
  // resumes where it stopped", which is the same row taking more answers.
  const at = Date.now();
  const first = await upload(context, parent, childId, [
    attempt({ lessonId: examId, stopId: paper.stops[0]!.id, at }),
    attempt({ lessonId: examId, stopId: paper.stops[1]!.id, at: at + 1 }),
  ]);
  expect(first.status, `first batch: HTTP ${first.status}`).toBe(200);

  const results = await context.get(`/teacher/exams/${examId}/results`, { headers: await staff() });
  const midway = (await results.json()) as { sat: number; submitted: number };
  expect(midway.sat, 'she is inside the paper').toBe(1);
  expect(midway.submitted, 'and has not handed it in').toBe(0);

  // A pause the clock can see: `secondsTaken` is the span from her first answer to her last, and
  // an exam sat in one millisecond makes the results page's "time taken" column unfalsifiable.
  await page.waitForTimeout(2_000);

  const second = await upload(context, parent, childId, [
    attempt({ lessonId: examId, stopId: paper.stops[2]!.id, correct: false, at: at + 2 }),
    attempt({ lessonId: examId, stopId: paper.stops[3]!.id, at: at + 3 }),
    attempt({ lessonId: examId, stopId: paper.stops[4]!.id, at: at + 4 }),
  ]);
  expect(second.status, `second batch: HTTP ${second.status}`).toBe(200);

  // One sitting, and the server is the one holding the rule.
  const again = await upload(context, parent, childId, [
    attempt({ lessonId: examId, stopId: paper.stops[0]!.id, at: at + 5 }),
  ]);
  expect(again.status, 'a second sitting is refused').toBe(409);
  expect(again.code).toBe('exam_already_taken');

  // The teacher's page: three right, one wrong, the retell waiting — (300 + 0) / 4 = 75.
  await signInAsSara(page);
  await page.goto(`teacher/exams/${examId}/results`);
  await expect(page.getByRole('heading', { name: EXAM })).toBeVisible({ timeout: 15_000 });
  const row = page.getByRole('table', { name: 'Every child' }).getByRole('row').filter({ hasText: CHILD });
  await expect(row.locator('[data-hq-score]')).toHaveText('13', { timeout: 15_000 });
  await expect(row.getByText('75%')).toBeVisible();
  await expect(row.locator('[data-band="secure"]')).toBeVisible();
  await expect(row.getByText('Handed in').first()).toBeVisible();
  // Time taken, as a real span rather than the em dash a sitting with no clock would print.
  // By column rather than by text: the sixth cell is "Time taken" (`exam-results.page.ts:182`),
  // and a loose regex would also match the "Handed in" moment two cells along.
  await expect(row.locator('td').nth(5)).toHaveText(/^\s*\d+:\d{2}\s*$/);

  // The two charts §8 asks for, now that one child has landed somewhere.
  await expect(page.getByText('Nobody has sat it yet, so there is nothing to spread.')).toBeHidden();
  // The distribution's accessible name is its caption, not the card's title.
  const distribution = page.getByRole('table', { name: 'Children in each level band' });
  await expect(distribution).toBeVisible();
  await expect(distribution.getByRole('row').filter({ hasText: 'Secure' })).toContainText('1');
  const questions = page.getByRole('table', { name: 'Question by question' });
  await expect(questions).toBeVisible();
  await expect(questions.getByRole('row')).toHaveCount(paper.stops.length + 1); // + the header

  // Every download the page offers, fetched with the bearer and handed to the browser. The size
  // is the assertion: a 0-byte PDF is what a broken renderer hands you, and the page cannot tell.
  const csv = await download(page, page.getByRole('button', { name: 'Export CSV' }), /\.csv$/);
  const xlsx = await download(page, page.getByRole('button', { name: 'Export Excel' }), /\.xlsx$/);
  const pdf = await download(page, row.getByRole('button', { name: 'Sheet' }), /\.pdf$/);
  console.log(`n4 step 7: downloads — csv ${csv} B, xlsx ${xlsx} B, pdf ${pdf} B`);
  for (const [what, size] of [['csv', csv], ['xlsx', xlsx], ['pdf', pdf]] as const)
    expect(size, `the ${what} download should not be empty`).toBeGreaterThan(0);
});

/** Clicks something that downloads, and answers with the bytes that landed. */
async function download(page: Page, trigger: Locator, name: RegExp): Promise<number> {
  const waiting = page.waitForEvent('download');
  await trigger.click();
  const file = await waiting;
  expect(file.suggestedFilename()).toMatch(name);
  const path = await file.path();
  expect(path, 'the download should land on disk').toBeTruthy();
  return (await stat(path)).size;
}

test('step 7 — an absent child is re-opened once, and only once', async ({ page }) => {
  expect(examId, 'the exam should exist by now').toBeTruthy();
  await signInAsSara(page);
  await page.goto(`teacher/exams/${examId}/results`);
  await expect(page.getByRole('heading', { name: EXAM })).toBeVisible({ timeout: 15_000 });

  const table = page.getByRole('table', { name: 'Every child' });
  const absent = table.getByRole('row').filter({ hasText: 'Absent' }).first();
  const name = ((await absent.locator('td').first().innerText()) ?? '').trim();
  await absent.getByRole('button', { name: 'Re-open' }).click();
  // Every absent row has a Re-open of its own, so the band is scoped by its own heading.
  const confirm = page.getByRole('alert').filter({ hasText: /^Re-open for/ });
  await expect(confirm.getByText(/and only one/)).toBeVisible();
  await confirm.getByRole('button', { name: 'Re-open', exact: true }).click();
  await expect(page.getByText(/may sit it again until/)).toBeVisible({ timeout: 15_000 });

  // The second one is the server's refusal, asked for directly: the screen stops offering the
  // button, and a rule that lives only in a hidden button is not a rule.
  const roster = await context.get(`/teacher/exams/${examId}/results`, { headers: await staff() });
  const body = (await roster.json()) as { children: { childId: string; name: string; reopened: boolean }[] };
  const reopened = body.children.find((child) => child.name === name || child.reopened);
  expect(reopened, `${name} should be marked re-opened`).toBeTruthy();
  const twice = await context.post(`/teacher/exams/${examId}/reopen/${reopened!.childId}`, {
    headers: await staff(),
  });
  expect(twice.status(), 'a second re-opening is refused').toBe(409);
  expect(((await twice.json()) as { code: string }).code).toBe('exam_already_reopened');
});

test('step 8 — another teacher gets nothing: results, gradebook, exam', async ({ page }) => {
  const omar = bearer(await signInForToken(OMAR));
  const sara = await staff(SARA);

  // Hers answer, so the refusals below mean something.
  for (const path of [
    `/teacher/lessons/${lessonId}/results`,
    `/teacher/classes/${encodeURIComponent(classId)}/gradebook`,
    `/teacher/exams/${examId}/results`,
  ]) {
    const mine = await context.get(path, { headers: sara });
    expect(mine.status(), `Sara should read ${path}`).toBe(200);
    const refused = await context.get(path, { headers: omar });
    expect([403, 404], `${path} answered Omar ${refused.status()}`).toContain(refused.status());
  }

  // Writing is refused as well: a mark and a release on a lesson that is not his.
  const marked = await context.put('/teacher/marks', {
    headers: omar,
    data: { marks: [{ lessonId, childId, stars: 3 }] },
  });
  expect([403, 404], `PUT /teacher/marks answered Omar ${marked.status()}`).toContain(marked.status());
  const reopened = await context.post(`/teacher/exams/${examId}/reopen/${childId}`, { headers: omar });
  expect([403, 404], `reopen answered Omar ${reopened.status()}`).toContain(reopened.status());

  // And the dashboard agrees: his URL bar does not get him there either.
  await signIn(page, OMAR);
  await page.goto(`teacher/exams/${examId}/results`);
  await expect(page.getByRole('heading', { name: EXAM })).toBeHidden();
  await page.goto(`teacher/classes/${encodeURIComponent(classId)}?tab=gradebook`);
  await expect(page.getByRole('table', { name: 'Gradebook' })).toBeHidden();
});

test('screenshots', async ({ page }) => {
  await mkdir(SHOTS, { recursive: true });
  await signInAsSara(page);

  const frames = async (suffix: string) => {
    await openResults(page);
    await shoot(page, `${SHOTS}/results-1366-${suffix}.png`, page.getByRole('heading', { name: HOMEWORK }), {
      fullPage: true,
    });
    await page.goto(`teacher/exams/${examId}/results`);
    await shoot(page, `${SHOTS}/exam-results-1366-${suffix}.png`, page.getByRole('heading', { name: EXAM }), {
      fullPage: true,
    });
    await page.goto(`teacher/classes/${encodeURIComponent(classId)}?tab=gradebook`);
    const grid = page.getByRole('table').first();
    await expect(grid).toBeVisible({ timeout: 15_000 });
    await shoot(page, `${SHOTS}/gradebook-1366-${suffix}.png`, grid, { fullPage: true });
  };

  await frames('en');
  await setScheme(page, 'dark');
  await frames('en-dark');
  await setScheme(page, 'light');
  await setLanguage(page, 'ar');
  await frames('ar');
  await setLanguage(page, 'en');

  // The child page, reached the way a teacher reaches it, in both languages.
  await page.goto(`teacher/classes/${encodeURIComponent(classId)}?tab=gradebook`);
  await page
    .getByRole('table', { name: 'Gradebook' })
    .getByRole('row')
    .filter({ hasText: CHILD })
    .getByRole('link', { name: CHILD })
    .click();
  const heading = page.getByRole('heading', { name: CHILD });
  await expect(heading).toBeVisible({ timeout: 15_000 });
  await shoot(page, `${SHOTS}/child-1366-en.png`, heading, { fullPage: true });
  await setLanguage(page, 'ar');
  await shoot(page, `${SHOTS}/child-1366-ar.png`, page.getByRole('heading', { name: CHILD }), { fullPage: true });
  await setLanguage(page, 'en');
});
