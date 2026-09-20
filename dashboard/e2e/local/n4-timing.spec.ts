import { type APIRequestContext } from '@playwright/test';
import { writeFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { RUN, expect, teacherClasses, test } from './env';
import {
  adminHeaders,
  api,
  attempt,
  createChild,
  joinCodeOf,
  parentOf,
  paperOf,
  publishExam,
  publishHomework,
  removeChild,
  schoolOfSara,
  staff,
  upload,
  withFlags,
  type Parent,
} from './n4-api';

/**
 * N4.5's second half: **what the gradebook and an exam's results cost** on a class the size the
 * brief asks about — 30 children × 20 lessons — rather than on the four rows `seed/attempts.csv`
 * leaves behind.
 *
 * The target is p95 < 1 s over 20 calls each. Both reads are the ones that grow with the class:
 * `GET /teacher/classes/{id}/gradebook` scores every child against every published lesson in its
 * window, and `GET /teacher/exams/{id}/results` scores a whole roster over a derived paper. A
 * regression here is an N+1 or a missing index, and it will not show up on the seed.
 *
 * **The fixture is built through the API**, the same way `n4-flow.spec.ts` builds its one lesson:
 * 30 children created by one parent with 1B's join code, 20 hand-written homeworks published on
 * the 20 most recent teaching days (the gradebook's default window is the last 30 days), and one
 * upload per child carrying her answers to all of them — 30 × 20 × 5 = 3 000 attempts. Nothing is
 * written behind the product's back, so the numbers are the numbers the product really produces.
 *
 * **1B, not 1A.** `n4-flow.spec.ts` asserts a grid of its own on 1A; this file would add 30 rows
 * and 20 columns to it. Sara teaches both, so the read path is identical.
 *
 * Local only, and slow by nature: it is skipped unless `E2E_TIMING` is set, so the everyday suite
 * stays at four minutes. The numbers land in `e2e/.output/n4-timing.json` and in the run's log.
 *
 *     E2E_TIMING=1 pnpm e2e:local n4-timing
 */
const CHILDREN = 30;
const LESSONS = 20;
const CALLS = 20;
const OUT = resolve(process.cwd(), 'e2e/.output');

const SECTION = '1B British';
const FLAGS = ['gradebook', 'openStopMarking', 'exams'] as const;

let context: APIRequestContext;
let restoreFlags: () => Promise<void> = async () => {};
let classId = '';
let parent: Parent;
const childIds: string[] = [];
const lessonIds: string[] = [];
let examId = '';

test.skip(
  !process.env['E2E_TIMING'] || !!process.env['E2E_BASE_URL'],
  'builds 30 children and 20 lessons: local H2 only, and only when E2E_TIMING asks for it',
);
test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  test.setTimeout(600_000);
  context = await api();
  const section = (await teacherClasses()).find((row) => row.className === SECTION);
  expect(section, `Sara should teach ${SECTION}`).toBeTruthy();
  classId = section!.classId;
  restoreFlags = await withFlags(context, await schoolOfSara(context), FLAGS);

  parent = parentOf(`n45t-${RUN.toLowerCase()}`);
  const joinCode = await joinCodeOf(context, classId);
  for (let i = 0; i < CHILDREN; i += 1)
    childIds.push(await createChild(context, parent, `Timing ${RUN}-${String(i).padStart(2, '0')}`, joinCode));

  const stopsByLesson = new Map<string, readonly string[]>();
  for (const date of pastTeachingDays(LESSONS)) {
    const lesson = await publishHomework(context, { classId, title: `Timing ${RUN} ${date}`, date });
    lessonIds.push(lesson.lessonId);
    stopsByLesson.set(lesson.lessonId, lesson.stopIds);
  }

  // One upload per child: 100 attempts each, inside the 500 the endpoint takes at once. Every
  // fourth child gets one wrong answer and every third leaves the retell to the teacher, so the
  // grid the read has to build is a mix of bands and of cells waiting for a mark rather than a
  // wall of identical 100s.
  for (const [index, childId] of childIds.entries()) {
    const attempts: Record<string, unknown>[] = [];
    const at = Date.now() - 60_000;
    for (const [lesson, stops] of stopsByLesson) {
      const skipRetell = index % 3 === 0;
      stops.forEach((stopId, position) => {
        const open = position === stops.length - 1;
        if (open && skipRetell) return;
        attempts.push(
          attempt({
            lessonId: lesson,
            stopId,
            correct: !(index % 4 === 0 && position === 1),
            at: at + attempts.length,
          }),
        );
      });
    }
    const ack = await upload(context, parent, childId, attempts);
    expect(ack.status, `upload for child ${index}: HTTP ${ack.status}`).toBe(200);
  }

  // And one exam the whole class has sat, so what is timed is a scoring pass over 30 sittings
  // rather than over an empty paper.
  const exam = await publishExam(context, { classId, title: `Timing exam ${RUN}`, minutes: 60 });
  examId = exam.examId;
  const paper = await paperOf(context, parent, childIds[0]!, examId);
  for (const [index, childId] of childIds.entries()) {
    const at = Date.now();
    const ack = await upload(
      context,
      parent,
      childId,
      paper.stops.map((stop, position) =>
        attempt({
          lessonId: examId,
          stopId: stop.id,
          correct: !(index % 5 === 0 && position === 2),
          at: at + position,
        }),
      ),
    );
    expect(ack.status, `exam upload for child ${index}: HTTP ${ack.status}`).toBe(200);
  }
});

test.afterAll(async () => {
  const headers = await staff();
  const admin = await adminHeaders();
  for (const id of [examId, ...lessonIds].filter(Boolean)) {
    await context.post(`/teacher/lessons/${id}/unpublish`, { headers });
    await context.delete(`/admin/lessons/${id}`, { headers: admin });
  }
  for (const id of childIds) await removeChild(context, parent, id);
  await restoreFlags();
  await context.dispose();
});

/** The `count` most recent teaching days, oldest first — all inside the gradebook's 30-day window. */
function pastTeachingDays(count: number): readonly string[] {
  const teaching = new Set([0, 1, 2, 3, 4]);
  const days: string[] = [];
  const day = new Date();
  while (days.length < count) {
    if (teaching.has(day.getUTCDay())) days.push(day.toISOString().slice(0, 10));
    day.setUTCDate(day.getUTCDate() - 1);
  }
  return days.reverse();
}

/** `count` calls, timed one after another, as the numbers a percentile can be taken from. */
async function measure(path: string, count: number): Promise<readonly number[]> {
  const headers = await staff();
  const timings: number[] = [];
  for (let i = 0; i < count; i += 1) {
    const started = Date.now();
    const response = await context.get(path, { headers });
    expect(response.ok(), `${path}: HTTP ${response.status()}`).toBeTruthy();
    await response.body();
    timings.push(Date.now() - started);
  }
  return timings;
}

/** The nearest-rank p95 — with 20 samples that is the 19th, which is the honest one to quote. */
function p95(timings: readonly number[]): number {
  const sorted = [...timings].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil(0.95 * sorted.length) - 1)] ?? 0;
}

/** The middle of a sorted sample — quoted beside the p95 so one slow call cannot tell the story. */
function median(timings: readonly number[]): number {
  const sorted = [...timings].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0
    ? Math.round(((sorted[middle - 1] ?? 0) + (sorted[middle] ?? 0)) / 2)
    : (sorted[middle] ?? 0);
}

test('the gradebook and an exam results page answer inside a second', async () => {
  test.setTimeout(300_000);

  // What is being timed, stated rather than assumed: a grid this size, with cells in it.
  const grid = await context.get(`/teacher/classes/${encodeURIComponent(classId)}/gradebook`, {
    headers: await staff(),
  });
  expect(grid.ok(), `GET gradebook: HTTP ${grid.status()}`).toBeTruthy();
  const book = (await grid.json()) as {
    lessons: unknown[];
    children: { cells: { attempted?: boolean }[] }[];
  };
  expect(book.lessons.length, 'the window should hold this run\'s lessons').toBeGreaterThanOrEqual(LESSONS);
  expect(book.children.length, 'the roster should hold this run\'s children').toBeGreaterThanOrEqual(CHILDREN);
  const played = book.children.reduce(
    (count, child) => count + child.cells.filter((cell) => cell.attempted === true).length,
    0,
  );
  expect(played, 'the grid should be full of played cells').toBeGreaterThanOrEqual(CHILDREN * LESSONS);

  const gradebook = await measure(`/teacher/classes/${encodeURIComponent(classId)}/gradebook`, CALLS);
  const results = await measure(`/teacher/exams/${examId}/results`, CALLS);

  const report = {
    children: book.children.length,
    lessons: book.lessons.length,
    playedCells: played,
    calls: CALLS,
    gradebook: { p95: p95(gradebook), median: median(gradebook), all: gradebook },
    examResults: { p95: p95(results), median: median(results), all: results },
  };
  await mkdir(OUT, { recursive: true });
  await writeFile(resolve(OUT, 'n4-timing.json'), JSON.stringify(report, null, 2));
  console.log(
    `n4 timing: ${report.children} children × ${report.lessons} lessons (${report.playedCells} played cells) — ` +
      `gradebook p95 ${report.gradebook.p95} ms (median ${report.gradebook.median}), ` +
      `exam results p95 ${report.examResults.p95} ms (median ${report.examResults.median})`,
  );

  expect(report.gradebook.p95, 'gradebook p95 should be under a second').toBeLessThan(1_000);
  expect(report.examResults.p95, 'exam results p95 should be under a second').toBeLessThan(1_000);
});
