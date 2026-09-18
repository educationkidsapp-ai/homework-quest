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
  while (Date.now() < deadline) {
    try {
      const response = await fetch(health, { redirect: 'manual' });
      if (response.status === 200) return;
      lastError = `HTTP ${response.status}`;
    } catch (error) {
      lastError = error instanceof Error ? error.message : String(error);
    }
    await new Promise((resolve) => setTimeout(resolve, INTERVAL_MS));
  }
  throw new Error(`${health} did not answer 200 within ${TIMEOUT_MS / 1000}s — last: ${lastError}`);
}
