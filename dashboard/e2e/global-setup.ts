/**
 * Wait for the API to be *seeded*, not merely answering, before the suite starts.
 *
 * Two different lies a server tells while it is coming up, and this file is the one place that
 * catches both.
 *
 * 1. **The deployment is not serving yet.** `pnpm e2e:qa` runs straight after `deploy-qa.yml` has
 *    pushed a new Cloud Run revision, and Cloud Run answers the deploy long before the container
 *    is serving: the first requests get a cold-start timeout or a 503 from the revision still
 *    coming up. Every spec would fail on its first `page.goto`, which reads as "QA is broken"
 *    rather than "QA is not up yet".
 *
 * 2. **`/health` is 200 before the seed has finished.** `SchoolSeed` is a `CommandLineRunner`
 *    (`server/.../classes/SchoolSeed.java`), and Spring Boot starts Tomcat during the context
 *    refresh — so the server serves requests while the runner is still loading `classes.csv`,
 *    `teachers.csv`, `assignments.csv` and `children.csv`, in that order. A suite launched the
 *    moment the process starts therefore signs Sara in successfully and then finds *no classes*,
 *    or her two classes with *no children* in them. That is what made the first one or two tests
 *    of a cold local run fail — `admin-classes-teachers` and `lesson-editor` in T4's and T5's
 *    runs — and pass on a re-run, which is the worst shape a failure can have: it teaches
 *    everyone to re-run rather than to read.
 *
 * So the gate below polls what the specs actually need: Sara's **two** classes and a **non-empty
 * roster** on the first of them. The last phase the seed writes is the children, so a roster that
 * answers is the whole load having finished, not a guess at it.
 *
 * It runs for the local H2 server as well as for a deployment — the flake above is a local one —
 * which is why the API base is read from `E2E_BASE_URL` *or* `HQ_API` (the same resolution
 * `e2e/local/env.ts` uses) rather than from `E2E_BASE_URL` alone.
 */
import type { FullConfig } from '@playwright/test';

const HEALTH_TIMEOUT_MS = 90_000;
const SEED_TIMEOUT_MS = 120_000;
const INTERVAL_MS = 2_000;

/** `seed/teachers.csv`: the teacher the suite runs as. Kept in step with `e2e/local/env.ts`. */
const SARA_EMAIL = 'sara.al-harbi@school.test';
/** `seed/assignments.csv`: her two sections, and the subject both are taught in. */
const HER_CLASSES = ['1A British', '1B British'] as const;
const HER_SUBJECT = 'math';

/** Thrown to stop the polling dead: nothing that waiting longer could fix. */
class SetupFailure extends Error {}

export default async function globalSetup(config: FullConfig): Promise<void> {
  const api = apiBase(config);
  await waitForHealth(api);
  await waitForSeed(api);
}

/**
 * Where the API is.
 *
 * Against a deployment the API serves the dashboard under `/dashboard/`, so `/health` is on the
 * origin of the baseURL and never under the dashboard's path. Locally the suite's baseURL is
 * `e2e/local/serve.mjs` — a proxy that Playwright may not have started yet — so the API itself is
 * addressed directly, on `HQ_API`, exactly as `e2e/local/env.ts` addresses it.
 */
function apiBase(config: FullConfig): string {
  const deployed = process.env['E2E_BASE_URL'];
  if (deployed) return new URL(config.projects[0]?.use.baseURL ?? deployed).origin;
  return process.env['HQ_API'] ?? 'http://localhost:18080';
}

async function waitForHealth(api: string): Promise<void> {
  const health = new URL('/health', api).href;
  const deadline = Date.now() + HEALTH_TIMEOUT_MS;
  for (;;) {
    const why = await ping(health);
    if (why === null) return;
    if (Date.now() >= deadline) {
      throw new Error(`${health} did not answer 200 within ${HEALTH_TIMEOUT_MS / 1000}s — last: ${why}`);
    }
    await sleep(INTERVAL_MS);
  }
}

/** `null` when `/health` answered 200; otherwise why it did not. */
async function ping(health: string): Promise<string | null> {
  try {
    const response = await fetch(health, { redirect: 'manual' });
    return response.status === 200 ? null : `HTTP ${response.status}`;
  } catch (error) {
    return message(error);
  }
}

/**
 * One sign-in and two reads, until they all agree the seed is in, or 120 s.
 *
 * The sign-in half is T4's and keeps its semantics: a wrong secret costs seconds rather than the
 * job's whole cap. `E2E_STAFF_PASSWORD` in the `qa` environment once did not match the server's
 * `SEED_STAFF_PASSWORD`, so Sara could not sign in — and every spec discovered that for itself,
 * in its own `beforeAll`, each one burning its retry and its share of the budget on a screen that
 * was never going to open. The job was cancelled at the 20-minute cap, which uploads no report
 * and fires no `if: failure()` step, so the only evidence left was a red square. Failing here
 * gives a first line that names the variable to fix.
 *
 * A 429 is always fatal, on either target: it is the sign-in throttle holding earlier attempts
 * against this run, and polling it only feeds the bucket. A 401 is fatal **against a deployment**,
 * where the seed ran when the revision was built and the only remaining explanation is the
 * password. Locally it is not: a cold server 401s for the seconds between Tomcat accepting
 * connections and the seed's `teachers` phase writing Sara, so it is retried inside the same
 * bounded window — and if it is still 401 at the deadline the failure names the variable anyway.
 */
async function waitForSeed(api: string): Promise<void> {
  const password = process.env['E2E_STAFF_PASSWORD'];
  if (!password) {
    throw new Error(
      "E2E_STAFF_PASSWORD is not set — it must hold the same value as the server's SEED_STAFF_PASSWORD (see dashboard/e2e/local/README.md)",
    );
  }

  const started = Date.now();
  const deadline = started + SEED_TIMEOUT_MS;
  for (;;) {
    const why = await probe(api, password);
    if (why === null) {
      console.log(`e2e: the seed answered everything the suite needs after ${seconds(started)}s`);
      return;
    }
    if (Date.now() >= deadline) throw notSeeded(api, why);
    await sleep(INTERVAL_MS);
  }
}

function seconds(since: number): number {
  return Math.round((Date.now() - since) / 1000);
}

/** The one failure this file exists to make readable. */
function notSeeded(api: string, last: string): Error {
  return new Error(
    [
      `The one-school seed at ${api} was still incomplete after ${SEED_TIMEOUT_MS / 1000}s — last: ${last}.`,
      `The suite needs ${SARA_EMAIL} to have ${HER_CLASSES.join(' and ')} (${HER_SUBJECT}) with children in them.`,
      'SchoolSeed (server/src/main/java/quest/server/classes/SchoolSeed.java) writes classes, teachers,',
      'assignments and children from server/src/main/resources/seed/*.csv in a CommandLineRunner, so',
      'GET /health answers 200 while it is still running. Check the server log for the line it ends with',
      '("school seed … ready: … new classes, … new teachers, …") and that the server was started with',
      'SEED_SCHOOL=true — see dashboard/e2e/local/README.md.',
      'Aborting before the specs run: they would each rediscover this in their own beforeAll.',
    ].join('\n'),
  );
}

/** One round of the gate. `null` means ready; a string says what is still missing. */
async function probe(api: string, password: string): Promise<string | null> {
  const token = await signIn(api, password);
  if (typeof token !== 'string') return token.why;

  const classes = await getJson(api, '/teacher/classes', token);
  if ('why' in classes) return classes.why;
  const rows = classes.body as { className?: string; subject?: string; classId?: string }[];
  if (!Array.isArray(rows)) return 'GET /teacher/classes did not answer a list';
  const hers = HER_CLASSES.map((name) =>
    rows.find((row) => row.className === name && (row.subject ?? '').toLowerCase() === HER_SUBJECT),
  );
  if (hers.some((row) => row === undefined)) {
    const seen = rows.map((row) => `${row.className} (${row.subject})`).join(', ') || 'nothing';
    return `GET /teacher/classes has not got ${HER_CLASSES.join(' + ')} yet — it answered: ${seen}`;
  }

  const classId = hers[0]?.classId;
  if (!classId) return 'GET /teacher/classes answered a row with no classId';
  const roster = await getJson(api, `/teacher/classes/${classId}/students`, token);
  if ('why' in roster) return roster.why;
  const children = roster.body as unknown[];
  if (!Array.isArray(children) || children.length === 0) {
    return `${HER_CLASSES[0]} has no children yet — the seed's last phase has not run`;
  }
  return null;
}

/** Sara's access token, or why there is not one yet. Fatal cases throw rather than return. */
async function signIn(api: string, password: string): Promise<string | { why: string }> {
  const signInUrl = new URL('/auth/sign-in', api).href;
  let response: Response;
  try {
    response = await fetch(signInUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      // The same account every teacher spec signs in as, so what passes here is what they need.
      body: JSON.stringify({ email: SARA_EMAIL, password }),
    });
  } catch (error) {
    return { why: `${signInUrl} could not be reached although /health answered — ${message(error)}` };
  }

  if (response.status === 200) {
    const body = (await response.json()) as { token?: string };
    return body.token ?? { why: 'the sign-in answered 200 with no token' };
  }
  if (response.status === 429) {
    throw new SetupFailure(
      [
        `${SARA_EMAIL} could not sign in against ${signInUrl} (HTTP 429).`,
        'The sign-in throttle is still holding earlier attempts against this run; wait for it to clear,',
        'then check E2E_STAFF_PASSWORD. Polling it would only refill the bucket.',
      ].join('\n'),
    );
  }
  if (response.status === 401 && process.env['E2E_BASE_URL']) {
    throw new SetupFailure(
      [
        `${SARA_EMAIL} could not sign in against ${signInUrl} (HTTP 401).`,
        "E2E_STAFF_PASSWORD is not the password this deployment seeded — it must be the same value as the server's SEED_STAFF_PASSWORD, in the `qa` GitHub environment as well as here.",
        'Aborting before the specs run — every one of them would otherwise wait on its own sign-in and the job would be cancelled at its cap with no report.',
      ].join('\n'),
    );
  }
  return {
    why:
      response.status === 401
        ? `${SARA_EMAIL} is refused (HTTP 401) — either the seed has not written teachers.csv yet, or E2E_STAFF_PASSWORD is not the server's SEED_STAFF_PASSWORD`
        : `POST /auth/sign-in answered HTTP ${response.status}`,
  };
}

async function getJson(
  api: string,
  path: string,
  token: string,
): Promise<{ body: unknown } | { why: string }> {
  try {
    const response = await fetch(new URL(path, api).href, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (response.status !== 200) return { why: `GET ${path} answered HTTP ${response.status}` };
    return { body: await response.json() };
  } catch (error) {
    return { why: `GET ${path} could not be read — ${message(error)}` };
  }
}

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
