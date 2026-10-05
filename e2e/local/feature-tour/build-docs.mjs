#!/usr/bin/env node
/**
 * Turns the feature-tour manifests into `docs/screenshots/feature-tour/dashboard/README.md` and one PDF per role
 * (`<role>.pdf`: a title page, then one screenshot per page with its caption, in tour order).
 *
 *   node e2e/local/feature-tour/build-docs.mjs
 *
 * Needs no server: it reads `<role>/manifest.json` and the PNGs `capture.mjs` wrote.
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from './session.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(here, '../../../docs/screenshots/feature-tour/dashboard');

const ROLES = [
  { key: 'admin', title: 'Admin', who: 'Platform Admin (admin@quest.local), school scope “Green Valley Academy”' },
  { key: 'manager', title: 'Manager', who: 'Huda Salem — British department manager' },
  { key: 'coordinator', title: 'Coordinator', who: 'Rasha Kamal — maths coordinator, both tracks' },
  { key: 'teacher', title: 'Teacher', who: 'Sara Al Harbi — maths, 1A and 1B British' },
];

/** What the shots show that is not right, so nobody mistakes it for the design (reported to the planner). */
const DEFECTS = [
  'Teacher **This week** (`dashboard/src/app/features/week/week.page.ts`) shows hard-coded figures that are not the school’s: the “Grade 2C · Math Exam: Closing Soon” and “Grade 1A · Fractions: 3 Open Stops” alerts, the whole “Exams & Tests Hub” (Midterm Math Assessment 88.5% …), the 96.4% attendance rate, the “+3 vs last week” and “+2.4%” trends, and each class card’s student count and attendance percentage.',
  'Stat cards that count up (`ui/motion/count-up.directive.ts`) end on **0** when the data lands during the first animation — Admin Teachers and Classes showed “Total Faculty 0 / Total Sections 0” with 40 teachers and 30 classes listed. The shots are taken with reduced motion, where the directive writes the value directly.',
  'The “Questions ready” toast is not an overlay: it renders as an unstyled bar after the page content (`teacher/23-lesson-ready-toast.png` is scrolled to it).',
  'Coordinator **All lessons**: the Class and Teacher columns are empty — `GET /coordinator/lessons` returns `classId`/`teacherId` but the page reads `className`/`teacherName`.',
  'Gradebook (teacher and coordinator): a teacher’s score on a lesson the child has not played shows as “—” in the cell, yet it counts in the row and class averages.',
  'Results and marking: “Student answer” shows the option id (`a`, `b`) while “Expected” shows the label (`4`).',
  'The exam window column on My class → Exams drops the closing date (“Oct 7, 11:08 PM – 11:08 PM” for a window that closes on Oct 8).',
  'Navigating from one lesson straight to another (`/teacher/lessons/A` → `/teacher/lessons/B`, e.g. Back/Forward or a notification) keeps showing lesson A.',
  'Reloading a flag-gated Admin screen (`/dashboard/admin/messages`, `/admin/complaints`) lands on “Nothing here” — the guard reads the platform flags before the school scope is restored. Reached from the menu, the screens work.',
];

const NOT_ATTEMPTED = [
  ['manager', 'Typing indicator in Messages', 'needs a second live client typing over the chat WebSocket at the moment of the shot; not scripted.'],
];

const manifests = Object.fromEntries(
  ROLES.filter((r) => existsSync(join(OUT, r.key, 'manifest.json'))).map((r) => [r.key, JSON.parse(readFileSync(join(OUT, r.key, 'manifest.json'), 'utf8'))]),
);

// ---------------------------------------------------------------- README
const md = [];
md.push('# Feature tour — dashboard', '');
md.push(
  'One screenshot per feature of the Schools Dashboard, per role, taken by Playwright (headless Chromium, 1440×900, English and light unless the row says Arabic or dark) against a **local** H2 server — never QA — filled with demo data by [`e2e/local/feature-demo-data.sh`](../../../../e2e/local/feature-demo-data.sh). Every name, child and message is invented.',
  '',
  'Each role also has a PDF: a title page, then one screenshot per page with its caption — [admin.pdf](admin.pdf), [manager.pdf](manager.pdf), [coordinator.pdf](coordinator.pdf), [teacher.pdf](teacher.pdf).',
  '',
  'To take them again, see [e2e/README.md](../../../../e2e/README.md) → “Feature tour screenshots”.',
  '',
);
for (const role of ROLES) {
  const rows = manifests[role.key];
  if (!rows) continue;
  md.push(`## ${role.title}`, '', `Signed in as ${role.who}.`, '', '| # | Feature | File | Caption |', '|---|---|---|---|');
  rows.forEach((row, i) => {
    const variant = /arabic|dark/i.test(row.feature) ? '' : row.language === 'ar' ? ' (Arabic)' : row.theme === 'dark' ? ' (dark)' : '';
    const file = row.file ? `[${row.file}](${role.key}/${row.file})` : `not captured: ${row.missing}`;
    md.push(`| ${i + 1} | ${row.feature}${variant} | ${file} | ${row.caption} |`);
  });
  md.push('');
}
md.push('## Not captured', '');
const missing = ROLES.flatMap((r) => (manifests[r.key] ?? []).filter((row) => !row.file).map((row) => `- ${r.title}: ${row.feature} — ${row.missing}`));
for (const [role, feature, why] of NOT_ATTEMPTED) missing.push(`- ${ROLES.find((r) => r.key === role).title}: ${feature} — ${why}`);
md.push(...(missing.length ? missing : ['- Nothing.']), '');
md.push('## Defects visible in these screenshots', '', 'Reported to the planner; the screenshots show the product as it is on `develop`.', '');
md.push(...DEFECTS.map((d) => `- ${d}`), '');
writeFileSync(join(OUT, 'README.md'), md.join('\n'));

// ---------------------------------------------------------------- PDFs
const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const browser = await chromium.launch();
try {
  for (const role of ROLES) {
    const rows = (manifests[role.key] ?? []).filter((row) => row.file);
    if (!rows.length) continue;
    const pages = rows
      .map((row, i) => {
        const data = readFileSync(join(OUT, role.key, row.file)).toString('base64');
        const variant = /arabic|dark/i.test(row.feature) ? '' : row.language === 'ar' ? ' — Arabic' : row.theme === 'dark' ? ' — dark mode' : '';
        return `<section class="shot"><h2><span>${i + 1}</span>${esc(row.feature)}${variant}</h2>
          <img src="data:image/png;base64,${data}" alt="${esc(row.feature)}"><p>${esc(row.caption)}</p></section>`;
      })
      .join('');
    const html = `<!doctype html><html><head><meta charset="utf-8"><style>
      @page { size: A4 landscape; margin: 10mm 12mm; }
      * { box-sizing: border-box; } body { margin: 0; font-family: -apple-system, 'Segoe UI', Arial, sans-serif; color: #1f2937; }
      .title { height: 188mm; display: flex; flex-direction: column; justify-content: center; padding-left: 14mm; page-break-after: always;
               border-left: 6px solid #1d4ed8; }
      .title h1 { font-size: 38pt; margin: 0 0 6mm; } .title p { font-size: 14pt; margin: 1.5mm 0; color: #475569; }
      .shot { height: 188mm; display: flex; flex-direction: column; page-break-after: always; }
      .shot:last-child { page-break-after: auto; }
      .shot h2 { font-size: 15pt; margin: 0 0 3mm; display: flex; gap: 3mm; align-items: baseline; }
      .shot h2 span { color: #1d4ed8; font-size: 12pt; }
      .shot img { max-width: 100%; max-height: 160mm; object-fit: contain; align-self: center; border: 1px solid #cbd5e1; }
      .shot p { font-size: 11.5pt; margin: 3mm 0 0; color: #334155; }
    </style></head><body>
      <section class="title"><h1>MySchool — ${role.title} (dashboard)</h1>
        <p>${rows.length} features, one per page.</p><p>Signed in as ${esc(role.who)}.</p>
        <p>Local demo data; every name is invented. 1440×900, English and light unless noted.</p></section>
      ${pages}</body></html>`;
    const page = await browser.newPage();
    await page.setContent(html, { waitUntil: 'load' });
    await page.pdf({ path: join(OUT, `${role.key}.pdf`), format: 'A4', landscape: true, printBackground: true });
    await page.close();
    process.stdout.write(`  ${role.key}.pdf — ${rows.length + 1} pages\n`);
  }
} finally {
  await browser.close();
}
