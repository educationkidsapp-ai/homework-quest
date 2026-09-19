import { request, type Locator, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import {
  addStopThroughForm,
  API,
  expect,
  removeLessonsOfThisRun,
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
 * T4's acceptance: the **whole teacher flow**, walked once as Sara, in both schemes and at all
 * three widths — the owner's own list for the TailAdmin restyle (T1 #79, T2 #80, T3 #81).
 *
 *     sign in → This week (her Home) → My classes → a class (Calendar and Children) →
 *     All lessons → New lesson → the lesson editor (stop editor, parent panel, publish sheet) →
 *     Profile → sign out
 *
 * Four tests, in order, because each hands the next something it needs:
 *
 *   1. **the walk** — every screen opened the way she opens it, and every form on the path
 *      submitted exactly once: the lesson created, a stop saved, the parent panel saved, the day
 *      moved, the publish sheet confirmed, the language switched back and forth on Profile. A
 *      screenshot proves a screen was drawn; only a submit proves it still works.
 *   2. **1366, light and dark** — per screen: its heading, no sideways *page* scroll, its
 *      primary action on screen, and in dark mode the contrast of the body text against the
 *      surface it sits on, computed in the page from `getComputedStyle` rather than from the
 *      token file. §5 rewrites eleven colour roles for dark mode; the arithmetic is the only
 *      thing that can say whether the result is readable.
 *   3. **768 and 375, light** — the same four assertions where the shell becomes a drawer and
 *      the grids change shape.
 *   4. **the screenshot set** — eight screens × EN/AR × light/dark at 1366 (32 frames), plus
 *      English light at 768 and 375 (16), into `docs/screenshots/theme-t4/`.
 *
 * What this adds over `theme-kit.spec.ts` (T3's own): that file proves the *kit* — no page
 * scrolls sideways, and a picture of each screen. This one walks the flow as a person, submits
 * the forms, and puts a number on dark mode. Both run; neither replaces the other.
 *
 * The console gate in `env.ts` applies here as it does to every file in this directory, so
 * "no console errors and no NG0xxx warnings" is asserted on every screen below without a line
 * of its own.
 *
 *     pnpm e2e:local theme-flow          # H2, one-school seed — see e2e/local/README.md
 *     E2E_BASE_URL=<api> … pnpm e2e:qa   # the same file against the deployment
 */
const SHOTS = resolve(process.cwd(), '../docs/screenshots/theme-t4');

const WIDE = { width: 1366, height: 768 };
const TABLET = { width: 768, height: 1024 };
const PHONE = { width: 375, height: 812 };

const TITLE = `Shapes and sides ${RUN}`;
/** A stretch of teaching days this run owns: one lesson per day per class, and QA is never reset. */
const BASE = 400 + (Math.floor(Date.now() / 1000) % 40);
const LESSON_DAY = schoolDayFromNow(BASE);
const MOVED_DAY = schoolDayFromNow(BASE + 1);

/** WCAG AA for body text. The spec's own floor, and the one the owner asked dark mode to meet. */
const AA_NORMAL = 4.5;

let classId = '';
let className = '';
let siblingName = '';
let lessonPath = '';

test.describe.configure({ mode: 'serial' });

/** Sara's own sections, on her own token — 1A British Math and its sibling 1B. */
test.beforeAll(async () => {
  const token = await signInForToken(SARA);
  const api = await request.newContext({ baseURL: API });
  try {
    const response = await api.get('/teacher/classes', {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(response.ok(), `GET /teacher/classes: HTTP ${response.status()}`).toBeTruthy();
    const rows = (await response.json()) as {
      classId: string;
      className: string;
      subject: string;
    }[];
    const math = rows.filter((row) => row.subject === 'math');
    const own = math.find((row) => /1A/i.test(row.className));
    const sibling = math.find((row) => /1B/i.test(row.className));
    expect(
      own && sibling,
      `the seed should give Sara 1A and 1B Math; she has ${rows.map((r) => `${r.className}/${r.subject}`).join(', ')}`,
    ).toBeTruthy();
    classId = own!.classId;
    className = own!.className;
    siblingName = sibling!.className;
  } finally {
    await api.dispose();
  }
});

test.afterAll(removeLessonsOfThisRun);

// ---------------------------------------------------------------------------------------------
// The eight screens
// ---------------------------------------------------------------------------------------------

interface Screen {
  readonly name: string;
  readonly open: () => Promise<void>;
  /** The element that says this screen has finished painting — `shoot`'s render barrier. */
  readonly marker: () => Locator;
  /**
   * The one thing she came to this screen to do, found by the class the kit draws it with
   * rather than by its label: the screenshot pass runs in Arabic too, and `btn--primary` is
   * the same string in both languages.
   */
  readonly primary: () => Locator;
}

function newLessonQuery(date: string): string {
  return new URLSearchParams({ classId, subject: 'math', date }).toString();
}

/**
 * The eight screens a teacher has, each opened the way the dashboard actually routes to it.
 *
 * **There is no separate teacher Home.** `screens.ts:111` makes `/teacher` a redirect to
 * `week` — "Her Home *is* This week, so `/teacher` redirects rather than drawing a second
 * landing" — so This week is both the first screen of the walk and her home. `/teacher/home`
 * matches no route at all and renders the not-found screen; T3's own spec photographs it as
 * `home-*.png`, which is reported rather than repeated here.
 *
 * The class page counts as two, because its two tabs are two screens with different content
 * and different failure modes: a month grid that has to scroll inside its card, and a roster
 * table that has to do the same.
 */
function screens(page: Page): readonly Screen[] {
  const heading = () => page.getByRole('heading', { level: 1 });
  // By position, not by name: the screenshot pass runs in Arabic, and `class.page.ts:147` fixes
  // the order — Calendar, Children, Gradebook, Exams.
  const openClass = async (tab: 0 | 1) => {
    await page.goto(`teacher/classes/${classId}?subject=math`);
    const tabs = page.getByRole('tab');
    await expect(tabs.nth(tab)).toBeVisible({ timeout: 30_000 });
    await tabs.nth(tab).click();
  };
  return [
    {
      // Her Home. `screens.ts:111` redirects `/teacher` here rather than drawing a landing.
      name: 'week',
      open: async () => void (await page.goto('teacher/week')),
      marker: () => page.getByRole('grid'),
      primary: () => page.locator('.btn--primary').first(),
    },
    {
      name: 'my-classes',
      open: async () => void (await page.goto('teacher/classes')),
      marker: () => page.locator('hq-card').first(),
      // The class's own link, not the card's "Add today's lesson" primary button. That button
      // is drawn only `@if (card.status === 'none')` (`my-classes.page.html:53`) — a class that
      // already has a lesson today does not offer to make a second one — so on QA's shared
      // database, where every section has been given one by an earlier run, there is no
      // `.hq-linkbutton--primary` on this screen at all. The action that is always here is the
      // one she came for: opening a class.
      primary: () => page.locator('.my-classes__link').first(),
    },
    {
      name: 'class-calendar',
      open: () => openClass(0),
      marker: () => page.getByRole('grid'),
      primary: () => page.locator('.hq-linkbutton--primary').first(),
    },
    {
      name: 'class-children',
      open: () => openClass(1),
      marker: () => page.locator('hq-class-children'),
      primary: () => page.locator('.hq-linkbutton--primary').first(),
    },
    {
      name: 'lessons',
      open: async () => void (await page.goto('teacher/lessons?curriculum=british&grade=1')),
      marker: heading,
      primary: () => page.locator('.btn--primary').first(),
    },
    {
      name: 'new-lesson',
      open: async () => void (await page.goto(`teacher/lessons/new?${newLessonQuery(LESSON_DAY)}`)),
      marker: heading,
      primary: () => page.locator('.btn--primary').first(),
    },
    {
      name: 'lesson',
      open: async () => void (await page.goto(lessonPath)),
      marker: () => page.getByRole('heading', { level: 1, name: TITLE }),
      // Published by the time this runs, so the footer's control is Unpublish — a secondary
      // box. The footer itself is the screen's action area; its last control is the one.
      primary: () => page.locator('[page-footer]').getByRole('button').last(),
    },
    {
      name: 'profile',
      open: async () => void (await page.goto('profile')),
      marker: heading,
      // Profile has no primary action: name, photo and role are read-only (there is no
      // self-edit route), so the only control that does anything is Help's button.
      primary: () => page.locator('.btn--secondary').first(),
    },
  ];
}

// ---------------------------------------------------------------------------------------------
// The four assertions each screen answers
// ---------------------------------------------------------------------------------------------

/**
 * Has the layout settled? The same barrier `shoot` uses, minus the photograph.
 *
 * A skeleton, a count-up mid-flight or a font still swapping is a different layout from the one
 * an overflow or a contrast question is about.
 */
async function settled(page: Page): Promise<void> {
  await page.evaluate(() => document.fonts.ready);
  await page.waitForFunction(
    () => document.getAnimations().every((animation) => animation.playState !== 'running'),
    undefined,
    { timeout: 10_000 },
  );
}

/** Does the *document* scroll sideways? A pane that scrolls inside its card is the point (§2). */
async function pageScrollsSideways(page: Page): Promise<boolean> {
  await settled(page);
  return page.evaluate(() => {
    const root = document.scrollingElement;
    return root !== null && root.scrollWidth > root.clientWidth + 1;
  });
}

interface Contrast {
  readonly ratio: number;
  /** What was measured, for the failure message: the colours and the text that wore them. */
  readonly sample: string;
  readonly measured: number;
}

/**
 * The contrast of the screen's **body text on the surface under it**, computed in the page.
 *
 * Deliberately not read from `_theme.scss`. A token file says what was intended; only
 * `getComputedStyle` on a live element says what a teacher is looking at, and in dark mode §5
 * makes `--hq-color-ink` a *translucent* white (`rgb(255 255 255 / 0.9)`) over a `#171f2e`
 * card — so the answer depends on compositing, which no reading of the stylesheet gives you.
 * Every layer is therefore composited here, from the outermost opaque ancestor inwards, before
 * the ratio is taken.
 *
 * "Body text" is defined as the elements whose computed `color` is the primary ink role — found
 * by painting a probe element with `var(--hq-color-ink)` and reading what that resolves to, so
 * the match is on the role and not on a colour written down twice. The softer roles
 * (`--hq-color-ink-soft`, `--hq-color-ink-muted`) are *reported* but not asserted: they are
 * hints, placeholders and disabled text, and AA's 4.5 is the floor for body copy.
 */
async function bodyTextContrast(page: Page): Promise<Contrast> {
  await settled(page);
  return page.evaluate(() => {
    type Rgba = readonly [number, number, number, number];

    const parse = (colour: string): Rgba => {
      // `color-mix()` computes to `color(srgb r g b / a)` in Chrome, whose channels are 0–1 and
      // whose colour space would otherwise be read as a number.
      const space = colour.startsWith('color(');
      const parts =
        (space ? colour.replace(/^color\(\s*[\w-]+/, '') : colour).match(/[\d.]+/g)?.map(Number) ?? [];
      const scale = space ? 255 : 1;
      return [(parts[0] ?? 0) * scale, (parts[1] ?? 0) * scale, (parts[2] ?? 0) * scale, parts[3] ?? 1];
    };
    const channel = (value: number): number => {
      const v = value / 255;
      return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
    };
    const luminance = (rgb: readonly number[]): number =>
      0.2126 * channel(rgb[0]!) + 0.7152 * channel(rgb[1]!) + 0.0722 * channel(rgb[2]!);
    const over = (top: Rgba, bottom: readonly number[]): number[] =>
      [0, 1, 2].map((i) => top[3] * top[i]! + (1 - top[3]) * bottom[i]!);
    const ratio = (a: number, b: number): number => (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);

    /** Every painted background from the element up, composited bottom-up onto white. */
    const surfaceUnder = (element: Element): number[] => {
      const stack: Rgba[] = [];
      for (let node: Element | null = element; node !== null; node = node.parentElement) {
        const background = parse(getComputedStyle(node).backgroundColor);
        if (background[3] > 0) stack.push(background);
      }
      return stack.reduceRight<number[]>((under, layer) => over(layer, under), [255, 255, 255]);
    };

    // What `--hq-color-ink` actually resolves to right now, in this scheme, on this page.
    const probe = document.createElement('span');
    probe.style.color = 'var(--hq-color-ink)';
    probe.style.position = 'absolute';
    document.body.append(probe);
    const ink = getComputedStyle(probe).color;
    probe.remove();

    const main = document.querySelector('main') ?? document.body;
    const candidates = [...main.querySelectorAll<HTMLElement>('*')].filter((element) => {
      const text = [...element.childNodes]
        .filter((node) => node.nodeType === Node.TEXT_NODE)
        .map((node) => node.textContent ?? '')
        .join('')
        .trim();
      if (text.length < 2) return false;
      const box = element.getBoundingClientRect();
      if (box.width < 1 || box.height < 1) return false;
      const style = getComputedStyle(element);
      if (style.visibility === 'hidden' || style.opacity === '0') return false;
      return style.color === ink;
    });

    if (candidates.length === 0) {
      // No body text in the primary ink on this screen — fall back to the roles themselves, so
      // the assertion still has something true to say rather than silently passing.
      const surface = surfaceUnder(main);
      return {
        ratio: ratio(luminance(over(parse(ink), surface)), luminance(surface)),
        sample: `no element on this screen wears --hq-color-ink (${ink}); measured the role against the main surface`,
        measured: 0,
      };
    }

    let worst = { value: Infinity, note: '' };
    for (const element of candidates.slice(0, 60)) {
      const surface = surfaceUnder(element);
      const value = ratio(luminance(over(parse(ink), surface)), luminance(surface));
      if (value < worst.value) {
        const text = (element.textContent ?? '').trim().slice(0, 40);
        worst = {
          value,
          note: `"${text}" — ${ink} on rgb(${surface.map((c) => Math.round(c)).join(', ')})`,
        };
      }
    }
    return { ratio: worst.value, sample: worst.note, measured: candidates.length };
  });
}

/**
 * The contrast of a **colour role used as text**, composited on the surface of the card it sits
 * on, computed in the page.
 *
 * {@link bodyTextContrast} above measures `--hq-color-ink` where a teacher is actually reading
 * it. These three are the roles §4.5 of `docs/reports/tailadmin-restyle.md` measured by hand and
 * found short in dark mode, so they are asked the same question directly — a role is a promise
 * the theme makes whether or not this screen happens to be wearing it, and two of the three are
 * derived at runtime (`color-mix` on a school's own accent), which no reading of `_theme.scss`
 * would resolve.
 */
async function roleContrast(page: Page, roles: readonly string[]): Promise<Record<string, number>> {
  await settled(page);
  return page.evaluate((properties: readonly string[]) => {
    type Rgba = readonly [number, number, number, number];
    const parse = (colour: string): Rgba => {
      // `color-mix()` computes to `color(srgb r g b / a)` in Chrome, whose channels are 0–1 and
      // whose colour space would otherwise be read as a number.
      const space = colour.startsWith('color(');
      const parts =
        (space ? colour.replace(/^color\(\s*[\w-]+/, '') : colour).match(/[\d.]+/g)?.map(Number) ?? [];
      const scale = space ? 255 : 1;
      return [(parts[0] ?? 0) * scale, (parts[1] ?? 0) * scale, (parts[2] ?? 0) * scale, parts[3] ?? 1];
    };
    const channel = (value: number): number => {
      const v = value / 255;
      return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
    };
    const luminance = (rgb: readonly number[]): number =>
      0.2126 * channel(rgb[0]!) + 0.7152 * channel(rgb[1]!) + 0.0722 * channel(rgb[2]!);
    const over = (top: Rgba, bottom: readonly number[]): number[] =>
      [0, 1, 2].map((i) => top[3] * top[i]! + (1 - top[3]) * bottom[i]!);

    // A real card if the screen has one, the page ground if it does not — the darker of the
    // two grounds is the card, so this is the harder of the two questions.
    const host = document.querySelector('hq-card') ?? document.querySelector('main') ?? document.body;
    const stack: Rgba[] = [];
    for (let node: Element | null = host; node !== null; node = node.parentElement) {
      const background = parse(getComputedStyle(node).backgroundColor);
      if (background[3] > 0) stack.push(background);
    }
    const surface = stack.reduceRight<number[]>((under, layer) => over(layer, under), [255, 255, 255]);
    const below = luminance(surface);

    const probe = document.createElement('span');
    probe.style.position = 'absolute';
    host.append(probe);
    const answers: Record<string, number> = {};
    for (const property of properties) {
      probe.style.color = `var(${property})`;
      const above = luminance(over(parse(getComputedStyle(probe).color), surface));
      answers[property] = (Math.max(above, below) + 0.05) / (Math.min(above, below) + 0.05);
    }
    probe.remove();
    return answers;
  }, roles);
}

/** The four questions, asked of one screen at one size in one scheme. */
async function check(
  page: Page,
  screen: Screen,
  size: { width: number; height: number },
  scheme: 'light' | 'dark',
): Promise<void> {
  const where = `${screen.name} at ${size.width} px in ${scheme}`;

  await expect(screen.marker().first(), `${where}: the screen never painted`).toBeVisible({
    timeout: 30_000,
  });
  await expect(
    page.getByRole('heading', { level: 1 }).first(),
    `${where}: no level-1 heading — every screen names itself`,
  ).toBeVisible({ timeout: 30_000 });

  expect(
    await pageScrollsSideways(page),
    `${where}: the page scrolls sideways — a grid track that cannot shrink, or a pane that should be scrolling instead of the document`,
  ).toBe(false);

  const primary = screen.primary().first();
  await expect(primary, `${where}: the screen's main action is not on it`).toBeVisible({
    timeout: 30_000,
  });
  const box = await primary.boundingBox();
  expect(box, `${where}: the main action has no box`).not.toBeNull();
  expect(
    box!.x >= -1 && box!.x + box!.width <= size.width + 1,
    `${where}: the main action is off the side of the viewport (x ${Math.round(box!.x)}, width ${Math.round(box!.width)})`,
  ).toBe(true);

  if (scheme === 'dark') {
    const contrast = await bodyTextContrast(page);
    expect(
      contrast.ratio,
      `${where}: body text fails AA — ${contrast.ratio.toFixed(2)}:1 against the surface under it, over ${contrast.measured} elements. Worst: ${contrast.sample}`,
    ).toBeGreaterThanOrEqual(AA_NORMAL);

    // T5.2: the two roles §4.5 caught short, now held to the same floor on every screen.
    // `--hq-color-accent-ink` is the school's own accent mixed toward white until it is a
    // colour you can set words in; `--hq-color-error-ink` is one step up the error ramp.
    // `--hq-color-ink-muted` is *reported* and not asserted: it is the placeholder and
    // disabled ink, which WCAG exempts and which has to read as unavailable.
    const roles = await roleContrast(page, [
      '--hq-color-accent-ink',
      '--hq-color-error-ink',
      '--hq-color-ink-muted',
    ]);
    for (const role of ['--hq-color-accent-ink', '--hq-color-error-ink']) {
      expect(
        roles[role],
        `${where}: ${role} fails AA as text — ${roles[role]?.toFixed(2) ?? '?'}:1 on the card under it`,
      ).toBeGreaterThanOrEqual(AA_NORMAL);
    }
  }
}

// ---------------------------------------------------------------------------------------------
// 1 — the walk, and every form on it
// ---------------------------------------------------------------------------------------------

test('the walk — every teacher screen, every form on the path, and out again', async ({ page }) => {
  // The parent panel's generation is the long pole; everything else is seconds.
  test.setTimeout(300_000);
  await page.setViewportSize(WIDE);
  await signInAsSara(page);

  // Sign-in lands her on This week (§6), which is the first screen of the walk.
  await expect(page).toHaveURL(/\/teacher\/week/);
  await expect(page.getByRole('heading', { level: 1, name: 'This week' })).toBeVisible();
  await expect(page.getByRole('grid', { name: 'This week' })).toBeVisible();

  // Her Home is this screen: `/teacher` redirects here rather than drawing a second landing
  // (`screens.ts:111`). Asserted, because the owner's list names "home/This week" as one step
  // and a regression that gave teachers a second, empty Home would otherwise pass unseen.
  await page.goto('teacher');
  await expect(page, '/teacher should redirect to This week').toHaveURL(/\/teacher\/week/);
  await expect(page.getByRole('heading', { level: 1, name: 'This week' })).toBeVisible();

  // My classes → a class → both of its tabs.
  await page.getByRole('navigation').getByRole('link', { name: 'My classes' }).click();
  await expect(page).toHaveURL(/\/teacher\/classes/);
  await page.getByRole('link', { name: `${className} · Math · British`, exact: true }).click();
  await expect(page.getByRole('grid'), 'the class page opens on its calendar').toBeVisible();
  await page.getByRole('tab', { name: 'Children' }).click();
  await expect(page.locator('hq-class-children'), 'the Children tab never drew the roster').toBeVisible({
    timeout: 30_000,
  });
  await page.getByRole('tab', { name: 'Calendar' }).click();
  await expect(page.getByRole('grid')).toBeVisible();

  // All lessons, reached from the class page's own link.
  await page.getByRole('link', { name: 'All lessons' }).click();
  await expect(page).toHaveURL(/\/teacher\/lessons/);
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();

  // --- form 1: New lesson -------------------------------------------------------------------
  await page.goto(`teacher/lessons/new?${newLessonQuery(LESSON_DAY)}`);
  await expect(page.getByRole('heading', { name: 'New lesson' })).toBeVisible();
  await page.getByLabel('Title').fill(TITLE);
  await page.getByRole('button', { name: /Write it yourself/ }).click();
  await page.getByRole('button', { name: 'Create and write the questions' }).click();
  await expect(page).toHaveURL(/\/teacher\/lessons\/[0-9a-f-]+/, { timeout: 30_000 });
  lessonPath = new URL(page.url()).pathname.replace(/^\/dashboard\//, '');

  // --- form 2: the stop editor --------------------------------------------------------------
  // Through CR2's form: #90 replaced the twenty-two-entry template menu this used to click, and
  // `lesson-editor.spec.ts` asserts the menu is gone. This walk was left on the old menu item and
  // had been hanging here for five minutes until the test timed out.
  await addStopThroughForm(page, {
    title: `How many sides ${RUN}`,
    question: 'Pip says: show a triangle and a circle, and ask which one has three sides.',
    type: 'choice',
  });
  const stops = page.getByRole('listbox', { name: 'Stops' }).getByRole('option');
  await expect(stops).toHaveCount(1, { timeout: 30_000 });
  await stops.first().click();

  const editor = page.locator('hq-stop-editor');
  const stopTitle = editor.getByLabel('Title');
  await expect(stopTitle).toBeVisible({ timeout: 30_000 });
  const rewritten = `How many sides? ${RUN}`;
  await stopTitle.fill(rewritten);
  const saveStop = page.getByRole('button', { name: 'Save the stop' });
  await expect(saveStop).toBeEnabled({ timeout: 15_000 });
  await saveStop.click();
  await expect(
    stops.filter({ hasText: rewritten }),
    'the saved title never reached the stop list',
  ).toBeVisible({ timeout: 30_000 });
  // The pinned phone follows the selection, so the edit is visible where a child would see it.
  await expect(page.locator('.phone__caption')).toHaveText(rewritten);

  // --- form 3: the parent panel -------------------------------------------------------------
  // Local only, for the reason `lesson-editor.spec.ts` gives: a panel only exists once the
  // levels have been generated, and on QA that is the real model writing three levels, an
  // Again variant and the panel from a *manual* source, so nothing is in the generation cache.
  // It took longer than this whole file's budget and put the post-deploy job back near its
  // 20-minute cap. `teacher-flow.spec.ts` is the file that proves the pipeline on QA.
  if (!process.env['E2E_BASE_URL']) {
    await page
      .getByLabel('What this lesson is about')
      .fill('Sorting two-dimensional shapes by their number of sides.');
    await page.getByRole('button', { name: 'Generate the levels' }).click();
    await expect(page.getByRole('tab', { name: 'Level 2' })).toBeEnabled({ timeout: 180_000 });

    const savePanel = page.getByRole('button', { name: 'Save the parent panel' });
    await expect(savePanel).toBeVisible({ timeout: 60_000 });
    const objective = page.getByLabel("What they'll learn 1 — English");
    await objective.fill(`Sort shapes by sides ${RUN}`);
    await expect(savePanel).toBeEnabled();
    await savePanel.click();
    await expect(page.getByText('Parent panel saved.')).toBeVisible({ timeout: 30_000 });
  }

  // --- form 4: the day moves, while the lesson is still unpublished -------------------------
  const day = page.getByLabel('Lesson day');
  await expect(day).toHaveValue(LESSON_DAY);
  await day.fill(MOVED_DAY);
  await expect(day, 'the move was not kept').toHaveValue(MOVED_DAY);
  await page.reload();
  await expect(page.getByLabel('Lesson day'), 'the move did not survive a reload').toHaveValue(MOVED_DAY, {
    timeout: 30_000,
  });

  // --- form 5: the publish sheet ------------------------------------------------------------
  await page.getByRole('button', { name: 'Publish', exact: true }).click();
  const sheet = page.getByRole('dialog');
  await expect(sheet).toBeVisible();
  await expect(sheet.getByRole('heading', { name: new RegExp(`^Publish to ${className}`) })).toBeVisible();
  // `hq-checkbox` hides the real input behind the drawn box, so the label is the hit target.
  await sheet.locator('label.check', { hasText: siblingName }).click();
  await expect(sheet.getByRole('checkbox')).toBeChecked();
  await sheet.getByRole('button', { name: 'Publish', exact: true }).click();
  await expect(page.getByText('Published. Each class has its own copy:')).toBeVisible({
    timeout: 60_000,
  });
  await expect(page.getByRole('button', { name: 'Unpublish' })).toBeVisible();

  // --- form 6: Profile ----------------------------------------------------------------------
  // The only control on Profile that writes anything. Name, photo and role are disabled on
  // purpose — the contract has no self-edit route — so there is no Save here to press; the
  // gap is in the report rather than papered over. Switched and switched back, so the rest of
  // this file still finds its English labels.
  await page.goto('profile');
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  // Scoped to the page: the header's own language control is also labelled "Language", and it
  // is a menu button rather than a `<select>`.
  const language = page.locator('main').getByLabel('Language');
  await language.selectOption('ar');
  await expect(page.locator('html'), 'the language choice did not apply').toHaveAttribute('dir', 'rtl');
  await setLanguage(page, 'en');
  await expect(page.locator('html')).toHaveAttribute('dir', 'ltr');

  // --- and out ------------------------------------------------------------------------------
  await page.locator('.header__user').click();
  await page.getByRole('menuitem', { name: 'Sign out' }).click();
  await expect(page, 'signing out should land on the sign-in screen').toHaveURL(/\/sign-in/, {
    timeout: 30_000,
  });
  await expect(page.getByLabel('Email')).toBeVisible();
});

// ---------------------------------------------------------------------------------------------
// 2 and 3 — the same eight screens, measured
// ---------------------------------------------------------------------------------------------

test('every teacher screen holds up at 1366, in light and in dark', async ({ page }) => {
  test.setTimeout(300_000);
  await page.setViewportSize(WIDE);
  await signInAsSara(page);

  for (const scheme of ['light', 'dark'] as const) {
    for (const screen of screens(page)) {
      await screen.open();
      await setScheme(page, scheme);
      await check(page, screen, WIDE, scheme);
    }
  }

  await setScheme(page, 'light');
});

test('every teacher screen holds up at 768 and at 375', async ({ page }) => {
  test.setTimeout(300_000);
  await page.setViewportSize(WIDE);
  await signInAsSara(page);
  await setScheme(page, 'light');

  for (const size of [TABLET, PHONE]) {
    await page.setViewportSize(size);
    for (const screen of screens(page)) {
      await screen.open();
      await check(page, screen, size, 'light');
    }
  }

  await page.setViewportSize(WIDE);
});

// ---------------------------------------------------------------------------------------------
// 4 — the set
// ---------------------------------------------------------------------------------------------

test('the screenshot set: eight screens, two languages, two schemes, three widths', async ({ page }) => {
  test.setTimeout(900_000);
  await mkdir(SHOTS, { recursive: true });
  await page.setViewportSize(WIDE);
  await signInAsSara(page);

  for (const screen of screens(page)) {
    for (const language of ['en', 'ar'] as const) {
      await page.setViewportSize(WIDE);
      await setLanguage(page, language);

      for (const scheme of ['light', 'dark'] as const) {
        await screen.open();
        await setScheme(page, scheme);
        await shoot(page, `${SHOTS}/${screen.name}-${language}-${scheme}-1366.png`, screen.marker());
      }
      await setScheme(page, 'light');
    }

    // The two narrow frames are English and light: what changes below 1366 is the layout, and a
    // second language and a second scheme of the same layout is four files saying one thing.
    await setLanguage(page, 'en');
    for (const size of [TABLET, PHONE]) {
      await page.setViewportSize(size);
      await screen.open();
      await shoot(page, `${SHOTS}/${screen.name}-en-light-${size.width}.png`, screen.marker());
    }
    await page.setViewportSize(WIDE);
  }

  await setLanguage(page, 'en');
});
