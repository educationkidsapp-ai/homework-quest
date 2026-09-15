# e2e — QA fixture and cross-school isolation

Two scripts, no dependencies beyond Node 22 (built-ins only), `bash`, `curl` and — optionally — `jq`:

| File | What it does |
|---|---|
| `seed/seed.mjs` | Creates the two-school fixture (schools, staff, one published manual lesson per school, a parent and a child per school) and writes the ids to `e2e/.seed.json`. Idempotent. |
| `isolation.sh` | Reads `e2e/.seed.json` and asserts prompt §2 / §10 isolation over the API. One `PASS` / `FAIL` / `BLOCKED` line per assertion. |

The fixture:

| | School A | School B |
|---|---|---|
| name / code | Al Noor School · `ALNOOR` | Green Valley School · `GREENV` |
| curricula / grades | british, american · 1–3 | british · 1–2 |
| teacher | `teacher.a@alnoor.test` — math, british, grades 1–2 | `teacher.b@greenvalley.test` — english, british, grade 1 |
| managerial | `manager.a@alnoor.test` | `manager.b@greenvalley.test` |
| lesson (manual, published) | british/1/math "Counting by 2s — Al Noor" | british/1/english "The sh sound — Green Valley" |
| parent · child | `parent.a@alnoor.test` · Aya (british, grade 1) | `parent.b@greenvalley.test` · Bilal (british, grade 1) |

Classes are not created directly: `AdminLessonService.create()` puts every new lesson in the class for its
(school, curriculum, grade, subject) and makes one when the school has none — before the lesson row is even saved.
`publish()` touches no class.

## Environment

Nothing below is ever printed by either script. Pass them in the shell; do not commit them.

| Variable | Default | Meaning |
|---|---|---|
| `E2E_BASE_URL` | the QA API | API origin |
| `E2E_ADMIN_EMAIL` | `admin@quest.local` | the platform ADMIN |
| `E2E_ADMIN_PASSWORD` | — | **required** |
| `E2E_STAFF_PASSWORD` | — | password for the seeded TEACHER / MANAGERIAL accounts |
| `E2E_PARENT_PASSWORD` | — | password of the seeded Firebase parents (needed off localhost) |
| `E2E_PARENT_AUTH` | `fake` on localhost, else `firebase` | how a parent token is obtained |
| `E2E_FIREBASE_API_KEY` | from `androidApp/src/qa/google-services.json` | Identity Toolkit web key |
| `E2E_SEED_OUT` | `e2e/.seed.json` | where the ids are written / read |
| `E2E_NO_JQ` | unset | set to `1` to force `isolation.sh`'s node JSON reader even where `jq` exists |

Exit codes — both scripts: `0` everything held, `1` a failure or an unexpected response, `2` complete as far as the
API allows (each `BLOCKED` line says why).

## Against a local H2 server

```bash
export JAVA_HOME=/Users/kareemshehab/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home
./gradlew :shared-api:publishToMavenLocal -Pquest.serverOnly=true     # the server builds against the published contract

# either server/run-local.sh (port 8080, reads ../.env), or explicitly:
cd server && ./mvnw -q package -DskipTests
SPRING_PROFILES_ACTIVE=h2 ADMIN_EMAIL=admin@quest.local ADMIN_PASSWORD='<throwaway>' \
  LLM_PROVIDER=fake PORT=8089 java -jar target/server.jar &
```

`SPRING_PROFILES_ACTIVE=h2` gives an in-memory database, no Firebase and no model calls (`LLM_PROVIDER=fake`). It also
turns on `FAKE_AUTH`, so a parent token is literally `Bearer fake-token-<uid>` and the scripts use that instead of
Firebase — nothing needs a Firebase project locally. `ADMIN_EMAIL`/`ADMIN_PASSWORD` seed the platform ADMIN at start-up;
they are the only account whose password can be chosen.

```bash
export E2E_BASE_URL=http://127.0.0.1:8089
export E2E_ADMIN_EMAIL=admin@quest.local
export E2E_ADMIN_PASSWORD='<the same throwaway>'
export E2E_STAFF_PASSWORD='<anything ≥ 10 chars>'

node e2e/seed/seed.mjs
e2e/isolation.sh
```

The H2 database is in memory: restart the server and the fixture is gone, so run the seed again. Re-running it against
a live server is safe — every step finds its row and skips.

## Against QA

QA is Cloud Run and may have scaled to zero; the first request then waits for a cold start. Both scripts retry a
transport error (curl exit 6, 7, 28, …) or a 502/503/504 four times with a growing backoff — `seed.mjs` in `call()`,
`isolation.sh` in `req()` — so one cold start is not a failure. Waking QA (`infra/env.sh qa wake`) is the planner's
call; do not run it from here. If QA still answers 5xx or times out after that, report it and finish the local run.

```bash
export E2E_BASE_URL=https://homework-quest-api-625882725080.me-central1.run.app
export E2E_ADMIN_EMAIL=admin@quest.local
export E2E_ADMIN_PASSWORD="$(grep -E '^ADMIN_PASSWORD=' .env | cut -d= -f2-)"   # git-ignored .env, never echoed
export E2E_STAFF_PASSWORD='<a throwaway you generate>'
export E2E_PARENT_PASSWORD='<a throwaway you generate>'

node e2e/seed/seed.mjs
e2e/isolation.sh
```

Parents are Firebase Auth accounts, not dashboard users. Off localhost the scripts register (or sign in) the seed's
throwaway parent through the Identity Toolkit REST API with the web key in `androidApp/src/qa/google-services.json` —
the same path the Android app takes. The accounts it creates are `parent.a@alnoor.test` and `parent.b@greenvalley.test`
in the `homework-quest-qa` Firebase project; delete them there when the fixture is no longer wanted.

Both scripts refuse early, with the deployed commit in the message, when the target predates P1.3
`backend/dashboard-auth` — `GET /auth/sign-in` answers 405 wherever that route is mapped and 401/403/404 where it is not.

## How staff accounts get a password

`seed.mjs` tries three things per TEACHER / MANAGERIAL account, in order:

1. **sign in** with `E2E_STAFF_PASSWORD` — the account is already there (the idempotent path);
2. **`POST /admin/schools/{id}/users`** (ADMIN only, P1.9: `email, role, password, displayName, teacherProfile?`) when
   the target's OpenAPI document (`/v3/api-docs`) lists it. The account comes back active with `mustChangePassword`,
   which the first sign-in clears by re-setting the same password;
3. **`POST /admin/schools/{id}/invites`**, accepting the one-time token — *if* the response carries it. `seed.mjs`
   looks for `token`, `inviteToken`, `acceptToken`, `oneTimeToken` or an accept link.

**Against a target with none of 2 or 3 the account row exists but has no usable password**, because the invite token
only leaves the server by email and the mailer is `LogMailer`, which logs the subject and a redacted recipient and
deliberately never logs the body (`POST /admin/users/{id}/reset-password` and `POST /auth/forgot-password` likewise only
email a link; `POST /admin/users/{id}/impersonate` issues a read-only token). The only account whose password can be
chosen is then the platform ADMIN, from `ADMIN_EMAIL`/`ADMIN_PASSWORD` at start-up.

In that case the seed exits `2` and names the accounts, and `isolation.sh` falls back to the ADMIN-only, read-only
**View as…** token (`POST /admin/users/{id}/impersonate`) for the staff read assertions — enough for every `GET`, and
reported as `BLOCKED` for the two that need a write (the teacher's `subject` refusal and the managerial refusal). The
lesson is then created by ADMIN with `X-School-Id` instead of by the teacher's own token.
