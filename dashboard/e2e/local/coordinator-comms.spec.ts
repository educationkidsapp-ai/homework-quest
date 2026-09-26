import { type APIRequestContext, type Page } from '@playwright/test';
import { COORDINATOR, expect, signIn, teacherClasses, test } from './env';
import {
  api,
  bearer,
  createChild,
  joinCodeOf,
  parentOf,
  removeChild,
  schoolOfSara,
  withFlags,
  type Parent,
} from './n4-api';

/**
 * R7's acceptance (`docs/coordinator-flow.md` §5), against the built bundle and a local API on H2
 * started with `SEED_SCHOOL=true`.
 *
 * **The complaint is real.** Nothing here writes into the database behind the product's back: a
 * fake-auth parent creates a child with 1A British's join code (the app's own path, `n4-api.ts`),
 * asks `GET /children/{id}/coordinators` who supervises the subject, and posts the first message
 * with `{"topic":"complaint"}` — which is the only way a complaint can exist (R4: a parent names
 * it, the staff side never does). Rasha Kamal then sees it in her inbox because the child sits in
 * a section her Math scope covers.
 *
 * `chat` and `announcements` are both off in the one-school seed, so this file turns them on and
 * puts them back — `coordinator-area.spec.ts` asserts the flag-off rail (four links) and would
 * fail on a leftover.
 */
const SECTION = '1A British';

let context: APIRequestContext;
let schoolId = '';
let restoreFlags: (() => Promise<void>) | null = null;
let parent: Parent;
let childId = '';
let complaintOpened = false;

test.beforeAll(async () => {
  context = await api();
  schoolId = await schoolOfSara(context);
  restoreFlags = await withFlags(context, schoolId, ['chat', 'announcements']);

  const section = (await teacherClasses()).find(
    (row) => row.className === SECTION && row.subject.toLowerCase() === 'math',
  );
  expect(section, `Sara should teach Math in ${SECTION} (seed/assignments.csv)`).toBeTruthy();

  parent = parentOf(`hq-r7-${Date.now()}`);
  childId = await createChild(
    context,
    parent,
    'R7 Complaint Child',
    await joinCodeOf(context, section!.classId),
  );

  // Who may hear a complaint about this child: the coordinators whose scope covers a subject
  // taught in her section. A complaint has to name one — `complaint` on a teacher's thread is
  // 400 `complaint_needs_coordinator`.
  const listed = await context.get(`/children/${childId}/coordinators`, { headers: bearer(parent.token) });
  expect(listed.ok(), `GET /children/{id}/coordinators: HTTP ${listed.status()}`).toBeTruthy();
  const rows = (await listed.json()) as { teacherId: string; teacherName: string }[];
  expect(rows.length, 'the seeded Math coordinator should be reachable from 1A British').toBeGreaterThan(0);

  const posted = await context.post(`/children/${childId}/chat/threads/${rows[0]!.teacherId}/messages`, {
    headers: bearer(parent.token),
    data: { body: 'Nobody has marked the homework for two weeks.', topic: 'complaint' },
  });
  expect(posted.ok(), `POST the complaint: HTTP ${posted.status()} ${await posted.text()}`).toBeTruthy();
  complaintOpened = true;
});

test.afterAll(async () => {
  if (childId) await removeChild(context, parent, childId);
  if (restoreFlags) await restoreFlags();
  await context.dispose();
});

function rail(page: Page) {
  return page.locator('hq-nav');
}

async function openCoordinator(page: Page): Promise<void> {
  await signIn(page, COORDINATOR);
  await expect(page).toHaveURL(/\/coordinator$/);
}

test.describe('the coordinator’s messages, complaints and announcements', () => {
  test('her rail gains the three screens, and the header badge links to Messages', async ({ page }) => {
    await openCoordinator(page);

    await expect(rail(page).getByRole('link')).toHaveText([
      'Home',
      'Teachers',
      'Classes',
      'All lessons',
      'Messages',
      'Complaints',
      'Announcements',
    ]);

    // The complaint is the first line of "What needs you": the only one she can act on herself.
    expect(complaintOpened).toBe(true);
    await expect(page.locator('main')).toContainText('Complaint');
  });

  test('reads the complaint in the conversation and answers it', async ({ page }) => {
    await openCoordinator(page);
    await rail(page).getByRole('link', { name: 'Messages' }).click();

    await expect(page.getByRole('heading', { level: 1, name: 'Messages' })).toBeVisible();
    await expect(page.locator('main')).toContainText('R7 Complaint Child');
    await page.getByText('R7 Complaint Child').first().click();
    await expect(page.locator('main')).toContainText('Nobody has marked the homework');

    await page.getByPlaceholder('Write a message...').fill('Thank you — I will speak to the teacher today.');
    await page.getByRole('button', { name: 'Send' }).click();
    await expect(page.locator('main')).toContainText('I will speak to the teacher today');
  });

  test('resolves the complaint from the inbox, behind a confirm band', async ({ page }) => {
    await openCoordinator(page);
    await rail(page).getByRole('link', { name: 'Complaints' }).click();

    const table = page.getByRole('table', { name: 'Complaints' });
    await expect(table).toContainText('R7 Complaint Child');
    await expect(table).toContainText(SECTION);
    // No parent name on the contract's thread row, so the From column says whose parent it is.
    await expect(table).toContainText('Parent of R7 Complaint Child');

    await table.getByRole('button', { name: 'Mark resolved' }).first().click();
    await expect(page.getByText('Mark this complaint resolved?')).toBeVisible();
    await page.getByRole('button', { name: 'Yes, resolve it' }).click();

    // The list is a filter *on* status, so the row leaves the open tab entirely.
    await expect(page.getByText('No open complaints.')).toBeVisible();
    await page.getByRole('tab', { name: 'Resolved' }).click();
    await expect(page.getByRole('table', { name: 'Complaints' })).toContainText('R7 Complaint Child');

    // And back, so the file leaves the thread as it found it.
    await page.getByRole('button', { name: 'Reopen' }).first().click();
    await page.getByRole('button', { name: 'Yes, reopen it' }).click();
    await expect(page.getByText('No resolved complaints yet.')).toBeVisible();
  });

  test('posts an announcement to one of her classes', async ({ page }) => {
    await openCoordinator(page);
    await rail(page).getByRole('link', { name: 'Announcements' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Announcements' })).toBeVisible();

    await page.getByRole('button', { name: 'Write an announcement' }).click();
    const sheet = page.getByRole('dialog');
    // Nothing typed yet: the one primary action of the sheet is refused, not hidden.
    await expect(sheet.getByRole('button', { name: 'Post' })).toBeDisabled();

    const body = `R7 reading week — ${Date.now()}`;
    await sheet.getByLabel('Announcement (English)').fill(body);
    await sheet.getByRole('checkbox').first().check();
    await sheet.getByRole('button', { name: 'Post' }).click();

    await expect(page.getByText('Posted to the parents.')).toBeVisible();
    await expect(page.locator('main')).toContainText(body);
  });
});
