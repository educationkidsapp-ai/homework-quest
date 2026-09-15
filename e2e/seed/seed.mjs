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
    lesson: { curriculum: 'british', grade: 1, subject: 'math', date: DATE_A, title: 'Counting by 2s — Al Noor' },
    parent: { uid: 'e2e-parent-a', email: 'parent.a@alnoor.test' },
    child: { name: 'Aya', avatarColor: 'sky', curriculum: 'british', grade: 1 },
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
    lesson: { curriculum: 'british', grade: 1, subject: 'english', date: DATE_B, title: 'The sh sound — Green Valley' },
    parent: { uid: 'e2e-parent-b', email: 'parent.b@greenvalley.test' },
    child: { name: 'Bilal', avatarColor: 'mint', curriculum: 'british', grade: 1 },
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
  const session = await call('POST', '/auth/sign-in', { body: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD } });
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

/**
 * A dashboard user with a password we know.
 *
 * The only account the platform can create with a chosen password is the ADMIN, from ADMIN_EMAIL/ADMIN_PASSWORD at
 * start-up. `POST /admin/schools/{id}/invites` returns an {@code Invite} without the token (server/openapi.json), and
 * the one-time link is only emailed — the QA mailer is `LogMailer`, which deliberately logs the subject and a redacted
 * recipient but never the body. So unless a future response carries the token, this step can create the account row
 * (status `invited`) but cannot give it a password, and the run ends as BLOCKED rather than working around it.
 */
async function ensureStaff(token, schoolId, role, spec) {
  if (!STAFF_PASSWORD) fail('E2E_STAFF_PASSWORD is not set.');

  const signedIn = await call('POST', '/auth/sign-in', {
    body: { email: spec.email, password: STAFF_PASSWORD },
    expect: [200, 401, 403],
    raw: true,
  });
  if (signedIn.status === 200) {
    const s = signedIn.body;
    if (s.mustChangePassword) {
      await call('POST', '/auth/change-password', {
        token: s.token,
        body: { currentPassword: STAFF_PASSWORD, newPassword: STAFF_PASSWORD },
        expect: [204, 400],
      });
    }
    note(`${role.toLowerCase()} ${spec.email}: found`);
    const id = await userId(token, schoolId, spec.email);
    return { id, email: spec.email, role, schoolId: s.schoolId, token: s.token, usable: true, created: false };
  }

  const invite = await call('POST', `/admin/schools/${schoolId}/invites`, {
    token,
    schoolId,
    body: {
      email: spec.email,
      role,
      teacherProfile: role === 'TEACHER'
        ? { displayName: spec.displayName, subjects: spec.subjects, curriculum: spec.curriculum, grades: spec.grades }
        : { displayName: spec.displayName },
    },
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

/** A published manual lesson: create → one play → two stops → publish (the sequence in server ManualLessonTest). */
async function ensureLesson(adminTok, school, spec, teacher) {
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

  const lesson = await call('POST', '/admin/lessons', {
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
    // A school a parent can join must be known by code before the child is created.
    const byCode = await call('GET', `/schools/by-code/${spec.code}`, { expect: [200] });
    if (byCode.name !== school.name) fail(`GET /schools/by-code/${spec.code} is "${byCode.name}", not "${school.name}"`);

    const teacher = await ensureStaff(token, school.id, 'TEACHER', spec.teacher);
    const managerial = await ensureStaff(token, school.id, 'MANAGERIAL', spec.managerial);
    const lesson = await ensureLesson(token, school, spec.lesson, teacher);

    const parent = await parentToken(spec.parent);
    const child = await ensureChild(parent.token, spec.child, spec.code);
    if (child.schoolId !== school.id) {
      blockers.push(`child ${child.name} is in school ${child.schoolId}, not ${school.id} — the join code did not take.`);
    }

    result.schools[spec.key] = {
      id: school.id,
      name: school.name,
      code: school.code,
      teacher: { id: teacher.id, email: teacher.email, usable: teacher.usable, viewAsOnly: Boolean(teacher.viewAsOnly) },
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
      '  Today the only account with a settable password is the platform ADMIN (ADMIN_EMAIL/ADMIN_PASSWORD at\n' +
      '  start-up). POST /admin/schools/{id}/invites returns an Invite with no token, and the one-time link is only\n' +
      '  emailed — LogMailer logs the subject and a redacted recipient, never the body.\n' +
      '  Either add the token to the Invite response for an ADMIN caller, or add POST /admin/schools/{id}/users\n' +
      '  (email, role, password, teacherProfile). Owner: the `backend` agent.');
    process.exit(2);
  }
  console.log('\nseeded.');
}

function summary(result) {
  const rows = [];
  for (const [key, s] of Object.entries(result.schools)) {
    rows.push([`school ${key}`, s.name, s.code, s.id]);
    rows.push([`  teacher`, s.teacher.email, s.teacher.usable ? 'signs in' : 'NO PASSWORD (View-as only)', s.teacher.id]);
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
