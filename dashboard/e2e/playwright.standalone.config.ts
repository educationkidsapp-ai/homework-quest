import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './',
  testMatch: 'homework-quest.spec.ts',
  outputDir: './.output',
  fullyParallel: false,
  workers: 1,
  timeout: 30000,
  use: {
    viewport: { width: 1366, height: 768 },
    trace: 'off',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
