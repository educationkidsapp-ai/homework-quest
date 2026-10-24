#!/usr/bin/env node
/**
 * The dashboard feature tour: one screenshot per feature, per role, at 1440×900 — English and light unless a shot
 * says otherwise. LOCAL ONLY, against a server seeded by `e2e/local/feature-demo-data.sh`.
 *
 *   API=http://localhost:8090 ADMIN_PASSWORD=… SEED_STAFF_PASSWORD=… \
 *     node e2e/local/feature-tour/capture.mjs [role …]
 *
 * Writes `docs/screenshots/feature-tour/dashboard/<role>/NN-name.png` and `<role>/manifest.json` (feature, file,
 * caption, in tour order), which `build-docs.mjs` turns into the README tables and the per-role PDFs. A shot that
 * cannot be taken is recorded in the manifest with the reason instead of a file, and the run carries on.
 *
 * Navigation inside a role is in-app (`history.pushState` + `popstate`, which the Angular router follows) rather
 * than `page.goto`: a full reload of a flag-gated admin screen lands on "Nothing here" before the school's flags are
 * known (reported separately), and a reload would also spend the refresh token again.
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { API, BASE, chromium, context, settle, signIn } from './session.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(here, '../../../docs/screenshots/feature-tour/dashboard');
const CLASS_1A = 'default:british:1:1a british';
const enc = encodeURIComponent;

// ---------------------------------------------------------------- helpers
async function go(page, path) {
  // Same screen, another id (lesson → lesson): the router reuses the component and the page keeps showing the first
  // one (reported separately), so leave the screen first.
  const family = (p) => p.replace(/^\/?dashboard\//, '').split(/[/?]/).slice(0, 2).join('/');
  if (family(new URL(page.url()).pathname) === family(path)) await route(page, '');
  await route(page, path);
  await page.evaluate(() => window.scrollTo(0, 0));
}
async function route(page, path) {
  await page.evaluate((href) => {
    history.pushState({}, '', href);
    dispatchEvent(new PopStateEvent('popstate', { state: {} }));
  }, `/dashboard/${path}`);
  await settle(page, 900);
}
const click = async (locator, ms = 700) => {
  await locator.first().click();
  await settle(locator.page(), ms);
};
const byText = (page, text, scope = 'main') => page.locator(scope).getByText(text, { exact: false });
const button = (page, name, scope = 'body') => page.locator(scope).getByRole('button', { name });
async function scrollTo(page, text) {
  const heading = page.locator('main').getByRole('heading', { name: text });
  const target = (await heading.count()) ? heading : page.locator('main').getByText(text, { exact: false });
  await target.first().evaluate((e) => e.scrollIntoView({ block: 'start' }));
  await page.evaluate(() => window.scrollBy(0, -90));
  await page.waitForTimeout(400);
}
async function toBottom(page) {
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await page.waitForTimeout(400);
}
async function api(role, method, path) {
  const { token } = await signIn(role);
  const r = await fetch(`${API}${path}`, { method, headers: { Authorization: `Bearer ${token}`, 'X-School-Id': 'default' } });
  return r.json();
}

/** The ids a few shots need, read from the seeded server rather than written down. */
async function facts() {
  const lessons = await api('teacher', 'GET', '/teacher/lessons');
  const id = (title) => lessons.find((l) => l.title === title)?.id;
  const kids = await api('teacher', 'GET', `/teacher/classes/${enc(CLASS_1A)}/children`);
  const layla = (Array.isArray(kids) ? kids : kids.children).find((c) => c.name === 'Layla Hassan');
  return {
    counting: id('Counting in 2s'),
    draft: id('Odd and even'),
    exam: id('Autumn maths check'),
    layla: layla?.id ?? layla?.childId,
  };
}

// ---------------------------------------------------------------- the tour
/** Each shot: [file stem, feature, caption, async (page, f) => {…}, options?] — options: { language, theme }. */
const TOUR = {
  admin: [
    ['home', 'Home counts', 'Admin home: managers, coordinators, teachers, children, classes, workers, schools and this week’s lessons at a glance.', (p) => go(p, 'admin')],
    ['teachers', 'Teachers', 'Every teacher with email, phone, subjects, curriculum and classes; the row menu holds Message, Assignments, Edit, Reset password and Disable account.', async (p) => {
      await go(p, 'admin/teachers');
      await click(p.locator('main button', { hasText: '⋮' }), 600);
    }],
    ['coordinators', 'Coordinators', 'Subject coordinators with their scope (grade, subject, curriculum) and contact details.', (p) => go(p, 'admin/coordinators')],
    ['managers', 'Managers', 'School managers and the curriculum each one runs.', (p) => go(p, 'admin/managers')],
    ['workers', 'Workers', 'Non-teaching staff — driver, nurse, caretaker — with job and phone.', (p) => go(p, 'admin/workers')],
    ['children-parents', 'Children & parents', 'Children with their class and parent (name, email, phone), searchable and paged.', (p) => go(p, 'admin/children')],
    ['add-child-dialog', 'Add a Child dialog', 'Admitting a child: class, child’s name and the parent login that is created with it.', async (p) => {
      await go(p, 'admin/children');
      await click(button(p, /admit a child/i, 'main'));
    }],
    ['classes', 'Classes', 'All 30 classes by curriculum and grade, with teachers, roster size and the class join code.', (p) => go(p, 'admin/classes')],
    ['class-join-code-copied', 'Class join code — Copy', 'Copying a class’s join code to hand to parents; the code puts a child straight into that class.', async (p) => {
      await go(p, 'admin/classes');
      await click(button(p, /copy the join code for 1A British/i, 'main'), 300);
    }],
    ['messages-thread', 'Messages', 'A conversation with a parent: text, a photo and a PDF, with read state and the reply box.', async (p) => {
      await go(p, 'admin/messages');
      await click(byText(p, 'Layla Hassan'), 1200);
    }],
    ['complaints', 'Complaints (read-only)', 'The Admin sees every complaint in the school and its history, but answering is left to the recipient.', async (p) => {
      await go(p, 'admin/complaints');
      await click(byText(p, 'Bus arrived late'), 1000);
      await p.evaluate(() => window.scrollTo(0, 0));
    }],
    ['school-logo', 'School logo card', 'The school card on Home: the logo shown in the menu and on the sign-in page, with Replace and Remove.', async (p) => {
      await go(p, 'admin');
      await scrollTo(p, 'PNG, JPEG or WebP');
    }],
    ['notifications', 'Notification bell', 'The bell opens the latest notifications — new messages and complaints — with Mark all as read.', async (p) => {
      await go(p, 'admin');
      await click(p.getByRole('button', { name: 'Notifications' }), 900);
    }],
    ['home-arabic', 'Home in Arabic (right-to-left)', 'The same Home in Arabic: the whole dashboard mirrors right-to-left.', (p) => go(p, 'admin'), { language: 'ar' }],
    ['messages-dark', 'Messages in dark mode', 'Messages in dark mode.', async (p) => {
      await go(p, 'admin/messages');
      await click(byText(p, 'Layla Hassan'), 1200);
    }, { theme: 'dark' }],
  ],

  manager: [
    ['home-statistics', 'Home statistics', 'Manager home: classes, teachers, coordinators and children, how the department is doing, and what needs her.', (p) => go(p, 'management')],
    ['coordinators', 'Coordinators', 'The coordinators in her school with scope, phone and Message.', (p) => go(p, 'management/coordinators')],
    ['teachers', 'Teachers', 'Teachers with subjects and classes, phone and Message.', (p) => go(p, 'management/teachers')],
    ['children', 'Children', 'Every child with class and parent; Message parent and Export CSV.', (p) => go(p, 'management/children')],
    ['weekly-plans', 'Weekly plans — this week and archive', 'This week at a glance per grade, and every earlier plan with how many parents have read it.', (p) => go(p, 'management/weekly-plans')],
    ['weekly-plan-pdf', 'Weekly plan — PDF card', 'A plan posted as a PDF: the card opens the file in place.', async (p) => {
      await go(p, 'management/weekly-plans');
      await scrollTo(p, 'Every weekly plan');
    }],
    ['weekly-plan-compose', 'Compose a weekly plan', 'Posting a weekly plan: week, grade and an image or PDF of the plan.', async (p) => {
      await go(p, 'management/weekly-plans');
      await click(button(p, /add plan/i, 'main'));
      await p.locator('[role=dialog]:visible select, dialog[open] select').first().selectOption({ label: 'Grade 3' });
      await settle(p, 400);
    }],
    ['announcements', 'Announcements', 'Announcements and events for parents and teachers, with what she posted and weekly plans in tabs.', (p) => go(p, 'management/announcements')],
    ['announcement-compose', 'Compose an announcement', 'Writing an announcement: English and Arabic text, who reads it (parents, teachers, coordinators), the whole department or one grade, and an end date.', async (p) => {
      await go(p, 'management/announcements');
      await click(button(p, /write an announcement/i, 'main'));
      await composeAnnouncement(p, 'Book week', 'Next week is Book week: children may come dressed as a favourite story character on Thursday.',
        'الأسبوع القادم أسبوع الكتاب: يمكن للأطفال الحضور بزي شخصية من قصتهم المفضلة يوم الخميس.');
    }],
    ['messages-thread', 'Messages', 'A parent conversation with a PDF and a photo; tabs for parents, teachers, coordinators and admin.', async (p) => {
      await go(p, 'management/messages');
      await click(byText(p, 'Layla Hassan'), 1200);
    }],
    ['complaints', 'Complaints — list', 'Complaints addressed to her and those she supervises, by status.', (p) => go(p, 'management/complaints')],
    ['complaint-open', 'Complaint — open, with Resolve and reply', 'An open complaint with its history (resolved, reopened by the parent), the reply box and Resolve.', async (p) => {
      await go(p, 'management/complaints');
      await click(byText(p, 'Bus arrived late'), 1200);
    }],
    ['staff-attendance-day', 'Staff attendance — one day', 'Marking staff present, absent, late or on leave for a day.', (p) => go(p, 'management/staff-attendance')],
    ['staff-attendance-month', 'Staff attendance — monthly summary', 'The month per person: present, absent, late, leave and the attendance rate.', async (p) => {
      await go(p, 'management/staff-attendance');
      await click(p.locator('main').getByRole('button', { name: /this month/i }).or(p.locator('main').getByRole('tab', { name: /this month/i })), 1000);
    }],
    ['school-usage', 'School usage', 'How often each teacher publishes, with totals and Export CSV.', (p) => go(p, 'management/usage')],
    ['attendance', 'Attendance (read-only)', 'Children’s attendance by class over a date range — present, late, absent, excused and the rate — read-only, with Export CSV.', async (p) => {
      await go(p, 'management/attendance');
      await p.locator('main input[type=date]').first().fill(daysFromNow(-10));
      await settle(p, 1000);
    }],
    ['weekly-plans-arabic', 'Weekly plans in Arabic (right-to-left)', 'Weekly plans in Arabic.', (p) => go(p, 'management/weekly-plans'), { language: 'ar' }],
    ['home-dark', 'Home in dark mode', 'Manager home in dark mode.', (p) => go(p, 'management'), { theme: 'dark' }],
  ],

  coordinator: [
    ['home', 'Home', 'Coordinator home: her scope in numbers, what needs her, and shortcuts to teachers, classes and records.', (p) => go(p, 'coordinator')],
    ['teachers', 'Teachers', 'The teachers in her subject and grades, with phone and Message.', (p) => go(p, 'coordinator/teachers')],
    ['classes-calendar', 'Classes and month calendar', 'Classes in her scope by grade, and a class’s month of lessons and exams.', async (p) => {
      await go(p, 'coordinator/classes');
      await pick(p, '1A British');
      await p.locator('main button', { hasText: '›' }).first().evaluate((e) => e.scrollIntoView({ block: 'start' }));
      await p.evaluate(() => window.scrollBy(0, -120));
    }],
    ['lessons', 'Lessons with status chips', 'Every lesson in her scope, filterable by status: draft, generating, ready to review, published…', (p) => go(p, 'coordinator/lessons')],
    ['attendance', 'Attendance', 'Attendance for a class in her scope over a date range, read-only, with Export CSV.', async (p) => {
      await go(p, 'coordinator/attendance');
      await pick(p, '1A British');
      await p.locator('main input[type=date]').first().fill(daysFromNow(-10));
      await settle(p, 1000);
    }],
    ['gradebook', 'Gradebook', 'The class gradebook: each child’s score per lesson and exam, with bands.', async (p) => {
      await go(p, 'coordinator/gradebook');
      await pick(p, '1A British');
    }],
    ['exam-results', 'Exam results', 'An exam’s results in her scope: who sat it, scores, bands and the hardest questions.', async (p, f) => {
      await go(p, `coordinator/exams/${f.exam}/results`);
    }],
    ['child-page', 'Child page', 'One child: lessons, exam results and teacher comments.', async (p, f) => {
      await go(p, `coordinator/children/${f.layla}`);
    }],
    ['weekly-plans', 'Weekly plans', 'Weekly plans from management, as teachers and coordinators see them.', async (p) => {
      await go(p, 'coordinator/announcements');
      await click(p.locator('main').getByRole('tab', { name: /weekly plans/i }).or(button(p, /weekly plans/i, 'main')), 900);
    }],
    ['announcement-compose', 'Compose an announcement', 'A coordinator writing an announcement to the classes in her scope.', async (p) => {
      await go(p, 'coordinator/announcements');
      await click(button(p, /write an announcement/i, 'main'));
      await composeAnnouncement(p, 'Maths week quiz', 'Grade 1 maths quiz on Thursday: counting in 2s and number bonds to 10.');
    }],
    ['messages', 'Messages', 'Her conversations with a parent and with a teacher.', async (p) => {
      await go(p, 'coordinator/messages');
      await click(byText(p, 'Layla Hassan'), 1200);
    }],
    ['complaints', 'Complaints (supervision view)', 'Every complaint in her scope — to her and to the teachers she supervises — open and resolved.', async (p) => {
      await go(p, 'coordinator/complaints');
      await click(p.locator('main').getByRole('button', { name: /^all$/i }).or(p.locator('main').getByRole('tab', { name: /^all$/i })), 900);
    }],
    ['complaint-open', 'Complaint — reply and resolve', 'A complaint addressed to her: the history, the reply box, Attach image or PDF, and Mark resolved.', async (p) => {
      await go(p, 'coordinator/complaints');
      await click(byText(p, 'Missing workbook pages'), 1200);
    }],
    ['manager', 'Manager page', 'Who her managers are, with phone, email and Message.', (p) => go(p, 'coordinator/manager')],
    ['home-arabic', 'Home in Arabic (right-to-left)', 'Coordinator home in Arabic.', (p) => go(p, 'coordinator'), { language: 'ar' }],
  ],

  teacher: [
    ['this-week', 'This week', 'This week: lessons and exams per class and day, quick actions and the week’s alerts.', (p) => go(p, 'teacher/week')],
    ['this-week-grid', 'This week — grid', 'The week grid: each class against Sunday to Thursday with lesson and exam status.', async (p) => {
      await go(p, 'teacher/week');
      await scrollTo(p, 'This Week: Lessons');
    }],
    ['this-week-search', 'This week — search', 'Search this week by class, subject or lesson.', async (p) => {
      await go(p, 'teacher/week');
      await p.locator('header input, input[type=search]').first().fill('Counting');
      await settle(p, 900);
      await scrollTo(p, 'This Week: Lessons');
    }],
    ['new-lesson-upload', 'New lesson — upload choice', 'New lesson: class, date and title, then where the questions come from — PDF, slides, photos or write it yourself.', async (p) => {
      await go(p, `teacher/lessons/new?classId=${enc(CLASS_1A)}&subject=math`);
      await p.locator('main input[type=text]').first().fill('Number line jumps').catch(() => {});
      await toBottom(p);
    }],
    ['new-lesson-write', 'New lesson — write it yourself', 'Choosing “Write it yourself”: an empty lesson the teacher fills question by question.', async (p) => {
      await go(p, `teacher/lessons/new?classId=${enc(CLASS_1A)}&subject=math`);
      await p.locator('main input[type=text]').first().fill('Number line jumps').catch(() => {});
      await click(byText(p, 'Write it yourself'));
      await toBottom(p);
    }],
    ['assistant-draft', 'Assistant draft', 'A lesson the assistant wrote from one sentence: three levels and an Again round, waiting for review.', async (p, f) => {
      await go(p, `teacher/lessons/${f.draft}`);
      await scrollTo(p, 'Levels');
    }],
    ['lesson-levels', 'Lesson — levels 1–3 and Again', 'A published lesson: Level 1 as written, Levels 2 and 3 and an Again round, with a live phone preview.', async (p, f) => {
      await go(p, `teacher/lessons/${f.counting}`);
      await scrollTo(p, 'Levels');
      await click(p.locator('main').getByRole('tab', { name: 'Level 2' }).or(button(p, 'Level 2', 'main')), 700);
    }],
    ['publish-dialog', 'Publish dialog', 'Publishing to one or more classes.', async (p, f) => {
      await go(p, `teacher/lessons/${f.draft}`);
      await click(button(p, /^publish/i), 900);
    }],
    ['my-class-calendar', 'My class — Calendar', 'A class’s calendar: lessons and exams by day.', (p) => go(p, `teacher/classes/${enc(CLASS_1A)}`)],
    ['my-class-children', 'My class — Children', 'The roster with parents.', async (p) => {
      await go(p, `teacher/classes/${enc(CLASS_1A)}`);
      await classTab(p, 'Children');
    }],
    ['my-class-attendance', 'My class — Attendance', 'Taking the roll: present, absent, late or excused, with notes.', async (p) => {
      await go(p, `teacher/classes/${enc(CLASS_1A)}`);
      await classTab(p, 'Attendance');
    }],
    ['my-class-gradebook', 'My class — Gradebook', 'The gradebook: every child against every lesson and exam, with average and band; CSV and Excel.', async (p) => {
      await go(p, `teacher/classes/${enc(CLASS_1A)}`);
      await classTab(p, 'Gradebook');
    }],
    ['my-class-exams', 'My class — Exams', 'The class’s exams: open, scheduled and released.', async (p) => {
      await go(p, `teacher/classes/${enc(CLASS_1A)}`);
      await classTab(p, 'Exams');
    }],
    ['results-marking', 'Results and marking', 'A lesson’s results: who played, scores per level, and marks and comments per child.', async (p, f) => {
      await go(p, `teacher/lessons/${f.counting}/results`);
      await click(byText(p, 'Layla Hassan'), 800);
    }],
    ['exam-create', 'Exam creation', 'A new exam: title, time limit, release mode, level, window and where the questions come from.', async (p) => {
      await go(p, `teacher/exams/new?classId=${enc(CLASS_1A)}`);
      await p.locator('main input[type=text]').first().fill('Unit 3 test — numbers to 50');
      await p.locator('main input[type=number]').first().fill('20').catch(() => {});
    }],
    ['exam-results', 'Exam results', 'An exam’s results: how the class landed, question by question, every child, Re-open and Release.', async (p, f) => {
      await go(p, `teacher/exams/${f.exam}/results`);
    }],
    ['messages', 'Messages with attachments', 'A parent conversation with a photo and PDFs both ways.', async (p) => {
      await go(p, 'teacher/chat');
      await click(byText(p, 'Layla Hassan'), 1200);
    }],
    ['complaints', 'Complaints', 'A complaint addressed to the teacher, answered and resolved.', async (p) => {
      await go(p, 'teacher/complaints');
      await click(p.locator('main').getByRole('button', { name: /resolved/i }).or(p.locator('main').getByRole('tab', { name: /resolved/i })), 700);
      await click(byText(p, 'Homework too long'), 1000);
    }],
    ['coordinators', 'Coordinators page', 'Her coordinator, with phone, email and Message.', (p) => go(p, 'teacher/coordinators')],
    ['manager', 'Manager page', 'Her manager, with phone, email and Message.', (p) => go(p, 'teacher/manager')],
    ['weekly-plans', 'Weekly plans', 'Weekly plans from management, in Announcements.', async (p) => {
      await go(p, 'teacher/announcements');
      await click(p.locator('main').getByRole('tab', { name: /weekly plans/i }).or(button(p, /weekly plans/i, 'main')), 900);
    }],
    ['notifications', 'Notification bell', 'The bell: lessons the assistant finished, messages and complaints.', async (p) => {
      await go(p, 'teacher/week');
      await click(p.getByRole('button', { name: 'Notifications' }), 900);
    }],
    ['lesson-ready-toast', 'Lesson ready toast', 'When the assistant finishes a lesson the teacher is told at once — “Questions ready” — wherever she is in the dashboard.', async (p, f) => {
      await go(p, 'teacher/week');
      const { token } = await signIn('teacher');
      await fetch(`${API}/teacher/lessons/${f.draft}/plays/3/generate?replace=true`, { method: 'POST', headers: { Authorization: `Bearer ${token}` } });
      const toast = p.locator('.toast', { hasText: /ready/i }).first();
      await toast.waitFor({ state: 'attached', timeout: 20000 });
      await toast.evaluate((e) => e.scrollIntoView({ block: 'end' }));
      await p.waitForTimeout(300);
    }],
    ['this-week-dark', 'This week in dark mode', 'This week in dark mode.', (p) => go(p, 'teacher/week'), { theme: 'dark' }],
    ['lesson-arabic', 'Lesson in Arabic (right-to-left)', 'A lesson in Arabic.', async (p, f) => {
      await go(p, `teacher/lessons/${f.counting}`);
      await scrollTo(p, 'المستويات').catch(() => {});
    }, { language: 'ar' }],
  ],
};

function daysFromNow(n) {
  const d = new Date(Date.now() + n * 86_400_000);
  return d.toISOString().slice(0, 10);
}
async function composeAnnouncement(p, title, en, ar) {
  const dialog = p.locator('[role=dialog]:visible, dialog[open]').last();
  await dialog.locator('input[type=text]').first().fill(title);
  const areas = dialog.locator('textarea');
  await areas.nth(0).fill(en);
  if (ar && (await areas.count()) > 1) await areas.nth(1).fill(ar);
  // Management picks readers and a grade; a coordinator picks classes. The boxes are styled, so tick them in the DOM.
  const ticked = await dialog.evaluate((d, names) => {
    let last = null;
    for (const label of d.querySelectorAll('label')) {
      if (!names.includes(label.innerText.trim())) continue;
      const box = label.querySelector('input[type=checkbox]') ?? (label.htmlFor ? d.querySelector(`#${CSS.escape(label.htmlFor)}`) : null);
      if (box && !box.checked) box.click();
      if (box) last = label;
    }
    last?.scrollIntoView({ block: 'center' });
    return last !== null;
  }, ['Parents', 'Teachers', '1A British', '1B British']);
  let last = null;
  const selects = dialog.locator('select');
  if ((await selects.count()) > 1) {
    last = selects.last();
    await last.selectOption({ label: 'Grade 1' }).catch(() => {});
  }
  if (last) await last.evaluate((e) => e.scrollIntoView({ block: 'center' }));
  if (!ticked && !last) throw new Error('no audience fields in the dialog');
  await settle(p, 400);
}
async function classTab(page, name) {
  await click(page.locator('main').getByRole('tab', { name }).or(page.locator('main').getByRole('button', { name, exact: true })), 1000);
}
async function pick(page, label) {
  const select = page.locator('main select').first();
  if (await select.count()) {
    const value = await select.evaluate((s, l) => [...s.options].find((o) => o.text.includes(l))?.value, label);
    if (value != null) await select.selectOption(value);
  } else {
    await click(byText(page, label));
  }
  await settle(page, 1000);
}

// ---------------------------------------------------------------- run
const roles = process.argv.slice(2).length ? process.argv.slice(2) : Object.keys(TOUR);
const f = await facts();
const browser = await chromium.launch();
try {
  for (const role of roles) {
    const dir = join(OUT, role);
    mkdirSync(dir, { recursive: true });
    const manifest = [];
    const pages = new Map(); // one signed-in page per language/theme pair
    const pageFor = async (options = {}) => {
      const key = `${options.language ?? 'en'}/${options.theme ?? 'light'}`;
      if (!pages.has(key)) {
        const ctx = await context(browser, role, options);
        const page = await ctx.newPage();
        page.setDefaultTimeout(8000);
        await page.goto(BASE);
        await settle(page, 1200);
        pages.set(key, page);
      }
      return pages.get(key);
    };
    let n = 0;
    for (const [stem, feature, caption, act, options] of TOUR[role]) {
      const file = `${String(++n).padStart(2, '0')}-${stem}.png`;
      try {
        const page = await pageFor(options);
        await page.keyboard.press('Escape').catch(() => {});
        await act(page, f);
        await page.mouse.move(0, 899);
        // The app focuses the page heading after a navigation; its outline is not what a person sees.
        await page.evaluate(() => {
          const a = document.activeElement;
          if (a && a !== document.body && !a.closest('[role=dialog], dialog, .cdk-overlay-container')) a.blur();
        });
        await page.screenshot({ path: join(dir, file) });
        manifest.push({ feature, file, caption, ...(options ?? {}) });
        process.stdout.write(`  ${role}/${file}\n`);
      } catch (error) {
        manifest.push({ feature, file: null, caption, missing: String(error.message ?? error).split('\n')[0] });
        process.stdout.write(`  ${role}/${file} — NOT CAPTURED: ${String(error.message ?? error).split('\n')[0]}\n`);
      }
    }
    writeFileSync(join(dir, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`);
    for (const page of pages.values()) await page.context().close();
  }
} finally {
  await browser.close();
}
