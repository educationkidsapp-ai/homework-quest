import { request, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import {
  ADMIN,
  API,
  addStopThroughForm,
  expect,
  RUN,
  SARA,
  setLanguage,
  setScheme,
  shoot,
  signInAsSara,
  signInForToken,
  teacherClasses,
  test,
} from './env';

/**
 * N4.4, `docs/teacher-flow.md` §4 step 10: Sara sets an exam for 1A with a window that opens in
 * two minutes, publishes it, finds it on the class calendar wearing its ribbon, and opens its
 * results — where the whole class is still absent, because the window has only just opened.
 *
 * **Two minutes, not two days.** The window has to be in the future when she creates it (the
 * settings card freezes at the opening instant and `PATCH` answers 409 after it), and close
 * enough that the run does not depend on a clock that has moved. The suite never waits for it to
 * open: the assertions are about the states before and around it, which is what §8's screens
 * actually show a teacher.
 *
 * **The flag.** `exams` is off in the one-school seed (`V7__sections.sql`), so this file turns it
 * on for the school and puts it back afterwards — `my-classes.spec.ts` asserts the flag-off shape
 * of the class page's tabs and must still find it.
 *
 * Manual is the source: `LLM_PROVIDER=fake` makes the pipeline instant, and one hand-written stop
 * is all `publishReady` asks for. Nobody sits it — there is no web player yet and the owner tests
 * the child's side on the mobile app — so the results page is asserted on exactly the shape §8
 * has to get right first: a class that is entirely absent.
 */
const SHOTS = resolve(process.cwd(), '../docs/screenshots/n4.4');

const SECTION = '1A British';
const TITLE = `Mid-term ${RUN}`;
const FLAG = 'exams';

let classId = '';
let schoolId = '';
let examId = '';
let wasOn = false;
let zone = 'UTC';

async function asAdmin() {
  const api = await request.newContext({ baseURL: API });
  const token = await signInForToken(ADMIN);
  return { api, headers: { Authorization: `Bearer ${token}` }, dispose: () => api.dispose() };
}

test.beforeAll(async () => {
  const section = (await teacherClasses()).find((row) => row.className === SECTION);
  expect(section, `Sara should teach ${SECTION} (seed/assignments.csv)`).toBeTruthy();
  classId = section!.classId;

  const me = await request.newContext({ baseURL: API });
  const response = await me.get('/me', {
    headers: { Authorization: `Bearer ${await signInForToken(SARA)}` },
  });
  expect(response.ok(), `GET /me: HTTP ${response.status()}`).toBeTruthy();
  schoolId = ((await response.json()) as { schoolId: string }).schoolId;
  const settings = await me.get('/platform-settings');
  zone = ((await settings.json()) as { timezone?: string }).timezone ?? 'UTC';
  await me.dispose();

  const admin = await asAdmin();
  try {
    const flags = (await (await admin.api.get(`/schools/${schoolId}/flags`)).json()) as Record<
      string,
      boolean
    >;
    wasOn = flags[FLAG] === true;
    const flipped = await admin.api.put(`/admin/schools/${schoolId}/flags/${FLAG}`, {
      headers: admin.headers,
      data: { enabled: true },
    });
    expect(flipped.ok(), `PUT ${FLAG}: HTTP ${flipped.status()}`).toBeTruthy();
  } finally {
    await admin.dispose();
  }
});

test.afterAll(async () => {
  const admin = await asAdmin();
  try {
    // The exam is a lesson, so it is removed the way every other lesson this suite makes is.
    if (examId) {
      const staff = { Authorization: `Bearer ${await signInForToken(SARA)}` };
      await admin.api.post(`/teacher/lessons/${examId}/unpublish`, { headers: staff });
      await admin.api.delete(`/admin/lessons/${examId}`, { headers: admin.headers });
    }
    if (schoolId && !wasOn)
      await admin.api.put(`/admin/schools/${schoolId}/flags/${FLAG}`, {
        headers: admin.headers,
        data: { enabled: false },
      });
  } finally {
    await admin.dispose();
  }
});

/** The seed's own week, as `Date.getUTCDay()` numbers it: Sunday to Thursday. */
const TEACHING = new Set([0, 1, 2, 3, 4]);

/** An instant as the wall clock of a given zone reads it. */
function fieldsIn(instant: Date, zone: string): { readonly date: string; readonly time: string } {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: zone,
    hour12: false,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).formatToParts(instant);
  const read = (type: string): string => parts.find((part) => part.type === type)?.value ?? '00';
  const hour = read('hour') === '24' ? '00' : read('hour');
  return { date: `${read('year')}-${read('month')}-${read('day')}`, time: `${hour}:${read('minute')}` };
}

function isTeachingDay(dateIso: string): boolean {
  return TEACHING.has(new Date(`${dateIso}T00:00:00Z`).getUTCDay());
}

/**
 * A window opening two minutes from now — nudged forward when that instant is a day the school
 * does not teach on **in either clock**.
 *
 * Two clocks, and that is the point. The form takes a wall clock in the school's timezone
 * (Asia/Riyadh in the seed) and sends an instant; the server then derives the exam's lesson day
 * from that instant **in UTC** (`ExamService.create`), and refuses a day the school does not
 * teach on. So Sunday 02:14 in Riyadh is Saturday 23:14 in UTC and is refused, although it is a
 * perfectly ordinary Sunday morning to the teacher who typed it. That is a server defect (the
 * day should be derived in the school's zone) and it is reported rather than papered over; until
 * it is fixed, a test that wants a real window has to find an instant both clocks agree about.
 *
 * Stepping by an hour rather than jumping to a fixed time keeps "two minutes from now" true
 * whenever it can be — which is every run except one started in the small hours of a weekend.
 */
function windowFrom(zone: string): {
  readonly date: string;
  readonly opens: string;
  readonly closes: string;
} {
  let opens = new Date(Date.now() + 2 * 60_000);
  for (let step = 0; step < 200; step += 1) {
    if (isTeachingDay(fieldsIn(opens, zone).date) && isTeachingDay(fieldsIn(opens, 'UTC').date)) break;
    opens = new Date(opens.getTime() + 60 * 60_000);
  }
  const local = fieldsIn(opens, zone);
  const later = fieldsIn(new Date(opens.getTime() + 60 * 60_000), zone);
  // Clamped so a window opened at ten to midnight does not close on the following day.
  return { date: local.date, opens: local.time, closes: later.date === local.date ? later.time : '23:59' };
}

test.describe.configure({ mode: 'serial' });

test('sets an exam, publishes it, and finds it on the calendar with its ribbon', async ({ page }) => {
  test.setTimeout(120_000);
  await signInAsSara(page);
  await page.goto(`teacher/classes/${classId}?tab=exams`);

  await page.getByRole('link', { name: 'New exam' }).click();
  await expect(page.getByRole('heading', { name: 'New exam' })).toBeVisible({ timeout: 15_000 });

  await page.getByLabel('Title', { exact: true }).fill(TITLE);
  const when = windowFrom(zone);
  await page.getByLabel('Opens on').fill(when.date);
  await page.getByLabel('Opens at').fill(when.opens);
  await page.getByLabel('Closes on').fill(when.date);
  await page.getByLabel('Closes at').fill(when.closes);
  await page.getByRole('radio', { name: /Level 1/ }).check();
  await page.getByLabel('Results release').selectOption('manual');
  await page.getByRole('button', { name: /Write it yourself/ }).click();

  await page.getByRole('button', { name: 'Create the exam' }).click();

  // The editor she lands in is the lesson editor, with the settings card on top of it.
  await expect(page.getByRole('heading', { name: TITLE })).toBeVisible({ timeout: 60_000 });
  const settings = page.locator('[data-hq-exam-settings]');
  await expect(settings).toBeVisible();
  await expect(settings.getByText(/Children see it only between/)).toBeVisible();
  examId = new URL(page.url()).pathname.split('/').pop() ?? '';
  expect(examId, 'the editor URL should carry the new exam id').toBeTruthy();

  // One stop is all `publishReady` asks of a hand-written lesson — and of a hand-written exam.
  await addStopThroughForm(page, {
    title: `Which shape has three sides ${RUN}`,
    question: 'Pip says: show a triangle and a circle, and ask which one has three sides.',
    type: 'choice',
  });

  // Publish. An exam never gets the multi-class sheet (a copy of an exam would carry no
  // window), so this is the plain confirm band — with §8's sentence on it, because that is
  // what publishing promises.
  await page.getByRole('button', { name: 'Publish', exact: true }).click();
  const confirm = page.getByRole('alert').filter({ hasText: 'Publish this lesson?' });
  await expect(confirm).toBeVisible();
  await expect(confirm.getByText(/Children see it only between/)).toBeVisible();
  await confirm.getByRole('button', { name: 'Publish', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Unpublish' })).toBeVisible({ timeout: 30_000 });

  // The class calendar carries the exam ribbon on the day the window opens.
  await page.goto(`teacher/classes/${classId}?tab=calendar`);
  const cell = page.locator(`[data-date="${when.date}"]`);
  await expect(cell).toBeVisible({ timeout: 15_000 });
  await expect(cell.getByText('Exam')).toBeVisible();

  // And the Exams tab lists it, scheduled until its window opens.
  await page.goto(`teacher/classes/${classId}?tab=exams`);
  const row = page.getByRole('row').filter({ hasText: TITLE });
  await expect(row).toBeVisible({ timeout: 15_000 });
  await expect(row.getByText(/Scheduled|Open now/)).toBeVisible();
});

test('the results page shows a class that is entirely absent, and exports it', async ({ page }) => {
  expect(examId, 'the first test should have created the exam').toBeTruthy();
  await signInAsSara(page);
  await page.goto(`teacher/exams/${examId}/results`);

  await expect(page.getByRole('heading', { name: TITLE })).toBeVisible({ timeout: 15_000 });

  // Nobody has sat it, so every child is absent and the average is a dash, not a zero.
  const table = page.getByRole('table', { name: 'Every child' });
  await expect(table).toBeVisible();
  const absent = table.getByText('Absent');
  expect(await absent.count()).toBeGreaterThan(0);
  await expect(page.getByText('Nobody has sat it yet, so there is nothing to spread.')).toBeVisible();

  // Re-open is offered for an absent child, and asks before it spends her one extra sitting.
  const first = table.getByRole('row').filter({ hasText: 'Absent' }).first();
  await first.getByRole('button', { name: 'Re-open' }).click();
  // The confirmation is the red band, scoped by its own heading — every absent row has a
  // Re-open button of its own, and clicking one of those would replace the band rather than
  // answer it.
  const confirm = page.getByRole('alert').filter({ hasText: /^Re-open for/ });
  await expect(confirm.getByText(/and only one/)).toBeVisible();
  await confirm.getByRole('button', { name: 'Re-open', exact: true }).click();
  await expect(page.getByText(/may sit it again until/)).toBeVisible({ timeout: 15_000 });

  // The CSV is fetched with the bearer and handed to the browser, never linked to.
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Export CSV' }).click();
  const file = await download;
  expect(file.suggestedFilename()).toMatch(/\.csv$/);
});

test('screenshots', async ({ page }) => {
  await mkdir(SHOTS, { recursive: true });
  await signInAsSara(page);

  const shotsOf = async (suffix: string) => {
    await page.goto(`teacher/classes/${classId}?tab=exams`);
    const tab = page.getByRole('table').first();
    await shoot(page, `${SHOTS}/exams-tab-1366-${suffix}.png`, tab, { fullPage: true });

    await page.goto(`teacher/exams/${examId}/results`);
    const heading = page.getByRole('heading', { name: TITLE });
    await shoot(page, `${SHOTS}/exam-results-1366-${suffix}.png`, heading, { fullPage: true });
  };

  await shotsOf('en');
  await setScheme(page, 'dark');
  await shotsOf('en-dark');
  await setScheme(page, 'light');

  await setLanguage(page, 'ar');
  await shotsOf('ar');
  await setLanguage(page, 'en');

  // The phone: §2's rule is that nothing scrolls sideways at 375.
  await page.setViewportSize({ width: 375, height: 812 });
  await newExam(page);
  await shoot(page, `${SHOTS}/new-exam-375-en.png`, page.getByRole('heading', { name: 'New exam' }), {
    fullPage: true,
  });
  await setLanguage(page, 'ar');
  await newExam(page);
  await shoot(page, `${SHOTS}/new-exam-375-ar.png`, page.getByRole('button').first(), {
    fullPage: true,
  });
  await setLanguage(page, 'en');
  await page.setViewportSize({ width: 1366, height: 768 });

  await newExam(page);
  await shoot(page, `${SHOTS}/new-exam-1366-en.png`, page.getByRole('heading', { name: 'New exam' }), {
    fullPage: true,
  });
});

async function newExam(page: Page): Promise<void> {
  await page.goto(`teacher/exams/new?classId=${classId}`);
  await expect(page.getByRole('button').first()).toBeVisible({ timeout: 15_000 });
}
