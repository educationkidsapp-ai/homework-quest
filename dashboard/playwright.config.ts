import { defineConfig, devices } from '@playwright/test';

/**
 * 1366 × 768 is the dashboard's reference viewport (the size in the brief and the one
 * every screenshot in docs/screenshots uses). The dev server is started by Playwright so
 * `pnpm e2e` works from a clean checkout and in CI without a separate step.
 *
 * `E2E_BASE_URL` points the same suite at a deployed environment instead — `pnpm e2e:qa`,
 * which `deploy-qa.yml` runs after the QA deploy. The dashboard is served by the API at
 * `<origin>/panel/`, and no dev server is started.
 */
const deployed = process.env['E2E_BASE_URL'];
const baseURL = deployed ? new URL('/panel/', deployed).href : 'http://localhost:4200/panel/';

export default defineConfig({
  testDir: './e2e',
  outputDir: './e2e/.output',
  fullyParallel: true,
  forbidOnly: !!process.env['CI'],
  retries: process.env['CI'] ? 1 : 0,
  workers: process.env['CI'] ? 1 : undefined,
  reporter: process.env['CI'] ? [['github'], ['list']] : [['list']],
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
        url: 'http://localhost:4200/panel/styleguide',
        reuseExistingServer: !process.env['CI'],
        timeout: 180_000,
      },
});
