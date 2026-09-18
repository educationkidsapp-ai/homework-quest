# Local end-to-end suite

`e2e/local/` runs the **built** dashboard against a **local** API, in the shape the container
will have (P3.4): one origin, `/dashboard/**` from `dist/browser`, everything else proxied to the
API, and the same Content-Security-Policy the server sends. `e2e/styleguide.spec.ts` is the other
suite and still runs against `ng serve` (`pnpm e2e`).

Neither suite runs in CI — both need a browser download, and this one needs a JDK, a database and
a seed. They run here and, from P3.4, against QA after a deploy.

## Running it

```bash
# 1. the API on in-memory H2 — JDK 21, no Docker, no Firebase, no model calls
export JAVA_HOME=…/jdk-21…            # the default `java` on this Mac is 17
./gradlew :shared-api:publishToMavenLocal -Pquest.serverOnly=true   # once
(cd server && ./mvnw -q -B package -DskipTests)

export ADMIN_EMAIL=admin@quest.local
export ADMIN_PASSWORD='<throwaway>'   # never a real one, never committed
SPRING_PROFILES_ACTIVE=h2 LLM_PROVIDER=fake PORT=18080 \
  PUBLIC_URL=http://localhost:18080 DASHBOARD_URL=http://localhost:4300 \
  java -jar server/target/server.jar &

# 2. two schools, every role, one published lesson each
E2E_BASE_URL=http://localhost:18080 \
E2E_ADMIN_EMAIL="$ADMIN_EMAIL" E2E_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
E2E_STAFF_PASSWORD='<throwaway>' E2E_PARENT_PASSWORD='<throwaway>' \
  node e2e/seed/seed.mjs

# 3. the bundle the container will serve
cd dashboard && pnpm build --configuration=production

# 4. the suite (Playwright starts e2e/local/serve.mjs itself)
E2E_ADMIN_EMAIL=… E2E_ADMIN_PASSWORD=… E2E_STAFF_PASSWORD=… pnpm e2e:local
```

Accounts come from the seed: `admin@quest.local`, `teacher.a@alnoor.test` (Ms Sara, Al Noor,
British Grade 1–2 Math) and `manager.a@alnoor.test`. Passwords are read from the environment and
never printed — a missing one fails with the variable's name, not its value.

### Injecting a pipeline failure (`lesson-retry.spec.ts`)

`LessonPipeline.java` has a test hook: `-Dquest.pipeline.fail-once-at=<step>` fails that step
the first time it runs for each lesson, so "Retry and continue" has something real to retry
past. It needs its own server (the happy-path suite's server was not started this way), so run
it as a second pass:

```bash
# same jar, one JVM property added, a fresh port so both servers can be up at once
SPRING_PROFILES_ACTIVE=h2 LLM_PROVIDER=fake PORT=18081 \
  PUBLIC_URL=http://localhost:18081 DASHBOARD_URL=http://localhost:4300 \
  java -Dquest.pipeline.fail-once-at=generate_L2 -jar server/target/server.jar &

E2E_BASE_URL=http://localhost:18081 E2E_ADMIN_EMAIL="$ADMIN_EMAIL" \
  E2E_ADMIN_PASSWORD="$ADMIN_PASSWORD" E2E_STAFF_PASSWORD='<throwaway>' \
  E2E_PARENT_PASSWORD='<throwaway>' node e2e/seed/seed.mjs

HQ_API=http://localhost:18081 E2E_FAIL_ONCE_AT=1 \
  E2E_ADMIN_EMAIL="$ADMIN_EMAIL" E2E_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
  pnpm e2e:local --grep 'injected failure'
```

Every other test in the suite skips this file (`E2E_FAIL_ONCE_AT` unset), since a server that
fails `generate_L2` once per lesson would make the happy-path run flaky.

## What it proves

| Test                                        | What would break without it                                     |
| ------------------------------------------- | --------------------------------------------------------------- |
| sign-in is branded from `PlatformSettings`  | a product name baked into the bundle (§A)                       |
| the bundle runs clean under the API's CSP   | an inline script or `onload=` handler leaving the page unstyled |
| one login per role lands on its Home        | the role guards and the `/` redirect                            |
| a wrong password is a band, in place        | a 401 read as "your session expired" and a bounce               |
| EN/AR flips `dir` without a reload          | a cached English sentence in a `computed()` (§6)                |
| the Admin rail carries every §6 screen      | a nav item gated on a permission the role does not hold         |
| the switcher scopes every screen            | `X-School-Id` not reaching the API, or the Home not re-reading  |
| a teacher is offered nothing of the Admin's | the rail, and the URL behind it                                 |
| `?` opens the sheet, Esc closes it          | the keyboard contract (§7)                                      |
| the screenshot set                          | the RTL mirror, silently                                        |
| an Admin creates 1A/1B with distinct codes  | the Classes screen, and `POST /admin/classes` reaching a school |
| a temporary password shows once, then goes  | a secret kept on screen for as long as the tab is open          |
| a second Math teacher for 1A is refused     | N1.1's unique constraint never reaching the person (N1.2)       |

`admin-classes-teachers.spec.ts` (N1.2) runs the Admin's half of `docs/teacher-flow.md` §10 step 1:
classes, teachers, the one-time password and the assignment picker. It creates everything it needs
through the screens and names the rows after the run, so it is safe to run twice against one H2
database — and it does **not** need `e2e/seed/seed.mjs` to finish (that script fails at its lesson
step since N1.1: publishing now requires an assignment, which `test/seed-one-school-e2e` fixes).
Run it with `SEED_SCHOOL=false` unless you want the 30-class seed behind it.

`lesson-review.spec.ts` (P3.2d) carries a PDF lesson through skills confirmation, the three
levels + Again, the pinned phone preview and publish; `lesson-retry.spec.ts` proves a failed
step actually retries past its failure (see above).

Screenshots land in `docs/screenshots/dashboard-p3.1/`, `docs/screenshots/dashboard-p3.2d/` and
`docs/screenshots/dashboard-n1.2/`
(1366 × 768, EN and AR) and are committed.

`this-week.spec.ts` (N2.2) and `my-classes.spec.ts` (N2.3) are the suites that want the
**one-school seed**, so they need their own server (`pnpm e2e:local my-classes` for the second;
its screenshots land in `docs/screenshots/dashboard-n2.3/`):

```bash
SPRING_PROFILES_ACTIVE=h2 SEED_SCHOOL=true SEED_STAFF_PASSWORD="$E2E_STAFF_PASSWORD" \
  ADMIN_EMAIL="$E2E_ADMIN_EMAIL" ADMIN_PASSWORD="$E2E_ADMIN_PASSWORD" \
  LLM_PROVIDER=fake PORT=18080 java -jar server/target/server.jar &

cd dashboard && pnpm build --configuration=production && pnpm e2e:local this-week
```

It signs in as Sara Al Harbi (`seed/teachers.csv`) and, because no seeded teacher has two
sections of the same grade *and* subject, creates one more Grade 1 British section through the
Admin API in `beforeAll` and assigns it to her — the drag-to-copy rule needs a sibling row. It
creates lessons, so **start it against a fresh H2**: a second run on the same database finds the
week already full and has no empty cell left to press `+` on.

## The static server

`serve.mjs` is deliberately small and deliberately not a dev server: hashed assets immutable,
`index.html` no-cache, SPA fallback, gzip for text, and the CSP. It exists so the suite measures
the artefact that ships rather than a development build — and so Lighthouse numbers taken here
mean something.
