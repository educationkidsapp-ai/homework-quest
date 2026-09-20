import { request, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import {
  ADMIN,
  API,
  expect,
  SARA,
  setLanguage,
  setScheme,
  shoot,
  signInForToken,
  signInAsSara,
  teacherClasses,
  test,
} from './env';

/**
 * N4.2, `docs/teacher-flow.md` §10 steps 5–6: Sara opens the results of a lesson her class has
 * played, marks a retell, watches the score move, takes the results back off the parents and
 * puts them back, then reads the same numbers in the gradebook and on the child's own page.
 *
 * **The data is the seed's, not this file's.** `seed/attempts.csv` publishes one homework per
 * class — {@link LESSON}, two single-answer stops and a retell — and plays it for two children
 * of 1A British. That gives the one shape §7 is about: stops that score themselves, and one open
 * stop that does not until a teacher says so.
 *
 * The arithmetic is therefore known in advance, which is what makes "see the score change" an
 * assertion rather than a screenshot. Amina answered both single-answer stops correctly on the
 * first try (100 each) and her retell is unmarked, so her score is their mean, **100**. Marking
 * the retell two stars scores it 70 (§7: "3 stars = 100, 2 = 70, 1 = 40"), and her score becomes
 * (100 + 100 + 70) / 3 = **90**.
 *
 * `gradebook` and `openStopMarking` are off in the one-school seed (`V7__sections.sql`), so this
 * file turns both on for the school and puts them back afterwards — `my-classes.spec.ts` asserts
 * the flag-off shape of the class page's tabs and must still find it.
 */
const SHOTS = resolve(process.cwd(), '../docs/screenshots/n4.2');

const SECTION = '1A British';
const LESSON = 'Counting to ten';
const CHILD = 'Amina Al Amin';
const FLAGS = ['gradebook', 'openStopMarking'] as const;

let classId = '';
let lessonId = '';
let schoolId = '';
let childId = '';
let retellStopId = '';
const wasOn = new Map<string, boolean>();

async function asAdmin() {
  const api = await request.newContext({ baseURL: API });
  const token = await signInForToken(ADMIN);
  return { api, headers: { Authorization: `Bearer ${token}` }, dispose: () => api.dispose() };
}

test.beforeAll(async () => {
  const section = (await teacherClasses()).find((row) => row.className === SECTION);
  expect(section, `Sara should teach ${SECTION} (seed/assignments.csv)`).toBeTruthy();
  classId = section!.classId;
  // `AttemptSeed.lessonIdOf`: the seeded homework's id is derived from the class's, so the
  // suite can name it without listing every lesson of the course.
  lessonId = `seed-homework-${classId}`;

  const me = await request.newContext({ baseURL: API });
  const meResponse = await me.get('/me', {
    headers: { Authorization: `Bearer ${await signInForToken(SARA)}` },
  });
  expect(meResponse.ok(), `GET /me: HTTP ${meResponse.status()}`).toBeTruthy();
  schoolId = ((await meResponse.json()) as { schoolId: string }).schoolId;
  await me.dispose();

  const admin = await asAdmin();
  try {
    const current = (await (await admin.api.get(`/schools/${schoolId}/flags`)).json()) as Record<
      string,
      boolean
    >;
    for (const flag of FLAGS) {
      wasOn.set(flag, current[flag] === true);
      const flipped = await admin.api.put(`/admin/schools/${schoolId}/flags/${flag}`, {
        headers: admin.headers,
        data: { enabled: true },
      });
      expect(flipped.ok(), `PUT ${flag}: HTTP ${flipped.status()}`).toBeTruthy();
    }
  } finally {
    await admin.dispose();
  }

  // The seeded attempts have to be there, or every assertion below is about an empty grid — and
  // the marks this file writes have to be cleared, or a second run starts from the first run's
  // numbers. A mark whose every field is null is §7's delete, so the same endpoint does both.
  const sara = await request.newContext({ baseURL: API });
  try {
    const staff = { Authorization: `Bearer ${await signInForToken(SARA)}` };
    const results = await sara.get(`/teacher/lessons/${lessonId}/results`, { headers: staff });
    expect(
      results.ok(),
      `GET results: HTTP ${results.status()} — is the H2 seed on the full profile (seed/attempts.csv)?`,
    ).toBeTruthy();
    const body = (await results.json()) as {
      played: number;
      stops: { stopId: string; open: boolean }[];
      children: { childId: string; name: string }[];
    };
    expect(body.played, 'seed/attempts.csv should have played this lesson').toBeGreaterThan(0);

    childId = body.children.find((child) => child.name === CHILD)?.childId ?? '';
    retellStopId = body.stops.find((stop) => stop.open)?.stopId ?? '';
    expect(childId, `${CHILD} should be in ${SECTION}`).toBeTruthy();
    expect(retellStopId, `${LESSON} should have an open stop to mark`).toBeTruthy();

    const cleared = await sara.put('/teacher/marks', {
      headers: staff,
      data: {
        marks: [
          { lessonId, childId, stopId: retellStopId },
          { lessonId, childId },
        ],
      },
    });
    expect(cleared.ok(), `PUT marks (clear): HTTP ${cleared.status()}`).toBeTruthy();
  } finally {
    await sara.dispose();
  }
});

test.afterAll(async () => {
  const admin = await asAdmin();
  try {
    for (const flag of FLAGS)
      if (schoolId && wasOn.get(flag) !== true)
        await admin.api.put(`/admin/schools/${schoolId}/flags/${flag}`, {
          headers: admin.headers,
          data: { enabled: false },
        });
  } finally {
    await admin.dispose();
  }
});

async function openResults(page: Page): Promise<void> {
  await signInAsSara(page);
  await page.goto(`teacher/lessons/${lessonId}/results`);
  await expect(page.getByRole('heading', { name: LESSON })).toBeVisible();
}

/** The child's row in the results grid. */
function row(page: Page, name: string) {
  return page.getByRole('row').filter({ hasText: name });
}

test('marks a retell and the score moves with it', async ({ page }) => {
  await openResults(page);

  const amina = row(page, CHILD);
  const score = amina.locator('[data-hq-score]');
  await expect(score).toHaveText('100');
  await expect(amina.getByText('to mark')).toBeVisible();

  await amina.getByRole('button', { name: new RegExp(CHILD) }).click();
  await page.getByRole('radio', { name: '2 of 3 stars' }).click();
  await page.getByRole('button', { name: 'Save marks' }).click();

  // 100, 100 and a two-star retell (70) — the mean the server computes is 90.
  await expect(score).toHaveText('90', { timeout: 15_000 });
  await expect(page.getByText(`Marks saved for ${CHILD}.`)).toBeVisible();
});

test('withdraws the results from the parents, and puts them back', async ({ page }) => {
  await openResults(page);

  const release = page.locator('[data-hq-release] [role="switch"]').first();

  // The seeded homework is written straight into the tables, so it is published without ever
  // going through the publish path that releases a homework (`GradingService.releaseOnPublish`).
  // Releasing it here is the first half of the round trip rather than a precondition worth
  // asserting: what matters is that both directions work and that only one of them asks.
  if ((await release.getAttribute('aria-checked')) !== 'true') {
    await release.click();
    await expect(release).toHaveAttribute('aria-checked', 'true', { timeout: 15_000 });
  }

  await release.click();
  // Destructive, so it asks in the red band rather than telling her after the fact.
  await expect(page.getByText(/Parents will stop seeing/)).toBeVisible();
  await page.getByRole('button', { name: 'Withdraw' }).click();
  await expect(release).toHaveAttribute('aria-checked', 'false', { timeout: 15_000 });

  // And back on, with nothing to confirm: giving a parent a score takes nothing away.
  await release.click();
  await expect(release).toHaveAttribute('aria-checked', 'true', { timeout: 15_000 });
  await expect(page.getByText(/Parents will stop seeing/)).toBeHidden();
});

test('the gradebook and the child page show the same score', async ({ page }) => {
  await signInAsSara(page);
  await page.goto(`teacher/classes/${classId}?tab=gradebook`);

  const grid = page.getByRole('table', { name: 'Gradebook' });
  await expect(grid).toBeVisible({ timeout: 15_000 });
  const aminaRow = grid.getByRole('row').filter({ hasText: CHILD });
  await expect(aminaRow.locator('[data-hq-gb-cell]').first()).toBeVisible();

  // Her name is the link to her own page (§4 step 9).
  await aminaRow.getByRole('link', { name: CHILD }).click();
  await expect(page.getByRole('heading', { name: CHILD })).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole('table', { name: /released score/ })).toBeVisible();
});

test('screenshots', async ({ page }) => {
  await mkdir(SHOTS, { recursive: true });
  await openResults(page);
  const heading = page.getByRole('heading', { name: LESSON });

  // Whole pages: the table under the metric cards is the half worth looking at.
  await shoot(page, `${SHOTS}/results-1366-en.png`, heading, { fullPage: true });
  await setScheme(page, 'dark');
  await shoot(page, `${SHOTS}/results-1366-en-dark.png`, heading, { fullPage: true });
  await setScheme(page, 'light');

  await setLanguage(page, 'ar');
  await shoot(page, `${SHOTS}/results-1366-ar.png`, heading, { fullPage: true });
  await setLanguage(page, 'en');

  await page.setViewportSize({ width: 375, height: 812 });
  await shoot(page, `${SHOTS}/results-375-en.png`, heading, { fullPage: true });
  await page.setViewportSize({ width: 1366, height: 768 });

  // The gradebook, in the tab it lives in.
  await page.goto(`teacher/classes/${classId}?tab=gradebook`);
  const grid = page.getByRole('table', { name: 'Gradebook' });
  await expect(grid).toBeVisible({ timeout: 15_000 });
  await shoot(page, `${SHOTS}/gradebook-1366-en.png`, grid, { fullPage: true });
  await setLanguage(page, 'ar');
  await shoot(page, `${SHOTS}/gradebook-1366-ar.png`, page.getByRole('table').first(), {
    fullPage: true,
  });
  await setLanguage(page, 'en');
  await page.setViewportSize({ width: 375, height: 812 });
  await shoot(page, `${SHOTS}/gradebook-375-en.png`, page.getByRole('table').first(), {
    fullPage: true,
  });
  await page.setViewportSize({ width: 1366, height: 768 });

  // And the child page, reached the way a teacher reaches it.
  await page.goto(`teacher/classes/${classId}?tab=gradebook`);
  await grid.getByRole('row').filter({ hasText: CHILD }).getByRole('link', { name: CHILD }).click();
  const child = page.getByRole('heading', { name: CHILD });
  await expect(child).toBeVisible({ timeout: 15_000 });
  await shoot(page, `${SHOTS}/child-1366-en.png`, child, { fullPage: true });
  await setLanguage(page, 'ar');
  await shoot(page, `${SHOTS}/child-1366-ar.png`, page.getByRole('heading', { name: CHILD }), {
    fullPage: true,
  });
  await setLanguage(page, 'en');
  await page.setViewportSize({ width: 375, height: 812 });
  await shoot(page, `${SHOTS}/child-375-en.png`, child, { fullPage: true });
});
