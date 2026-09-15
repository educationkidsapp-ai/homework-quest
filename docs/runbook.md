# Runbook

Operating the platform as it stands on `develop` (phase 1: tenancy, roles, dashboard accounts, the Angular workspace).
Feature flags, themes and the Angular screens are phases 2–3 and are not described here.

Commands were run against a local server on the in-memory H2 profile unless the line says **needs QA credentials**.
Secrets appear by name only; nothing here prints a value.

- [Environments](#environments)
- [Tenancy model](#tenancy-model)
- [Roles and permissions](#roles-and-permissions)
- [Dashboard accounts](#dashboard-accounts)
- [Environment variables](#environment-variables)
- [Seeding two schools and checking isolation](#seeding-two-schools-and-checking-isolation)
- [Design tokens](#design-tokens)
- [CI](#ci)
- [Rollback](#rollback)

## Environments

Two GCP projects, nothing shared between them. Full bootstrap and workflow detail is in
[deploy/README.md](../deploy/README.md); this is what you need to operate QA day to day.

| | QA | Production |
|---|---|---|
| Git branch | `develop` (PRs target it) | `main` |
| GCP project | `homework-quest-qa` | `homework-quest-prod` |
| Spring profile | `qa` | `prod` |
| API | `https://homework-quest-api-625882725080.me-central1.run.app` | same shape, prod project |
| Panel | `<API>/panel/` (served by the API, same origin — D2) | `<API>/panel/` |
| Deploy | merge to `develop` | `deploy-production.yml`, manual, `confirm=deploy` |

Firebase is used for **parents' Authentication only**. The panel is served by the API container, not Firebase Hosting.

### Sleep and wake

Cloud Run scales to zero on its own; Cloud SQL is the only part that bills while idle.

```bash
infra/env.sh qa status    # prints the instance's state and activation policy
infra/env.sh qa sleep     # stops Cloud SQL; the API answers 503 meanwhile
infra/env.sh qa wake      # starts it again (~1–2 min)
```

Needs QA credentials (`gcloud auth login`, access to `homework-quest-qa`). The deploy workflows wake the environment
themselves before deploying, so a merge to `develop` does not need a manual wake.

A first request after a sleep or a scale-to-zero waits for a cold start. The e2e scripts retry transport errors and
502/503/504 four times with a growing backoff, so one cold start is not a failure.

### Local

```bash
export JAVA_HOME=/Users/kareemshehab/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home
./gradlew :shared-api:publishToMavenLocal -Pquest.serverOnly=true   # the server builds against the published contract
./server/run-local.sh          # in-memory H2 on :8080, FAKE_AUTH, reads ../.env
docker compose up --build      # Postgres 16 + API on :8080 (profile local)
cd dashboard && corepack pnpm install && pnpm start   # http://localhost:4200/panel/
```

`run-local.sh` seeds the platform ADMIN from `ADMIN_EMAIL` / `ADMIN_PASSWORD` (defaults `admin@quest.local` /
`admin1234` when `.env` sets neither).

## Tenancy model

Every school is a tenant. `School(id, name, code, curriculumOptions[], gradeOptions[], theme, featureFlags, status,
createdAt)`; `code` is the six-character A–Z/0–9 join code a parent types.

**The default school.** `V4__schools_roles.sql` creates one school with id `default`, name "Default school", code
`HQ0001`, curricula `american` + `british`, grades 1–3, and backfills every pre-tenancy user, child and lesson into it.
The seeded platform ADMIN is the one account with `school_id = null`.

**Classes.** `Class(id, schoolId, curriculum, grade, subject, teacherId)` is per school. Nothing creates classes
directly: `AdminLessonService.create()` puts a new lesson in the class for its (school, curriculum, grade, subject) and
makes one when the school has none. A child belongs to a school + curriculum + grade, and the map merges every
published lesson of every matching class.

**Isolation is in the server, not the UI.** `school_id` sits on `users`, `children`, `lessons` and `classes`; each of
those entities carries Hibernate's `@Filter(name = "school")`, and `TenantTransactionManager` enables it on the session
of *every* physical transaction opened while a request is scoped — service transactions, the implicit
per-repository-method ones and `REQUIRES_NEW` alike. `TenantArchitectureTest` fails the build if anything outside
`quest.server.tenancy` calls `enableFilter`, or if one of the tenant repositories loses its interface-level
`@Transactional`.

**The Admin school switcher.** A TEACHER or MANAGERIAL token carries its own `schoolId` claim. An ADMIN token carries
none and picks a school with the `X-School-Id` request header instead:

| Caller | `X-School-Id` | Reads | Writes |
|---|---|---|---|
| ADMIN | absent | across every school | into `default` (D6, keeps `webAdmin/` working) |
| ADMIN | a school id | that school only | that school |
| ADMIN | an unknown id | 404 `not_found` | — |
| TEACHER / MANAGERIAL | absent | own school | own school |
| TEACHER / MANAGERIAL | another school's id | 403 `forbidden` | — |

Verified locally: `GET /admin/schools` as ADMIN with no header returned both `ALNOOR` and `HQ0001`; the same token with
`X-School-Id: <unknown>` got 404, with a real id 200.

**It fails closed.** `users.school_id` is nullable and the token omits the claim when it is null, so a TEACHER or
MANAGERIAL principal can arrive with no school at all. Rather than run unfiltered, `TenantContext.schoolId()` refuses:
a missing school, and a school id no `schools` row has, are both **403 `forbidden`** with the message *"This account is
not attached to a school yet — ask your school administrator to add you to one."* The refusal is thrown from
`schoolId()` itself, so it holds even for a caller that never passed `TenantInterceptor`, and
`TenantTransactionManager` asserts it a second time before beginning an unfiltered transaction. ADMIN reading across
schools is the only authenticated dashboard principal allowed to run unfiltered; a parent carries no scope at all and
is scoped by `parent_id` instead.

Operationally: **a staff account whose school was deleted, or that was never attached to one, cannot use the dashboard
at all** — every route refuses, not just the school pages. Fix it by setting the user's school, not by re-issuing a
token.

`IsolationTest` proves this per endpoint: a school-less dashboard token is refused on every tenant route, a teacher of
school A gets 404 on B's rows, and no repository query runs unfiltered for a principal without a school.

## Roles and permissions

Three dashboard roles — `ADMIN`, `TEACHER`, `MANAGERIAL` — plus `PARENT` (Firebase) and `PUBLIC` (no token).

`server/src/main/resources/permissions.json` is the single definition. It has two halves:

```json
{
  "permissions": { "lesson.publish": ["ADMIN", "TEACHER"] },
  "endpoints":   [ { "method": "POST", "path": "/admin/lessons/{id}/publish", "permission": "lesson.publish" } ]
}
```

- Controllers read it through `@PreAuthorize("@permit.has('lesson.publish')")` — the `permit` bean is
  `PermissionExpressions`, which looks the key up and checks it against the caller's role. An undeclared key is
  refused.
- `GET /me/permissions` serves the keys the caller's role holds, so the dashboard can hide what the server would
  refuse. It also returns `readOnly`, true while the caller is an impersonated ("View as") session.
- `PermissionsTest` asserts every endpoint has an entry and that the matrix says what it should;
  `PreAuthorizeCoverageTest` checks the annotations against the file.

Changing it is a **contract change**: `permissions.json` is owned by the `backend` worker and changes in its own
package.

### The matrix

Generated from `permissions.json` on `develop` (`96d208c`). `✓` = granted; the last column counts the endpoints the
key guards.

| Permission | ADMIN | TEACHER | MANAGERIAL | PARENT | PUBLIC | Endpoints |
|---|---|---|---|---|---|---|
| `health.read` | · | · | · | · | ✓ | 1 |
| `panel.read` | · | · | · | · | ✓ | 3 |
| `media.read` | · | · | · | · | ✓ | 2 |
| `auth.signIn` | · | · | · | · | ✓ | 2 |
| `auth.refresh` | · | · | · | · | ✓ | 1 |
| `auth.signOut` | · | · | · | · | ✓ | 1 |
| `auth.forgotPassword` | · | · | · | · | ✓ | 1 |
| `auth.resetPassword` | · | · | · | · | ✓ | 1 |
| `auth.changePassword` | ✓ | ✓ | ✓ | · | · | 1 |
| `school.join` | · | · | · | · | ✓ | 1 |
| `invite.read` | · | · | · | · | ✓ | 1 |
| `invite.accept` | · | · | · | · | ✓ | 1 |
| `child.read` | · | · | · | ✓ | · | 3 |
| `child.write` | · | · | · | ✓ | · | 3 |
| `child.play` | · | · | · | ✓ | · | 2 |
| `lesson.play` | · | · | · | ✓ | · | 1 |
| `lesson.read` | ✓ | ✓ | ✓ | · | · | 2 |
| `lesson.write` | ✓ | ✓ | · | · | · | 10 |
| `lesson.publish` | ✓ | ✓ | · | · | · | 2 |
| `lesson.delete` | ✓ | ✓ | · | · | · | 2 |
| `play.write` | ✓ | ✓ | · | · | · | 3 |
| `stop.write` | ✓ | ✓ | · | · | · | 4 |
| `cache.read` | ✓ | · | · | · | · | 1 |
| `usage.read` | ✓ | · | ✓ | · | · | 1 |
| `calendar.read` | ✓ | ✓ | ✓ | · | · | 1 |
| `school.read` | ✓ | ✓ | ✓ | · | · | 2 |
| `school.write` | ✓ | · | · | · | · | 2 |
| `user.read` | ✓ | · | ✓ | · | · | 1 |
| `user.write` | ✓ | · | ✓ | · | · | 2 |
| `user.invite` | ✓ | · | ✓ | · | · | 1 |
| `user.impersonate` | ✓ | · | · | · | · | 1 |
| `me.read` | ✓ | ✓ | ✓ | · | · | 1 |
| `me.permissions` | ✓ | ✓ | ✓ | · | · | 1 |

33 permissions over 62 endpoints. An ADMIN token holds 18 keys.

Two rules the matrix does not show, enforced in `TenantGuard` because a service reached from a job or another service
has to refuse just the same:

- **MANAGERIAL reads her school's lessons and changes none of them** — every lesson write is refused with
  *"Managerial accounts can read this school's lessons but not change them."*
- **A TEACHER creates lessons only for a subject she teaches, her curriculum and one of her grades.** The refusal names
  the field: `subject: you teach math, not english.` Editing or publishing a colleague's lesson inside her own school
  is allowed; who may edit whose lesson is phase 4.

## Dashboard accounts

Dashboard users live in `users` (email + BCrypt password, role, optional `school_id`). Parents are Firebase Auth
accounts and are not in this table. There is no self-registration.

### Sign in

```bash
curl -s -X POST "$API/auth/sign-in" -H 'Content-Type: application/json' \
  -d '{"email":"admin@quest.local","password":"…"}'
# { token, email, expiresAt, role, schoolId, displayName, mustChangePassword, refreshToken }
```

Access token 15 minutes, refresh token 30 days. Sign-in is rate-limited to 10 attempts per email + IP per 15 minutes,
per instance (Cloud Run may run several, so the effective limit is attempts × instances).

### Refresh rotation

`POST /auth/refresh` with `{refreshToken}` returns a new pair and revokes the presented one. Only the SHA-256 hash is
stored. **Presenting a token that was already rotated away, or signed out, is treated as theft: every live token of
that user is revoked** and they have to sign in again. Verified locally — a replay of a rotated token answered 401
*"That session has expired. Sign in again."* `POST /auth/sign-out` with a refresh token revokes just that one, and an
unknown token is not an error.

### Forgot / reset password

`POST /auth/forgot-password` **always** answers 204, whether or not the address has an account, so the page cannot be
used to enumerate addresses. The link goes out by email and is valid for one hour, once.
`POST /auth/reset-password` with `{token, newPassword}` completes it. Passwords are at least 10 characters.

### First-login password change

Invited and admin-reset accounts carry `mustChangePassword: true`; it is on the sign-in response and on `GET /me`. The
dashboard sends the person to `POST /auth/change-password` (`{currentPassword, newPassword}`), which clears the flag.
Nothing on the server blocks other calls while the flag is set — it is the client that must route to the change screen.

### Invites

```bash
curl -s -X POST "$API/admin/schools/$SCHOOL_ID/invites" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"email":"teacher.a@alnoor.test","role":"TEACHER",
       "teacherProfile":{"displayName":"Ms Sara","subjects":["math"],"curriculum":"british","grades":[1,2]}}'
# { id, email, role, schoolId, invitedBy, expiresAt, acceptedAt, createdAt }   — no token in the response
```

ADMIN and MANAGERIAL may invite (`user.invite`). The invite creates the user row immediately with status `invited` and
saves the teacher profile, so the account is complete the moment it is accepted. The one-time link expires in 7 days;
the recipient opens `GET /invites/{token}` (public — email, role, school name, expiry) and finishes with
`POST /invites/{token}/accept` (`{password, displayName}`), which signs them in.

**The token leaves the server only by email.** It is not in the API response and not in the log.

### The mailer in QA

`MAIL_PROVIDER` defaults to `log`, so QA runs `LogMailer`: it logs the subject and a redacted recipient and
deliberately never logs the body, which is where the token is.

```
mail "Schools Dashboard — you have been invited to Al Noor School" -> t***a@alnoor.test
mail "Schools Dashboard — reset your password" -> a***n@quest.local
```

Both lines above are from a verified local run. The consequence for operators: **until `MAIL_PROVIDER=resend` and
`RESEND_API_KEY` are set, nobody can complete an invite or a password reset in QA** — the link never reaches anyone.
The only account whose password can be chosen directly is the platform ADMIN, from `ADMIN_EMAIL` / `ADMIN_PASSWORD` at
start-up (`AdminSeed` re-applies them on every boot). See
[Seeding two schools](#seeding-two-schools-and-checking-isolation) for how the e2e seed works around this.

> **In review** (P1.9, PR #36, not on `develop` yet): `POST /admin/schools/{id}/users` — ADMIN-only direct creation of
> an active account with a chosen password and `mustChangePassword`, which removes the need for email to stand up a
> staff account; and media authorisation, which takes `/media/pages/**` and `/media/child/**` off `permitAll` so a
> token is required and every request is resolved back to its lesson's or child's school (refusals are 404, never
> 403). Both sections here describe `develop` as it stands and will need a pass once it merges.

Links are built from `DASHBOARD_URL` (falling back to `PUBLIC_URL`) as `<base>/panel/accept-invite?token=…` and
`<base>/panel/reset-password?token=…`; subjects carry the platform name (`PLATFORM_NAME`, default
"Schools Dashboard").

### View as (impersonation)

```bash
curl -s -X POST "$API/admin/users/$USER_ID/impersonate" -H "Authorization: Bearer $ADMIN_TOKEN"
# the same shape as a sign-in, for that user, for 30 minutes
```

ADMIN only. The token is **read-only**: `ReadOnlyGuard` refuses every non-GET with 403 *"You are viewing as someone
else; this view is read-only."* and audit-logs every request, at most one row per (actor, target, path) per minute.
`GET /me` reports `impersonatedBy` and `GET /me/permissions` reports `readOnly: true`, which is what the dashboard's
banner is driven from.

The target must be **active**: impersonating an account that is still `invited` answers 400 *"That account is not
active."* (verified locally).

### The legacy alias

`POST /admin/auth/sign-in` is kept for `webAdmin/`. It returns `{token, email, expiresAt, role, schoolId, displayName,
mustChangePassword}` — byte-for-byte what that client decodes, with **no** `refreshToken` field (`webAdmin/` decodes
with `ignoreUnknownKeys = false`, so no field may be added), and a long 12-hour token because it cannot refresh. It
retires with `webAdmin/` in phase 3. New clients use `POST /auth/sign-in`.

## Environment variables

Names only — never paste a value into a PR, a commit, a log or a chat. Values live in `.env` locally (git-ignored;
`.env.example` lists the names) and in Secret Manager for QA and production, wired into Cloud Run by
`infra/terraform`. `infra/bootstrap.sh qa|prod` reads them from your shell and stores them with
`gcloud secrets versions add` + `gh secret set`, so they never enter the Terraform state.

| Name | Where it matters | Notes |
|---|---|---|
| `ADMIN_EMAIL` | every environment | seeds the platform ADMIN with `school_id = null` |
| `ADMIN_PASSWORD` | every environment | re-applied on every boot; blank with `ADMIN_EMAIL` = no seeding |
| `ADMIN_JWT_SECRET` | every environment | signing key, **≥ 32 bytes**; Terraform generates 48 random characters per project |
| `MAIL_PROVIDER` | QA, prod | `log` (default) or `resend` |
| `RESEND_API_KEY` | QA, prod | required when `MAIL_PROVIDER=resend`; empty falls back to the log mailer with an error line |
| `MAIL_FROM` | QA, prod | the verified sender; default `no-reply@localhost` |
| `DASHBOARD_URL` | QA, prod | origin the dashboard is served from; blank falls back to `PUBLIC_URL` |
| `PLATFORM_NAME` | QA, prod | blank = the seeded default "Schools Dashboard" (§A); phase 2 moves it into `platform_settings` |
| `DEEPSEEK_API_KEY`, `DEEPSEEK_MODEL`, `DEEPSEEK_VISION_MODEL`, `DEEPSEEK_BASE_URL`, `DEEPSEEK_MAX_TOKENS` | every environment | `LLM_PROVIDER=deepseek` |
| `ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL` | optional | `LLM_PROVIDER=anthropic` |
| `LLM_PROVIDER` | every environment | `deepseek` \| `anthropic` \| `fake` |
| `FIREBASE_CREDENTIALS` | QA, prod | parents' token verification; empty + profile `local`/`h2` = `FAKE_AUTH` |
| `DB_URL`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `CLOUD_SQL_INSTANCE` | every environment | Cloud SQL socket factory in `qa`/`prod` |
| `STORAGE_KIND`, `STORAGE_DIR`, `GCS_BUCKET` | every environment | `local` or `gcs` |
| `PUBLIC_URL` | every environment | base URL the server puts in media links |
| `CORS_ORIGINS` | every environment | only local dev origins matter; the panel is same-origin |
| `PORT`, `APP_VERSION`, `PANEL_DIR`, `SPRING_PROFILES_ACTIVE`, `FAKE_AUTH` | runtime | the image sets `PANEL_DIR` |

`ADMIN_JWT_SECRET` has a placeholder default in `application.yml` so a developer can boot without one. **Any deployed
environment must set it** — Terraform does, from `random_password.jwt`.

Terraform wires `DB_PASSWORD`, `ADMIN_JWT_SECRET`, `DEEPSEEK_API_KEY` and `ADMIN_PASSWORD` into Cloud Run as required
secrets, plus `ANTHROPIC_API_KEY` and `FIREBASE_CREDENTIALS` once a value exists. `MAIL_PROVIDER`, `RESEND_API_KEY`,
`MAIL_FROM`, `DASHBOARD_URL` and `PLATFORM_NAME` are **not wired into Terraform yet** — QA therefore runs the log
mailer, builds links from `PUBLIC_URL`, and shows the seeded platform name. Adding them is an `infra` package
(phase 5's `infra/mail-push` covers the mail three).

## Seeding two schools and checking isolation

`e2e/` holds the fixture and the isolation assertions — two Node-and-bash scripts, no dependencies beyond Node 22,
`curl` and optionally `jq`. Full detail in [e2e/README.md](../e2e/README.md).

| | School A | School B |
|---|---|---|
| name / code | Al Noor School · `ALNOOR` | Green Valley School · `GREENV` |
| curricula / grades | british, american · 1–3 | british · 1–2 |
| teacher | `teacher.a@alnoor.test` — math, british, grades 1–2 | `teacher.b@greenvalley.test` — english, british, grade 1 |
| managerial | `manager.a@alnoor.test` | `manager.b@greenvalley.test` |
| lesson (manual, published) | british/1/math | british/1/english |
| parent · child | `parent.a@alnoor.test` · Aya | `parent.b@greenvalley.test` · Bilal |

Against a local H2 server:

```bash
export JAVA_HOME=/Users/kareemshehab/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home
./gradlew :shared-api:publishToMavenLocal -Pquest.serverOnly=true
cd server && ./mvnw -q package -DskipTests
SPRING_PROFILES_ACTIVE=h2 ADMIN_EMAIL=admin@quest.local ADMIN_PASSWORD='<throwaway>' \
  LLM_PROVIDER=fake PORT=8089 java -jar target/server.jar &

export E2E_BASE_URL=http://127.0.0.1:8089
export E2E_ADMIN_EMAIL=admin@quest.local
export E2E_ADMIN_PASSWORD='<the same throwaway>'
export E2E_STAFF_PASSWORD='<anything ≥ 10 chars>'
node e2e/seed/seed.mjs      # idempotent; writes ids to e2e/.seed.json
e2e/isolation.sh            # one PASS / FAIL / BLOCKED line per assertion
```

Against QA (**needs QA credentials**): set `E2E_BASE_URL` to the QA API, take `E2E_ADMIN_PASSWORD` from the
git-ignored `.env` without echoing it, and add `E2E_PARENT_PASSWORD` — off localhost the scripts create the throwaway
parents through Firebase Identity Toolkit in `homework-quest-qa`, with the web key from
`androidApp/src/qa/google-services.json`. Delete those two Firebase accounts when the fixture is no longer wanted.
Wake QA first if it is asleep.

Exit codes for both scripts: `0` everything held, `1` a failure or an unexpected response, `2` complete as far as the
API allows (each `BLOCKED` line says why).

**Why a run can end in `2`.** Staff passwords. `seed.mjs` tries, in order: signing in with `E2E_STAFF_PASSWORD`;
`POST /admin/schools/{id}/users` when the target's OpenAPI document lists it; then an invite, *if* the response carries
the token. On `develop` today the invite response carries no token and the log mailer swallows the email, so against a
server without the direct-creation endpoint the staff rows exist with no usable password. `isolation.sh` then falls
back to the ADMIN-only **View as** token for the read assertions — enough for every `GET` — and reports `BLOCKED` for
the two that need a write.

The isolation assertions mirror `IsolationTest` over HTTP: a teacher of A gets 404 for B's lessons, children and
media; the header switcher behaves as the table above; a parent never reaches another school's lesson.

## Design tokens

`design/tokens.json` at the repository root is the single source both front-ends generate from. Nothing outside a
generated file may contain a literal hex or px value — a literal is a colour that cannot be themed per school.

| Command | Generates |
|---|---|
| `cd dashboard && pnpm tokens` | `src/styles/_tokens.generated.scss` (CSS custom properties `--hq-<group>-<name>` + an SCSS map) and `src/app/ui/motion/tokens.generated.ts` |
| `./gradlew :shared-ui:generateDesignTokens` | `quest/ui/design/DesignTokens.kt` (runs automatically as part of the `shared-ui` compile) |

Two drift gates, both run in CI and both green locally on `develop`:

```bash
cd dashboard && pnpm tokens --check       # "tokens: generated files are up to date."
./gradlew :shared-ui:checkTokens          # fails when Tokens.kt / Theme.kt hard-code a value tokens.json spells differently
cd dashboard && node tools/fonts.mjs --check   # the subset woff2 files against the TTFs in shared-ui
```

Editing a token: change `design/tokens.json`, run both generators, commit the generated files with it. `tokens.json`
is a shared interface — it is created and owned by the `dashboard` worker and consumed by `mobile`.

## CI

`.github/workflows/ci.yml` on every PR and push to `develop` / `main`. Six workflows in all
([deploy/README.md](../deploy/README.md) has the deploy ones).

| Job | What it runs |
|---|---|
| `contract` | `:shared-api:jvmTest` + publishes the contract to `~/.m2` for the server job |
| `server` | `cd server && ./mvnw test` — H2, plus PostgreSQL 16 through Testcontainers |
| `app` | `:shared:desktopTest` (architecture, journey, screenshots), Android QA debug APK, the Wasm admin panel |
| `dashboard` | lint, Vitest, `pnpm build --configuration=qa`, and the tokens + fonts drift checks |
| `ios` | simulator build + full-cycle UI test — only on `main` or a PR labelled `ios` (macOS minutes) |
| `ci` | the aggregate status check the deploy workflows and branch rules wait for |

The **`dashboard` job** is path-filtered *inside* the job (`dashboard/**`, `design/tokens.json`,
`.github/workflows/ci.yml`) rather than with a workflow-level `paths:`, so the `ci` aggregate always exists as a status
check; a PR that touches nothing under `dashboard/` skips every step after the filter and `skipped` counts as success.
It caches pnpm only — no Java toolchain — and costs about ten seconds of runner time on an unrelated PR. Playwright is
deliberately not in CI: it needs browser downloads and a deployed target, and runs against QA after the deploy in
phase 3.

Node is pinned by `.nvmrc` (22); pnpm by `dashboard/package.json`'s `packageManager` field, enabled with corepack. The
dev Mac runs Node 25, which only produces an engine warning.

**Renovate** (`renovate.json`) groups minor and patch bumps into one weekly PR per ecosystem (dashboard npm, server
maven, gradle, github-actions), keeps majors ungrouped and labelled `major`, and leaves Terraform to Dependabot so the
two bots never open the same PR. It only runs once the **Renovate GitHub App is installed on the repository** — until
then the file is inert and nothing opens those PRs.

Watching a run:

```bash
gh pr checks <n> --watch
gh run watch
```

## Rollback

`.github/workflows/rollback.yml` shifts Cloud Run traffic back to a previous revision. No build, no migration run.

```bash
gh workflow run rollback.yml -f environment=production                       # the previously-serving revision
gh workflow run rollback.yml -f environment=qa -f revision=homework-quest-api-00042-abc
gh run watch
```

It resolves the target revision, moves 100 % of traffic to it, and smoke-tests `GET /health`.

**Migrations are forward-only.** A rolled-back image still meets the current schema, which is why every migration must
be additive — `migration-check.yml` enforces that on any PR touching
`server/src/main/resources/db/migration/**`: applied migrations unchanged, no DROP or RENAME, applied on PostgreSQL 16
on top of `develop`'s schema, and JPA `validate` boots afterwards. A change that cannot be additive needs a
deploy-time plan, not a rollback.
