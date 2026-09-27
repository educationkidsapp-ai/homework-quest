import { type APIRequestContext, type Page } from '@playwright/test';
import { COORDINATOR, MANAGER, SARA, expect, signIn, test } from './env';
import { api, schoolOfSara, withFlags } from './n4-api';

/**
 * RM3b's acceptance (`docs/management-flow.md` §6, `docs/coordinator-flow.md` §6), against the
 * built bundle and a local API on H2 started with `SEED_SCHOOL=true`.
 *
 * The round trip DR6 asks for, and nothing is written behind the product's back: Huda Salem, the
 * manager of the British department, posts this week's plan through her own compose sheet; Sara
 * Al Harbi, who teaches Math in 1A and 1B British, finds it pinned at the top of her feed with
 * the bell ringing; and Rasha Kamal, the Math coordinator, posts an announcement to the parents
 * of her classes on the same screen with the kind she is allowed.
 *
 * **Re-runnable on purpose.** A weekly plan replaces the one before it for that week and its read
 * marks go with it, so running this file twice posts the same plan again and Sara's row is unread
 * again — no cleanup, and no state a second run has to dodge.
 *
 * `announcements` is off in the one-school seed, so this file turns it on and puts it back:
 * `coordinator-area.spec.ts` and `management-area.spec.ts` both assert the flag-off rail and
 * would fail on a leftover.
 */
const PLAN_TITLE = 'Subtraction week';
const PLAN_BODY = 'Subtraction all week, and swimming on Thursday.';
const NOTE_TITLE = 'Reading week';

let context: APIRequestContext;
let restoreFlags: (() => Promise<void>) | null = null;

test.beforeAll(async () => {
  context = await api();
  restoreFlags = await withFlags(context, await schoolOfSara(context), ['announcements']);
});

test.afterAll(async () => {
  if (restoreFlags) await restoreFlags();
  await context.dispose();
});

function rail(page: Page) {
  return page.locator('hq-nav');
}

/** The card whose head button carries this title — the row, open or closed. */
function card(page: Page, title: string) {
  return page.locator('hq-card').filter({ hasText: title }).first();
}

async function openBroadcasts(page: Page, who: Parameters<typeof signIn>[1]): Promise<void> {
  await signIn(page, who);
  await rail(page).getByRole('link', { name: 'Broadcasts' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Broadcasts' })).toBeVisible();
}

test.describe('broadcasts', () => {
  test('the manager posts the week plan for her department', async ({ page }) => {
    await openBroadcasts(page, MANAGER);

    await page.getByRole('button', { name: 'Write a broadcast' }).click();
    await page.getByLabel('What is this').selectOption('weekly_plan');

    // Only Sundays are on offer, because the server snaps `weekStart` back to one and a date
    // input would have let her pick a Wednesday and read a different week back.
    const week = page.getByLabel('Week');
    await expect(week).toBeVisible();
    await expect(page.getByText('replaces the current plan')).toBeVisible();
    await week.selectOption({ index: 1 });

    await page.getByLabel('Title').fill(PLAN_TITLE);
    await page.getByLabel('Message (English)').fill(PLAN_BODY);
    // Huda runs one department, so there is nothing to choose: the row carries `british` from her
    // own scope. Teachers have to be in the audience for Sara to see it at all.
    await expect(page.getByLabel('Department')).toBeHidden();
    await page.getByRole('checkbox', { name: 'Teachers' }).check();

    await page.getByRole('button', { name: 'Post', exact: true }).click();
    await expect(page.getByText('Posted.')).toBeVisible();

    // Her own list, expired rows and all — the composer's list, not the feed she reads.
    await page.getByRole('tab', { name: 'You posted' }).click();
    await expect(card(page, PLAN_TITLE)).toBeVisible();
    await expect(card(page, PLAN_TITLE)).toContainText('Weekly plan');
  });

  test('the British teacher finds it pinned, unread, and on her bell', async ({ page }) => {
    await openBroadcasts(page, SARA);

    // Pinned: the plan is the first row whatever order the feed came back in.
    const rows = page.locator('.bc__title');
    await expect(rows.first()).toHaveText(PLAN_TITLE, { timeout: 30_000 });
    // And drawn open, because arriving on the screen *is* opening the one row she came for.
    await expect(page.locator('main')).toContainText(PLAN_BODY);
    await expect(card(page, PLAN_TITLE)).toContainText('Huda Salem');

    await page.getByRole('button', { name: 'Notifications' }).click();
    const bell = page.getByRole('menu', { name: 'Notifications' });
    await expect(bell).toContainText('New broadcast');
    // The notification links at her own area's screen, not at the manager's.
    await bell.getByRole('menuitem').filter({ hasText: 'New broadcast' }).first().click();
    await expect(page).toHaveURL(/\/teacher\/broadcasts$/);
  });

  test('a teacher gets no composer, because the plan is the department’s', async ({ page }) => {
    await openBroadcasts(page, SARA);
    await expect(page.getByRole('button', { name: 'Write a broadcast' })).toHaveCount(0);
    await expect(page.getByRole('tab', { name: 'You posted' })).toHaveCount(0);
  });

  test('the coordinator posts an announcement to the parents of her classes', async ({ page }) => {
    await openBroadcasts(page, COORDINATOR);

    await page.getByRole('button', { name: 'Write a broadcast' }).click();
    // DR6: the weekly plan belongs to the manager, so it is not one of her kinds — the server
    // answers 400 and the select does not offer it.
    await expect(page.getByLabel('What is this').locator('option')).toHaveText(['Announcement', 'Event']);
    // Her audience is fixed: the server sends hers to the parents of her classes and ignores the
    // field, so there is nothing here to pick and the sheet says so instead of offering boxes.
    await expect(page.getByRole('checkbox', { name: 'Teachers' })).toHaveCount(0);
    await expect(page.getByText('The parents of the classes you coordinate')).toBeVisible();

    const title = `${NOTE_TITLE} ${Date.now().toString(36).slice(-4)}`;
    await page.getByLabel('Title').fill(title);
    await page.getByLabel('Message (English)').fill('Reading week starts on Sunday.');
    await page.getByRole('button', { name: 'Post', exact: true }).click();
    await expect(page.getByText('Posted.')).toBeVisible();

    await page.getByRole('tab', { name: 'You posted' }).click();
    await expect(card(page, title)).toContainText('Announcement');
  });
});
