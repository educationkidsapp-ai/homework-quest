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
import { expect, request, test as base, type Locator, type Page, type TestInfo } from '@playwright/test';

export const API = process.env['E2E_BASE_URL'] ?? process.env['HQ_API'] ?? 'http://localhost:18080';

/**
 * Noise the gate below is allowed to ignore, each with the reason it is not ours to fix.
 *
 * The list is short on purpose and every entry names something outside `src/app/**`. Anything
 * the dashboard itself logs — an `NG0` warning, a failed subscription, a template error — is a
 * defect to report, not an entry here. A pattern that stops matching anything is dead weight:
 * delete it rather than leave it as cover for a future regression.
 */
const ALLOWED_NOISE: readonly { readonly pattern: RegExp; readonly why: string }[] = [
  {
    pattern: /Download the Angular DevTools/i,
    why: "Angular's own development-mode banner; it is a console.log in dev builds and never ships",
  },
  {
    pattern: /\[Violation\]|Forced reflow/i,
    why: 'Chrome performance advisories, not errors — emitted by the scheduler, not by the app',
  },
  {
    // Narrow on purpose: this status, on this one route. A 429 anywhere else, or any other
    // status here, still fails.
    pattern: /status of 429 .*\(https?:\S*\/schools\/logo\)/,
    why:
      "the server's own throttle, not the dashboard: `POST /schools/logo` is the sign-in page's " +
      'unauthenticated school lookup and it sits in a per-email/per-IP bucket ' +
      '(`server/.../SchoolController.java:117`). One suite run signs in a dozen times from one ' +
      'address, so the bucket empties; the screen handles the refusal correctly by drawing no ' +
      'logo, and what Chrome logs is the response, which no application code can suppress.',
  },
];

/**
 * Noise one test expects, declared by that test.
 *
 * Some tests are *about* a refusal — `shell.spec.ts` types a wrong password to prove the screen
 * answers with a band rather than a toast, and the 401 it asks for is the whole point. That is
 * not a defect and it is not global noise either, so it is declared where it happens instead of
 * widening `ALLOWED_NOISE` for every other test in the directory.
 */
const EXPECTED: WeakMap<TestInfo, RegExp[]> = new WeakMap();

/**
 * "This test deliberately causes that console error." Call it inside the test, before or after
 * the thing that logs — the gate filters at teardown, so the order does not matter.
 */
export function expectConsoleError(pattern: RegExp, why: string): void {
  const info = base.info();
  EXPECTED.set(info, [...(EXPECTED.get(info) ?? []), pattern]);
  info.annotations.push({ type: 'expected-console-error', description: `${pattern.source} — ${why}` });
}

function isAllowed(text: string, testInfo: TestInfo): boolean {
  if (ALLOWED_NOISE.some((entry) => entry.pattern.test(text))) return true;
  return (EXPECTED.get(testInfo) ?? []).some((pattern) => pattern.test(text));
}

/**
 * The browser console, read as a test result.
 *
 * Every test in this directory gets this for free: `console.error`, any `console.warn` carrying
 * an `NG0` code, and an uncaught `pageerror` are collected for the whole test and asserted empty
 * at teardown. That is T4's console gate — the owner's "no console errors, no NG0xxx warnings"
 * turned into something that fails a run rather than something somebody has to remember to look
 * at. Angular's runtime warnings are the interesting half: `NG0100` (expression changed after it
 * was checked), `NG0913` (an image without dimensions), `NG0955` (a duplicate `track`) are all
 * warnings that a screen renders straight through, so no assertion about the screen would ever
 * see them.
 *
 * Two deliberate choices:
 *
 * - **Only `NG0` warnings**, not every warning. A `console.warn` from a library is not this
 *   suite's business, and a gate that fires on everything is a gate somebody disables.
 * - **Silent when the test already failed.** A failing test usually leaves a broken page behind
 *   it, and the console it then fills is the symptom, not the cause; reporting both puts the
 *   wrong one at the bottom of the log where the eye lands. The gate speaks only for a test that
 *   otherwise passed.
 */
async function withConsoleGate(page: Page, testInfo: TestInfo, run: () => Promise<void>): Promise<void> {
  // Everything is collected and filtered at the end rather than as it arrives, so a test may
  // declare what it expects with `expectConsoleError` at any point in its body.
  const seen: string[] = [];
  const note = (text: string) => void seen.push(text);

  page.on('console', (message) => {
    const type = message.type();
    // The URL is carried along because Chrome's own text for a bad response — "Failed to load
    // resource: the server responded with a status of …" — never says *which* resource, and an
    // allow-list entry that cannot name the route is a blanket one.
    const where = message.location().url;
    const text = where ? `${message.text()} (${where})` : message.text();
    if (type === 'error') note(`console.error — ${text}`);
    else if (type === 'warning' && text.includes('NG0')) note(`console.warn (Angular) — ${text}`);
  });
  page.on('pageerror', (error) => note(`pageerror — ${error.stack ?? error.message}`));

  await run();

  if (testInfo.status === 'failed' || testInfo.status === 'timedOut') return;
  const noise = seen.filter((text) => !isAllowed(text, testInfo));
  expect(
    noise,
    `the browser console was not clean during "${testInfo.title}" — each line below is a defect in the dashboard, not in the test (add to ALLOWED_NOISE in e2e/local/env.ts only with a reason that names something outside src/app/**)`,
  ).toEqual([]);
}

/**
 * The suite's `test`. Every spec in `e2e/local` imports it from here rather than from
 * `@playwright/test`, which is what puts the console gate on all of them at once.
 */
export const test = base.extend<{ consoleGate: void }>({
  consoleGate: [
    async ({ page }, use, testInfo) => {
      await withConsoleGate(page, testInfo, () => use());
    },
    { auto: true },
  ],
});

export { expect };

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

/**
 * R5: the full seed's Math coordinator (`seed/coordinators.csv`, Rasha Kamal).
 *
 * Her scope row names no curriculum, so she supervises Math on **both** tracks (DR1) — which is
 * why `coordinator-area.spec.ts` asserts "both tracks" rather than a track name. The acceptance
 * seed has a different pair (`coord.math@test.com`, British only) and no spec here runs on it.
 */
export const COORDINATOR: Account = {
  email: 'coordinator.math@school.test',
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

/** One row of `GET /teacher/classes` — the same shape the My classes cards are built from. */
export interface TeacherClass {
  readonly classId: string;
  readonly className: string;
  readonly subject: string;
  readonly curriculum: string;
  readonly grade: number;
  readonly todayLessonId: string | null;
  readonly todayStatus: string | null;
}

/** Sara's sections, on her own token. */
export async function teacherClasses(who: Account = SARA): Promise<readonly TeacherClass[]> {
  const api = await request.newContext({ baseURL: API });
  try {
    const response = await api.get('/teacher/classes', {
      headers: { Authorization: `Bearer ${await signInForToken(who)}` },
    });
    expect(response.ok(), `GET /teacher/classes: HTTP ${response.status()}`).toBeTruthy();
    return (await response.json()) as readonly TeacherClass[];
  } finally {
    await api.dispose();
  }
}

/**
 * A section of hers with **nothing on today**, so the card offers "Add today's lesson".
 *
 * Read rather than hard-coded. `my-classes.spec.ts` used to name 1B and say in a comment that
 * "the seed leaves 1B with no lesson at all" — true of the local H2 seed, false of QA, whose
 * database is shared and never reset. Both of Sara's sections had a lesson dated today there, so
 * `@if (card.status === 'none')` (`my-classes.page.html:53`) was correctly false, the button was
 * correctly absent, and the test waited fifteen seconds for it on every deploy.
 *
 * When every section is taken, this frees one rather than giving up, and it is careful about
 * which: only a lesson the suite itself left behind. A row the server created with no title
 * reads back as `Untitled lesson`, and no seed writes one — every seeded lesson is named
 * ("The sh sound + sight words"). QA was carrying five of them, from runs that predate the
 * per-run title tag `removeLessonsOfThisRun` cleans by. Anything else is left alone and the
 * failure names what it found, because a spec that deletes seed data to make itself pass is
 * worse than a spec that fails.
 */
export async function classFreeToday(): Promise<TeacherClass> {
  const rows = await teacherClasses();
  const free = rows.find((row) => row.todayLessonId === null);
  if (free) return free;

  const api = await request.newContext({ baseURL: API });
  try {
    const staff = { Authorization: `Bearer ${await signInForToken(SARA)}` };
    for (const row of rows) {
      const lesson = await api.get(`/teacher/lessons/${row.todayLessonId}`, { headers: staff });
      if (!lesson.ok()) continue;
      const title = ((await lesson.json()) as { title?: string | null }).title ?? null;
      if (title !== null && title !== 'Untitled lesson') continue;

      // Unpublish first: `DELETE /admin/lessons/{id}` is a 409 while the lesson is live.
      await api.post(`/teacher/lessons/${row.todayLessonId}/unpublish`, { headers: staff });
      const gone = await api.delete(`/admin/lessons/${row.todayLessonId}`, {
        headers: { Authorization: `Bearer ${await signInForToken(ADMIN)}` },
      });
      if (gone.ok()) return { ...row, todayLessonId: null, todayStatus: null };
    }
  } finally {
    await api.dispose();
  }

  throw new Error(
    `every one of Sara's sections (${rows.map((r) => `${r.className}: ${r.todayStatus}`).join(', ')}) has a lesson today, and none of them is an untitled leftover this suite may remove. Free one by hand, or teach this helper about the row it found.`,
  );
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

  // Motion off for the capture — a CSS *transition* cannot reliably be waited out.
  //
  // `document.getAnimations()` does include `CSSTransition`s, but only once they have started,
  // and a transition begins on the style recalc *after* the change that triggers it lands. So
  // the check below is trivially true in the gap between the two, and a frame taken in that gap
  // catches a colour on its way. Measured rather than assumed: a capture of the class page's
  // chips immediately after the tab is clicked shows the selected chip a washed pink, halfway
  // from `surface` to the accent. Polling harder only narrows the window; nothing closes it.
  //
  // `prefers-reduced-motion: reduce` closes it. `m.reduced-motion` (`_mixins.scss:14`) answers
  // the media query as well as the attribute, so every `motion-safe` transition collapses to
  // `transition-property: opacity` and the property snaps to its resting value — including one
  // already in flight, which is cancelled. That is the state a screenshot is meant to show.
  // Restored afterwards, because the tests that *assert* motion (the rail's width poll in
  // `theme-shell.spec.ts`, the row collapse, the undo strip) share this page.
  //
  // This is **not** what made the selected chip unreadable in the committed `class-*` frames;
  // that was a specificity bug in `ui/tabs/tabs.component.ts`, fixed there. Chasing it is how
  // this window came to light.
  await page.emulateMedia({ reducedMotion: 'reduce' });
  try {
    await page.waitForFunction(
      () => document.getAnimations().every((animation) => animation.playState !== 'running'),
      undefined,
      { timeout: 10_000 },
    );
    // Two frames, so the recalc the line above triggered has been through style *and* paint.
    await page.evaluate(
      () =>
        new Promise<void>((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
        ),
    );
    await capture(page, path, options);
  } finally {
    await page.emulateMedia({ reducedMotion: null });
  }
}

/** The photograph itself, once `shoot` has decided the screen is ready to be photographed. */
async function capture(
  page: Page,
  path: string,
  options: { readonly fullPage?: boolean },
): Promise<void> {
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

/**
 * Adds one stop through CR2's Add stop form, and waits until the list has it.
 *
 * Here rather than in a spec because CR2 (#90) replaced the twenty-two-template menu with this
 * form and `lesson-publish.spec.ts` was still clicking a `menuitem` that no longer exists — a
 * second copy of "how a stop is added" that nothing kept honest. `add-stop.spec.ts` is the file
 * that *tests* the form and keeps its own longer walk through it; this is the one every other
 * spec uses when a stop is merely a precondition.
 *
 * The trigger is found by attribute, not by name, for the same reason `setLanguage` is: the
 * button's accessible name is translated, and a caller may be in Arabic.
 */
export async function addStopThroughForm(
  page: Page,
  stop: { readonly title: string; readonly question: string; readonly type: string },
): Promise<void> {
  const rows = page.getByRole('listbox', { name: 'Stops' }).getByRole('option');
  const before = await rows.count();

  await page.locator('[data-hq-add-stop-trigger] button').click();
  const form = page.locator('[data-hq-add-stop]');
  await expect(form).toBeVisible();
  await form.getByLabel('Title', { exact: true }).fill(stop.title);
  await form.getByLabel('Type', { exact: true }).selectOption(stop.type);
  // E4b: on the five types the sheet writes out itself, the paragraph is behind this switch. A
  // stop that is only a precondition wants one way in for all twenty-two, so this takes it —
  // `add-stop.spec.ts` is where the structured fields and their single request are tested.
  const toWords = form.getByRole('button', { name: 'Let the assistant write it from my words' });
  if ((await toWords.count()) > 0) await toWords.click();
  await form.getByLabel('Question / what the child does', { exact: true }).fill(stop.question);
  await page.getByRole('button', { name: 'Save the question' }).click();

  // The one toast in this system: a success, politely announced, with no Undo on it.
  await expect(page.getByText('Question added')).toBeVisible({ timeout: 30_000 });
  await expect(form).toBeHidden();
  await expect(rows).toHaveCount(before + 1, { timeout: 30_000 });
}

/** Sunday to Thursday, as `Date.getUTCDay()` numbers them — see {@link schoolDayFromNow}. */
const TEACHING_DAYS = new Set([0, 1, 2, 3, 4]);

/**
 * The `days`-th **teaching day** from today, as an ISO date — the format every lesson date field
 * uses, and the only kind of date a lesson may carry.
 *
 * <p>`POST /teacher/lessons` and `PATCH /teacher/lessons/{id}` refuse a day the school does not
 * teach on with a 409 `not_teaching_day`, so plain "today plus N" is the wrong arithmetic here:
 * two of every seven offsets land on a Friday or a Saturday, and a suite built on them fails on
 * those days and passes on the others. Counting teaching days gives every spec what it was
 * actually asking for — consecutive, distinct, empty cells on a stretch this run owns — and can
 * never produce a date the server will refuse. `BASE + 1` is the next column of the grid rather
 * than the next square on a calendar, which is what the week view draws anyway.
 *
 * <p>The week is hard-coded: it is the server's `SchoolCalendar.DEFAULT_WEEK` and what the
 * one-school seed runs. Reading it from `GET /platform-settings` would have to be awaited, and
 * every spec here fixes its days in a module-level `const` that cannot await anything. A seed
 * that ever moves off the Gulf week changes this one line.
 */
export function schoolDayFromNow(days: number): string {
  const day = new Date();
  while (!TEACHING_DAYS.has(day.getUTCDay())) day.setUTCDate(day.getUTCDate() + 1);
  for (let found = 0; found < days; found += 1) {
    do {
      day.setUTCDate(day.getUTCDate() + 1);
    } while (!TEACHING_DAYS.has(day.getUTCDay()));
  }
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
