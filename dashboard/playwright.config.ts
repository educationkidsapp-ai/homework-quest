import { defineConfig, devices } from '@playwright/test';

/**
 * 1366 × 768 is the dashboard's reference viewport (the size in the brief and the one
 * every screenshot in docs/screenshots uses). The dev server is started by Playwright so
 * `pnpm e2e` works from a clean checkout and in CI without a separate step.
 *
 * `E2E_BASE_URL` points Playwright at a deployed environment instead — `pnpm e2e:qa`, which
 * `deploy-qa.yml` runs after the QA deploy. The dashboard is served by the API at
 * `<origin>/dashboard/` (P3.4), and no dev server is started.
 *
 * Against a deployment `globalSetup` waits for `<api>/health` first (see `e2e/global-setup.ts`)
 * and `expect` gets 15 s rather than the default 5: a Cloud Run instance that has just been
 * created is slower than this Mac at everything, and a timeout there says "the assertion is
 * wrong" when the truth is "the box is cold".
 *
 * Against a deployment the suite is `e2e/local/` — sign-in per role, the switcher, RTL, the
 * screenshots (P3.1). `e2e/styleguide.spec.ts` stays behind `ng serve`: both the `qa` and the
 * `production` configuration replace `styleguide.route.ts`, so that route is not in any built
 * bundle. Those specs share one seeded database and the school switcher, so they run serially
 * exactly as `playwright.local.config.ts` runs them locally.
 */
const path = '/dashboard/'; // where the API serves the dashboard — one place, so every target moves together
const deployed = process.env['E2E_BASE_URL'];
const baseURL = deployed ? new URL(path, deployed).href : `http://localhost:4200${path}`;

export default defineConfig({
  testDir: deployed ? './e2e/local' : './e2e',
  outputDir: './e2e/.output',
  fullyParallel: !deployed,
  forbidOnly: !!process.env['CI'],
  retries: process.env['CI'] ? 1 : 0,
  workers: deployed || process.env['CI'] ? 1 : undefined,
  reporter: process.env['CI'] ? [['github'], ['list']] : [['list']],
  globalSetup: deployed ? './e2e/global-setup.ts' : undefined,
  expect: { timeout: deployed ? 15_000 : 5_000 },
  use: {
    baseURL,
    viewport: { width: 1366, height: 768 },
    trace: 'on-first-retry',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: deployed
    ? undefined
    : {
        // `npx ng` rather than `pnpm start`: Playwright spawns through /bin/sh, which does not
        // have corepack's pnpm shim on PATH in every environment (including this repo's CI image).
        command: 'npx ng serve --port 4200',
        url: `http://localhost:4200${path}styleguide`,
        reuseExistingServer: !process.env['CI'],
        timeout: 180_000,
      },
});
