/**
 * What every spec in this directory needs before it can talk to anything: where the API is, who
 * the seeded people are, and how a teacher gets past the sign-in screen.
 *
 * The API base matters more than it looks. The suite runs against two targets — a local H2
 * server behind `e2e/local/serve.mjs` (`HQ_API`, default `localhost:18080`) and the deployed QA
 * API (`E2E_BASE_URL`, `playwright.config.ts`) — and a spec that reaches for `request.newContext`
 * to set something up has to follow the target the browser is on. Until N2.5 three specs read
 * `HQ_API` alone, so against QA their `beforeAll` posted a sign-in to `localhost:18080` and every
 * test in the file failed before it opened a page. One constant, both targets.
 *
 * Passwords are read from the environment, never written down, and a missing one fails with the
 * variable's name rather than its value. `E2E_STAFF_PASSWORD` is the seeded staff password —
 * `SEED_STAFF_PASSWORD` on the server side; the two must be the same value wherever the suite
 * runs, including the `qa` GitHub environment.
 */
import { expect, request, type Page } from '@playwright/test';

export const API =
  process.env['E2E_BASE_URL'] ?? process.env['HQ_API'] ?? 'http://localhost:18080';

export function env(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set — see dashboard/e2e/local/README.md`);
  return value;
}

export interface Account {
  readonly email: string;
  readonly password: string;
}

/** `seed/teachers.csv` + `seed/assignments.csv`: Sara has 1A and 1B British Math, Omar 3A/3B. */
export const SARA: Account = {
  email: 'sara.al-harbi@school.test',
  get password() {
    return env('E2E_STAFF_PASSWORD');
  },
};

export const OMAR: Account = {
  email: 'omar.nasser@school.test',
  get password() {
    return env('E2E_STAFF_PASSWORD');
  },
};

export const ADMIN: Account = {
  email: process.env['E2E_ADMIN_EMAIL'] ?? 'admin@quest.local',
  get password() {
    return env('E2E_ADMIN_PASSWORD');
  },
};

/** A bearer token for one of the accounts above, for the setup a screen cannot do. */
export async function signInForToken(who: Account): Promise<string> {
  const api = await request.newContext({ baseURL: API });
  try {
    const response = await api.post('/admin/auth/sign-in', {
      data: { email: who.email, password: who.password },
    });
    expect(
      response.ok(),
      `${who.email} could not sign in against ${API} (HTTP ${response.status()}) — is E2E_STAFF_PASSWORD the seeded staff password?`,
    ).toBeTruthy();
    return ((await response.json()) as { token: string }).token;
  } finally {
    await api.dispose();
  }
}

/**
 * Sign-in through the screen, tour dismissed.
 *
 * `localStorage.clear()` needs an origin, hence the first `goto` — a clear on `about:blank`
 * throws. The tour is modal, so nothing below it is clickable until Skip has been pressed.
 */
export async function signIn(page: Page, who: Account): Promise<void> {
  await page.goto('sign-in');
  await page.evaluate(() => localStorage.clear());
  await page.goto('sign-in');
  await page.getByLabel('Email').fill(who.email);
  await page.getByLabel('Password').fill(who.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  const skip = page.getByRole('button', { name: 'Skip' });
  await skip.waitFor({ state: 'visible', timeout: 30_000 });
  await skip.click();
  await expect(page.getByRole('dialog').first()).toBeHidden();
}

export function signInAsSara(page: Page): Promise<void> {
  return signIn(page, SARA);
}

/** An ISO day `days` from today, in UTC — the format every lesson date field uses. */
export function dayFromNow(days: number): string {
  const day = new Date();
  day.setUTCDate(day.getUTCDate() + days);
  return day.toISOString().slice(0, 10);
}

/**
 * A short, unique-per-run tag.
 *
 * QA's database is shared and never reset, so nothing this suite creates may collide with what
 * the last run left behind — every title carries this, and every date is offset by it.
 */
export const RUN = Date.now().toString(36).slice(-5).toUpperCase();

/**
 * Deletes the lessons this run created, and only those.
 *
 * Unpublished as Sara, then deleted as the Admin. Two refusals shape this: a teacher may remove
 * only a draft or a failed lesson (409 by design — §8) and most of what this suite makes is a
 * `review` or a `published` one; and `DELETE /admin/lessons/{id}` is itself a 409 while the
 * lesson is live. The list is read with Sara's token so the search stays inside her two sections,
 * and only titles carrying `RUN` are touched, so the seed and any parallel run are safe. Best
 * effort throughout: tidying up is not an assertion, and a red `afterAll` would hide a green run.
 */
export async function removeLessonsOfThisRun(): Promise<void> {
  const api = await request.newContext({ baseURL: API });
  try {
    const hers = await api.get('/teacher/lessons', {
      headers: { Authorization: `Bearer ${await signInForToken(SARA)}` },
    });
    if (!hers.ok()) return;
    const mine = ((await hers.json()) as { id: string; title?: string }[]).filter((lesson) =>
      lesson.title?.includes(RUN),
    );
    if (mine.length === 0) return;

    const staff = { Authorization: `Bearer ${await signInForToken(SARA)}` };
    const admin = { Authorization: `Bearer ${await signInForToken(ADMIN)}` };
    for (const lesson of mine) {
      // Unpublish first: `DELETE /admin/lessons/{id}` answers 409 for a live lesson, so a run
      // that ended on a published copy would otherwise leave both copies on QA for good.
      await api.post(`/teacher/lessons/${lesson.id}/unpublish`, { headers: staff });
      await api.delete(`/admin/lessons/${lesson.id}`, { headers: admin });
    }
  } catch {
    // never fail a run on the cleanup
  } finally {
    await api.dispose();
  }
}
