import { request, type APIRequestContext } from '@playwright/test';
import { ADMIN, API, expect, SARA, signInForToken, type Account } from './env';

/**
 * The half of N4.5 no screen can drive: the child's side.
 *
 * `docs/teacher-flow.md` §10 steps 5 and 7 both begin with a child playing something — a homework
 * in the player, an exam inside its window — and there is **no web player** (N3 is not built; the
 * owner tests the child's side on the mobile app). So the child here plays over the API the app
 * itself uses: a parent bearer, `GET /children/{id}/map`, `GET /lessons/{id}?childId=…` for the
 * paper, and `POST /children/{id}/attempts` for what she answered. Nothing is written into the
 * database behind the product's back — every row this file creates came through a public endpoint
 * with the app's own permissions on it.
 *
 * **The parent.** `seed/children.csv` gives every roster child a `parentEmail`, but a roster child
 * has no `parent_id` until a parent claims her (V7; `ChildService.owned`), and there is no
 * endpoint that hands an existing roster child to an account. So the suite walks the path the app
 * walks on a real phone: a parent signs in, then creates her child with the **class join code**
 * from the card (`ChildService.create`), which settles the school, the section, the curriculum and
 * the grade at once. Firebase is excluded exactly as the brief says — the local server runs with
 * `quest.auth.fake` on (`application.yml`, the `h2` profile), where `Bearer fake-token-<uid>` is
 * the parent's token and the `parents` row is written on first sight. That is also why every
 * caller here is **local only**: QA runs real Firebase and is read-only for this package.
 *
 * Everything is scoped by a per-run tag so two runs never collide, and every helper is safe to
 * call twice.
 */

/** A parent as the API sees her: a fake-auth uid, which is all `FirebaseTokenFilter` needs. */
export interface Parent {
  readonly uid: string;
  readonly token: string;
}

export function parentOf(uid: string): Parent {
  return { uid, token: `fake-token-${uid}` };
}

export async function api(): Promise<APIRequestContext> {
  return request.newContext({ baseURL: API });
}

export function bearer(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}` };
}

/** A staff bearer, cached per account so a spec does not sign Sara in for every call it makes. */
const tokens = new Map<string, string>();
export async function staff(who: Account = SARA): Promise<Record<string, string>> {
  const cached = tokens.get(who.email);
  if (cached) return bearer(cached);
  const token = await signInForToken(who);
  tokens.set(who.email, token);
  return bearer(token);
}

export async function adminHeaders(): Promise<Record<string, string>> {
  return staff(ADMIN);
}

/** `GET /me` → the caller's school, which every flag route is addressed by. */
export async function schoolOfSara(context: APIRequestContext): Promise<string> {
  const me = await context.get('/me', { headers: await staff() });
  expect(me.ok(), `GET /me: HTTP ${me.status()}`).toBeTruthy();
  return ((await me.json()) as { schoolId: string }).schoolId;
}

/**
 * Turns the package's flags on and hands back the restore.
 *
 * The one-school seed ships `gradebook`, `openStopMarking`, `exams` and `teacher.rosterEdit`
 * **off** (`V7__sections.sql`), and `my-classes.spec.ts` asserts that flag-off shape of the class
 * page, so a spec that turns one on has to put it back — including when it fails.
 */
export async function withFlags(
  context: APIRequestContext,
  schoolId: string,
  keys: readonly string[],
): Promise<() => Promise<void>> {
  const headers = await adminHeaders();
  const before = (await (await context.get(`/schools/${schoolId}/flags`)).json()) as Record<string, boolean>;
  for (const key of keys) {
    const flipped = await context.put(`/admin/schools/${schoolId}/flags/${key}`, {
      headers,
      data: { enabled: true },
    });
    expect(flipped.ok(), `PUT flag ${key}: HTTP ${flipped.status()}`).toBeTruthy();
  }
  return async () => {
    for (const key of keys)
      if (before[key] !== true)
        await context.put(`/admin/schools/${schoolId}/flags/${key}`, { headers, data: { enabled: false } });
  };
}

/** The class's join code, from the Admin's own list — the code printed on the class's card. */
export async function joinCodeOf(context: APIRequestContext, classId: string): Promise<string> {
  const response = await context.get('/admin/classes', { headers: await adminHeaders() });
  expect(response.ok(), `GET /admin/classes: HTTP ${response.status()}`).toBeTruthy();
  const rows = (await response.json()) as { id: string; joinCode: string }[];
  const row = rows.find((section) => section.id === classId);
  expect(row?.joinCode, `class ${classId} should have a join code`).toBeTruthy();
  return row!.joinCode;
}

/** A child of this run, created by her parent with the class's join code — the app's own path. */
export async function createChild(
  context: APIRequestContext,
  parent: Parent,
  name: string,
  joinCode: string,
): Promise<string> {
  const response = await context.post('/children', {
    headers: bearer(parent.token),
    data: {
      name,
      avatarColor: 'sky',
      curriculum: 'british',
      grade: 1,
      languages: ['en'],
      joinCode,
    },
  });
  expect(response.ok(), `POST /children: HTTP ${response.status()} ${await response.text()}`).toBeTruthy();
  return ((await response.json()) as { id: string }).id;
}

/** Best effort: a child this run made, taken back off the roster. Never fails a run. */
export async function removeChild(context: APIRequestContext, parent: Parent, childId: string): Promise<void> {
  try {
    await context.delete(`/children/${childId}`, { headers: bearer(parent.token) });
  } catch {
    // tidying up is not an assertion
  }
}

/** One stop of a hand-written play: four that score themselves, one open one for §7's marking. */
export function choiceStop(index: number, title: string): Record<string, unknown> {
  return {
    id: `n45-${index}`,
    type: 'choice',
    title: `${title} ${index}`,
    speak: 'Count the carrots with me.',
    ingredient: { emoji: '🥕', name: 'carrot' },
    parentTip: { en: 'Count them together.', ar: 'عدّوها معًا.' },
    hint: 'Count them one at a time.',
    question: `How many carrots are in the pot? (${index})`,
    options: [
      { id: 'a', label: '3' },
      { id: 'b', label: '4' },
    ],
    correctOptionId: 'a',
  };
}

export function retellStop(title: string): Record<string, unknown> {
  return {
    id: 'n45-retell',
    type: 'retell',
    title,
    speak: 'Tell me the story back.',
    ingredient: { emoji: '🥔', name: 'potato' },
    parentTip: { en: 'Ask her to retell it.', ar: 'اطلبوا إعادة الحكاية.' },
    prompt: 'Tell me what happened in the kitchen.',
    // The server refuses a retell without all three stages (`AdminLessonService.validateStop`).
    cues: [
      { stage: 'beginning', cue: 'We picked the carrots.' },
      { stage: 'middle', cue: 'We counted them.' },
      { stage: 'end', cue: 'We made the soup.' },
    ],
    modelAnswer: 'We picked the carrots, counted them and made soup.',
    record: true,
  };
}

/** The stop ids the server gave a lesson's Level 1 play, in the order the child meets them. */
export async function stopIdsOf(context: APIRequestContext, lessonId: string): Promise<readonly string[]> {
  const response = await context.get(`/teacher/lessons/${lessonId}`, { headers: await staff() });
  expect(response.ok(), `GET lesson: HTTP ${response.status()}`).toBeTruthy();
  const lesson = (await response.json()) as { plays: { play: { stops: { id: string }[] } }[] };
  const play = lesson.plays[0];
  expect(play, 'the lesson should have a Level 1 play').toBeTruthy();
  return play!.play.stops.map((stop) => stop.id);
}

/**
 * A hand-written homework, published to one class — §10 step 3's ending, over the API.
 *
 * Manual is the source on purpose: `LLM_PROVIDER=fake` would make the pipeline instant anyway, but
 * a hand-written play is the only one whose arithmetic is known in advance, and the numbers this
 * package asserts are the point. A manual lesson arrives with its Level 1 play already created, so
 * the stops are added to that play rather than to a new one.
 */
export async function publishHomework(
  context: APIRequestContext,
  args: { readonly classId: string; readonly title: string; readonly date: string },
): Promise<{ readonly lessonId: string; readonly stopIds: readonly string[] }> {
  const headers = await staff();
  const created = await context.post('/teacher/lessons', {
    headers,
    data: {
      classId: args.classId,
      subject: 'math',
      date: args.date,
      source: 'manual',
      title: args.title,
      practiceLength: 5,
    },
  });
  expect(created.ok(), `POST /teacher/lessons: HTTP ${created.status()} ${await created.text()}`).toBeTruthy();
  const lessonId = ((await created.json()) as { id: string }).id;

  const playId = await firstPlayId(context, lessonId);
  const stopIds: string[] = [];
  const stops = [
    choiceStop(1, 'Carrots'),
    choiceStop(2, 'Carrots'),
    choiceStop(3, 'Carrots'),
    choiceStop(4, 'Carrots'),
    retellStop('Tell the soup story back'),
  ];
  for (const stop of stops) {
    const added = await context.post(`/teacher/plays/${playId}/stops`, { headers, data: stop });
    expect(added.ok(), `POST stop: HTTP ${added.status()} ${await added.text()}`).toBeTruthy();
    stopIds.push(((await added.json()) as { id: string }).id);
  }

  const published = await context.post(`/teacher/lessons/${lessonId}/publish`, {
    headers,
    data: { classIds: [args.classId] },
  });
  expect(published.ok(), `publish: HTTP ${published.status()} ${await published.text()}`).toBeTruthy();
  return { lessonId, stopIds };
}

async function firstPlayId(context: APIRequestContext, lessonId: string): Promise<string> {
  const response = await context.get(`/teacher/lessons/${lessonId}`, { headers: await staff() });
  expect(response.ok(), `GET lesson: HTTP ${response.status()}`).toBeTruthy();
  const lesson = (await response.json()) as { plays: { id: string }[] };
  expect(lesson.plays.length, 'a manual lesson arrives with its Level 1 play').toBeGreaterThan(0);
  return lesson.plays[0]!.id;
}

/** An exam with a window that is open **now**, published, over the same editor a lesson uses. */
export async function publishExam(
  context: APIRequestContext,
  args: { readonly classId: string; readonly title: string; readonly minutes: number },
): Promise<{ readonly examId: string; readonly stopIds: readonly string[] }> {
  const headers = await staff();
  const opensAt = Date.now();
  const created = await context.post(`/teacher/classes/${encodeURIComponent(args.classId)}/exams`, {
    headers,
    data: {
      title: args.title,
      opensAt,
      closesAt: opensAt + args.minutes * 60_000,
      level: '1',
      source: 'manual',
      durationMinutes: args.minutes,
      releaseMode: 'manual',
      practiceLength: 5,
    },
  });
  expect(created.ok(), `POST exam: HTTP ${created.status()} ${await created.text()}`).toBeTruthy();
  const examId = ((await created.json()) as { examId: string }).examId;

  const playId = await firstPlayId(context, examId);
  const stopIds: string[] = [];
  for (const stop of [
    choiceStop(1, 'Exam question'),
    choiceStop(2, 'Exam question'),
    choiceStop(3, 'Exam question'),
    choiceStop(4, 'Exam question'),
    retellStop('Tell the exam story back'),
  ]) {
    const added = await context.post(`/teacher/plays/${playId}/stops`, { headers, data: stop });
    expect(added.ok(), `POST exam stop: HTTP ${added.status()} ${await added.text()}`).toBeTruthy();
    stopIds.push(((await added.json()) as { id: string }).id);
  }

  const published = await context.post(`/teacher/exams/${examId}/publish`, { headers });
  expect(published.ok(), `publish exam: HTTP ${published.status()} ${await published.text()}`).toBeTruthy();
  return { examId, stopIds };
}

/** The paper as the child's device downloads it — an exam's is derived, never the raw levels. */
export async function paperOf(
  context: APIRequestContext,
  parent: Parent,
  childId: string,
  lessonId: string,
): Promise<{
  readonly type?: string;
  readonly hintsOff?: boolean;
  readonly numbersOff?: boolean;
  readonly stops: readonly { id: string; type: string; hint?: string }[];
}> {
  const response = await context.get(`/lessons/${lessonId}?childId=${childId}`, {
    headers: bearer(parent.token),
  });
  expect(response.ok(), `GET /lessons/${lessonId}: HTTP ${response.status()}`).toBeTruthy();
  const lesson = (await response.json()) as {
    type?: string;
    hintsOff?: boolean;
    numbersOff?: boolean;
    plays: { stops: { id: string; type: string; hint?: string }[] }[];
  };
  const play = lesson.plays[0];
  expect(play, `lesson ${lessonId} should carry a play for the child to sit`).toBeTruthy();
  return { type: lesson.type, hintsOff: lesson.hintsOff, numbersOff: lesson.numbersOff, stops: play!.stops };
}

/** The island a child sees for one lesson, or undefined — an exam has one only inside its window. */
export async function islandOf(
  context: APIRequestContext,
  parent: Parent,
  childId: string,
  lessonId: string,
): Promise<{ readonly state: string; readonly examWindow?: unknown } | undefined> {
  const from = isoDay(-7);
  const to = isoDay(7);
  const response = await context.get(`/children/${childId}/map?from=${from}&to=${to}`, {
    headers: bearer(parent.token),
  });
  expect(response.ok(), `GET map: HTTP ${response.status()}`).toBeTruthy();
  const map = (await response.json()) as { islands: { lessonId?: string; state: string; examWindow?: unknown }[] };
  return map.islands.find((island) => island.lessonId === lessonId);
}

function isoDay(offset: number): string {
  const day = new Date();
  day.setUTCDate(day.getUTCDate() + offset);
  return day.toISOString().slice(0, 10);
}

/** One answered stop, in the shape `AttemptUpload` asks for. */
export function attempt(args: {
  readonly lessonId: string;
  readonly stopId: string;
  readonly correct?: boolean;
  readonly stars?: number;
  readonly at?: number;
}): Record<string, unknown> {
  const correct = args.correct !== false;
  return {
    id: `${args.stopId}:${args.at ?? Date.now()}:${Math.random().toString(36).slice(2, 8)}`,
    stopId: args.stopId,
    lessonId: args.lessonId,
    level: 1,
    answerJson: JSON.stringify({ optionId: correct ? 'a' : 'b' }),
    correct,
    attemptNumber: 1,
    mistakes: correct ? 0 : 1,
    stars: args.stars ?? (correct ? 3 : 1),
    answeredAt: args.at ?? Date.now(),
  };
}

/** `POST /children/{id}/attempts` — the one write the player makes, and the one an exam turns on. */
export async function upload(
  context: APIRequestContext,
  parent: Parent,
  childId: string,
  attempts: readonly Record<string, unknown>[],
): Promise<{ readonly status: number; readonly code?: string; readonly accepted?: number }> {
  const response = await context.post(`/children/${childId}/attempts`, {
    headers: bearer(parent.token),
    data: attempts,
  });
  const body = (await response.json()) as { accepted?: number; code?: string };
  return { status: response.status(), code: body.code, accepted: body.accepted };
}
