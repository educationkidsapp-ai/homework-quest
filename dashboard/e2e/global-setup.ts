/**
 * Wait for the deployment to answer before the suite starts.
 *
 * `pnpm e2e:qa` runs straight after `deploy-qa.yml` has pushed a new Cloud Run revision, and
 * Cloud Run answers the deploy long before the container is serving: the first requests get a
 * cold-start timeout or a 503 from the revision still coming up. Every spec in the file would
 * then fail on its first `page.goto`, which reads as "QA is broken" rather than "QA is not up
 * yet". One poll here, and the report says what it means.
 *
 * Only for a deployed target: with no `E2E_BASE_URL`, Playwright starts `ng serve` itself and
 * its own `webServer.url` check already does this.
 */
import type { FullConfig } from '@playwright/test';

const TIMEOUT_MS = 90_000;
const INTERVAL_MS = 2_000;

export default async function globalSetup(config: FullConfig): Promise<void> {
  const deployed = process.env['E2E_BASE_URL'];
  if (!deployed) return;

  // The API serves the dashboard under `/dashboard/`, so the origin of the baseURL is the API
  // itself — `/health` is on the API, never under the dashboard's path.
  const baseURL = config.projects[0]?.use.baseURL ?? deployed;
  const health = new URL('/health', baseURL).href;

  const deadline = Date.now() + TIMEOUT_MS;
  let lastError = '';
  let up = false;
  while (!up && Date.now() < deadline) {
    try {
      const response = await fetch(health, { redirect: 'manual' });
      if (response.status === 200) up = true;
      else lastError = `HTTP ${response.status}`;
    } catch (error) {
      lastError = error instanceof Error ? error.message : String(error);
    }
    if (!up) await new Promise((resolve) => setTimeout(resolve, INTERVAL_MS));
  }
  if (!up) {
    throw new Error(`${health} did not answer 200 within ${TIMEOUT_MS / 1000}s — last: ${lastError}`);
  }

  await assertStaffPasswordWorks(baseURL);
}

/**
 * One sign-in, before any test, so a wrong secret costs seconds rather than the job's whole cap.
 *
 * This is here because of the 5c13cdf deploy. `E2E_STAFF_PASSWORD` in the `qa` environment did
 * not match the server's `SEED_STAFF_PASSWORD`, so Sara could not sign in — and every spec in
 * the suite discovered that for itself, in its own `beforeAll`, each one burning its retry and
 * its share of the budget on a screen that was never going to open. The job was cancelled at the
 * 20-minute cap, which uploads no report and fires no `if: failure()` step, so the only evidence
 * left was a red square. A run that cannot say what broke is worse than no run.
 *
 * Failing here instead gives a first line that names the variable to fix. It is deliberately
 * specific about the status: a 401 is the wrong password, a 429 is the sign-in throttle still
 * holding a previous run's attempts against this one, and anything else is the deployment.
 */
async function assertStaffPasswordWorks(baseURL: string): Promise<void> {
  const password = process.env['E2E_STAFF_PASSWORD'];
  if (!password) {
    throw new Error(
      "E2E_STAFF_PASSWORD is not set — it must hold the same value as the server's SEED_STAFF_PASSWORD (see dashboard/e2e/local/README.md)",
    );
  }

  const signIn = new URL('/auth/sign-in', baseURL).href;
  let response: Response;
  try {
    response = await fetch(signIn, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      // The same account every teacher spec signs in as, so what passes here is what they need.
      body: JSON.stringify({ email: SARA_EMAIL, password }),
    });
  } catch (error) {
    throw new Error(
      `${signIn} could not be reached although /health answered — ${error instanceof Error ? error.message : String(error)}`,
      { cause: error },
    );
  }

  if (response.status === 200) return;
  throw new Error(
    [
      `${SARA_EMAIL} could not sign in against ${signIn} (HTTP ${response.status}).`,
      response.status === 401
        ? "E2E_STAFF_PASSWORD is not the password this deployment seeded — it must be the same value as the server's SEED_STAFF_PASSWORD, in the `qa` GitHub environment as well as here."
        : response.status === 429
          ? 'The sign-in throttle is still holding earlier attempts against this run; wait for it to clear, then check E2E_STAFF_PASSWORD.'
          : 'Neither the password nor the suite: the deployment answered /health but not a sign-in.',
      'Aborting before the specs run — every one of them would otherwise wait on its own sign-in and the job would be cancelled at its cap with no report.',
    ].join('\n'),
  );
}

/** `seed/teachers.csv`: the teacher the suite runs as. Kept in step with `e2e/local/env.ts`. */
const SARA_EMAIL = 'sara.al-harbi@school.test';
