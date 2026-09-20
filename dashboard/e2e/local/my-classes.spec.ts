import { type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { classFreeToday, expect, removeLessonsOfThisRun, RUN, shoot, signInAsSara, test } from './env';

/**
 * N2.3's acceptance (`docs/teacher-flow.md` §4 step 3 and §5), against the built bundle and a
 * local API on H2 started with `SEED_SCHOOL=true` — the one school, 30 classes, 40 teachers,
 * 600 children.
 *
 * Sara Al Harbi (`seed/assignments.csv`) teaches **1A British** and **1B British**, both Math,
 * and both classes are seeded with children — which is all this file needs. It creates nothing
 * through the Admin API and changes no assignment, so it can run before or after
 * `this-week.spec.ts` without either of them noticing.
 *
 * Two sections of one grade is the point: the review that sent N2.3 back found the Children tab
 * listing all 96 children of Grade 1 British, so this file checks that 1A's roster is 1A's own
 * size and that 1B's children are not in it.
 *
 * `teacher.rosterEdit` is **off** by default (`V7__sections.sql`), so the Children tab here is
 * the read-only shape: the roster columns and the add/edit controls must not appear, and the
 * roster endpoint must not be called. Turning the flag on is a per-school Admin action and is
 * covered by `class-children.component.spec.ts` against both maps.
 */
const SHOTS = resolve(process.cwd(), '../docs/screenshots/dashboard-n2.3');

/** My classes, reached the way she reaches it: the rail's second item. */
async function openMyClasses(page: Page): Promise<void> {
  await signInAsSara(page);
  await rail(page).getByRole('link', { name: 'My classes' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'My classes' })).toBeVisible();
}

/**
 * The 240 px rail, not the page's breadcrumb.
 *
 * Both are `role="navigation"` with a list inside, and the class page is the first screen to
 * carry both — `getByRole('navigation')` here would count the breadcrumb's "My classes" as a
 * fourth rail item.
 */
function rail(page: Page) {
  return page.locator('hq-nav');
}

/** The card's own link, as opposed to its "Add today's lesson" action. */
function cardLink(page: Page, className: string) {
  return page.getByRole('link', { name: `${className} · Math · British`, exact: true });
}

function cardOf(page: Page, className: string) {
  return page.locator('hq-card').filter({ hasText: `${className} · Math · British` });
}

/**
 * The number the card counts up to — the class's own size, per `GET /teacher/classes`.
 *
 * `hqCountUp` animates 0 → n over 600 ms, so the first read catches a number on its way up.
 * Polled until two reads agree rather than slept past, so it is not a timing bet.
 */
async function childrenCountOf(page: Page, className: string): Promise<number> {
  const count = cardOf(page, className).locator('.my-classes__count');
  await expect(count).toBeVisible();
  let last = -1;
  await expect
    .poll(async () => {
      const now = Number((await count.textContent())?.trim());
      const settled = now > 0 && now === last;
      last = now;
      return settled;
    })
    .toBe(true);
  return last;
}

test.describe.configure({ mode: 'serial' });

test('My classes shows a card per assignment, grouped by grade', async ({ page }) => {
  await openMyClasses(page);

  await expect(page.getByRole('heading', { name: 'Grade 1' })).toBeVisible();
  // The seed names her sections "1A British", so the card reads "1A British · Math · British" —
  // the row label This week uses: class · subject · curriculum.
  await expect(cardLink(page, '1A British')).toBeVisible();
  await expect(cardLink(page, '1B British')).toBeVisible();

  // Two sections of one grade, two different sizes: the card counts its own class, not the course.
  const one = await childrenCountOf(page, '1A British');
  const two = await childrenCountOf(page, '1B British');
  expect(one).toBeGreaterThan(0);
  expect(two).toBeGreaterThan(0);
  expect(one + two).toBeLessThan(96);
  await expect(cardOf(page, '1A British').getByText('children')).toBeVisible();
});

test("Add today's lesson lands on New lesson, pre-set to that class and today", async ({ page }) => {
  // Which section, read rather than named. The card only offers this action when today has
  // nothing on it (`my-classes.page.html:53`), and on QA's shared database both of Sara's
  // sections usually do — `classFreeToday()` finds one that is free, or frees one that an
  // earlier run left behind, and says so plainly if it can do neither (`env.ts`).
  const free = await classFreeToday();
  await openMyClasses(page);

  const add = cardOf(page, free.className).getByRole('link', { name: /^Add today's lesson/ });
  await expect(
    add,
    `${free.className} has nothing on today, so its card should offer the action`,
  ).toBeVisible();
  await add.click();

  await expect(page).toHaveURL(/lessons\/new\?.*classId=/);
  await expect(page.getByRole('heading', { name: 'New lesson' })).toBeVisible();
  // N2.4b: the link names her section and the section is the whole course — a teacher's New
  // lesson has one picker, already filled and locked, rather than three she must agree with.
  await expect(page.getByLabel('Class')).toHaveValue(`${free.classId}::${free.subject}`);

  const today = new Date().toISOString().slice(0, 10);
  const date = new URL(page.url()).searchParams.get('date') ?? '';
  expect(date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  // The school's today, which is at most a day either side of the runner's.
  expect(Math.abs(Date.parse(date) - Date.parse(today))).toBeLessThanOrEqual(86_400_000);
});

test('the class page puts the class in the rail and the lesson in the calendar', async ({ page }) => {
  await openMyClasses(page);
  await cardLink(page, '1B British').click();

  // The header is the calendar response's own `className` and `subject` (#70), not a guess.
  await expect(page.getByRole('heading', { level: 1, name: '1B British · Math' })).toBeVisible();
  await expect(page.getByRole('grid')).toBeVisible();
  const classUrl = page.url();

  // §5: a third rail item while she is inside the class, and only while she is.
  const items = rail(page).getByRole('list');
  await expect(items.getByRole('link')).toHaveCount(3);
  await expect(items.getByRole('link', { name: '1B British · Math' })).toBeVisible();

  // A lesson created from the calendar's `+` appears on that day.
  await page
    .getByRole('link', { name: /^Add a lesson on/ })
    .first()
    .click();
  await expect(page.getByRole('heading', { name: 'New lesson' })).toBeVisible();
  await page.getByLabel('Title').fill(`Shapes ${RUN}`);
  await page.getByRole('button', { name: /Write it yourself/ }).click();
  await page.getByRole('button', { name: 'Create and write the questions' }).click();
  await expect(page).toHaveURL(/\/teacher\/lessons\/[0-9a-f-]+/, { timeout: 30_000 });

  // The cell names the lesson by its own title (#70), not by its type.
  await page.goto(classUrl);
  const cell = page
    .getByRole('gridcell')
    .filter({ hasText: `Shapes ${RUN}` })
    .first();
  await expect(cell).toBeVisible({ timeout: 15_000 });
  await expect(cell.getByText(/played/)).toBeVisible();

  // And leaving takes the rail item with it.
  await items.getByRole('link', { name: 'This week' }).click();
  await expect(items.getByRole('link')).toHaveCount(2);
});

test('the Children tab lists the roster, and offers nothing to change while the flag is off', async ({
  page,
}) => {
  await openMyClasses(page);
  const size = await childrenCountOf(page, '1A British');
  await cardLink(page, '1A British').click();

  await page.getByRole('tab', { name: 'Children' }).click();
  await expect(page.getByRole('table', { name: 'Children' })).toBeVisible();
  await expect(page.getByRole('columnheader', { name: 'Stars this week' })).toBeVisible();
  await expect(page.getByRole('columnheader', { name: 'Level reached' })).toBeVisible();
  await expect(page.getByRole('columnheader', { name: 'Weak skills' })).toBeVisible();

  // The review's finding: the tab used to list all 96 children of Grade 1 British. It is 1A's
  // own roster now — the same number the card counts, and the count under the tab agrees.
  await expect(page.getByRole('row')).toHaveCount(size + 1); // + the header row
  await expect(page.getByText(`${size} children`)).toBeVisible();

  // `teacher.rosterEdit` is off: no add, no edit, no parent-email column.
  await expect(page.getByRole('button', { name: 'Add a child' })).toHaveCount(0);
  await expect(page.getByRole('columnheader', { name: 'Parent email' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /^Edit/ })).toHaveCount(0);

  // The arrow-key tabs pattern. Gradebook (N4.2) and Exams (N4.4) are both built, and this
  // school has neither flag — so both tabs stay and say what they would hold, rather than
  // disappearing from under her and leaving her wondering about her account.
  await page.getByRole('tab', { name: 'Children' }).press('ArrowRight');
  await expect(page.getByRole('tab', { name: 'Gradebook' })).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByText('This school does not have the gradebook yet.')).toBeVisible();

  await page.getByRole('tab', { name: 'Gradebook' }).press('ArrowRight');
  await expect(page.getByRole('tab', { name: 'Exams' })).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByText('This school does not have exams yet.')).toBeVisible();

  // "All lessons of this class" keeps the list one tap away, filtered to this class.
  await page.getByRole('link', { name: 'All lessons of this class' }).click();
  await expect(page).toHaveURL(/\/teacher\/lessons\?.*classId=/);
  await expect(page.getByText(/Showing only 1A British/)).toBeVisible();
});

test('the screenshot set, EN and AR', async ({ page }) => {
  await mkdir(SHOTS, { recursive: true });
  await openMyClasses(page);

  for (const language of ['en', 'ar'] as const) {
    await page.evaluate((lang) => localStorage.setItem('hq.language', lang), language);
    await page.goto('teacher/classes');
    await expect(page.locator('html')).toHaveAttribute('dir', language === 'ar' ? 'rtl' : 'ltr');
    // `shoot` waits out `listStagger` and `countUp` (30 ms apart, 250/600 ms each).
    await shoot(page, `${SHOTS}/01-my-classes-${language}.png`, page.locator('hq-card').first());

    await page.locator('hq-card').first().getByRole('link').first().click();
    await shoot(page, `${SHOTS}/02-class-calendar-${language}.png`, page.getByRole('grid'));

    await page.getByRole('tab').nth(1).click();
    await shoot(page, `${SHOTS}/03-class-children-${language}.png`, page.getByRole('table'));
  }

  await page.evaluate(() => localStorage.setItem('hq.language', 'en'));
});

/** The one lesson this file writes, off the shared database again (`env.ts`). */
test.afterAll(removeLessonsOfThisRun);
