#!/usr/bin/env node
/**
 * Demo files for the feature tour: a school logo, a weekly plan as an image and as a PDF, a photo and a PDF for the
 * chat threads. Rendered with the dashboard's own Playwright, so nothing is downloaded and nothing is real.
 *
 *   node e2e/local/feature-tour/make-assets.mjs <out-dir>
 */
import { createRequire } from 'node:module';
import { mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(resolve(here, '../../../dashboard/package.json'));
const { chromium } = require('@playwright/test');

const out = resolve(process.argv[2] ?? 'feature-tour-assets');
mkdirSync(out, { recursive: true });

const page = (body, css = '') => `<!doctype html><html><head><meta charset="utf-8"><style>
  * { box-sizing: border-box; } body { margin: 0; font-family: -apple-system, 'Segoe UI', Arial, sans-serif; color: #1f2937; }
  ${css}</style></head><body>${body}</body></html>`;

const logo = page(
  `<div class="l"><svg viewBox="0 0 120 120" width="220" height="220"><circle cx="60" cy="60" r="56" fill="#0f766e"/>
   <path d="M60 22 L98 40 L60 58 L22 40 Z" fill="#fde68a"/><path d="M34 48 v20 c0 10 52 10 52 0 v-20 L60 60 Z" fill="#fef3c7"/>
   </svg></div>`,
  '.l{width:256px;height:256px;display:grid;place-items:center;background:#fff}',
);

const days = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday'];
const subjects = [
  ['Maths', ['Counting in 2s', 'Number bonds to 10', 'Shapes around us', 'Measuring length', 'Quiz: week review']],
  ['English', ['Phonics: sh / ch', 'Reading: The Lost Kite', 'Spelling list 4', 'Writing a postcard', 'Library visit']],
  ['Science', ['Living things', 'Plants need water', 'Experiment: seeds', 'Seasons', 'Show and tell']],
  ['Arabic', ['حرف الباء', 'قراءة: القطة', 'إملاء', 'كتابة جملة', 'نشيد']],
];
const plan = (title) =>
  page(
    `<div class="p"><header><h1>${title}</h1><p>Grade 1 · British curriculum · Week of 4 October 2026</p></header>
     <table><thead><tr><th></th>${days.map((d) => `<th>${d}</th>`).join('')}</tr></thead><tbody>
     ${subjects.map(([s, items]) => `<tr><th>${s}</th>${items.map((i) => `<td>${i}</td>`).join('')}</tr>`).join('')}
     </tbody></table><footer>Homework is set every day in the app. Reading log: 10 minutes each evening.</footer></div>`,
    `.p{width:1200px;padding:36px;background:#f8fafc} header h1{margin:0;color:#0f766e;font-size:34px} header p{margin:4px 0 20px;color:#475569}
     table{width:100%;border-collapse:collapse;background:#fff} th,td{border:1px solid #cbd5e1;padding:14px;font-size:17px;text-align:left}
     thead th{background:#0f766e;color:#fff} tbody th{background:#ecfdf5;width:120px} footer{margin-top:18px;color:#334155}`,
  );

const photo = page(
  `<div class="ph"><div class="sun"></div><div class="hill"></div><div class="tree"><span></span></div>
   <div class="card">Our class garden 🌱</div></div>`,
  `.ph{width:960px;height:640px;position:relative;overflow:hidden;background:linear-gradient(#bae6fd,#e0f2fe)}
   .sun{position:absolute;top:60px;right:120px;width:120px;height:120px;border-radius:50%;background:#fbbf24}
   .hill{position:absolute;bottom:-220px;left:-100px;width:1200px;height:480px;border-radius:50%;background:#4ade80}
   .tree{position:absolute;left:220px;bottom:180px;width:30px;height:160px;background:#92400e}
   .tree span{position:absolute;left:-75px;top:-120px;width:180px;height:160px;border-radius:50%;background:#15803d}
   .card{position:absolute;left:40px;top:40px;background:#fff;padding:16px 24px;border-radius:14px;font-size:30px;font-weight:600}`,
);

const letter = page(
  `<div class="d"><h1>Medical note</h1><p>To the class teacher of 1A British,</p>
   <p>Layla had a dentist appointment on Sunday morning and arrived after the first lesson. Please excuse the late arrival.</p>
   <p>Thank you,<br>Mona Hassan (parent)</p></div>`,
  '.d{padding:60px;font-size:20px;line-height:1.6} h1{color:#0f766e}',
);

const browser = await chromium.launch();
try {
  const shot = async (html, file, width, height) => {
    const p = await browser.newPage({ viewport: { width, height } });
    await p.setContent(html);
    await p.screenshot({ path: join(out, file), fullPage: true });
    await p.close();
  };
  const pdf = async (html, file) => {
    const p = await browser.newPage();
    await p.setContent(html);
    await p.pdf({ path: join(out, file), format: 'A4', landscape: file.includes('plan'), printBackground: true });
    await p.close();
  };
  await shot(logo, 'logo.png', 256, 256);
  await shot(plan('Weekly plan — Grade 1'), 'weekly-plan.png', 1200, 500);
  await pdf(plan('Weekly plan — Grade 1 (printable)'), 'weekly-plan.pdf');
  await shot(photo, 'class-garden.png', 960, 640);
  await pdf(letter, 'medical-note.pdf');
} finally {
  await browser.close();
}
process.stdout.write(`assets in ${out}\n`);
