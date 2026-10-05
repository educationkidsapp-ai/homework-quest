/**
 * Signed-in browser contexts for the feature tour, without a password ever reaching the browser.
 *
 * The staff member signs in through the API (`POST /auth/sign-in`) and the dashboard is handed the refresh token the
 * way a reload finds it: `localStorage['hq.refresh']`. The dashboard spends it on its first load and writes the
 * rotated one back, so the init script only seeds an empty storage — writing the spent token again on the next
 * navigation would look like a replay and revoke the session.
 */
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(resolve(here, '../../../dashboard/package.json'));
export const { chromium } = require('@playwright/test');

export const API = process.env.API ?? 'http://localhost:8090';
export const BASE = `${API}/dashboard/`;

/** Who each role signs in as — the full seed's people; the password comes from the environment variable named. */
export const PEOPLE = {
  admin: { email: process.env.ADMIN_EMAIL ?? 'admin@quest.local', password: 'ADMIN_PASSWORD' },
  manager: { email: 'manager.a@school.test', password: 'SEED_STAFF_PASSWORD' },
  coordinator: { email: 'coordinator.math@school.test', password: 'SEED_STAFF_PASSWORD' },
  teacher: { email: 'sara.al-harbi@school.test', password: 'SEED_STAFF_PASSWORD' },
};

export async function signIn(role) {
  const who = PEOPLE[role];
  const password = process.env[who.password];
  if (!password) throw new Error(`set ${who.password}`);
  const response = await fetch(`${API}/auth/sign-in`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: who.email, password }),
  });
  if (!response.ok) throw new Error(`${who.email} could not sign in (HTTP ${response.status})`);
  return response.json(); // { token, refreshToken, … } — kept in memory only
}

/** A context signed in as `role`, in `language` ('en' | 'ar') and `theme` ('light' | 'dark'). */
export async function context(browser, role, { language = 'en', theme = 'light', viewport = { width: 1440, height: 900 } } = {}) {
  const { refreshToken } = await signIn(role);
  const ctx = await browser.newContext({
    viewport,
    deviceScaleFactor: 1,
    locale: language === 'ar' ? 'ar-SA' : 'en-GB',
    timezoneId: 'Asia/Riyadh',
    colorScheme: theme,
    // Reduced motion: shots are of the settled screen, and the stat cards' count-up otherwise ends on the value it
    // started from when the data lands mid-animation (reported separately).
    reducedMotion: 'reduce',
    permissions: ['clipboard-read', 'clipboard-write'],
  });
  await ctx.addInitScript(
    ({ refreshToken, language, theme, school }) => {
      try {
        if (!localStorage.getItem('hq.refresh')) localStorage.setItem('hq.refresh', refreshToken);
        localStorage.setItem('hq.language', language);
        localStorage.setItem('theme', theme);
        if (school && !localStorage.getItem('hq.school')) localStorage.setItem('hq.school', school);
      } catch {
        /* storage unavailable: the page shows sign-in, which the shot would make obvious */
      }
    },
    { refreshToken, language, theme, school: role === 'admin' ? 'default' : null },
  );
  return ctx;
}

/** Waits until the page has stopped fetching and animating, so a shot is not of a skeleton. */
export async function settle(page, ms = 600) {
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForFunction(() => !document.querySelector('.skeleton, [aria-busy="true"], hq-skeleton'), null, { timeout: 8000 }).catch(() => {});
  await page.waitForTimeout(ms);
}
