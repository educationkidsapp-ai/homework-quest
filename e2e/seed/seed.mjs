#!/usr/bin/env node
// QA/local seed for the two-school fixture (P1.6).
//
// Node 22, built-ins only. Idempotent: every step looks for the row it would create (school by code, user by email,
// lesson by title, child by name) and skips when it is already there, so a second run changes nothing.
//
// Environment (nothing is ever printed):
//   E2E_BASE_URL         API origin                      default: the QA API
//   E2E_ADMIN_EMAIL      platform ADMIN                  default: admin@quest.local
//   E2E_ADMIN_PASSWORD   required
//   E2E_STAFF_PASSWORD   password given to the seeded TEACHER/MANAGERIAL accounts
//   E2E_PARENT_PASSWORD  password of the seeded Firebase parents (non-local targets)
//   E2E_PARENT_AUTH      fake | firebase                 default: fake for localhost, firebase otherwise
//   E2E_FIREBASE_API_KEY Identity Toolkit web key        default: read from androidApp/src/qa/google-services.json
//   E2E_SEED_OUT         where the id summary is written default: e2e/.seed.json (git-ignored; isolation.sh reads it)
//   E2E_RESEED_THEMES    1 = PUT the theme even when the school already carries it (otherwise the PUT is skipped
//                            when GET /admin/schools/{id}/theme already answers this appName)
//
// Exit codes: 0 seeded, 1 an unexpected response, 2 seeded as far as the API allows (see BLOCKED in the output).

import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..', '..');

const QA_API = 'https://homework-quest-api-625882725080.me-central1.run.app';
const BASE = (process.env.E2E_BASE_URL || QA_API).replace(/\/+$/, '');
const ADMIN_EMAIL = process.env.E2E_ADMIN_EMAIL || 'admin@quest.local';
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD || '';
const STAFF_PASSWORD = process.env.E2E_STAFF_PASSWORD || '';
const PARENT_PASSWORD = process.env.E2E_PARENT_PASSWORD || '';
const OUT = process.env.E2E_SEED_OUT || resolve(REPO, 'e2e', '.seed.json');
const RESEED_THEMES = process.env.E2E_RESEED_THEMES === '1';

const LOCAL = /^https?:\/\/(localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1\])(:|\/|$)/.test(BASE);
const PARENT_AUTH = (process.env.E2E_PARENT_AUTH || (LOCAL ? 'fake' : 'firebase')).toLowerCase();

// ---------------------------------------------------------------- the fixture

/** Lesson dates are fixed so a re-run finds the same rows; they sit in the future so the map window is easy to reason about. */
const DATE_A = '2027-03-02';
const DATE_B = '2027-03-03';

const SCHOOLS = [
  {
    key: 'A',
    name: 'Al Noor School',
    code: 'ALNOOR',
    curriculumOptions: ['british', 'american'],
    gradeOptions: [1, 2, 3],
    teacher: {
      email: 'teacher.a@alnoor.test',
      displayName: 'Ms Sara',
      subjects: ['math'],
      curriculum: 'british',
      grades: [1, 2],
    },
    managerial: { email: 'manager.a@alnoor.test', displayName: 'Mr Omar' },
    // The section the teacher is assigned to and the lesson is dated into (V7 / N2.1): since N2.3b a teacher's
    // lesson is created with `POST /teacher/lessons {classId, subject}`, so the class and the assignment come first.
    section: { curriculum: 'british', grade: 1, name: '1A' },
    lesson: { curriculum: 'british', grade: 1, subject: 'math', date: DATE_A, title: 'Counting by 2s — Al Noor' },
    parent: { uid: 'e2e-parent-a', email: 'parent.a@alnoor.test' },
    child: { name: 'Aya', avatarColor: 'sky', curriculum: 'british', grade: 1 },
    // §3 white label: a deep-green house with a warm amber action colour. Every pair below is measured by
    // `assertThemeValid` before it is sent — see the comment there for why a dark `ground` forces a light everything.
    theme: {
      logoUrl: 'https://placehold.co/256x256/0E3B2E/FFFFFF.png?text=AN',
      appName: 'Al Noor',
      primary: '#145C46',
      primaryInk: '#FFFFFF',
      accent: '#F5B971',
      ground: '#0E3B2E',
      softBorder: '#2A6B57',
      mascotColor: '#7FD1AE',
      worldPalettes: {
        math: { primary: '#3FAE8B', deep: '#1E7A5E', soft: '#E3F5EE', ink: '#123B2E' },
        english: { primary: '#F0A868', deep: '#C2762F', soft: '#FDF1E3', ink: '#4A2C10' },
      },
      fontChoice: 'nunito',
    },
  },
  {
    key: 'B',
    name: 'Green Valley School',
    code: 'GREENV',
    curriculumOptions: ['british'],
    gradeOptions: [1, 2],
    teacher: {
      email: 'teacher.b@greenvalley.test',
      displayName: 'Ms Hana',
      subjects: ['english'],
      curriculum: 'british',
      grades: [1],
    },
    managerial: { email: 'manager.b@greenvalley.test', displayName: 'Mrs Dina' },
    section: { curriculum: 'british', grade: 1, name: '1A' },
    lesson: { curriculum: 'british', grade: 1, subject: 'english', date: DATE_B, title: 'The sh sound — Green Valley' },
    parent: { uid: 'e2e-parent-b', email: 'parent.b@greenvalley.test' },
    child: { name: 'Bilal', avatarColor: 'mint', curriculum: 'british', grade: 1 },
    // A navy/teal house, a different font and different worlds: nothing here is school A's, so a screenshot or a
    // theme leak between the two tenants is visible at a glance rather than by reading ids.
    theme: {
      logoUrl: 'https://placehold.co/256x256/0B2A45/FFFFFF.png?text=GV',
      appName: 'Green Valley',
      primary: '#123C5F',
      primaryInk: '#FFFFFF',
      accent: '#7FD3E8',
      ground: '#0B2A45',
      softBorder: '#21506E',
      mascotColor: '#4FB3C9',
      worldPalettes: {
        math: { primary: '#5EC8D8', deep: '#2A8FA5', soft: '#E5F6FA', ink: '#0E3542' },
        english: { primary: '#9AA7FF', deep: '#5566D8', soft: '#ECEEFF', ink: '#1C2250' },
      },
      fontChoice: 'fredoka',
    },
  },
];

// ---------------------------------------------------------------- http

class ApiError extends Error {
  constructor(method, path, status, body) {
    super(`${method} ${path} -> ${status} ${typeof body === 'string' ? body.slice(0, 300) : JSON.stringify(body).slice(0, 300)}`);
    this.status = status;
    this.body = body;
  }
}

/**
 * One request. `expect` lists the statuses the caller can handle; anything else throws (exit 1). QA can be scaled to
 * zero, so a transport error or a 502/503/504 is retried a few times with a backoff before it counts as a failure.
 */
async function call(method, path, { token, schoolId, body, expect = [200, 201, 204], raw = false } = {}) {
  const url = path.startsWith('http') ? path : BASE + path;
  const headers = {};
  if (token) headers.authorization = `Bearer ${token}`;
  if (schoolId) headers['X-School-Id'] = schoolId;
  if (body !== undefined) headers['content-type'] = 'application/json';

  let last;
  for (let attempt = 1; attempt <= 4; attempt++) {
    let res;
    try {
      res = await fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    } catch (e) {
      last = e;
      if (attempt === 4) throw new ApiError(method, path, 0, String(e.message || e));
      await sleep(attempt * 2000);
      continue;
    }
    const text = await res.text();
    const parsed = text === '' ? null : tryJson(text);
    if (res.status >= 502 && res.status <= 504 && attempt < 4) {
      last = new ApiError(method, path, res.status, parsed ?? text);
      await sleep(attempt * 2000);
      continue;
    }
    if (!expect.includes(res.status)) throw new ApiError(method, path, res.status, parsed ?? text);
    return raw ? { status: res.status, body: parsed ?? text } : (parsed ?? text);
  }
  throw last;
}

const tryJson = (t) => { try { return JSON.parse(t); } catch { return t; } };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------------------------------------------------------------- steps

const notes = [];
const blockers = [];
const note = (line) => { notes.push(line); console.log(line); };

/**
 * Refuses early, and legibly, when the target predates P1.3 (`backend/dashboard-auth`). `GET /auth/sign-in` is 405
 * "method not allowed" wherever the route is mapped and 401/403/404 where it is not, so it tells the two apart
 * without a password. Everything this seed does — sign-in, schools, invites, the by-code lookup — arrived in P1.3.
 */
async function requireDashboardApi() {
  const probe = await call('GET', '/auth/sign-in', { expect: [200, 401, 403, 404, 405], raw: true });
  if (probe.status === 405) return;
  let version = 'unknown';
  try { version = (await call('GET', '/health', { expect: [200] })).version ?? 'unknown'; } catch { /* keep "unknown" */ }
  fail(`${BASE} has no POST /auth/sign-in (GET is ${probe.status}, not 405): it is running ${version}, which is older than\n` +
       `        P1.3 backend/dashboard-auth. Deploy develop to this environment and run the seed again.`);
}

async function adminToken() {
  if (!ADMIN_PASSWORD) fail('E2E_ADMIN_PASSWORD is not set.');
  const signedIn = await call('POST', '/auth/sign-in', { body: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD }, expect: [200, 401, 403, 429], raw: true });
  if (signedIn.status === 429) fail(`sign-in is rate limited (429) for ${ADMIN_EMAIL}: SignInRateLimiter allows 10 failures per email + IP per window. Wait for the window to clear.`);
  if (signedIn.status !== 200) fail(`POST /auth/sign-in refused ${ADMIN_EMAIL} with ${signedIn.status}.`);
  const session = signedIn.body;
  if (session.role !== 'ADMIN') fail(`${ADMIN_EMAIL} signs in as ${session.role}, not ADMIN.`);
  if (session.mustChangePassword) fail(`${ADMIN_EMAIL} must change its password before it can seed.`);
  return session.token;
}

async function ensureSchool(token, spec) {
  const existing = (await call('GET', '/admin/schools', { token })).find((s) => s.code === spec.code);
  if (existing) {
    note(`school ${spec.key}: found ${spec.code}`);
    return { id: existing.id, name: existing.name, code: existing.code, created: false };
  }
  const school = await call('POST', '/admin/schools', {
    token,
    body: { name: spec.name, code: spec.code, curriculumOptions: spec.curriculumOptions, gradeOptions: spec.gradeOptions },
  });
  note(`school ${spec.key}: created ${school.code}`);
  return { id: school.id, name: school.name, code: school.code, created: true };
}

// ---------------------------------------------------------------- the theme (§3 white label)

/**
 * WCAG 2.2 §1.4.3 relative luminance and the (L1 + 0.05) / (L2 + 0.05) ratio — the same arithmetic as the server's
 * `quest.server.platform.Contrast`, restated here on purpose. The point is that a palette edited in this file is
 * measured *locally*, before a request goes out: a tweak that drops a pair below its bar fails with the pair and the
 * ratio named, rather than as a 400 from a server that may not even be running.
 */
const CONTRAST_TEXT = 4.5;      // Contrast.MINIMUM       — every text/background pair
const CONTRAST_GRAPHIC = 3.0;   // Contrast.MINIMUM_NON_TEXT — the mascot, a shape rather than a string

const channel = (value) => { const c = value / 255; return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };

function luminance(hex) {
  const rgb = Number.parseInt(hex.slice(1), 16);
  return 0.2126 * channel((rgb >> 16) & 0xFF) + 0.7152 * channel((rgb >> 8) & 0xFF) + 0.0722 * channel(rgb & 0xFF);
}

function contrast(foreground, background) {
  const a = luminance(foreground), b = luminance(background);
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}

const HEX = /^#[0-9A-Fa-f]{6}$/;

/**
 * Everything `ThemeService.validated` would refuse, checked before the PUT: the colour syntax, the two free-text caps
 * (`SafeText`: https-only `logoUrl` ≤ 2000, `appName` ≤ 60) and the six contrast pairs, in the order the server
 * reports them. A future palette tweak in this file therefore fails fast and locally.
 */
function assertThemeValid(key, theme) {
  const colours = ['primary', 'primaryInk', 'accent', 'ground', 'softBorder', 'mascotColor'];
  for (const field of colours)
    if (!HEX.test(theme[field] ?? '')) fail(`school ${key} theme: ${field} must be #RRGGBB, not ${theme[field]}`);
  for (const [world, palette] of Object.entries(theme.worldPalettes ?? {}))
    for (const field of ['primary', 'deep', 'soft', 'ink'])
      if (!HEX.test(palette[field] ?? '')) fail(`school ${key} theme: ${world}.${field} must be #RRGGBB, not ${palette[field]}`);

  if (!theme.logoUrl.startsWith('https://')) fail(`school ${key} theme: logoUrl must be an https:// URL`);
  if (theme.logoUrl.length > 2000) fail(`school ${key} theme: logoUrl is ${theme.logoUrl.length} characters, over the 2000 cap`);
  if (theme.appName.length > 60) fail(`school ${key} theme: appName is ${theme.appName.length} characters, over the 60 cap`);

  const pairs = [
    ['primaryInk', theme.primaryInk, 'primary', theme.primary, CONTRAST_TEXT],
    ['primaryInk', theme.primaryInk, 'ground', theme.ground, CONTRAST_TEXT],
    ['accent', theme.accent, 'ground', theme.ground, CONTRAST_TEXT],
    ['mascotColor', theme.mascotColor, 'ground', theme.ground, CONTRAST_GRAPHIC],
  ];
  for (const world of ['math', 'english']) {
    const palette = theme.worldPalettes?.[world];
    if (palette) pairs.push([`${world}.ink`, palette.ink, `${world}.soft`, palette.soft, CONTRAST_TEXT]);
  }
  for (const [on, foreground, over, background, minimum] of pairs) {
    const ratio = contrast(foreground, background);
    if (ratio < minimum)
      fail(`school ${key} theme: ${on} ${foreground} on ${over} ${background} is ${ratio.toFixed(1)}:1, needs ${minimum.toFixed(1)}:1`);
  }
}

/**
 * `PUT /admin/schools/{id}/theme`, skipped when the school already carries this `appName` — so a second run is a
 * single GET. `E2E_RESEED_THEMES=1` forces the write, which is what to use after editing a palette above.
 */
async function ensureTheme(token, school, spec) {
  const wanted = spec.theme;
  assertThemeValid(spec.key, wanted);

  const current = await call('GET', `/admin/schools/${school.id}/theme`, { token });
  if (!RESEED_THEMES && current?.appName === wanted.appName) {
    note(`theme ${school.code}: found "${current.appName}" (set E2E_RESEED_THEMES=1 to write it again)`);
    return { ...current, applied: false };
  }

  const saved = await call('PUT', `/admin/schools/${school.id}/theme`, { token, body: wanted, expect: [200] });
  for (const field of ['primary', 'accent', 'ground', 'mascotColor'])
    if (saved[field]?.toUpperCase() !== wanted[field].toUpperCase())
      fail(`theme ${school.code}: PUT answered ${field} ${saved[field]}, not ${wanted[field]}`);
  if (saved.appName !== wanted.appName) fail(`theme ${school.code}: PUT answered appName "${saved.appName}", not "${wanted.appName}"`);
  note(`theme ${school.code}: applied "${saved.appName}"`);
  return { ...saved, applied: true };
}

/**
 * A dashboard user with a password we know. Three paths, in order:
 *
 * <ol>
 *   <li>sign in — the account is already there with this password (the idempotent path);</li>
 *   <li>`POST /admin/schools/{id}/users` (ADMIN only, P1.9: email, role, password, displayName, teacherProfile) when
 *       the target's OpenAPI document lists it. The account comes back active with `mustChangePassword`, which the
 *       first sign-in clears;</li>
 *   <li>`POST /admin/schools/{id}/invites` and accept the one-time token — <em>if</em> the response ever carries it.</li>
 * </ol>
 *
 * Where a target has none of 2 or 3, the row can be created but not given a password: the token only leaves by email,
 * and the mailer is `LogMailer`, which logs the subject and a redacted recipient and never the body. The run then ends
 * BLOCKED rather than working around it.
 */
async function ensureStaff(token, schoolId, role, spec) {
  if (!STAFF_PASSWORD) fail('E2E_STAFF_PASSWORD is not set.');
  const profile = role === 'TEACHER'
    ? { displayName: spec.displayName, subjects: spec.subjects, curriculum: spec.curriculum, grades: spec.grades }
    : { displayName: spec.displayName };

  const session = await staffSession(spec.email);
  if (session) {
    note(`${role.toLowerCase()} ${spec.email}: found`);
    const id = await userId(token, schoolId, spec.email);
    return { id, email: spec.email, role, schoolId: session.schoolId, token: session.token, usable: true, created: false };
  }

  if (await hasCreateUserEndpoint()) {
    const created = await call('POST', `/admin/schools/${schoolId}/users`, {
      token,
      schoolId,
      body: { email: spec.email, role, password: STAFF_PASSWORD, displayName: spec.displayName, teacherProfile: profile },
      expect: [200, 201, 400, 409],
      raw: true,
    });
    if (created.status === 200 || created.status === 201) {
      const fresh = await staffSession(spec.email);
      if (!fresh) fail(`POST /admin/schools/${schoolId}/users created ${spec.email} but it cannot sign in.`);
      note(`${role.toLowerCase()} ${spec.email}: created with a password`);
      const id = created.body?.id ?? (await userId(token, schoolId, spec.email));
      return { id, email: spec.email, role, schoolId: fresh.schoolId, token: fresh.token, usable: true, created: true };
    }
    // 400/409 = the address is taken by a row whose password we do not have; the invite path below reports it.
  }

  const invite = await call('POST', `/admin/schools/${schoolId}/invites`, {
    token,
    schoolId,
    body: { email: spec.email, role, teacherProfile: profile },
    expect: [200, 400],
    raw: true,
  });
  // 400 = the address already has an account that is no longer `invited` — a re-run over a fixture this seed itself
  // left behind. Either way the row exists and cannot be given a password here.
  const inviteToken = invite.status === 400 ? null : tokenFromInvite(invite.body);
  if (!inviteToken) {
    // Nobody can sign into the row. Activating it is a first-class Admin action (§6 screen 6) and is what lets
    // `POST /admin/users/{id}/impersonate` — "View as…", also ADMIN-only, and read-only — issue a token scoped to this
    // account, which is all the read half of the isolation suite needs until the password gap is closed.
    const id = await userId(token, schoolId, spec.email);
    await call('PATCH', `/admin/users/${id}`, { token, schoolId, body: { status: 'active' } });
    blockers.push(`${spec.email}: the row exists, but no API hands the seed a password or the invite token for it — activated for View-as instead.`);
    return { id, email: spec.email, role, schoolId, usable: false, viewAsOnly: true, created: invite.status !== 400 };
  }

  const accepted = await call('POST', `/invites/${encodeURIComponent(inviteToken)}/accept`, {
    body: { password: STAFF_PASSWORD, displayName: spec.displayName },
  });
  note(`${role.toLowerCase()} ${spec.email}: invited and accepted`);
  const id = await userId(token, schoolId, spec.email);
  return { id, email: spec.email, role, schoolId: accepted.schoolId, token: accepted.token, usable: true, created: true };
}

/**
 * Signs a staff account in with `E2E_STAFF_PASSWORD`, clearing `mustChangePassword` on the way (a first login must,
 * §5, and P1.9 creates accounts with the flag set). Returns null when the account cannot sign in with that password.
 */
async function staffSession(email) {
  const signedIn = await call('POST', '/auth/sign-in', {
    body: { email, password: STAFF_PASSWORD },
    expect: [200, 401, 403, 429],
    raw: true,
  });
  if (signedIn.status === 429) {
    fail(`sign-in is rate limited (429) for ${email}: SignInRateLimiter allows 10 failures per email + IP per window. Wait for the window to clear.`);
  }
  if (signedIn.status !== 200) return null;
  const s = signedIn.body;
  if (s.mustChangePassword) {
    // Re-setting the same password is accepted (AuthService.changePassword has no reuse rule) and clears the flag.
    await call('POST', '/auth/change-password', {
      token: s.token,
      body: { currentPassword: STAFF_PASSWORD, newPassword: STAFF_PASSWORD },
      expect: [204, 400],
    });
  }
  return s;
}

/**
 * Whether this target has the ADMIN-only create-user endpoint (P1.9). Asked of the served OpenAPI document rather than
 * probed with a request, so a target without it is never sent a call it would refuse. Looked up once.
 */
let createUserEndpoint;
async function hasCreateUserEndpoint() {
  if (createUserEndpoint !== undefined) return createUserEndpoint;
  createUserEndpoint = false;
  for (const path of ['/v3/api-docs', '/openapi.json']) {
    const doc = await call('GET', path, { expect: [200, 401, 403, 404], raw: true });
    if (doc.status !== 200 || typeof doc.body !== 'object' || !doc.body?.paths) continue;
    if (doc.body.paths['/admin/schools/{id}/users']?.post) { createUserEndpoint = true; }
    break;
  }
  return createUserEndpoint;
}

/** The dashboard user row for an address inside one school. */
async function userId(token, schoolId, email) {
  const found = (await call('GET', `/admin/users?schoolId=${encodeURIComponent(schoolId)}`, { token }))
    .find((u) => u.email.toLowerCase() === email.toLowerCase());
  if (!found) fail(`no dashboard user ${email} in school ${schoolId}`);
  return found.id;
}

/**
 * The one-time token, if a response ever carries it. Kept generous on purpose: the moment the backend adds `token`
 * (or an accept link) to the invite response, or an ADMIN-only create-user-with-password endpoint, this seed works
 * unchanged. It never guesses and never reads it from anywhere but the response body.
 */
function tokenFromInvite(invite) {
  if (!invite || typeof invite !== 'object') return null;
  for (const key of ['token', 'inviteToken', 'acceptToken', 'oneTimeToken']) {
    if (typeof invite[key] === 'string' && invite[key].length > 0) return invite[key];
  }
  for (const key of ['acceptUrl', 'link', 'url', 'acceptInviteUrl']) {
    const value = invite[key];
    if (typeof value !== 'string') continue;
    const match = /[?&]token=([^&]+)/.exec(value);
    if (match) return decodeURIComponent(match[1]);
  }
  return null;
}

/**
 * The section a teacher teaches in, by name inside its (curriculum, grade) — `1A` of British Grade 1. Idempotent:
 * an existing section with that name is reused, because a join code may not be regenerated for free.
 */
async function ensureSection(token, school, spec) {
  const list = await call('GET', `/admin/classes?curriculum=${spec.curriculum}&grade=${spec.grade}`, { token, schoolId: school.id });
  const found = (Array.isArray(list) ? list : []).find((k) => k.name === spec.name);
  if (found) {
    note(`class ${school.code}: found ${spec.name}`);
    return found;
  }
  const created = await call('POST', '/admin/classes', {
    token,
    schoolId: school.id,
    body: { curriculum: spec.curriculum, grade: spec.grade, name: spec.name },
    expect: [200, 201],
  });
  note(`class ${school.code}: created ${spec.name}`);
  return created;
}

/**
 * The teaching assignment that makes the section hers (`docs/teacher-flow.md` §2). `PUT` takes the complete set she
 * should hold rather than a delta, so sending the one pair this fixture needs is idempotent on a re-run.
 */
async function ensureAssignment(token, school, teacher, section, subject) {
  if (!teacher?.id) return null;
  const held = await call('PUT', `/admin/teachers/${teacher.id}/assignments`, {
    token,
    schoolId: school.id,
    body: { assignments: [{ classId: section.id, subject }] },
  });
  note(`assignment ${school.code}: ${teacher.email} teaches ${section.name} · ${subject}`);
  return Array.isArray(held) ? held : [];
}

/** A published manual lesson: create → one play → two stops → publish (the sequence in server ManualLessonTest). */
async function ensureLesson(adminTok, school, spec, teacher, section) {
  // The teacher's own token proves the §5 restriction (her subject, curriculum and grade); ADMIN + X-School-Id is the
  // fallback so the rest of the fixture still exists when staff could not be given a password.
  const asTeacher = Boolean(teacher && teacher.usable);
  const token = asTeacher ? teacher.token : adminTok;
  const schoolId = asTeacher ? undefined : school.id;
  const by = asTeacher ? `teacher ${teacher.email}` : `ADMIN with X-School-Id (teacher token unavailable)`;

  const list = await call('GET', `/admin/lessons?curriculum=${spec.curriculum}&grade=${spec.grade}&subject=${spec.subject}`, { token, schoolId });
  const found = (Array.isArray(list) ? list : []).find((l) => l.title === spec.title);
  if (found) {
    if (found.status !== 'published') {
      await call('POST', `/admin/lessons/${found.id}/publish`, { token, schoolId });
      note(`lesson ${school.code}: published existing ${spec.title}`);
    } else {
      note(`lesson ${school.code}: found ${spec.title}`);
    }
    return { id: found.id, title: spec.title, status: 'published', createdBy: 'existing row' };
  }

  // A teacher creates her lesson on a class she is assigned to (N2.1/N2.3b); an ADMIN without a teacher token still
  // has the course-shaped `/admin/lessons` route, which is what keeps the fixture complete on a target where staff
  // could not be given a password.
  const lesson = asTeacher
    ? await call('POST', '/teacher/lessons', {
      token,
      body: { classId: section.id, subject: spec.subject, date: spec.date, source: 'manual', title: spec.title },
      expect: [200, 201],
    })
    : await call('POST', '/admin/lessons', {
      token,
      schoolId,
      body: { curriculum: spec.curriculum, grade: spec.grade, subject: spec.subject, date: spec.date, source: 'manual', title: spec.title },
      expect: [200, 201],
    });
  const playId = lesson.plays?.[0]?.id ?? (await call('POST', `/admin/lessons/${lesson.id}/plays`, { token, schoolId, body: { level: 1, variant: 0 } })).id;

  for (const stop of stopsFor(spec)) {
    await call('POST', `/admin/plays/${playId}/stops`, { token, schoolId, body: stop });
  }
  const published = await call('POST', `/admin/lessons/${lesson.id}/publish`, { token, schoolId });
  if (published.status !== 'published') fail(`lesson ${lesson.id} is ${published.status} after publish`);
  note(`lesson ${school.code}: created and published ${spec.title} (by ${by})`);
  return { id: lesson.id, title: spec.title, status: published.status, createdBy: by };
}

function stopsFor(spec) {
  const tip = { en: 'Do one more together at home.', ar: 'جرّبا واحدة أخرى في البيت.' };
  if (spec.subject === 'math') {
    return [
      {
        type: 'readPage', id: '', title: 'Twos on the number line', speak: "Let's hop in twos.",
        ingredient: { emoji: '🔢', name: 'twos' }, parentTip: tip,
        pageNumber: 1, sentences: ['We hop two at a time: 2, 4, 6.', 'Every hop adds two.'],
      },
      {
        type: 'multiSelect', id: '', title: 'Which numbers do we land on?', speak: 'Tap the two numbers we land on.',
        ingredient: { emoji: '🐸', name: 'hops' }, parentTip: tip,
        prompt: 'Tap two numbers we say when counting by 2s.',
        options: [{ id: 'a', label: '4' }, { id: 'b', label: '7' }, { id: 'c', label: '8' }],
        correctIds: ['a', 'c'], pick: 2,
      },
    ];
  }
  return [
    {
      type: 'readPage', id: '', title: 'The sh sound', speak: "Let's listen for sh.",
      ingredient: { emoji: '🐚', name: 'shell' }, parentTip: tip,
      pageNumber: 1, sentences: ['A shell sits on the shore.', 'Sh makes a quiet sound.'],
    },
    {
      type: 'trueFalse', id: '', title: 'True or false', speak: 'Does ship start with sh?',
      ingredient: { emoji: '🚢', name: 'ship' }, parentTip: tip,
      hint: 'Say the word slowly.', statement: 'The word "ship" starts with the sh sound.', answer: true,
    },
  ];
}

// ---------------------------------------------------------------- parents

/**
 * Parents live in Firebase Auth, not in the dashboard user table. Locally (`FAKE_AUTH`) a bearer token is just
 * `fake-token-<uid>`; against QA the app registers with email + password through the Identity Toolkit REST API using
 * the web key of `androidApp/src/qa/google-services.json`, and the seed does the same for its throwaway parent.
 */
async function parentToken(spec) {
  if (PARENT_AUTH === 'fake') return { token: `fake-token-${spec.uid}`, identity: `fake uid ${spec.uid}` };
  if (!PARENT_PASSWORD) fail('E2E_PARENT_PASSWORD is not set (needed for a non-local target).');
  const key = await firebaseApiKey();
  const body = { email: spec.email, password: PARENT_PASSWORD, returnSecureToken: true };

  const signIn = await call('POST', `https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${key}`, { body, expect: [200, 400] , raw: true });
  if (signIn.status === 200) return { token: signIn.body.idToken, identity: `firebase ${spec.email}` };

  const signUp = await call('POST', `https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${key}`, { body, expect: [200, 400], raw: true });
  if (signUp.status === 200) return { token: signUp.body.idToken, identity: `firebase ${spec.email} (registered)` };

  const reason = signUp.body?.error?.message || signIn.body?.error?.message || 'unknown';
  fail(`could not obtain a Firebase token for the seed parent: ${reason}`);
}

let cachedKey;
async function firebaseApiKey() {
  if (process.env.E2E_FIREBASE_API_KEY) return process.env.E2E_FIREBASE_API_KEY;
  if (cachedKey) return cachedKey;
  const path = resolve(REPO, 'androidApp', 'src', 'qa', 'google-services.json');
  let config;
  try {
    config = JSON.parse(await readFile(path, 'utf8'));
  } catch {
    fail(`set E2E_FIREBASE_API_KEY, or run from a checkout that has ${path}`);
  }
  cachedKey = config.client?.[0]?.api_key?.[0]?.current_key;
  if (!cachedKey) fail(`no web API key in ${path}`);
  return cachedKey;
}

async function ensureChild(token, spec, code) {
  const children = await call('GET', '/children', { token });
  const found = (Array.isArray(children) ? children : []).find((c) => c.name === spec.name);
  if (found) {
    note(`child ${spec.name}: found`);
    return { id: found.id, name: found.name, schoolId: found.schoolId, created: false };
  }
  const child = await call('POST', '/children', {
    token,
    body: { name: spec.name, avatarColor: spec.avatarColor, curriculum: spec.curriculum, grade: spec.grade, languages: ['en'], schoolCode: code },
    expect: [200, 201],
  });
  note(`child ${spec.name}: created and joined ${code}`);
  return { id: child.id, name: child.name, schoolId: child.schoolId, created: true };
}

// ---------------------------------------------------------------- run

function fail(message) {
  console.error(`\nFAILED: ${message}`);
  process.exit(1);
}

async function main() {
  console.log(`seeding ${BASE} (parents: ${PARENT_AUTH} auth)\n`);
  await requireDashboardApi();
  const token = await adminToken();
  const result = { baseUrl: BASE, seededAt: new Date().toISOString(), schools: {} };

  for (const spec of SCHOOLS) {
    const school = await ensureSchool(token, spec);
    const theme = await ensureTheme(token, school, spec);
    // A school a parent can join must be known by code before the child is created; `JoinSchoolInfo.theme` is what
    // the app's confirm step runs its colour transition from, so the theme has to be on the school by now.
    const byCode = await call('GET', `/schools/by-code/${spec.code}`, { expect: [200] });
    if (byCode.name !== school.name) fail(`GET /schools/by-code/${spec.code} is "${byCode.name}", not "${school.name}"`);
    if (byCode.theme?.appName !== spec.theme.appName)
      fail(`GET /schools/by-code/${spec.code} carries theme.appName "${byCode.theme?.appName}", not "${spec.theme.appName}"`);

    const teacher = await ensureStaff(token, school.id, 'TEACHER', spec.teacher);
    const managerial = await ensureStaff(token, school.id, 'MANAGERIAL', spec.managerial);
    const section = await ensureSection(token, school, spec.section);
    await ensureAssignment(token, school, teacher, section, spec.lesson.subject);
    const lesson = await ensureLesson(token, school, spec.lesson, teacher, section);

    const parent = await parentToken(spec.parent);
    const child = await ensureChild(parent.token, spec.child, spec.code);
    if (child.schoolId !== school.id) {
      blockers.push(`child ${child.name} is in school ${child.schoolId}, not ${school.id} — the join code did not take.`);
    }

    result.schools[spec.key] = {
      id: school.id,
      name: school.name,
      code: school.code,
      theme: {
        appName: theme.appName,
        primary: theme.primary,
        accent: theme.accent,
        ground: theme.ground,
        mascotColor: theme.mascotColor,
        logoUrl: theme.logoUrl,
        fontChoice: theme.fontChoice,
        applied: theme.applied,
      },
      teacher: { id: teacher.id, email: teacher.email, usable: teacher.usable, viewAsOnly: Boolean(teacher.viewAsOnly) },
      section: { id: section.id, name: section.name, curriculum: section.curriculum, grade: section.grade, subject: spec.lesson.subject },
      managerial: { id: managerial.id, email: managerial.email, usable: managerial.usable, viewAsOnly: Boolean(managerial.viewAsOnly) },
      lesson: { id: lesson.id, title: lesson.title, status: lesson.status, createdBy: lesson.createdBy, ...spec.lesson },
      parent: { uid: spec.parent.uid, email: spec.parent.email, auth: PARENT_AUTH },
      child: { id: child.id, name: child.name, schoolId: child.schoolId },
    };
  }

  await mkdir(dirname(OUT), { recursive: true });
  await writeFile(OUT, JSON.stringify(result, null, 2) + '\n');

  summary(result);
  if (blockers.length) {
    console.log('\nBLOCKED — the fixture is as complete as the API allows:');
    for (const b of blockers) console.log(`  - ${b}`);
    console.log(
      '\n  The seed needs an ADMIN-only way to create a dashboard user with a known password.\n' +
      '  This target has neither POST /admin/schools/{id}/users (P1.9) in its OpenAPI document nor a token in the\n' +
      '  POST /admin/schools/{id}/invites response, and the one-time link is only emailed — LogMailer logs the\n' +
      '  subject and a redacted recipient, never the body. The only account with a settable password is the platform\n' +
      '  ADMIN (ADMIN_EMAIL/ADMIN_PASSWORD at start-up). Owner: the `backend` agent.');
    process.exit(2);
  }
  console.log('\nseeded.');
}

function summary(result) {
  const rows = [];
  for (const [key, s] of Object.entries(result.schools)) {
    rows.push([`school ${key}`, s.name, s.code, s.id]);
    rows.push([`  theme`, `"${s.theme.appName}"`, `primary ${s.theme.primary} accent ${s.theme.accent} ${s.theme.fontChoice}`,
      s.theme.applied ? 'applied' : 'already set']);
    rows.push([`  teacher`, s.teacher.email, s.teacher.usable ? 'signs in' : 'NO PASSWORD (View-as only)', s.teacher.id]);
    rows.push([`  class`, s.section.name, `${s.section.curriculum}/${s.section.grade} · ${s.section.subject}`, s.section.id]);
    rows.push([`  managerial`, s.managerial.email, s.managerial.usable ? 'signs in' : 'NO PASSWORD (View-as only)', s.managerial.id]);
    rows.push([`  lesson`, s.lesson.title, `${s.lesson.curriculum}/${s.lesson.grade}/${s.lesson.subject} ${s.lesson.status}`, s.lesson.id]);
    rows.push([`  parent`, s.parent.email, s.parent.auth, s.parent.uid]);
    rows.push([`  child`, s.child.name, `school ${s.child.schoolId === s.id ? 'ok' : s.child.schoolId}`, s.child.id]);
  }
  const width = (i) => Math.max(...rows.map((r) => String(r[i]).length));
  const w = [0, 1, 2, 3].map(width);
  console.log('');
  for (const r of rows) console.log(r.map((cell, i) => String(cell).padEnd(i === 3 ? 0 : w[i])).join('  '));
  console.log(`\nids written to ${OUT}`);
}

main().catch((e) => {
  if (e instanceof ApiError) fail(e.message);
  fail(e?.stack || String(e));
});
