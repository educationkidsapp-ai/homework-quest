import { type APIRequestContext } from '@playwright/test';
import { SARA, expect, signIn, test } from './env';
import { api, schoolOfSara, staff, withFlags } from './n4-api';

/**
 * T2 items (a) and (b): **the staff directory, against a real server.**
 *
 * The owner's complaint was that a teacher had no way to see who her department manager is, let
 * alone reach her: the conversation existed but only the manager could open it. This walks the
 * screen that fixes it — the rail row, the card with the job label the `jobParts` are turned into,
 * the `tel:` and the `mailto:`, and Message landing on the conversation itself.
 *
 * **The names come from the server, not from this file.** The owner's list names Lina and Nour,
 * who are people in her school; the seed's department managers are Huda Salem and her American
 * counterpart. So the spec asks `GET /teacher/managers` who Sara's manager is and asserts the
 * screen shows *that* person — which is the assertion that would still hold in the owner's school.
 *
 * `chat` is off in the seed (V15 seeds it off), so the file turns it on and puts it back: the row
 * carries the flag, and `teacher-flow.spec.ts` asserts the flag-off rail.
 */
interface JobPart {
  readonly kind?: string;
  readonly subject?: string;
  readonly curriculum?: string;
  readonly grades?: readonly number[];
}

interface Contact {
  readonly userId: string;
  readonly displayName: string;
  readonly email?: string;
  readonly phone?: string;
  readonly curriculum?: string;
  readonly jobParts?: readonly JobPart[];
}

let context: APIRequestContext;
let restoreFlags: (() => Promise<void>) | null = null;
let managers: readonly Contact[] = [];
let coordinators: readonly Contact[] = [];

async function directory(path: string): Promise<readonly Contact[]> {
  const listed = await context.get(path, { headers: await staff(SARA) });
  expect(listed.ok(), `GET ${path}: HTTP ${listed.status()}`).toBeTruthy();
  return (await listed.json()) as Contact[];
}

test.beforeAll(async () => {
  context = await api();
  restoreFlags = await withFlags(context, await schoolOfSara(context), ['chat']);

  managers = await directory('/teacher/managers');
  coordinators = await directory('/teacher/coordinators');
  expect(
    managers.length,
    'Sara should report to at least one department manager (seed/managers.csv)',
  ).toBeGreaterThan(0);
  expect(
    coordinators.length,
    'Sara teaches Math in 1A British, so a Math coordinator should cover her (seed/coordinators.csv)',
  ).toBeGreaterThan(0);
});

test.afterAll(async () => {
  await restoreFlags?.();
  await context.dispose();
});

test('a teacher finds her department manager, with the job, the phone and the address', async ({ page }) => {
  await signIn(page, SARA);

  // The rail row is the whole point of item (a): the screen existed nowhere before it.
  await page.getByRole('link', { name: 'Manager' }).click();
  await expect(page).toHaveURL(/\/teacher\/manager$/);

  for (const manager of managers) {
    const card = page.getByRole('listitem').filter({ hasText: manager.displayName });
    await expect(card).toBeVisible();
    // "American department manager" / "British department manager" — the job label built from
    // `jobParts`, in the reader's language rather than as a sentence off the wire.
    if (manager.curriculum) {
      await expect(card).toContainText(new RegExp(`${manager.curriculum} department manager`, 'i'));
    }
    if (manager.phone) {
      await expect(card.locator(`a[href="tel:${manager.phone}"]`)).toBeVisible();
    }
    if (manager.email) {
      await expect(card.locator(`a[href="mailto:${manager.email}"]`)).toBeVisible();
    }
  }
});

test('a teacher finds the coordinators of the subjects she teaches', async ({ page }) => {
  await signIn(page, SARA);

  await page.getByRole('link', { name: 'Coordinators' }).click();
  await expect(page).toHaveURL(/\/teacher\/coordinators$/);

  for (const person of coordinators) {
    const card = page.getByRole('listitem').filter({ hasText: person.displayName });
    await expect(card).toBeVisible();
    // One line per `jobParts` entry, each naming the subject it is for. The subject is the word the
    // dashboard translates, so it is matched case-insensitively rather than against the raw value.
    for (const part of person.jobParts ?? []) {
      if (part.subject) await expect(card).toContainText(new RegExp(part.subject, 'i'));
    }
    if (person.phone) await expect(card.locator(`a[href="tel:${person.phone}"]`)).toBeVisible();
  }
});

test('Message opens the conversation she shares with her coordinator', async ({ page }) => {
  const coordinator = coordinators[0]!;
  await signIn(page, SARA);
  await page.goto('/teacher/coordinators');

  const card = page.getByRole('listitem').filter({ hasText: coordinator.displayName });
  await card.getByRole('button', { name: 'Message' }).click();

  // `POST /teacher/chat/staff-threads {coordinatorUserId}` — one row per pair, whichever side asks.
  await expect(page).toHaveURL(/\/teacher\/chat\?thread=/);
  await expect(page.getByRole('heading', { name: coordinator.displayName })).toBeVisible();
  await expect(page.getByPlaceholder('Write a message...')).toBeVisible();
});

test('Message opens the conversation she shares with her manager', async ({ page }) => {
  const manager = managers[0]!;
  await signIn(page, SARA);
  await page.goto('/teacher/manager');

  const card = page.getByRole('listitem').filter({ hasText: manager.displayName });
  await card.getByRole('button', { name: 'Message' }).click();

  // `POST /teacher/chat/staff-threads` answers the thread that exists or opens one, so this is a
  // navigation: she lands on her own Messages screen with the conversation already on it.
  await expect(page).toHaveURL(/\/teacher\/chat\?thread=/);
  await expect(page.getByRole('heading', { name: manager.displayName })).toBeVisible();
  // The composer is hers to use — a teacher writes on a staff thread (MG1).
  await expect(page.getByPlaceholder('Write a message...')).toBeVisible();
});
