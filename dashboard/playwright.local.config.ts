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
 *   1. start the API on H2 with the one-school seed (JDK 21 on PATH) — the server seeds itself,
 *      so there is no separate seed step (`e2e/seed/seed.mjs` is the two-school legacy and is not
 *      used by any spec here; see `e2e/local/README.md`):
 *      SPRING_PROFILES_ACTIVE=h2 SEED_SCHOOL=true SEED_STAFF_PASSWORD=… ADMIN_EMAIL=… \
 *        ADMIN_PASSWORD=… LLM_PROVIDER=fake PORT=18080 java -jar server/target/server.jar
 *   2. build:    pnpm build --configuration=production
 *   3. run:      pnpm e2e:local
 *
 * Credentials come from the environment and are never written down here:
 * `E2E_ADMIN_EMAIL`, `E2E_ADMIN_PASSWORD`, `E2E_STAFF_PASSWORD`.
 */
export default defineConfig({
  testDir: './e2e/local',
  outputDir: './e2e/.output',
  fullyParallel: false, // one seeded database, and the lessons a spec writes are shared state
  forbidOnly: !!process.env['CI'],
  workers: 1,
  reporter: [['list']],
  // `e2e/global-setup.ts` waits for the API on `HQ_API` to be *seeded*, not merely answering:
  // `SchoolSeed` is a `CommandLineRunner`, so `/health` is 200 while it is still writing classes,
  // assignments and children, and a suite started with the server failed its first test or two on
  // a cold start and passed on a re-run. The gate polls Sara's two sections and her roster.
  globalSetup: './e2e/global-setup.ts',
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
