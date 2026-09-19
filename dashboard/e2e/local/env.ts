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
import { expect, request, type Locator, type Page } from '@playwright/test';

export const API = process.env['E2E_BASE_URL'] ?? process.env['HQ_API'] ?? 'http://localhost:18080';

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
 * Puts the browser on the sign-in screen with no session behind it.
 *
 * `localStorage.clear()` needs an origin, hence the first `goto` — a clear on `about:blank`
 * throws. And one clear is not always enough: `/sign-in` bounces a signed-in browser to its Home,
 * and that Home writes the session back while it loads, so a clear followed by a single
 * navigation can land on the Home again with the token restored. Clearing until the form is
 * actually on screen is the only version of this that does not flake when one test signs in as
 * two people in a row.
 */
async function openSignIn(page: Page): Promise<void> {
  // On a *static* file of the same origin, so nothing is running that could write the session
  // back. `localStorage.clear()` needs an origin, so it cannot be done on `about:blank`; done on
  // a dashboard route instead, the app is alive underneath it — `/sign-in` bounces a signed-in
  // browser to its Home, the Home refreshes the token, and `hq.refresh` is back in storage before
  // the next navigation reads it. That is what made a test signing in as two people in a row land
  // on the first one's Home with no form to fill in.
  await page.goto('assets/i18n/en.json');
  await page.evaluate(() => localStorage.clear());
  await page.goto('sign-in');
  await expect(
    page.getByLabel('Email'),
    'the sign-in form never appeared — is a session still stored?',
  ).toBeVisible();
}

/**
 * Sign-in through the screen, tour dismissed.
 *
 * The tour is modal, so nothing below it is clickable until Skip has been pressed.
 */
export async function signIn(page: Page, who: Account): Promise<void> {
  await openSignIn(page);
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

/**
 * Takes a screenshot only once there is something on screen worth photographing.
 *
 * The render barrier is the point. `document.getAnimations()` being quiet is trivially true
 * *before* Angular paints at all — nothing is animating because nothing exists — so a settle on
 * animations alone let four screenshots through as identical 4 255-byte blank grey frames, and
 * they were committed. The barrier here is, in order:
 *
 *   1. `marker` visible — the screen's own heading, table, dialog or grid, passed by every
 *      caller, so the wait is on the content of *this* page rather than on a sign of life;
 *   2. the main landmark grown to a real height, which rules out a painted shell with nothing
 *      under it (its *first child* will not do: Angular leaves a zero-size `<router-outlet>`
 *      there and the screen is its sibling);
 *   3. `document.fonts.ready`, so no frame is captured mid-swap with fallback metrics;
 *   4. and only then the animations, so count-ups and list staggers have finished.
 *
 * The byte-length assertion at the end is the backstop. The blank frames were 4 255 bytes; the
 * sparsest real screen here, sign-in, is about 19 kB. 10 kB sits between them with roughly a
 * factor of two either way, which is the most a size check can honestly claim.
 */
export async function shoot(
  page: Page,
  path: string,
  marker: Locator,
  options: { readonly fullPage?: boolean } = {},
): Promise<void> {
  await expect(marker, `nothing to photograph for ${path}`).toBeVisible({ timeout: 30_000 });
  await page.waitForFunction(
    () => (document.querySelector('main')?.getBoundingClientRect().height ?? 0) > 200,
    undefined,
    { timeout: 30_000 },
  );
  await page.evaluate(() => document.fonts.ready);
  await page.waitForFunction(
    () => document.getAnimations().every((animation) => animation.playState !== 'running'),
    undefined,
    { timeout: 10_000 },
  );

  // A document that scrolls sideways is photographed whole.
  //
  // Chrome's *viewport* capture takes its origin from the scrollable area rather than from the
  // layout viewport, and in an RTL document that range runs from negative to zero — so a class
  // page at 375 px, laid out correctly and correct on screen, came out as empty ground with a
  // 40 px sliver of the shell at one edge, identically on every run. The full-page path does
  // not have that bug, and on a screen whose content is wider than the viewport it is the more
  // useful picture in any case: what is off the edge is what one wants to see.
  //
  // The horizontal overflow itself is a screen's own business — at the time of writing it is
  // the class calendar under about 900 px — and this only decides how to photograph it.
  const wider = await page.evaluate(() => {
    const root = document.scrollingElement;
    return root !== null && root.scrollWidth > root.clientWidth + 1;
  });
  const shot = await page.screenshot({ path, fullPage: options.fullPage === true || wider });

  expect(
    shot.byteLength,
    `${path} is a blank frame (${shot.byteLength} bytes) — the screen had not painted`,
  ).toBeGreaterThan(10_000);
}

/**
 * Puts the dashboard in `scheme`, through the header control rather than through storage.
 *
 * The control is found by `data-hq-scheme-toggle` and not by its accessible name, because every
 * caller here photographs the same screen in Arabic too and the name changes with the language.
 */
export async function setScheme(page: Page, scheme: 'light' | 'dark'): Promise<void> {
  const toggle = page.locator('[data-hq-scheme-toggle]');
  const isDark = (await toggle.getAttribute('aria-pressed')) === 'true';
  if (isDark !== (scheme === 'dark')) await toggle.click();
  await expect(toggle).toHaveAttribute('aria-pressed', String(scheme === 'dark'));
}

/**
 * Switches the dashboard's language through the header's own control.
 *
 * T2 moved the two language rows out of the account menu and gave them a control of their own
 * beside the scheme toggle (spec §2 "Header"), so a spec that reaches for the account menu to
 * find Arabic no longer finds it there. The trigger is found by `data-hq-language` for the same
 * reason `setScheme` uses an attribute: its accessible name is itself translated.
 *
 * The two options are named in their own language in both bundles — "English" and "العربية" —
 * so the row to click does not depend on which language is on.
 */
export async function setLanguage(page: Page, language: 'en' | 'ar'): Promise<void> {
  const html = page.locator('html');
  if ((await html.getAttribute('lang')) === language) return;
  await page.locator('[data-hq-language]').click();
  await page.getByRole('menuitem', { name: language === 'ar' ? 'العربية' : 'English' }).click();
  await expect(html).toHaveAttribute('dir', language === 'ar' ? 'rtl' : 'ltr');
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
