import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { type APIRequestContext, type Page } from '@playwright/test';
import {
  COORDINATOR,
  MANAGER,
  expect,
  setLanguage,
  setScheme,
  shoot,
  signIn,
  teacherClasses,
  test,
} from './env';
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
 * R7's acceptance (`docs/coordinator-flow.md` §5), redrawn by D5 on B6's contract, against the
 * built bundle and a local API on H2 started with `SEED_SCHOOL=true`.
 *
 * **The complaint is real.** Nothing here writes into the database behind the product's back: a
 * fake-auth parent creates a child with 1A British's join code (the app's own path, `n4-api.ts`),
 * asks `GET /children/{id}/complaints/recipients` whom she may complain to, and opens a complaint
 * to the subject's coordinator with `POST /children/{id}/complaints` — B6's only way a complaint
 * can exist. Rasha Kamal then sees it on her **Complaints** page, never in Messages.
 *
 * `chat` and `announcements` are both off in the one-school seed, so this file turns them on and
 * puts them back — `coordinator-area.spec.ts` asserts the flag-off rail (four links) and would
 * fail on a leftover.
 */
const SECTION = '1A British';
const TITLE = 'Homework not marked';
/** The smallest document a PDF reader opens: B5 checks the bytes, not only the name. */
const MINIMAL_PDF =
  '%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n' +
  '3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n';
const SHOTS = resolve(process.cwd(), '../docs/screenshots/complaints-pages');

let context: APIRequestContext;
let schoolId = '';
let restoreFlags: (() => Promise<void>) | null = null;
let parent: Parent;
let childId = '';
let complaintId = '';

test.beforeAll(async () => {
  context = await api();
  schoolId = await schoolOfSara(context);
  restoreFlags = await withFlags(context, schoolId, ['chat', 'announcements']);

  const section = (await teacherClasses()).find(
    (row) => row.className === SECTION && row.subject.toLowerCase() === 'math',
  );
  expect(section, `Sara should teach Math in ${SECTION} (seed/assignments.csv)`).toBeTruthy();

  parent = parentOf(`hq-d5-${Date.now()}`);
  childId = await createChild(
    context,
    parent,
    'R7 Complaint Child',
    await joinCodeOf(context, section!.classId),
  );

  // Whom she may complain to about this child: its teachers, the coordinators of its subjects and
  // the manager of its department (B6). The seeded Math coordinator is the one this file signs in.
  const listed = await context.get(`/children/${childId}/complaints/recipients`, {
    headers: bearer(parent.token),
  });
  expect(listed.ok(), `GET /children/{id}/complaints/recipients: HTTP ${listed.status()}`).toBeTruthy();
  const rows = (await listed.json()) as { staffId: string; name: string; peerRole: string }[];
  const coordinator = rows.find((row) => row.peerRole === 'COORDINATOR' && row.name === 'Rasha Kamal');
  expect(coordinator, 'the seeded Math coordinator should be reachable from 1A British').toBeTruthy();

  const opened = await context.post(`/children/${childId}/complaints`, {
    headers: bearer(parent.token),
    data: {
      staffId: coordinator!.staffId,
      title: TITLE,
      body: 'Nobody has marked the homework for two weeks.',
    },
  });
  expect(opened.ok(), `POST the complaint: HTTP ${opened.status()} ${await opened.text()}`).toBeTruthy();
  complaintId = ((await opened.json()) as { complaint: { id: string } }).complaint.id;
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

test.describe('the coordinator’s messages, complaints and broadcasts', () => {
  test('her rail has Messages and Complaints apart, and the complaint is not a message', async ({ page }) => {
    // Her threads are read at sign-in; the assertion below is only worth something once they are.
    const threads = page.waitForResponse((response) => response.url().includes('/coordinator/chat/threads'));
    await openCoordinator(page);
    await threads;
    expect(complaintId).not.toBe('');

    await expect(rail(page).getByRole('link', { name: /^Messages/ })).toBeVisible();
    // The open count rides on the row.
    await expect(rail(page).getByRole('link', { name: /^Complaints/ })).toContainText('1');

    // B6: no Messages list carries a complaint.
    await rail(page)
      .getByRole('link', { name: /^Messages/ })
      .click();
    await expect(page.getByRole('heading', { level: 1, name: 'Messages' })).toBeVisible();
    await expect(page.locator('main').first()).not.toContainText('R7 Complaint Child');
  });

  test('answers the complaint in its own conversation, resolves it and reopens it', async ({ page }) => {
    await mkdir(SHOTS, { recursive: true });
    await openCoordinator(page);
    await rail(page)
      .getByRole('link', { name: /^Complaints/ })
      .click();

    const table = page.getByRole('table', { name: 'Complaints' });
    await expect(table).toContainText(TITLE);
    await expect(table).toContainText('R7 Complaint Child');
    await expect(table).toContainText(SECTION);
    await shoot(page, `${SHOTS}/01-list-en.png`, table);

    await table.getByRole('link', { name: TITLE }).click();
    await expect(page).toHaveURL(new RegExp(`open=${complaintId}`));
    const stream = page.getByRole('list', { name: 'Conversation' });
    await expect(stream).toContainText('Nobody has marked the homework');

    await page.getByRole('textbox', { name: 'Reply' }).fill('Thank you — I will speak to the teacher today.');
    // D4's composer on a complaint (B5): the file uploads on pick and goes with the reply by id.
    await page.locator('hq-complaint-conversation input[type="file"]').setInputFiles({
      name: 'marking-plan.pdf',
      mimeType: 'application/pdf',
      buffer: Buffer.from(MINIMAL_PDF),
    });
    const send = page.getByRole('button', { name: 'Send reply' });
    await expect(send).toBeEnabled();
    await send.click();
    await expect(stream).toContainText('I will speak to the teacher today');
    await expect(stream).toContainText('marking-plan.pdf');
    await expect(stream).not.toContainText('Sending…');

    await page.getByRole('button', { name: 'Mark resolved' }).click();
    await expect(page.getByText('Mark this complaint resolved?')).toBeVisible();
    await page.getByRole('button', { name: 'Yes, resolve it' }).click();
    await expect(stream).toContainText('Resolved by Rasha Kamal');
    await expect(page.getByRole('button', { name: 'Reopen' })).toBeVisible();
    await shoot(page, `${SHOTS}/02-conversation-en.png`, stream);

    await setLanguage(page, 'ar');
    await setScheme(page, 'dark');
    await shoot(
      page,
      `${SHOTS}/03-conversation-ar-dark.png`,
      page.locator('hq-complaint-conversation section'),
    );
    await setScheme(page, 'light');
    await setLanguage(page, 'en');

    // Back to the list: it moved tabs, so the open tab is empty and Resolved has it.
    await page.getByRole('button', { name: 'All complaints' }).click();
    await expect(page.getByText('No open complaints.')).toBeVisible();
    await page.getByRole('tab', { name: /Resolved/ }).click();
    await expect(page.getByRole('table', { name: 'Complaints' })).toContainText(TITLE);

    // And back, so the file leaves the complaint as it found it.
    await page.getByRole('table', { name: 'Complaints' }).getByRole('link', { name: TITLE }).click();
    await page.getByRole('button', { name: 'Reopen' }).click();
    await page.getByRole('button', { name: 'Yes, reopen it' }).click();
    await expect(page.getByRole('list', { name: 'Conversation' })).toContainText('Reopened by Rasha Kamal');
  });

  /**
   * B6: the department manager reads the complaints addressed to her coordinators — and may move
   * them — but the reply is the recipient's, so she gets a note where the composer would be.
   */
  test('the manager reads it as a supervisor, with no composer', async ({ page }) => {
    await signIn(page, MANAGER);
    await page.goto(`management/complaints?open=${complaintId}`);
    const stream = page.getByRole('list', { name: 'Conversation' });
    await expect(stream).toContainText('Nobody has marked the homework');
    await expect(page.getByRole('textbox', { name: 'Reply' })).toHaveCount(0);
    await expect(page.getByRole('note')).toContainText('Only Rasha Kamal replies in this complaint');
    await expect(page.getByRole('button', { name: 'Mark resolved' })).toBeVisible();
    await shoot(page, `${SHOTS}/04-supervisor-en.png`, stream);
  });

  /**
   * RM3b: her announcement is a broadcast now — `POST /coordinator/broadcasts` writes the
   * `announcements` rows the app's shipped screen reads as a side effect, so the parent's end is
   * unchanged and this test moved to `announcements.spec.ts` with the composer. What is asserted
   * here is that RM3b's own path still lands somewhere: MH2 item 5 made `announcements` the screen
   * again, so `broadcasts` is the redirect now and the arrow points the other way.
   */
  test('sends the old Broadcasts URL to Announcements', async ({ page }) => {
    await openCoordinator(page);
    await page.goto('coordinator/broadcasts');
    await expect(page).toHaveURL(/\/coordinator\/announcements$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Announcements' })).toBeVisible();
  });
});
