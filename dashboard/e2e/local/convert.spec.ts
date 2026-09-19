import { request, type Locator, type Page } from '@playwright/test';
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
 * CR4 §4, as Sara meets it: a file becomes **text** before the model reads a word of it.
 *
 * She uploads a PDF, the row under Files says *Converting…* and then *Ready · N words*, she
 * opens the preview and reads the page rather than the document format, she edits that text by
 * hand, and the pipeline starts again from Convert with her words. Everything is done through
 * the screen — the only API calls here are the ones that set the lesson up and the one that
 * reads the strip back, because "the pipeline continued" is a fact about the server.
 *
 * Runs against the local H2 server on the one-school seed with `LLM_PROVIDER=fake` (see
 * `README.md`). The two conversion binaries are absent on a laptop and in CI, so the server
 * falls back to its own text extraction and records the method as `text` — which is why the
 * hint beside *Ready* is not asserted here; `file-conversion.spec.ts` covers all three.
 */
const SHOTS = resolve(process.cwd(), '../docs/screenshots/cr4');
const ONE_PAGE_PDF = resolve(process.cwd(), 'e2e/fixtures/one-page.pdf');
const TITLE = `Converted first ${RUN}`;
/** A day this run owns: a class holds one lesson per day, and the database is never reset. */
const DATE = schoolDayFromNow(400 + (Math.floor(Date.now() / 1000) % 60));

/** The words she types over the converted text — short, and unmistakably hers. */
const TYPED = '# Shapes on the page\n\nA triangle has three sides.\n\n- a triangle\n- a circle';

let classId = '';
let saraToken = '';
let lessonId = '';
let lessonUrl = '';

test.beforeAll(async () => {
  await mkdir(SHOTS, { recursive: true });
  saraToken = await signInForToken(SARA);
  const api = await request.newContext({ baseURL: API });
  const classes = await api.get('/teacher/classes', {
    headers: { Authorization: `Bearer ${saraToken}` },
  });
  const rows = (await classes.json()) as { classId: string; className: string; subject: string }[];
  const mine = rows.find((row) => /1A British/i.test(row.className) && row.subject === 'math');
  expect(mine, 'the seed should give Sara a 1A British Math section').toBeTruthy();
  classId = mine!.classId;
  await api.dispose();
});

test.afterAll(async () => {
  await removeLessonsOfThisRun();
});

/** The one file's row under Files, and the pill inside it. */
function fileRow(page: Page): Locator {
  return page.locator('[data-hq-sources] [data-hq-file]').first();
}

function statusPill(page: Page): Locator {
  return fileRow(page).locator('[data-hq-file-status]');
}

/**
 * The preview's `<dialog>`, not its `hq-dialog` host.
 *
 * A modal `<dialog>` is painted in the top layer, so the custom element wrapping it has an empty
 * bounding box — which Playwright reads, correctly, as hidden.
 */
function previewDialog(page: Page): Locator {
  return page.locator('[data-hq-preview-dialog] dialog');
}

function typeDialog(page: Page): Locator {
  return page.locator('[data-hq-type-dialog] dialog');
}

async function openPreview(page: Page): Promise<void> {
  await fileRow(page).locator('[data-hq-preview]').click();
  await expect(previewDialog(page)).toBeVisible();
}

/** "Type the text" on a file that converted is reached through the preview's own Edit. */
async function openTypeText(page: Page): Promise<void> {
  await openPreview(page);
  await previewDialog(page).locator('[data-hq-edit-text]').click();
  await expect(typeDialog(page)).toBeVisible();
}

/** Esc rather than the Close button, whose name is translated. */
async function closeDialog(page: Page, dialog: Locator): Promise<void> {
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
}

/** The pipeline step strip's steps, in order, as text. */
async function stepNames(page: Page): Promise<string[]> {
  const steps = page.getByRole('list', { name: /pipeline|المسار/i }).getByRole('listitem');
  return (await steps.allTextContents()).map((text) => text.replace(/\s+/g, ' ').trim());
}

/** What the server says the convert and analyze steps are doing — the fact behind the screen. */
async function stepStatus(step: string): Promise<string> {
  const api = await request.newContext({ baseURL: API });
  try {
    const response = await api.get(`/teacher/lessons/${lessonId}`, {
      headers: { Authorization: `Bearer ${saraToken}` },
    });
    if (!response.ok()) return `HTTP ${response.status()}`;
    const lesson = (await response.json()) as { steps?: { step: string; status: string }[] };
    return lesson.steps?.find((row) => row.step === step)?.status ?? 'absent';
  } finally {
    await api.dispose();
  }
}

test.describe.configure({ mode: 'serial' });

test('her PDF is converted to text, and the row says so', async ({ page }) => {
  test.setTimeout(180_000);
  await signInAsSara(page);

  const query = new URLSearchParams({ classId, subject: 'math', date: DATE });
  await page.goto(`teacher/lessons/new?${query.toString()}`);
  await expect(page.getByRole('heading', { name: 'New lesson' })).toBeVisible();
  await page.getByLabel('Title').fill(TITLE);
  await page.getByRole('button', { name: /Upload a PDF/ }).click();
  await page.getByLabel('Drop a PDF here').setInputFiles(ONE_PAGE_PDF);
  await page.getByRole('button', { name: 'Create and read the PDF' }).click();

  await expect(page).toHaveURL(/\/teacher\/lessons\/[0-9a-f-]+/, { timeout: 60_000 });
  lessonUrl = new URL(page.url()).pathname.replace(/^\/dashboard\//, '');
  lessonId = lessonUrl.split('/').pop()!;

  // Convert sits between Upload and Analyze, named in words rather than in ours.
  // The first three, in order; what follows them is the generation half and not this spec's.
  expect((await stepNames(page)).slice(0, 3)).toEqual([
    expect.stringContaining('upload'),
    expect.stringContaining('Convert to text'),
    expect.stringContaining('analyze'),
  ]);

  // Two states, in order — but only one of them is guaranteed to be *seen*. Without the two
  // conversion binaries the server's own extraction finishes a one-page PDF in well under a
  // poll, so insisting on a frame that says "converting" would be insisting on losing a race.
  // What must hold is that the pill is never anything else on the way, and that it settles on
  // Ready with a word count; `lesson-sources.spec.ts` renders the converting state outright.
  await expect(statusPill(page)).toHaveAttribute('data-hq-file-status', /converting|ready/, {
    timeout: 30_000,
  });
  await expect(statusPill(page)).toHaveAttribute('data-hq-file-status', 'ready', { timeout: 60_000 });
  await expect(statusPill(page)).toHaveText(/Ready · [\d,]+ words/);

  expect(await stepStatus('convert')).toBe('done');
});

test('the preview shows the page, not the file — in English, Arabic and the dark scheme', async ({
  page,
}) => {
  test.setTimeout(180_000);
  await signInAsSara(page);
  await page.goto(lessonUrl);
  await expect(statusPill(page)).toHaveAttribute('data-hq-file-status', 'ready', { timeout: 60_000 });

  await shoot(page, `${SHOTS}/sources-status-en.png`, statusPill(page));
  await setScheme(page, 'dark');
  await shoot(page, `${SHOTS}/sources-status-dark.png`, statusPill(page));
  await setScheme(page, 'light');

  await openPreview(page);
  const prose = previewDialog(page).locator('[data-hq-markdown-preview]');

  // The point of the whole preview: what the model will read, as a page. `one-page.pdf` opens
  // with a heading, and it arrives as a heading — no `#`, and nothing that is a tag.
  await expect(prose).toBeVisible();
  await expect(prose.getByRole('heading').first()).toBeVisible();
  await expect(prose).not.toContainText(/^#|\*\*/);
  await shoot(page, `${SHOTS}/preview-en.png`, prose);
  await closeDialog(page, previewDialog(page));

  // Arabic is chosen from the header, which a modal dialog covers — so the language changes
  // first and the dialog is opened again, rather than the other way round.
  await setLanguage(page, 'ar');
  await shoot(page, `${SHOTS}/sources-status-ar.png`, statusPill(page));
  await openPreview(page);
  await expect(prose).toBeVisible();
  await shoot(page, `${SHOTS}/preview-ar.png`, prose);
  await closeDialog(page, previewDialog(page));
  await setLanguage(page, 'en');
});

test('she types the text herself and the pipeline starts again from Convert', async ({ page }) => {
  test.setTimeout(240_000);
  await signInAsSara(page);
  await page.goto(lessonUrl);
  await expect(statusPill(page)).toHaveAttribute('data-hq-file-status', 'ready', { timeout: 60_000 });

  await setLanguage(page, 'ar');
  await openTypeText(page);
  await shoot(page, `${SHOTS}/type-the-text-ar.png`, typeDialog(page));
  await closeDialog(page, typeDialog(page));
  await setLanguage(page, 'en');

  await openTypeText(page);
  const box = typeDialog(page).getByLabel('The text of this file');
  // Prefilled with what was converted, so she edits rather than starts again.
  await expect(box).not.toHaveValue('');
  await shoot(page, `${SHOTS}/type-the-text-en.png`, typeDialog(page));

  await box.fill(TYPED);
  await page.getByRole('button', { name: 'Save this text' }).click();
  await expect(typeDialog(page)).toBeHidden({ timeout: 30_000 });

  // Her words are now what the file holds, and the model is reading them.
  await expect(statusPill(page)).toHaveAttribute('data-hq-file-status', 'ready', { timeout: 90_000 });
  await openPreview(page);
  await expect(previewDialog(page).getByRole('heading', { name: 'Shapes on the page' })).toBeVisible({
    timeout: 30_000,
  });
  await closeDialog(page, previewDialog(page));

  // The fact behind the screen: Convert ran again and Analyse followed it.
  await expect
    .poll(() => stepStatus('analyze'), { timeout: 120_000, intervals: [2_000] })
    .toMatch(/running|done/);
});
