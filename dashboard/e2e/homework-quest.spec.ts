/* eslint-disable hq/no-product-name-literal */
import { test, expect } from '@playwright/test';
import { resolve } from 'node:path';

const HTML_PATH = `file://${resolve(__dirname, '../public/homework-quest.html')}`;

test.describe('EduManage & Homework Quest Dashboard — Pure Feature Suite', () => {

  test.beforeEach(async ({ page }) => {
    // Clear localStorage to ensure predictable state
    await page.addInitScript(() => {
      window.localStorage.clear();
    });
    await page.goto(HTML_PATH);
  });

  test('loads dashboard with EduManage branding, teacher profile Sara Al Harbi, and light theme default', async ({ page }) => {
    await expect(page).toHaveTitle(/EduManage & Homework Quest/);

    // Brand and profile
    await expect(page.locator('h1')).toContainText('EduManage');
    await expect(page.getByText('Sara Al Harbi')).toBeVisible();

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

  test('displays all 4 authentic hero KPI metric cards', async ({ page }) => {
    await expect(page.locator('#kpi-label-1')).toHaveText('Lessons This Week');
    await expect(page.locator('#kpi-value-1')).toHaveText('18');
    await expect(page.locator('#kpi-label-2')).toHaveText('Attendance Rate');
    await expect(page.locator('#kpi-value-2')).toHaveText('96.4%');
    await expect(page.locator('#kpi-label-3')).toHaveText('Active Classes');
    await expect(page.locator('#kpi-value-3')).toHaveText('6');
    await expect(page.locator('#kpi-label-4')).toHaveText('Needs Review');
    await expect(page.locator('#kpi-value-4')).toHaveText('5');
  });

  test('renders 5-Column Weekly Schedule and filters by Class, Status, and Search', async ({ page }) => {
    const days = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday'];
    for (const day of days) {
      await expect(page.locator(`#column-${day}`)).toBeVisible();
    }

    // Check specific real lesson cards
    const mondayCol = page.locator('#column-Monday');
    await expect(mondayCol.getByRole('heading', { name: 'Fractions Intro: Equal Parts' })).toBeVisible();
    await expect(mondayCol.getByRole('heading', { name: 'Addition with Regrouping' })).toBeVisible();

    // Filter by Class: Grade 1A · Math
    await page.locator('#filterClassSelect').selectOption('Grade 1A · Math');
    await expect(mondayCol.getByRole('heading', { name: 'Fractions Intro: Equal Parts' })).toBeVisible();
    await expect(mondayCol.getByRole('heading', { name: 'Addition with Regrouping' })).not.toBeVisible();

    // Reset Class filter
    await page.locator('#filterClassSelect').selectOption('ALL');
    await expect(mondayCol.getByRole('heading', { name: 'Addition with Regrouping' })).toBeVisible();

    // Filter by Status: Published
    await page.locator('#status-filter-published').click();
    await expect(mondayCol.getByRole('heading', { name: 'Fractions Intro: Equal Parts' })).toBeVisible();
    await expect(mondayCol.getByRole('heading', { name: 'Addition with Regrouping' })).not.toBeVisible();

    // Reset Status
    await page.locator('#status-filter-all').click();

    // Search filter
    await page.locator('#globalSearchInput').fill('Geometry');
    await expect(page.locator('#column-Tuesday').getByRole('heading', { name: 'Geometry & 2D Shapes' })).toBeVisible();
    await expect(mondayCol.getByRole('heading', { name: 'Fractions Intro: Equal Parts' })).not.toBeVisible();

    await page.locator('#globalSearchInput').clear();
    await expect(mondayCol.getByRole('heading', { name: 'Fractions Intro: Equal Parts' })).toBeVisible();
  });

  test('interacts with Class Attendance Sheet modal (toggles status, mark all present, saves)', async ({ page }) => {
    // Open Attendance Modal via Quick Action
    await page.getByRole('button', { name: /Attendance Daily roll call/i }).click();
    const modal = page.locator('#attendanceModal');
    await expect(modal).toBeVisible();

    // Check student list rendered
    await expect(modal.getByText('Adam Khalid')).toBeVisible();
    await expect(modal.getByText('Omar Farooq')).toBeVisible();

    // Click "Mark All Present"
    await modal.getByRole('button', { name: 'Mark All Present' }).click();
    await expect(modal.locator('#attStatsSummary')).toContainText('6 Present');

    // Save Attendance
    await modal.getByRole('button', { name: 'Save Attendance' }).click();
    await expect(modal).not.toBeVisible();
    await expect(page.locator('#toastContainer')).toContainText('Class attendance saved');
  });

  test('creates a new lesson through the New Lesson modal', async ({ page }) => {
    // Open modal via Quick Action
    await page.getByRole('button', { name: /New Lesson/i }).first().click();
    const modal = page.locator('#lessonModal');
    await expect(modal).toBeVisible();

    // Fill form
    await page.locator('#lessonTitleInput').fill('Measurement & Units Lab');
    await page.locator('#lessonClassInput').selectOption('Grade 1A · Math');
    await page.locator('#lessonDayInput').selectOption('Thursday');
    await page.locator('#lessonSourceInput').selectOption('PDF');

    // Submit form
    await modal.getByRole('button', { name: 'Create & Analyze Lesson' }).click();
    await expect(modal).not.toBeVisible();

    // Verify lesson appears on Thursday column
    const thursdayCol = page.locator('#column-Thursday');
    await expect(thursdayCol.getByRole('heading', { name: 'Measurement & Units Lab' })).toBeVisible();
    await expect(page.locator('#toastContainer')).toContainText('created and analyzed');
  });

  test('creates a new homework exam through the Create Exam modal', async ({ page }) => {
    // Open modal via Quick Action
    await page.getByRole('button', { name: /Create Exam Scheduled test/i }).click();
    const modal = page.locator('#examModal');
    await expect(modal).toBeVisible();

    // Fill form
    await page.locator('#examTitleInput').fill('Fractions & Decimals Unit Exam');
    await page.locator('#examClassInput').selectOption('Grade 1A · Math');
    await page.locator('#examQuestionsInput').fill('15');
    await page.locator('#examPassScoreInput').fill('75');

    // Submit form
    await modal.getByRole('button', { name: 'Create Exam' }).click();
    await expect(modal).not.toBeVisible();

    // Verify exam appears in recent exams overview
    await expect(page.locator('#recentExamsList')).toContainText('Fractions & Decimals Unit Exam');
    await expect(page.locator('#toastContainer')).toContainText('scheduled');
  });

  test('opens Results modal and marks open stop response', async ({ page }) => {
    // Open Results modal from Schedule Gaps card
    await page.getByRole('button', { name: /Review & Mark Stops/i }).click();
    const modal = page.locator('#gradingModal');
    await expect(modal).toBeVisible();

    await expect(modal.getByText('Adam Khalid')).toBeVisible();
    await expect(modal.getByText('Stop 3 (Retell)')).toBeVisible();

    // Submit mark
    await modal.getByRole('button', { name: 'Save Mark & Release to Parent' }).click();
    await expect(modal).not.toBeVisible();
    await expect(page.locator('#toastContainer')).toContainText('Mark saved and released');
  });

  test('opens Parent Chat Messenger and sends a message', async ({ page }) => {
    // Open Chat Modal via Quick Action
    await page.getByRole('button', { name: /Parent Chat/i }).first().click();
    const modal = page.locator('#chatModal');
    await expect(modal).toBeVisible();

    await expect(modal.getByRole('heading', { name: 'Mrs. Al Mansoor' })).toBeVisible();

    // Type and send message
    const input = page.locator('#chatComposerInput');
    await input.fill('Adam did a fantastic job on his fractions test today!');
    await modal.locator('button:has(.fa-paper-plane)').click();

    // Verify message appeared in conversation
    await expect(modal.locator('#chatMessagesContainer')).toContainText('Adam did a fantastic job on his fractions test today!');
    await expect(page.locator('#toastContainer')).toContainText('Message sent');
  });

  test('verifies non-existent features (leaderboard, events, fees, RPG classes) are absent', async ({ page }) => {
    const text = await page.locator('body').innerText();
    expect(text).not.toContain('Top Class Questers');
    expect(text).not.toContain('Aiden Thorne');
    expect(text).not.toContain('Science Fair Exhibition');
    expect(text).not.toContain('Winter Sports Meet');
    expect(text).not.toContain('Fee Status');
    expect(text).not.toContain('Boss Raid');
    expect(text).not.toContain('Level 14 Rogue');
  });

});
