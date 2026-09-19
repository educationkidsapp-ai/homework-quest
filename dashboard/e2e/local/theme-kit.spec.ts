import { request, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import {
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
 * T3's acceptance — the kit and the teacher screens against `docs/prompts/tailadmin-spec.md`
 * §1–§5, and the one defect the package was asked to fix.
 *
 * Two things are proved here that a unit test cannot:
 *
 * 1. **No screen scrolls the page sideways**, at 1366, 768 and 375. That is the whole of §2's
 *    grid rule in one assertion — every track is `minmax(0, …)`, and anything that cannot
 *    shrink (the week grid, the class calendar, a wide table) scrolls *inside its own card*.
 *    Before this package the class calendar failed it under about 900 px, which
 *    `e2e/local/README.md` recorded as known.
 * 2. **The screenshot set**: seven teacher screens, English and Arabic, light and dark, at the
 *    reference viewport, plus a light English frame at 768 and 375.
 *
 * The server runs on H2 with `SEED_SCHOOL=true` and `LLM_PROVIDER=fake` (see `README.md`).
 */
const SHOTS = resolve(process.cwd(), '../docs/screenshots/theme-t3');

const WIDE = { width: 1366, height: 768 };
const TABLET = { width: 768, height: 1024 };
const PHONE = { width: 375, height: 812 };

const TITLE = `Counting in twos ${RUN}`;
/** A day far out and different every run: a class holds one lesson per day (see N2.4a). */
const DATE = schoolDayFromNow(320 + (Math.floor(Date.now() / 1000) % 40));

let classId = '';
let lessonPath = '';

test.describe.configure({ mode: 'serial' });

/** Sara's own 1A British Math section, read with her token — never the Admin's. */
test.beforeAll(async () => {
  const token = await signInForToken(SARA);
  const api = await request.newContext({ baseURL: API });
  try {
    const response = await api.get('/teacher/classes', {
      headers: { Authorization: `Bearer ${token}` },
    });
    const rows = (await response.json()) as { classId: string; className: string; subject: string }[];
    const mine = rows.find((row) => /1A British/i.test(row.className) && row.subject === 'math');
    expect(mine, 'no 1A British Math section for Sara').toBeTruthy();
    classId = mine!.classId;
  } finally {
    await api.dispose();
  }
});

test.afterAll(removeLessonsOfThisRun);

/**
 * Does the *document* scroll sideways?
 *
 * The document, not a pane: a table or a month that scrolls inside its card is the behaviour
 * §2 asks for, and only the page scrolling is the failure. One pixel of slack, because a
 * fractional viewport width rounds against sub-pixel layout in both directions.
 */
async function pageScrollsSideways(page: Page): Promise<boolean> {
  // Settled first, and for the same reasons `shoot` settles: a skeleton, a count-up mid-flight
  // or a font still swapping is a different layout from the one this is asking about.
  await page.evaluate(() => document.fonts.ready);
  await page.waitForFunction(
    () => document.getAnimations().every((animation) => animation.playState !== 'running'),
    undefined,
    { timeout: 10_000 },
  );
  return page.evaluate(() => {
    const root = document.scrollingElement;
    return root !== null && root.scrollWidth > root.clientWidth + 1;
  });
}

/** The seven teacher screens, each with the element that says it has finished painting. */
function screens(page: Page) {
  return [
    {
      // Her Home, which is the teacher area's root. `screens.ts:111` gives `home` the path `''`
      // and redirects it to `week` — "Her Home *is* This week, so `/teacher` redirects rather
      // than drawing a second landing". This used to open `teacher/home`, which matches no
      // route, so all six `home-*.png` frames were the not-found page: the only barrier here is
      // a level-1 heading, and "Nothing here" has one.
      name: 'home',
      open: async () => void (await page.goto('teacher')),
      marker: () => page.getByRole('grid'),
    },
    {
      name: 'week',
      open: async () => void (await page.goto('teacher/week')),
      marker: () => page.getByRole('grid'),
    },
    {
      name: 'my-classes',
      open: async () => void (await page.goto('teacher/classes')),
      marker: () => page.locator('hq-card').first(),
    },
    {
      name: 'class',
      open: async () => void (await page.goto(`teacher/classes/${classId}?subject=math`)),
      marker: () => page.getByRole('grid'),
    },
    {
      name: 'lessons',
      open: async () => void (await page.goto('teacher/lessons?curriculum=british&grade=1')),
      marker: () => page.getByRole('heading', { level: 1 }),
    },
    {
      name: 'new-lesson',
      open: async () => void (await page.goto(`teacher/lessons/new?${newLessonQuery()}`)),
      marker: () => page.getByRole('heading', { level: 1 }),
    },
    {
      name: 'lesson',
      open: async () => void (await page.goto(lessonPath)),
      marker: () => page.getByRole('heading', { level: 1, name: TITLE }),
    },
    {
      name: 'profile',
      open: async () => void (await page.goto('profile')),
      marker: () => page.getByRole('heading', { level: 1 }),
    },
  ] as const;
}

function newLessonQuery(): string {
  return new URLSearchParams({
    classId,
    curriculum: 'british',
    grade: '1',
    subject: 'math',
    date: DATE,
  }).toString();
}

test('Sara writes the lesson the editor screens are taken on', async ({ page }) => {
  test.setTimeout(120_000);
  await page.setViewportSize(WIDE);
  await signInAsSara(page);

  await page.goto(`teacher/lessons/new?${newLessonQuery()}`);
  await expect(page.getByRole('heading', { name: 'New lesson' })).toBeVisible();
  await page.getByLabel('Title').fill(TITLE);
  await page.getByRole('button', { name: /Write it yourself/ }).click();
  await page.getByRole('button', { name: 'Create and write the questions' }).click();

  await expect(page).toHaveURL(/\/teacher\/lessons\/[0-9a-f-]+/, { timeout: 20_000 });
  lessonPath = new URL(page.url()).pathname.replace(/^\/dashboard\//, '');
  await expect(page.getByRole('button', { name: '+ Add stop' })).toBeVisible();
});

test('no teacher screen scrolls the page sideways at 1366, 768 or 375', async ({ page }) => {
  test.setTimeout(300_000);
  await page.setViewportSize(WIDE);
  await signInAsSara(page);

  // Arabic as well as English: every offset in the system is a logical property, and a rule
  // that is not would show up as an overflow on the mirrored side rather than on this one.
  for (const language of ['en', 'ar'] as const) {
    await page.setViewportSize(WIDE);
    await setLanguage(page, language);

    for (const screen of screens(page)) {
      for (const size of [WIDE, TABLET, PHONE]) {
        await page.setViewportSize(size);
        await screen.open();
        await expect(screen.marker().first()).toBeVisible({ timeout: 30_000 });
        expect(
          await pageScrollsSideways(page),
          `${screen.name} scrolls the page sideways at ${size.width} px in ${language} — a grid track that cannot shrink, or a pane that should be scrolling instead of the document`,
        ).toBe(false);
      }
    }
  }

  await page.setViewportSize(WIDE);
  await setLanguage(page, 'en');
});

/**
 * The set. Seven screens at the reference viewport in both languages and both schemes — the
 * matrix that shows a role layer really is doing the dark work — and a light English frame at
 * 768 and 375 for each, which is where the week grid and the class calendar change shape.
 */
test('the screenshot set: seven screens, two languages, two schemes, three widths', async ({ page }) => {
  test.setTimeout(600_000);
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

    // The two narrow frames are English and light: what changes below 1366 is the layout, and
    // a second language and a second scheme of the same layout is four files saying one thing.
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
