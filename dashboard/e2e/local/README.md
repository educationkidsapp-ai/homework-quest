# The end-to-end suite

`e2e/local/` runs the **built** dashboard against a real API — the local H2 server here, and the
deployed QA API after every deploy. One suite, two targets, which is the point: what CI proves
after a deploy is what you can reproduce on this Mac.

- **locally** (`pnpm e2e:local`) the bundle is served by `e2e/local/serve.mjs` in the shape the
  container has (P3.4): one origin, `/dashboard/**` from `dist/browser`, everything else proxied
  to the API, and the Content-Security-Policy the server sends.
- **against QA** (`pnpm e2e:qa`, `E2E_BASE_URL=<api>`) Playwright starts nothing: the API serves
  the dashboard itself at `<api>/dashboard/`, `e2e/global-setup.ts` waits for `<api>/health`
  first, and `expect` gets 15 s rather than 5 because a cold Cloud Run instance is slower than
  this Mac at everything.

`e2e/styleguide.spec.ts` is the other suite and still needs `ng serve` (`pnpm e2e`): the `qa` and
`production` configurations replace `styleguide.route.ts`, so that route is in no built bundle.

## One school

Everything here assumes the **one-school seed** the server writes at startup (`SEED_SCHOOL=true`,
`server/src/main/resources/seed/*.csv`): 31 classes, 40 teachers, ~600 children, one school.
D13 keeps `multiSchool` **off**, so there is no school switcher and no second school — the
two-school fixture of `e2e/seed/seed.mjs` is legacy and QA never runs it (see `e2e/README.md`).

The people the suite signs in as:

| Who | Email | What the seed gives them |
| --- | --- | --- |
| Admin | `$E2E_ADMIN_EMAIL` (default `admin@quest.local`) | the platform ADMIN, one school |
| Sara Al Harbi | `sara.al-harbi@school.test` | **1A British** and **1B British**, both Math — the sibling pair the publish sheet and the drag-to-copy exist for |
| Omar Nasser | `omar.nasser@school.test` | 3A/3B British Math — the other teacher, for the isolation checks |

Nothing seeds a MANAGERIAL user, so there is no managerial coverage here yet.

## Environment

Passwords come from the environment, are never printed and are never written down here. A missing
one fails with the variable's name, not its value.

| Variable | Used for |
| --- | --- |
| `E2E_BASE_URL` | the deployed API to test against; unset means the local server below |
| `E2E_ADMIN_EMAIL` | the Admin's email (default `admin@quest.local`) |
| `E2E_ADMIN_PASSWORD` | the Admin's password — the server's `ADMIN_PASSWORD` |
| `E2E_STAFF_PASSWORD` | the seeded teachers' password — **the same value** as the server's `SEED_STAFF_PASSWORD` |
| `HQ_API` | local only: where `serve.mjs` proxies (default `http://localhost:18080`) |
| `E2E_FAIL_ONCE_AT` | opts `lesson-retry.spec.ts` in; see below |

Setting `E2E_BASE_URL` also opts two files **out**: `admin-classes-teachers.spec.ts` entirely,
and `lesson-editor.spec.ts`'s level generation. Both are explained below.

`E2E_STAFF_PASSWORD` and `SEED_STAFF_PASSWORD` **must be the same value** wherever the suite runs,
including the `qa` GitHub environment: the first is what Sara types, the second is what the server
set for her. If they differ, every teacher spec fails at `beforeAll` with "could not sign in".

## Running it locally

```bash
# 1. the API on in-memory H2 with the one-school seed — JDK 21, no Docker, no model calls
export JAVA_HOME=…/jdk-21…            # the default `java` on this Mac is 17
./gradlew :shared-api:publishToMavenLocal -Pquest.serverOnly=true   # once
(cd server && ./mvnw -q -B package -DskipTests)

SPRING_PROFILES_ACTIVE=h2 SEED_SCHOOL=true SEED_STAFF_PASSWORD="$E2E_STAFF_PASSWORD" \
  ADMIN_EMAIL="$E2E_ADMIN_EMAIL" ADMIN_PASSWORD="$E2E_ADMIN_PASSWORD" \
  LLM_PROVIDER=fake PORT=18080 PUBLIC_URL=http://localhost:18080 \
  DASHBOARD_URL=http://localhost:4300 java -jar server/target/server.jar &

# 2. the bundle the container will serve
cd dashboard && pnpm build --configuration=production

# 3. the suite (Playwright starts e2e/local/serve.mjs itself)
E2E_ADMIN_EMAIL=… E2E_ADMIN_PASSWORD=… E2E_STAFF_PASSWORD=… pnpm e2e:local
# or one file: pnpm e2e:local teacher-flow
```

## Running it against QA

This is exactly what the `Dashboard e2e (Playwright, against QA)` job in `deploy-qa.yml` runs:

```bash
cd dashboard
E2E_BASE_URL=https://…run.app \
E2E_ADMIN_EMAIL=… E2E_ADMIN_PASSWORD=… E2E_STAFF_PASSWORD=… \
  pnpm e2e:qa            # = playwright test --retries=1
```

The job is capped at 20 minutes and the suite is written to finish well inside it: one worker,
one retry, a 120 s ceiling per test (`playwright.config.ts`) that only the pipeline specs raise,
and `test.describe.serial` wherever one test hands state to the next.

**QA's database is shared and never reset.** Every spec here is idempotent on it: titles carry a
per-run tag (`RUN` in `env.ts`), lessons are written on a stretch of future days chosen per run,
and each file deletes what it made in `afterAll` (`removeLessonsOfThisRun`, through the Admin —
a teacher may only delete a draft or a failed lesson). No spec touches seed data.

QA runs the **real** model (`LLM_PROVIDER=deepseek`), so `teacher-flow.spec.ts` waits minutes for
the pipeline rather than the seconds the local `fake` provider takes.

### The PDF fixture, and why it matters on QA

`e2e/fixtures/one-page.pdf` is a Year 1 British maths page — counting in 2s, with gaps to fill,
a word problem and an exit question. It used to read "One page test fixture" and nothing else,
which the `fake` provider happily analysed and the real model refused outright:

    analyze  no_teaching_content
    "These pages don't contain anything to practise. Check that you uploaded the lesson slides,
     not a cover page or a worksheet key."

That refusal is right, and it is why the pipeline had never actually run on QA. A fixture that a
real model will teach from is part of the test.

The server caches by **source hash**: `AnalysisService` caches the analysis and
`GenerationService` caches each play (`CacheKeys.playKey(hash, level, variant, seed)`). So the
first QA run after this file changes pays for the whole pipeline — eight or nine minutes of real
model calls — and every run after it is a cache hit and takes seconds. Change the fixture only
when something needs it, and expect one slow run when you do.

### Known: `generate_L1 model_failed` on QA

`teacher-flow.spec.ts` step 3b waits on the server's own status, so when the pipeline fails the
CI log names the step and the model's message rather than leaving a disabled tab behind. The one
seen so far, on three lessons out of four before the cache was warm:

    generate_L1 model_failed: The AI returned an invalid answer twice. …
    (Level 1 didn't match the schema: /stops/5/hint: all values fail against the false schema)

DeepSeek puts a `hint` on a stop type whose schema forbids it — `Play.schema.json` gives `hint` to
nine types and `additionalProperties: false` to the other thirteen, and the one retry
`GenerationService` allows repeats the mistake. It is a server-side prompt/schema question, not a
test one, and it is reported to the planner rather than worked around here. Once a valid Level 1
is in the generation cache for this fixture's hash the step is a cache hit and the flake is gone,
which is why the suite is green on QA today.

## What it proves

| File | What would break without it |
| --- | --- |
| `teacher-flow.spec.ts` | `docs/teacher-flow.md` §10 steps 2–4 and 8 as one chain: her two rows, the `+`, a PDF through the step strip, a Level 2 edit, Preview as child, publish to 1A **and** 1B, both calendars — plus N1.3, that Omar is refused her class and her lesson by API and by URL |
| `shell.spec.ts` | the sign-in branding, the CSP, where each role lands, rail and router being the same door, the keyboard contract, the screenshot set |
| `this-week.spec.ts` | the week grid, the `+`, the drag that moves a lesson and the drop that copies it to the sibling section |
| `my-classes.spec.ts` | a card per assignment, the class page's calendar and rail item, and a roster that is one section's own |
| `lesson-editor.spec.ts` | manual authoring: the stop menu, the JSON editor against the schema, reordering, pictures, the parent panel |
| `lesson-publish.spec.ts` | the publish sheet, Unpublish + Undo, moving and deleting a draft, "Analyzed before · 0 tokens", and the `ar` deep-reload regression |
| `admin-classes-teachers.spec.ts` | §10 step 1: an Admin creates 1A/1B and Sara, the one-time password shows once, a second Math teacher for 1A is refused (**local only** — see below) |
| `lesson-retry.spec.ts` | that a failed pipeline step really retries past its failure (opt-in, below) |

Screenshots land in `docs/screenshots/dashboard-p3.1/`, `dashboard-n1.2/`, `dashboard-n2.2/`,
`dashboard-n2.3/`, `dashboard-n2.4/` and `dashboard-n2.4b/` (1366 × 768, EN and AR) and are
committed.

### Retired with D13 (N2.5)

`lessons.spec.ts`, `new-lesson.spec.ts` and `lesson-review.spec.ts` are gone. All three drove the
Admin's school switcher or signed in as `teacher.a@alnoor.test` from the two-school fixture, so
none of them could pass on QA — and two of them waited three minutes each for a switcher that is
not drawn, which is what cancelled the post-deploy job at its 20-minute cap on every deploy after
4f67dc2. `lesson-review.spec.ts`'s subject — a PDF from upload through the levels and the preview
to published — is now `teacher-flow.spec.ts`, driven by the teacher whose flow it actually is.
`shell.spec.ts` and `this-week.spec.ts` were rewritten for one school rather than deleted.

### Local only: `admin-classes-teachers.spec.ts`

It skips itself when `E2E_BASE_URL` is set. The file creates two classes and a teacher through
the screens on every run, and there is no `DELETE /admin/classes/{id}` or
`/admin/teachers/{id}` to take them back — every other file here cleans up after itself in
`afterAll`, and this one cannot. On a local H2 database that is a fresh start each time; on QA's
shared, never-reset database it would leave a `1A<run>`, a `1B<run>` and a `sara.<run>@alnoor.test`
behind on every deploy, for good. Run it here, with `SEED_SCHOOL=false` unless you want the
30-class seed behind it:

```bash
pnpm e2e:local admin-classes-teachers
```

If an endpoint to remove a class or a teacher ever lands, delete the skip and give the file the
same `afterAll` the others have.

### Injecting a pipeline failure (`lesson-retry.spec.ts`)

`LessonPipeline.java` has a test hook: `-Dquest.pipeline.fail-once-at=<step>` fails that step the
first time it runs for each lesson, so "Retry and continue" has something real to retry past. It
needs its own server, so run it as a second pass; every other test skips this file while
`E2E_FAIL_ONCE_AT` is unset, since a server that fails `generate_L2` once per lesson would make
the happy path flaky.

```bash
SPRING_PROFILES_ACTIVE=h2 SEED_SCHOOL=true SEED_STAFF_PASSWORD="$E2E_STAFF_PASSWORD" \
  ADMIN_EMAIL=… ADMIN_PASSWORD=… LLM_PROVIDER=fake PORT=18081 \
  java -Dquest.pipeline.fail-once-at=generate_L2 -jar server/target/server.jar &

HQ_API=http://localhost:18081 E2E_FAIL_ONCE_AT=1 \
  E2E_ADMIN_EMAIL=… E2E_ADMIN_PASSWORD=… E2E_STAFF_PASSWORD=… \
  pnpm e2e:local --grep 'injected failure'
```

## The static server

`serve.mjs` is deliberately small and deliberately not a dev server: hashed assets immutable,
`index.html` no-cache, SPA fallback, gzip for text, and the CSP. It exists so the suite measures
the artefact that ships rather than a development build — and so Lighthouse numbers taken here
mean something.
