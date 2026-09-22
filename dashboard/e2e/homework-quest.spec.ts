/* eslint-disable hq/no-product-name-literal */
import { test, expect } from '@playwright/test';
import { resolve } from 'node:path';

const HTML_PATH = `file://${resolve(__dirname, '../public/homework-quest.html')}`;

test.describe('EduManage & Homework Quest Dashboard — Theme & Feature Suite', () => {

  test.beforeEach(async ({ page }) => {
    // Clear localStorage to ensure predictable state
    await page.addInitScript(() => {
      window.localStorage.clear();
    });
    await page.goto(HTML_PATH);
  });

  test('loads dashboard with EduManage branding, profile, and light theme default', async ({ page }) => {
    await expect(page).toHaveTitle(/EduManage & Homework Quest/);

    // Brand and profile
    await expect(page.locator('h1')).toContainText('EduManage');
    await expect(page.getByText('Prof. Helena Vance')).toBeVisible();

    // Default theme should be light
    await expect(page.locator('html')).toHaveClass(/light/);
    await expect(page.locator('#themeLabel')).toHaveText('Daylight Theme');
  });

  test('toggles seamlessly between Light Theme and Deep Space Dark theme', async ({ page }) => {
    const html = page.locator('html');
    const themeLabel = page.locator('#themeLabel');
    const headerThemeBtn = page.locator('#headerThemeIcon');

    // Initially light
    await expect(html).toHaveClass(/light/);
    await expect(themeLabel).toHaveText('Daylight Theme');

    // Click header theme button -> switches to dark
    await headerThemeBtn.click();
    await expect(html).toHaveClass(/dark/);
    await expect(themeLabel).toHaveText('Deep Space Dark');
    await expect(page.locator('#toastContainer')).toContainText('Switched to Deep Space Dark');

    // Click again -> switches back to light
    await headerThemeBtn.click();
    await expect(html).toHaveClass(/light/);
    await expect(themeLabel).toHaveText('Daylight Theme');
    await expect(page.locator('#toastContainer')).toContainText('Switched to Daylight Theme');
  });

  test('displays all 4 hero KPI metric cards and toggles between School & Quest stats', async ({ page }) => {
    // Initial Teacher mode
    await expect(page.locator('#kpi-label-1')).toHaveText('Active Quests');
    await expect(page.locator('#kpi-value-1')).toHaveText('18');
    await expect(page.locator('#kpi-label-2')).toHaveText('Attendance Rate');
    await expect(page.locator('#kpi-value-2')).toHaveText('96.4%');
    await expect(page.locator('#kpi-label-3')).toHaveText('Lessons Created');
    await expect(page.locator('#kpi-value-3')).toHaveText('42');
    await expect(page.locator('#kpi-label-4')).toHaveText('Needs Grading');
    await expect(page.locator('#kpi-value-4')).toHaveText('12');

    // Toggle to Quest stats
    await page.locator('#kpi-quest-btn').click();
    await expect(page.locator('#kpi-label-1')).toHaveText('Total Students');
    await expect(page.locator('#kpi-value-1')).toHaveText('182');
    await expect(page.locator('#kpi-label-2')).toHaveText('Submission Rate');
    await expect(page.locator('#kpi-value-2')).toHaveText('88.4%');
    await expect(page.locator('#kpi-label-3')).toHaveText('Avg Class XP');
    await expect(page.locator('#kpi-value-3')).toHaveText('1,420 XP');
    await expect(page.locator('#kpi-label-4')).toHaveText('Exams Scheduled');
    await expect(page.locator('#kpi-value-4')).toHaveText('3');

    // Toggle back to School stats
    await page.locator('#kpi-school-btn').click();
    await expect(page.locator('#kpi-label-1')).toHaveText('Active Quests');
  });

  test('renders 5-Column Kanban Board and filters by Class, Tier, and Search', async ({ page }) => {
    const days = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday'];
    for (const day of days) {
      await expect(page.locator(`#column-${day}`)).toBeVisible();
    }

    // Check specific quest cards
    const mondayCol = page.locator('#column-Monday');
    await expect(mondayCol.getByRole('heading', { name: 'Kinematics & Freefall Vectors' })).toBeVisible();
    await expect(mondayCol.getByRole('heading', { name: 'Derivatives of Trig Functions' })).toBeVisible();

    // Filter by Class: Physics 101
    await page.locator('#filterClassSelect').selectOption('Physics 101');
    await expect(mondayCol.getByRole('heading', { name: 'Kinematics & Freefall Vectors' })).toBeVisible();
    await expect(mondayCol.getByRole('heading', { name: 'Derivatives of Trig Functions' })).not.toBeVisible();

    // Reset Class filter
    await page.locator('#filterClassSelect').selectOption('ALL');
    await expect(mondayCol.getByRole('heading', { name: 'Derivatives of Trig Functions' })).toBeVisible();

    // Filter by Tier: BOSS
    await page.locator('#tier-filter-boss').click();
    await expect(page.locator('#column-Tuesday').getByRole('heading', { name: 'Orbital Mechanics & Kepler' })).toBeVisible();
    await expect(mondayCol.getByRole('heading', { name: 'Kinematics & Freefall Vectors' })).not.toBeVisible();

    // Reset Tier
    await page.locator('#tier-filter-all').click();

    // Search filter
    await page.locator('#globalSearchInput').fill('Kepler');
    await expect(page.locator('#column-Tuesday').getByRole('heading', { name: 'Orbital Mechanics & Kepler' })).toBeVisible();
    await expect(mondayCol.getByRole('heading', { name: 'Kinematics & Freefall Vectors' })).not.toBeVisible();

    await page.locator('#globalSearchInput').clear();
    await expect(mondayCol.getByRole('heading', { name: 'Kinematics & Freefall Vectors' })).toBeVisible();
  });

  test('interacts with Class Attendance Sheet modal (toggles status, mark all present, saves)', async ({ page }) => {
    // Open Attendance Modal via Quick Action
    await page.getByRole('button', { name: /Attendance Daily roll call/i }).click();
    const modal = page.locator('#attendanceModal');
    await expect(modal).toBeVisible();

    // Check student list rendered
    await expect(modal.getByText('Aiden Thorne')).toBeVisible();
    await expect(modal.getByText('Sophia Martinez')).toBeVisible();

    // Click "Mark All Present"
    await modal.getByRole('button', { name: 'Mark All Present' }).click();
    await expect(modal.locator('#attCountAbsent')).toHaveText('0');
    await expect(modal.locator('#attRate')).toHaveText('100%');

    // Change Chloe Dupont status to Absent
    const chloeRow = modal.locator('#attendanceStudentList > div').filter({ hasText: 'Chloe Dupont' });
    await chloeRow.getByRole('button', { name: 'Absent' }).click();
    await expect(modal.locator('#attCountAbsent')).toHaveText('1');

    // Save Attendance
    await modal.getByRole('button', { name: 'Save Attendance' }).click();
    await expect(modal).not.toBeVisible();
    await expect(page.locator('#toastContainer')).toContainText('Class attendance saved');
  });

  test('forges a new quest through the Forge Quest modal', async ({ page }) => {
    // Open modal via Quick Action
    await page.getByRole('button', { name: /Forge Quest/i }).first().click();
    const modal = page.locator('#questModal');
    await expect(modal).toBeVisible();

    // Fill form
    await page.locator('#questTitleInput').fill('Gravitational Waves Detection Lab');
    await page.locator('#questClassInput').selectOption('Physics 101');
    await page.locator('#questDayInput').selectOption('Thursday');
    await page.locator('#questTierInput').selectOption('BOSS');
    await page.locator('#questXpInput').fill('450');

    // Submit form
    await modal.getByRole('button', { name: 'Forge Quest' }).click();
    await expect(modal).not.toBeVisible();

    // Verify quest appears on Thursday column
    const thursdayCol = page.locator('#column-Thursday');
    await expect(thursdayCol.getByRole('heading', { name: 'Gravitational Waves Detection Lab' })).toBeVisible();
    await expect(page.locator('#toastContainer')).toContainText('forged successfully');
  });

  test('creates a new homework exam through the Create Exam modal', async ({ page }) => {
    // Open modal via Quick Action
    await page.getByRole('button', { name: /Create Exam Homework test/i }).click();
    const modal = page.locator('#examModal');
    await expect(modal).toBeVisible();

    // Fill form
    await page.locator('#examTitleInput').fill('Gravitation & Planetary Laws Exam');
    await page.locator('#examClassInput').selectOption('Physics 101');
    await page.locator('#examQuestionsInput').fill('15');
    await page.locator('#examXpInput').fill('600');

    // Submit form
    await modal.getByRole('button', { name: 'Create Exam' }).click();
    await expect(modal).not.toBeVisible();

    // Verify exam appears in recent exams overview
    await expect(page.locator('#recentExamsList')).toContainText('Gravitation & Planetary Laws Exam');
    await expect(page.locator('#toastContainer')).toContainText('created and scheduled');
  });

  test('opens Grading Arena and awards XP to student', async ({ page }) => {
    // Open Grading Arena via Quick Action
    await page.getByRole('button', { name: /Grading Arena/i }).first().click();
    const modal = page.locator('#gradingModal');
    await expect(modal).toBeVisible();

    await expect(modal.getByText('Aiden Thorne')).toBeVisible();
    await expect(modal.locator('#gradingXpDisplay')).toHaveText('250 XP');

    // Award XP
    await modal.getByRole('button', { name: 'Award XP & Pass Quest' }).click();
    await expect(modal).not.toBeVisible();
    await expect(page.locator('#toastContainer')).toContainText('Awarded 250 XP');
  });

  test('opens Parent Chat Messenger and sends a message', async ({ page }) => {
    // Open Chat Modal via Quick Action
    await page.getByRole('button', { name: /Parent Chat/i }).first().click();
    const modal = page.locator('#chatModal');
    await expect(modal).toBeVisible();

    await expect(modal.getByRole('heading', { name: 'Mrs. Thorne' })).toBeVisible();

    // Type and send message
    const input = page.locator('#chatComposerInput');
    await input.fill('Looking forward to seeing you at the science fair tomorrow!');
    await modal.locator('button:has(.fa-paper-plane)').click();

    // Verify message appeared in conversation
    await expect(modal.locator('#chatMessageList')).toContainText('Looking forward to seeing you at the science fair tomorrow!');
    await expect(page.locator('#toastContainer')).toContainText('Message sent');
  });

  test('renders Weekly Attendance Chart with student and teacher datasets', async ({ page }) => {
    const canvas = page.locator('#weeklyAttendanceChart');
    await expect(canvas).toBeVisible();

    // Check legend chips
    await expect(page.getByText('Grade 1-3', { exact: true })).toBeVisible();
    await expect(page.getByText('Grade 4-6', { exact: true })).toBeVisible();
  });

});
