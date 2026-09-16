import { defineConfig, devices } from '@playwright/test';

/**
 * The end-to-end suite, against a **local** server and the **built** bundle.
 *
 * Deliberately not the dev server: `e2e/local/serve.mjs` serves `dist/browser` at `/dashboard/`
 * and proxies everything else to the API, which is exactly the shape the container will have
 * (P3.4). Same origin, so `apiBaseUrl: ''`, the `Authorization` and `X-School-Id` headers and
 * the SPA fallback are all exercised as they will be in QA — none of which a `ng serve` on a
 * second origin would prove.
 *
 * Before running:
 *
 *   1. start the API on H2 (JDK 21 on PATH):
 *      SPRING_PROFILES_ACTIVE=h2 ADMIN_EMAIL=… ADMIN_PASSWORD=… LLM_PROVIDER=fake PORT=18080 \
 *        java -jar server/target/server.jar
 *   2. seed it:  E2E_BASE_URL=http://localhost:18080 … node e2e/seed/seed.mjs
 *   3. build:    pnpm build --configuration=production
 *   4. run:      pnpm e2e:local
 *
 * Credentials come from the environment and are never written down here:
 * `E2E_ADMIN_EMAIL`, `E2E_ADMIN_PASSWORD`, `E2E_STAFF_PASSWORD`.
 */
export default defineConfig({
  testDir: './e2e/local',
  outputDir: './e2e/.output',
  fullyParallel: false, // one seeded database; the school switcher is shared state
  forbidOnly: !!process.env['CI'],
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:4300/dashboard/',
    viewport: { width: 1366, height: 768 },
    trace: 'on-first-retry',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'node e2e/local/serve.mjs',
    url: 'http://localhost:4300/dashboard/',
    reuseExistingServer: !process.env['CI'],
    timeout: 60_000,
    env: { PORT: '4300', HQ_API: process.env['HQ_API'] ?? 'http://localhost:18080' },
  },
});
