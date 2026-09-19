import { request, type Locator, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import {
  ADMIN,
  API,
  expect,
  RUN,
  SARA,
  setLanguage,
  setScheme,
  shoot,
  signInForToken,
  signInAsSara,
  teacherClasses,
  test,
} from './env';

/**
 * Maya places a child the parents' app registered, and takes her out again.
 *
 * The owner's acceptance pass found the hole: a child registered in the app arrives with a
 * curriculum and a grade and **no section**, and every lesson of her grade then shows up for
 * her — one copy per section. The Children tab could add a *new* child and had no way to place
 * an existing one, so the fix was an Admin's job on a screen a teacher cannot reach.
 *
 * The setup is the shape a parent leaves behind, built with the Admin API because only the app
 * creates a roster-less child by itself: a child is created **in** 1A British (so she takes the
 * section's curriculum and grade) and then detached from its roster, which is exactly the row
 * `GET …/children/unassigned` answers with. `teacher.rosterEdit` is off in the seed
 * (`V7__sections.sql`), so this file turns it on for the school and puts it back afterwards —
 * `my-classes.spec.ts` asserts the flag-off shape of the same tab and must still find it.
 */
const SHOTS = resolve(process.cwd(), '../docs/screenshots/roster-place');

const SECTION = '1A British';
const FLAG = 'teacher.rosterEdit';
const CHILD = `Unplaced ${RUN}`;
const PARENT_EMAIL = `unplaced.${RUN.toLowerCase()}@home.test`;

let classId = '';
let schoolId = '';
let childId = '';
let flagWasOn = false;

/** The Admin's own context — the setup a teacher's screen deliberately cannot do. */
async function asAdmin() {
  const api = await request.newContext({ baseURL: API });
  const token = await signInForToken(ADMIN);
  return {
    api,
    headers: { Authorization: `Bearer ${token}` },
    dispose: () => api.dispose(),
  };
}

test.beforeAll(async () => {
  const section = (await teacherClasses()).find((row) => row.className === SECTION);
  expect(section, `Sara should teach ${SECTION} (seed/assignments.csv)`).toBeTruthy();
  classId = section!.classId;

  const me = await request.newContext({ baseURL: API });
  const meResponse = await me.get('/me', {
    headers: { Authorization: `Bearer ${await signInForToken(SARA)}` },
  });
  expect(meResponse.ok(), `GET /me: HTTP ${meResponse.status()}`).toBeTruthy();
  schoolId = ((await meResponse.json()) as { schoolId: string }).schoolId;
  await me.dispose();

  const admin = await asAdmin();
  try {
    const flags = await admin.api.get(`/schools/${schoolId}/flags`);
    flagWasOn = ((await flags.json()) as Record<string, boolean>)[FLAG] === true;
    const flipped = await admin.api.put(`/admin/schools/${schoolId}/flags/${FLAG}`, {
      headers: admin.headers,
      data: { enabled: true },
    });
    expect(flipped.ok(), `PUT ${FLAG}: HTTP ${flipped.status()}`).toBeTruthy();

    // A child of 1A's curriculum and grade …
    const created = await admin.api.post(`/admin/classes/${classId}/children`, {
      headers: admin.headers,
      data: { name: CHILD, parentEmail: PARENT_EMAIL },
    });
    expect(created.ok(), `POST children: HTTP ${created.status()}`).toBeTruthy();
    childId = ((await created.json()) as { id: string }).id;

    // … and then off the roster, which is where the app leaves one.
    const detached = await admin.api.delete(`/admin/classes/${classId}/roster/${childId}`, {
      headers: admin.headers,
    });
    expect(detached.ok(), `DELETE roster: HTTP ${detached.status()}`).toBeTruthy();
  } finally {
    await admin.dispose();
  }
});

test.afterAll(async () => {
  const admin = await asAdmin();
  try {
    if (childId) await admin.api.delete(`/admin/children/${childId}`, { headers: admin.headers });
    if (schoolId && !flagWasOn)
      await admin.api.put(`/admin/schools/${schoolId}/flags/${FLAG}`, {
        headers: admin.headers,
        data: { enabled: false },
      });
  } finally {
    await admin.dispose();
  }
});

/** The Children tab of 1A British, the way Maya reaches it. */
async function openChildren(page: Page): Promise<void> {
  await signInAsSara(page);
  await page.goto(`teacher/classes/${classId}?tab=children`);
  await expect(page.getByRole('table', { name: 'Children' })).toBeVisible();
}

function rosterRow(page: Page, name: string) {
  return page.getByRole('row').filter({ hasText: name });
}

/**
 * Asserts a control is **inside the viewport already**, then clicks it.
 *
 * Playwright scrolls an off-screen control into view before clicking, so a plain `click()`
 * passes on a row whose actions sit past the card's inline edge — which is exactly the defect
 * this file exists to keep out. The box is read first and the click only happens if the whole
 * of it is on screen at the width under test.
 */
async function clickWithoutScrolling(page: Page, control: Locator, what: string): Promise<void> {
  await expect(control).toBeVisible();
  const box = await control.boundingBox();
  const width = page.viewportSize()?.width ?? 0;
  expect(box, `${what} has no box`).toBeTruthy();
  expect(
    box!.x + box!.width,
    `${what} ends at ${Math.round(box!.x + box!.width)}px, past the ${width}px viewport — it can only be reached by scrolling the table sideways`,
  ).toBeLessThanOrEqual(width);
  expect(box!.x, `${what} starts at ${Math.round(box!.x)}px, off the inline start`).toBeGreaterThanOrEqual(0);
  await control.click();
}

/** The row's ⋯ — where Edit, Deactivate and Remove live. */
function rowActions(page: Page, name: string): Locator {
  return rosterRow(page, name).getByRole('button', { name: `Actions for ${name}` });
}

test.describe.configure({ mode: 'serial' });

test('the dialog lists the child the app registered, and Place puts her on the roster', async ({ page }) => {
  await openChildren(page);
  await expect(rosterRow(page, CHILD)).toHaveCount(0);

  await page.getByRole('button', { name: 'Place an existing child' }).click();
  const dialog = page.getByRole('dialog', { name: 'Place an existing child' });
  await expect(dialog).toBeVisible();

  // She is there, with the parent's email beside her name.
  await expect(dialog.getByRole('button', { name: `Place ${CHILD}` })).toBeVisible();
  await expect(dialog.getByText(PARENT_EMAIL)).toBeVisible();

  // The search narrows a grade that may hold a hundred children down to the one row.
  await dialog.getByLabel(/Search/).fill(RUN);
  await expect(dialog.getByRole('button', { name: `Place ${CHILD}` })).toBeVisible();

  await dialog.getByRole('button', { name: `Place ${CHILD}` }).click();
  await expect(dialog).toBeHidden();

  // The toast names where she went, and the roster has her.
  await expect(page.getByRole('status')).toHaveText(`${CHILD} placed in ${SECTION}.`);
  await expect(rosterRow(page, CHILD)).toBeVisible();
});

/**
 * The reason the three verbs moved into a menu: with a column of their own the table was wider
 * than its card, and the only way to Remove was to scroll the table sideways — which Playwright
 * does for you, so the e2e passed while a teacher could not reach the button.
 */
test('the table fits its card at every desktop width, with nothing to scroll sideways', async ({ page }) => {
  await openChildren(page);

  for (const width of [1024, 1280, 1366]) {
    await page.setViewportSize({ width, height: 768 });
    const overflow = await page.locator('.table__scroll').evaluate((el) => el.scrollWidth - el.clientWidth);
    expect(
      overflow,
      `the roster table overflows its card by ${overflow}px at ${width}px`,
    ).toBeLessThanOrEqual(1);
    await expect(rowActions(page, 'Ada Renshaw')).toBeVisible();
  }

  await page.setViewportSize({ width: 1366, height: 768 });
});

test('Remove asks in the red band and takes her off the section, not out of the school', async ({ page }) => {
  await openChildren(page);
  await expect(rosterRow(page, CHILD)).toBeVisible();

  // 1280 is the narrowest desktop the dashboard claims; the ⋯ has to be on screen there without
  // the table being scrolled, or the three verbs are unreachable for the teacher who has it.
  await page.setViewportSize({ width: 1280, height: 768 });
  await clickWithoutScrolling(page, rowActions(page, CHILD), `the ⋯ on ${CHILD}'s row`);
  await page.getByRole('menuitem', { name: 'Remove' }).click();
  await expect(page.getByText(`${CHILD} comes off ${SECTION}`)).toBeVisible();
  // The question is the point: nothing has changed until it is answered.
  await expect(rosterRow(page, CHILD)).toBeVisible();

  await page.getByRole('button', { name: 'Remove from class' }).click();
  await expect(rosterRow(page, CHILD)).toHaveCount(0);
  await page.setViewportSize({ width: 1366, height: 768 });

  // Still in the school: the place dialog offers her again, which is the way back.
  await page.getByRole('button', { name: 'Place an existing child' }).click();
  const dialog = page.getByRole('dialog', { name: 'Place an existing child' });
  await dialog.getByLabel(/Search/).fill(RUN);
  await expect(dialog.getByRole('button', { name: `Place ${CHILD}` })).toBeVisible();
});

test('the screenshot set, EN and AR, light and dark', async ({ page }) => {
  test.setTimeout(120_000);
  await mkdir(SHOTS, { recursive: true });
  await openChildren(page);

  for (const language of ['en', 'ar'] as const) {
    await setLanguage(page, language);
    await expect(page.getByRole('table')).toBeVisible();
    await shoot(page, `${SHOTS}/01-children-${language}.png`, page.getByRole('table'));

    await page.getByRole('button', { name: /Place an existing child|إضافة طفل مسجَّل/ }).click();
    const dialog = page.getByRole('dialog');
    await shoot(page, `${SHOTS}/02-place-dialog-${language}.png`, dialog);
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
  }

  await setLanguage(page, 'en');
  await setScheme(page, 'dark');
  await page.getByRole('button', { name: 'Place an existing child' }).click();
  await shoot(page, `${SHOTS}/03-place-dialog-dark.png`, page.getByRole('dialog'));
  await page.keyboard.press('Escape');
  await setScheme(page, 'light');
});
