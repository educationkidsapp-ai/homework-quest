/* eslint-disable hq/no-product-name-literal */
import { test, expect } from '@playwright/test';
import { resolve } from 'node:path';

const HTML_PATH = `file://${resolve(__dirname, '../public/homework-quest.html')}`;

test.describe('Homework Quest — Gamified Teacher Weekly Dashboard', () => {

  test.beforeEach(async ({ page }) => {
    await page.goto(HTML_PATH);
  });

  test('loads standalone application with brand header, profile and route indicator', async ({ page }) => {
    await expect(page).toHaveTitle(/Homework Quest — Gamified Teacher Weekly Dashboard/);

    // Brand header
    await expect(page.locator('h1')).toHaveText('HOMEWORK QUEST');
    await expect(page.getByText('/dashboard/teacher/week')).toBeVisible();

    // Teacher Profile
    await expect(page.getByText('Prof. Helena Vance')).toBeVisible();
    await expect(page.getByText('Subject Lead')).toBeVisible();
    await expect(page.getByText('42', { exact: true })).toBeVisible();
  });

  test('displays all 4 executive KPI metric cards with initial values', async ({ page }) => {
    // 1. Active Weekly Quests
    await expect(page.locator('#kpiActiveQuests')).toHaveText('7');

    // 2. Submission Rate
    await expect(page.locator('#kpiSubmissionRate')).toContainText('%');

    // 3. Avg Class XP
    await expect(page.locator('#kpiAvgXp')).toHaveText('1,420 XP');

    // 4. Needs Grading
    await expect(page.locator('#kpiNeedsGrading')).toHaveText('3');
  });

  test('renders 5 Kanban weekday columns (Monday through Friday)', async ({ page }) => {
    const days = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday'];
    for (const day of days) {
      await expect(page.locator(`#column-${day}`)).toBeVisible();
      await expect(page.locator(`h4:has-text("${day}")`)).toBeVisible();
    }

    // Verify initial cards are present in their respective columns
    await expect(page.locator('#column-Monday').getByRole('heading', { name: 'Quantum Tunneling Lab Report' })).toBeVisible();
    await expect(page.locator('#column-Tuesday').getByRole('heading', { name: 'Taylor Series Dungeon Quest' })).toBeVisible();
    await expect(page.locator('#column-Wednesday').getByRole('heading', { name: "Kepler's Orbits & Gravitation" })).toBeVisible();
  });

  test('filters quests reactively by class and tier', async ({ page }) => {
    const mondayCol = page.locator('#column-Monday');
    const tuesdayCol = page.locator('#column-Tuesday');

    // Initial: multiple classes visible
    await expect(mondayCol.getByRole('heading', { name: 'Quantum Tunneling Lab Report' })).toBeVisible(); // Physics
    await expect(tuesdayCol.getByRole('heading', { name: 'Taylor Series Dungeon Quest' })).toBeVisible(); // Calculus II

    // Filter by Physics 101
    await page.locator('button[data-class="Physics 101"]').click();
    await expect(mondayCol.getByRole('heading', { name: 'Quantum Tunneling Lab Report' })).toBeVisible();
    await expect(tuesdayCol.getByRole('heading', { name: 'Taylor Series Dungeon Quest' })).not.toBeVisible();

    // Reset to All Classes
    await page.locator('button[data-class="all"]').click();
    await expect(tuesdayCol.getByRole('heading', { name: 'Taylor Series Dungeon Quest' })).toBeVisible();

    // Filter by Tier: Boss Raid
    await page.locator('button[data-tier="Boss Raid"]').click();
    await expect(mondayCol.getByRole('heading', { name: 'Quantum Tunneling Lab Report' })).toBeVisible(); // Boss Raid
    await expect(mondayCol.getByRole('heading', { name: 'Harmonic Oscillator Simulation' })).not.toBeVisible(); // Common
  });

  test('searches quests in real-time using global search', async ({ page }) => {
    const searchInput = page.locator('#globalSearchInput');
    await searchInput.fill('Gravitation');

    // Kepler's Orbits should be visible, others hidden
    await expect(page.locator('#column-Wednesday').getByRole('heading', { name: "Kepler's Orbits & Gravitation" })).toBeVisible();
    await expect(page.locator('#column-Monday').getByRole('heading', { name: 'Quantum Tunneling Lab Report' })).not.toBeVisible();

    await searchInput.clear();
    await expect(page.locator('#column-Monday').getByRole('heading', { name: 'Quantum Tunneling Lab Report' })).toBeVisible();
  });

  test('navigates weeks and resets to today', async ({ page }) => {
    const weekDisplay = page.locator('#currentWeekDisplay');
    await expect(weekDisplay).toHaveText('Sept 20 - Sept 26, 2026');

    // Next week
    await page.locator('button[title="Next Week"]').click();
    await expect(weekDisplay).toHaveText('Sept 27 - Oct 03, 2026');

    // Reset to Today
    await page.getByRole('button', { name: 'TODAY' }).click();
    await expect(weekDisplay).toHaveText('Sept 20 - Sept 26, 2026');
  });

  test('forges a new homework quest dynamically through the modal', async ({ page }) => {
    // Open Forge Modal
    await page.getByRole('button', { name: '+ New Quest' }).click();
    await expect(page.locator('#forgeModal')).toBeVisible();

    // Fill form
    await page.locator('#questTitleInput').fill('Gravitational Waves Detection Mission');
    await page.locator('#questClassSelect').selectOption('Physics 101');
    await page.locator('#questDaySelect').selectOption('Thursday');
    await page.locator('#questTierSelect').selectOption('Boss Raid');
    await page.locator('#questInstructionsInput').fill('Analyze the interferometry data.');

    // Submit
    await page.getByRole('button', { name: 'Forge Quest' }).click();

    // Modal should close
    await expect(page.locator('#forgeModal')).not.toBeVisible();

    // Quest should now exist under Thursday column
    const thursdayCol = page.locator('#column-Thursday');
    await expect(thursdayCol.getByRole('heading', { name: 'Gravitational Waves Detection Mission' })).toBeVisible();

    // Active quest count should increase from 7 to 8
    await expect(page.locator('#kpiActiveQuests')).toHaveText('8');

    // Toast notification should appear
    await expect(page.locator('#toastContainer')).toContainText('Quest Forged');
  });

  test('grades a submission in the Grading Arena and awards XP', async ({ page }) => {
    // Open Grading Arena via the KPI card
    await page.locator('#kpiNeedsGrading').click();
    await expect(page.locator('#gradingModal')).toBeVisible();

    // Check student and rubric elements
    await expect(page.locator('#gradingStudentName')).toBeVisible();
    await expect(page.getByText('Methodology')).toBeVisible();

    // Award XP
    await page.getByRole('button', { name: 'Award XP & Pass Quest' }).click();

    // Modal should close
    await expect(page.locator('#gradingModal')).not.toBeVisible();

    // Needs grading count should decrement from 3 to 2
    await expect(page.locator('#kpiNeedsGrading')).toHaveText('2');

    // Toast notification for XP should appear
    await expect(page.locator('#toastContainer')).toContainText('Awarded +450 XP');

    // Activity feed should have new item
    await expect(page.locator('#liveActivityFeed')).toContainText('was awarded');
  });

  test('toggles audio synthesizer and dark/light themes', async ({ page }) => {
    // Toggle Audio SFX
    const sfxBtn = page.locator('#sfxToggleBtn');
    await sfxBtn.click();
    await expect(page.locator('#toastContainer')).toContainText('Audio SFX Muted');

    // Toggle Theme
    await page.getByRole('button', { name: 'Toggle' }).click();
    await expect(page.locator('html')).toHaveClass(/light/);
    await expect(page.locator('#themeLabel')).toHaveText('Daylight Theme');

    // Toggle back to Dark
    await page.getByRole('button', { name: 'Toggle' }).click();
    await expect(page.locator('html')).toHaveClass(/dark/);
    await expect(page.locator('#themeLabel')).toHaveText('Deep Space Dark');
  });

});
