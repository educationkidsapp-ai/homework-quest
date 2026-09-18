import { expect, request, test } from '@playwright/test';
import { resolve } from 'node:path';
import { API, RUN, SARA, dayFromNow, removeLessonsOfThisRun, signInAsSara, signInForToken } from './env';

/**
 * The pipeline actually breaking, and **Retry and continue** actually mending it.
 *
 * Needs a server started with `-Dquest.pipeline.fail-once-at=generate_L2`, which fails that step
 * exactly once per lesson (`LessonPipeline.java`), so it opts in on `E2E_FAIL_ONCE_AT` and is
 * skipped everywhere else — the happy-path suite's server is not started that way, and a server
 * that fails a step per lesson would make every other file flaky. `LessonRecoveryTest.java`
 * covers the same hook at the API; what is proved here is the button, which nothing else covers.
 *
 * Rewritten for one school (N2.5): it drove the Admin's School switcher and `All lessons`, which
 * D13 does not draw, so it could not have passed even if it had ever been run. It is Sara's
 * screen now, and her own `POST /teacher/lessons` behind it.
 *
 *     SPRING_PROFILES_ACTIVE=h2 SEED_SCHOOL=true SEED_STAFF_PASSWORD=… ADMIN_EMAIL=… \
 *       ADMIN_PASSWORD=… LLM_PROVIDER=fake PORT=18081 \
 *       java -Dquest.pipeline.fail-once-at=generate_L2 -jar server/target/server.jar &
 *
 *     HQ_API=http://localhost:18081 E2E_FAIL_ONCE_AT=1 E2E_ADMIN_EMAIL=… \
 *       E2E_ADMIN_PASSWORD=… E2E_STAFF_PASSWORD=… pnpm e2e:local --grep 'injected failure'
 */
const ONE_PAGE_PDF = resolve(process.cwd(), 'e2e/fixtures/one-page.pdf');
const TITLE = `Retry ${RUN}`;

let classId = '';

test.skip(
  process.env['E2E_FAIL_ONCE_AT'] !== '1',
  'needs the API started with quest.pipeline.fail-once-at=generate_L2',
);

test.beforeAll(async () => {
  const api = await request.newContext({ baseURL: API });
  const classes = await api.get('/teacher/classes', {
    headers: { Authorization: `Bearer ${await signInForToken(SARA)}` },
  });
  const rows = (await classes.json()) as { classId: string; className: string; subject: string }[];
  const mine = rows.find((row) => /1A British/i.test(row.className) && row.subject === 'math');
  expect(mine, `no 1A British Math among ${rows.map((r) => r.className).join(', ')}`).toBeTruthy();
  classId = mine!.classId;
  await api.dispose();
});

test('retries a lesson past an injected failure at generate_L2', async ({ page }) => {
  test.setTimeout(180_000);
  await signInAsSara(page);

  const query = new URLSearchParams({ classId, subject: 'math', date: dayFromNow(400) });
  await page.goto(`teacher/lessons/new?${query.toString()}`);
  await expect(page.getByRole('heading', { name: 'New lesson' })).toBeVisible();
  await page.getByLabel('Title').fill(TITLE);
  await page.getByRole('button', { name: /Upload a PDF/ }).click();
  await page.getByLabel('Drop a PDF here').setInputFiles(ONE_PAGE_PDF);
  await page.getByRole('button', { name: 'Create and read the PDF' }).click();
  await expect(page).toHaveURL(/\/teacher\/lessons\/[0-9a-f-]+/, { timeout: 30_000 });

  const confirmSkills = page.getByRole('button', { name: 'Make the quest' });
  await expect(confirmSkills).toBeVisible({ timeout: 60_000 });
  await confirmSkills.click();

  // generate_L2 fails once: the pipeline lands on `error`, not `review`, and the page says so.
  const retry = page.getByRole('button', { name: 'Retry and continue' });
  await expect(retry).toBeVisible({ timeout: 60_000 });
  await retry.click();

  // `failed.add(lessonId + ":" + step)` in `LessonPipeline.java` only fails a step once per
  // lesson, so the retry runs the real pipeline through to review.
  await expect(page.getByRole('heading', { name: 'Levels' })).toBeVisible({ timeout: 90_000 });
  await expect(page.getByText('Ready to review', { exact: false })).toBeVisible();
});

test.afterAll(removeLessonsOfThisRun);
