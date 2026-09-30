import { resolve } from 'node:path';
import { type APIRequestContext, type Page } from '@playwright/test';
import { COORDINATOR, MANAGER, SARA, expect, signIn, test } from './env';
import { api, schoolOfSara, withFlags } from './n4-api';

/**
 * RM3b's acceptance (`docs/management-flow.md` §6, `docs/coordinator-flow.md` §6), as MH2 left it,
 * against the built bundle and a local API on H2 started with `SEED_SCHOOL=true`.
 *
 * The round trip DR6 asks for, and nothing is written behind the product's back: Huda Salem, the
 * manager of the British department, uploads this week's plan for grade 1 as an image on her own
 * Weekly plans screen; Sara Al Harbi, who teaches Math in 1A and 1B British, finds the picture on
 * her Weekly plans tab with the bell ringing; and Rasha Kamal, the Math coordinator, posts an
 * announcement to the parents of her classes on the Announcements screen.
 *
 * **Re-runnable on purpose.** A weekly plan replaces the one before it for that grade and week, and
 * its read marks go with it, so running this file twice posts the same plan again and Sara's row is
 * unread again — no cleanup, and no state a second run has to dodge.
 *
 * `announcements` is off in the one-school seed, so this file turns it on and puts it back:
 * `coordinator-area.spec.ts` and `management-area.spec.ts` both assert the flag-off rail and
 * would fail on a leftover.
 */
const NOTE_TITLE = 'Reading week';
/** The only image fixture in the repo, and a PNG under 5 MB — which is the whole requirement. */
const PLAN_IMAGE = resolve(process.cwd(), 'e2e/fixtures/square.png');

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

async function openAnnouncements(page: Page, who: Parameters<typeof signIn>[1]): Promise<void> {
  await signIn(page, who);
  await rail(page).getByRole('link', { name: 'Announcements' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Announcements' })).toBeVisible();
}

test.describe('announcements and weekly plans', () => {
  /**
   * MH2 item 4: a plan is a grade, a week and an image. The compose sheet has no title and no body
   * at all, and the picture goes up through `POST /media/attachments` before the broadcast is written.
   */
  test('the manager uploads this week’s plan for a grade', async ({ page }) => {
    await signIn(page, MANAGER);
    await rail(page).getByRole('link', { name: 'Weekly plans' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Weekly plans' })).toBeVisible();

    // This week at a glance: one card per grade she manages, and no all-grades card — the server
    // refuses a plan without a grade.
    await expect(page.getByText('This week at a glance')).toBeVisible();
    await expect(page.locator('.wp__grade').first()).toContainText('Grade');
    await expect(page.getByText('All grades')).toHaveCount(0);

    // The grade-1 card's own action, which prefills the grade and the week.
    await page
      .locator('.wp__card')
      .first()
      .getByRole('button', { name: /Add plan|Replace plan/ })
      .click();
    await expect(page.getByLabel('Which grade')).toBeVisible();
    // Neither of the two fields the old sheet had: a plan is a picture.
    await expect(page.getByLabel('Title')).toHaveCount(0);
    await expect(page.getByLabel('Message (English)')).toHaveCount(0);

    await page.getByLabel('Week').selectOption({ index: 0 });
    await page.setInputFiles('input[type="file"]', PLAN_IMAGE);
    // The preview is the picked bytes as a data URL — the CSP has no `blob:`.
    await expect(page.locator('img.pc__preview')).toBeVisible();

    await page.getByRole('button', { name: /^(Post|Replace plan)$/ }).click();
    await expect(page.getByText('Posted.')).toBeVisible({ timeout: 30_000 });

    // The card now shows the picture, fetched with the bearer rather than by its absolute url.
    await expect(page.locator('img.wp__thumb').first()).toBeVisible();
    await expect(page.locator('img.wp__thumb').first()).toHaveAttribute('alt', /Weekly plan · Grade/);
    // And the archive lists it, with `readBy` — which only her archive answers.
    const archive = page.locator('hq-card').filter({ hasText: 'Every weekly plan' });
    await expect(archive.locator('img.pw__thumb').first()).toBeVisible();
    await expect(archive).toContainText('Read by');
  });

  /** The same archive, read-only, on the tab beside the notes she was sent. */
  test('the British teacher finds the picture on her Weekly plans tab', async ({ page }) => {
    await openAnnouncements(page, SARA);
    await page.getByRole('tab', { name: 'Weekly plans' }).click();

    const thumb = page.locator('img.pw__thumb').first();
    await expect(thumb).toBeVisible({ timeout: 30_000 });
    await expect(thumb).toHaveAttribute('alt', /Weekly plan · Grade/);
    // Hers carries no reader count: `readBy` is the manager's archive alone.
    await expect(page.locator('main')).not.toContainText('Read by');

    // Full size in a dialog, because a photographed A4 sheet is unreadable at 200 px.
    await thumb.click();
    await expect(page.locator('img.pw__full')).toBeVisible();
  });

  /** MH2 item 5: a plan is not on the feed at all — it is a picture, and the feed draws titles. */
  test('a teacher gets no composer, and no plan in the feed', async ({ page }) => {
    await openAnnouncements(page, SARA);
    await expect(page.getByRole('button', { name: 'Write an announcement' })).toHaveCount(0);
    await expect(page.getByRole('tab', { name: 'You posted' })).toHaveCount(0);
    await expect(page.locator('.bc__title').filter({ hasText: 'Weekly plan' })).toHaveCount(0);
  });

  test('the coordinator posts an announcement to the parents of her classes', async ({ page }) => {
    await openAnnouncements(page, COORDINATOR);

    await page.getByRole('button', { name: 'Write an announcement' }).click();
    // DR6 and MH2 item 5 together: the weekly plan is neither hers nor this screen's.
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

  /**
   * MH2 item 5: the manager's audience is a grade or the whole department, which is the one question
   * a grade select and a list of class checkboxes were asking twice.
   */
  test('the manager writes an announcement to one grade or the whole department', async ({ page }) => {
    await openAnnouncements(page, MANAGER);

    await page.getByRole('button', { name: 'Write an announcement' }).click();
    const scope = page.getByLabel('Who should read this');
    await expect(scope.locator('option').first()).toHaveText('The whole department');
    await expect(scope.locator('option')).toContainText(['Grade 1']);
    // No class checkboxes for her: the grade select is the same choice, one level up.
    await expect(page.getByRole('group', { name: 'Classes' })).toHaveCount(0);

    const title = `Department note ${Date.now().toString(36).slice(-4)}`;
    await page.getByLabel('Title').fill(title);
    await page.getByLabel('Message (English)').fill('Assembly on Sunday.');
    await page.getByRole('checkbox', { name: 'Teachers' }).check();
    await page.getByRole('button', { name: 'Post', exact: true }).click();
    await expect(page.getByText('Posted.')).toBeVisible();

    await page.getByRole('tab', { name: 'You posted' }).click();
    await expect(card(page, title)).toContainText('Announcement');
  });

  /** MH2 item 6: the bell's link still says `/broadcasts`, and it has to land on the renamed screen. */
  test('sends the old Broadcasts URL to Announcements', async ({ page }) => {
    await signIn(page, SARA);
    await page.goto('teacher/broadcasts');
    await expect(page).toHaveURL(/\/teacher\/announcements$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Announcements' })).toBeVisible();
  });
});
