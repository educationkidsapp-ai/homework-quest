# Runbook

Operating the platform as it stands on `develop` (phase 1: tenancy, roles, dashboard accounts, the Angular workspace;
phase 2: feature flags, school themes, platform settings, and the app that reads all three). The Angular dashboard
screens for flags and themes are phase 3 and are not described here — today those routes are driven with `curl`.

Commands were run against a local server on the in-memory H2 profile unless the line says **needs QA credentials**.
Secrets appear by name only; nothing here prints a value.

- [Environments](#environments)
- [Tenancy model](#tenancy-model)
- [Roles and permissions](#roles-and-permissions)
- [Dashboard accounts](#dashboard-accounts)
- [Feature flags](#feature-flags)
- [School themes](#school-themes)
- [Platform settings](#platform-settings)
- [Environment variables](#environment-variables)
- [Seeding two schools, isolation, flags and themes](#seeding-two-schools-isolation-flags-and-themes)
- [QA as the owner's acceptance environment](#qa-as-the-owners-acceptance-environment)
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
export PATH="$JAVA_HOME/bin:$PATH"   # the default `java` is 17; run-local.sh execs a bare `java`, which would not boot the jar
./gradlew :shared-api:publishToMavenLocal -Pquest.serverOnly=true   # the server builds against the published contract
./server/run-local.sh          # in-memory H2 on :8080, FAKE_AUTH, reads ../.env
docker compose up --build      # Postgres 16 + API on :8080 (profile local)
cd dashboard && corepack pnpm install && pnpm start   # http://localhost:4200/panel/
```

`run-local.sh` seeds the platform ADMIN from `ADMIN_EMAIL` / `ADMIN_PASSWORD` (defaults `admin@quest.local` /
`admin1234` when `.env` sets neither).

### Uploads become Markdown before the model reads them

An uploaded `.pdf`, `.pptx`, `.ppt`, `.docx`, `.doc`, `.xlsx` or `.csv` is converted to a `.md` next to the original
and the model reads only the `.md`; `.jpg`, `.png` and scanned (image-only) PDF pages go through OCR first. Two
binaries do that work and the server runs them with `ProcessBuilder`:

| Variable | In the image | Anywhere else (unset) |
|---|---|---|
| `QUEST_ANYDOC_BIN` | `/opt/anydoc/node_modules/.bin/anydoc` | a bare `anydoc` on `PATH` |
| `QUEST_TESSERACT_BIN` | `/usr/bin/tesseract` | a bare `tesseract` on `PATH` |

Neither is installed by `server/run-local.sh`. On the Mac, once:

```bash
brew install tesseract tesseract-lang        # the OCR engine plus every language pack (we use eng and ara)
npm --prefix tools/anydoc ci                 # @firecrawl/anydoc, exactly as tools/anydoc/package-lock.json pins it
export QUEST_ANYDOC_BIN=$PWD/tools/anydoc/node_modules/.bin/anydoc
export QUEST_TESSERACT_BIN=$(command -v tesseract)
```

`anydoc <file> -o <file>.md` writes GitHub-flavoured Markdown to that path. CSV has no file signature, so a CSV that
is not named `.csv` needs `--format csv`. Exit codes: `0` converted; `1` could not be converted (stderr is
`anydoc: malformed document: …`, `anydoc: document is encrypted`, `anydoc: unsupported input: …` or
`anydoc: io error: …`); `2` a usage mistake; `3` the PDF needs OCR, and stderr names the pages —
`anydoc: page 1 of 1 needs OCR`, `anydoc: pages … of N need OCR` or `anydoc: all N pages need OCR`. Exit 3 is the
signal to run Tesseract on those pages. **Never pass `--ocr hosted`**: that uploads the document to Firecrawl.
Everything else anydoc does stays on the machine.

When a binary is missing the server fails that conversion step with `tool_missing` and an error naming the variable,
rather than falling back to sending the original file to the model. The one exception is
`quest.pipeline.convert.allow-builtin-fallback`, which is **profile-gated, not environment-gated**: it is `false` in
the base configuration with no environment placeholder, `true` only under the `h2` and `test` profile documents, and
`ConversionService` additionally requires one of those two profiles to be active. A value set on a QA or production
Cloud Run service therefore cannot switch it on. Where it does apply, a missing binary falls back to the PDFBox/POI
text extraction the server already does for the child's page images, recorded honestly as `convertMethod: "text"` —
which is what lets `LLM_PROVIDER=fake` e2e runs and CI work on a machine with neither Node nor Tesseract. The
teacher's "Read with OCR" fallback never takes that path: with no Tesseract it fails with `tool_missing`, because
there is nothing built in that reads a picture.

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

Generated from `permissions.json` on `develop` (`152f2c8`). `✓` = granted; the last column counts the endpoints the
key guards.

| Permission | ADMIN | TEACHER | MANAGERIAL | PARENT | PUBLIC | Endpoints |
|---|---|---|---|---|---|---|
| `health.read` | · | · | · | · | ✓ | 1 |
| `panel.read` | · | · | · | · | ✓ | 3 |
| `media.page.read` | ✓ | ✓ | ✓ | ✓ | · | 1 |
| `media.child.read` | ✓ | ✓ | ✓ | ✓ | · | 1 |
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
| `user.create` | ✓ | · | · | · | · | 1 |
| `user.impersonate` | ✓ | · | · | · | · | 1 |
| `me.read` | ✓ | ✓ | ✓ | · | · | 1 |
| `me.permissions` | ✓ | ✓ | ✓ | · | · | 1 |
| `school.flags` | · | · | · | · | ✓ | 1 |
| `school.theme` | · | · | · | · | ✓ | 1 |
| `platform.read` | · | · | · | · | ✓ | 1 |
| `platform.manage` | ✓ | · | · | · | · | 1 |
| `platform.write` | ✓ | · | · | · | · | 1 |
| `flag.read` | ✓ | · | ✓ | · | · | 2 |
| `flag.write` | ✓ | · | · | · | · | 2 |
| `theme.read` | ✓ | ✓ | ✓ | · | · | 1 |
| `theme.write` | ✓ | · | · | · | · | 1 |

44 permissions over 74 endpoints. An ADMIN token holds 27 keys.

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
[Seeding two schools](#seeding-two-schools-isolation-flags-and-themes) for how the e2e seed works around this.

**The way round it** (P1.9, on `develop`): `POST /admin/schools/{id}/users` creates an active account directly, with a
chosen password and `mustChangePassword`, ADMIN only (`user.create`). That is how `e2e/seed/seed.mjs` stands staff
accounts up without email.

Links are built from `DASHBOARD_URL` (falling back to `PUBLIC_URL`) as `<base>/panel/accept-invite?token=…` and
`<base>/panel/reset-password?token=…`; subjects carry the platform name, read from the `platform_settings` row (see
[Platform settings](#platform-settings)) — there is no env var for it.

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

## Feature flags

Every feature of §6 is behind a flag. `feature_flags` defines the 14 of them platform-wide, `school_feature_flags`
holds the overrides a school has actually been given, and `flag_audit` records who flipped what. All three are created
and seeded by `V5__flags_themes.sql`; the same 14 keys and defaults are `DEFAULT_FLAGS` in `shared-api`
(`quest/api/ContentApi.kt`), which is what the app falls back to before its first sync.

`schools.feature_flags_json` (added in V4) stays **unused** — the normalised table is the only truth.

### The 14 keys

| Key | Seeded default | Stage | What it gates |
|---|---|---|---|
| `lessons.pdf` | on | ga | new lesson from a PDF |
| `lessons.slides` | on | ga | new lesson from PowerPoint slides |
| `lessons.images` | on | ga | new lesson from photos of the workbook |
| `lessons.manual` | on | ga | new lesson from typed questions |
| `levels.three` | on | ga | the Challenge path; off caps a lesson at level 2 |
| `retell.recording` | on | ga | children record themselves retelling the story |
| `openAnswer.drawing` | on | ga | children answer by drawing |
| `parentPanel.arabic` | on | ga | the Arabic parent panel and the language toggle |
| `stickers.treasureChest` | on | ga | the streak treasure chest in the sticker book |
| `certificates` | on | ga | certificates when a child finishes a skill |
| `complaints` | **off** | internal | parents send complaints from the app (phase 5) |
| `announcements` | **off** | internal | teachers post announcements to a class (phase 4) |
| `teacherQuestions` | **off** | internal | teachers send questions to their students (phase 4) |
| `progress.weeklyEmail` | **off** | internal | weekly progress email to parents |

On for what ships today, off for what phases 4–6 still have to build — so the migration switches nothing off that a
school already uses.

**The effective value** of a flag for a school is that school's row in `school_feature_flags`, and the flag's
`default_on` when it has none. A new school therefore inherits the defaults without a row being written, and a new
flag reaches every school the moment its definition is seeded. A caller with no school at all — the platform ADMIN who
sent no `X-School-Id`, an anonymous request — reads the defaults. `FeatureFlags` caches the set for 60 seconds per
school and drops it on a write, so on more than one Cloud Run instance another instance's flip is visible within that
minute.

**Off means 404.** `@FeatureFlag("key")` on a controller class or a single handler is enforced by
`FeatureFlagInterceptor`, which throws the *same* body an unknown path gets — `{"code":"not_found","message":"No such
endpoint."}` — so a school cannot tell a feature it does not have from one that was never built. A handler's own
annotation *replaces* its controller's rather than adding to it. Whose flags decide: a dashboard user's token school
(or the one an ADMIN picked with `X-School-Id`); a parent's child's school on `/children/{id}/**` and her **first**
child's school on every other parent route; nobody → the defaults.

Nothing on `develop` carries `@FeatureFlag` yet: the phase-1 controllers predate the flags and the flag, theme and
platform-settings controllers are infrastructure (a flag that could switch off the endpoint which switches flags has
no way back on). P3.0 and P4.0 annotate the routes they add.

### Reading and flipping them

The three sections below share these three variables; `$API` is the local H2 server from
[Seeding two schools](#seeding-two-schools-isolation-flags-and-themes), or the QA API (**needs QA credentials**).

```bash
API=http://127.0.0.1:8089
SCHOOL=$(jq -r .schools.A.id e2e/.seed.json)          # school A of the e2e fixture (Al Noor, ALNOOR)
TOKEN=$(curl -s -X POST "$API/auth/sign-in" -H 'Content-Type: application/json' \
  -d '{"email":"admin@quest.local","password":"…"}' | jq -r .token)

curl -s "$API/schools/$SCHOOL/flags"                  # public: all 14 as {key: boolean}, ETag + max-age=300
curl -s "$API/admin/flags" -H "Authorization: Bearer $TOKEN"          # definitions + a row per visible school
curl -s "$API/admin/flags/audit?limit=5" -H "Authorization: Bearer $TOKEN"

curl -s -X PUT "$API/admin/schools/$SCHOOL/flags/certificates" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"enabled":false}'          # one cell; answers the school's whole set
curl -s -X PUT "$API/admin/flags/certificates/all" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"enabled":true}'           # the whole column
```

Verified locally: the public read answered 14 keys (10 on, 4 off, exactly the table above); flipping `certificates`
off for A answered `false` on the very next public fetch and left school B `true`; the audit gained one row naming the
flag, the school and `admin@quest.local`. The column action leaves **one** row with `school_id` null — that null is
what "for all schools" means; the trail is append-only and nothing removes it.

`flag.read` is ADMIN + MANAGERIAL (a MANAGERIAL caller sees every definition but only her own school's row);
`flag.write` is ADMIN. A teacher's `PUT` is 403.

### In the app

`SchoolSession` fetches the school's flags alongside its theme **on launch and every six hours**
(`SYNC_INTERVAL_MILLIS` in `SchoolThemeHost.kt`), caches them in `SettingsStore` and restores the cached set before the
first frame. A failed refresh, a 304 or an offline launch all keep what the device has, and anything the fetch does not
name falls back to `DEFAULT_FLAGS` — so a slow network never hides a feature that ships today.

`FeatureGate("stickers.treasureChest") { … }` composes its content only when the flag is on; off means the content is
**never composed** — no placeholder, no message. `GateFallback` sends a route that was gated off while it was open back
where it came from, and `LevelGate` is the single door for `levels.three`. Gated today: the treasure chest, retell
recording, open-answer drawing, the Arabic parent panel, certificates and level 3.

**Two gates keep a new screen or controller from shipping without a flag:**

```bash
./gradlew :shared:checkFeatureGates      # "12 screens, 6 pre-existing exemptions, none missing a flag"
```

- `:shared:checkFeatureGates` (hung off `:shared:desktopTest`, so CI's App job runs it) fails when a `*Screen.kt`
  under `feature/*/presentation/` has neither a `FeatureGate(` / `featureEnabled(` reference nor a
  `// hq-flag: none (<reason>)` line. Today's six ungated screens are allow-listed in `shared/build.gradle.kts`, and
  the check *also* fails when an allow-listed screen grows a gate — the list can only shrink.
- `FeatureFlagCoverageTest` (server) fails when a `@RestController` added after P2.1 carries no `@FeatureFlag` on the
  class or on every handler, and when an annotation names a key `FlagKeys.ALL` does not have. Its two exemption lists —
  the eleven phase-1 controllers and the three infrastructure ones — are closed; a third test fails if a name on either
  list stops matching a real controller, so a rename cannot silently exempt anything.

Adding a flag is a migration plus `FlagKeys`: both are the `backend` worker's files.

## School themes

Each school has one theme JSON in `schools.theme_json`, edited by ADMIN and read by both front-ends. A school without
one is shown the platform-wide default (`platform_settings.default_theme_json`), and without that the theme the server
builds at start-up from `design/tokens.json` — so no school ever renders without colours.

```json
{ "logoUrl": null, "appName": null,
  "primary": "#FFFFFF", "primaryInk": "#201E1D", "accent": "#CC2A0F",
  "ground": "#F3F2F2", "softBorder": "#D9D6D2", "mascotColor": "#598FB8",
  "worldPalettes": { "math":    { "primary": "#6FC3FF", "deep": "#3F9BE0", "soft": "#EAF4FF", "ink": "#201E1D" },
                     "english": { "primary": "#B69CFF", "deep": "#7E63D8", "soft": "#F1ECFF", "ink": "#201E1D" } },
  "fontChoice": "nunito" }
```

That is the shipped default, as `GET /schools/by-code/ALNOOR` returns it for a school that has not been themed.
`worldPalettes` has exactly the two worlds `math` and `english`.

### What a save is validated against

`PUT /admin/schools/{id}/theme` (`theme.write`, ADMIN) normalises every colour to upper-case `#RRGGBB` and measures six
pairs **in this order**, refusing on the first failure with a 400 naming the pair and both ratios:

| # | Pair | Bar | Why |
|---|---|---|---|
| 1 | `primaryInk` on `primary` | 4.5:1 | text on the brand surface |
| 2 | `primaryInk` on `ground` | 4.5:1 | the same ink on the page |
| 3 | `accent` on `ground` | 4.5:1 | the colour of an action |
| 4 | `mascotColor` on `ground` | **3:1** | Pip is a graphic, not text (WCAG 1.4.11) |
| 5 | `math.ink` on `math.soft` | 4.5:1 | text on that world |
| 6 | `english.ink` on `english.soft` | 4.5:1 | text on that world |

`primaryInk` is measured on **both** `primary` and `ground`, which is what makes `primary` a light brand surface rather
than a saturated fill: white ink over a dark navy `primary` only validates when `ground` goes dark with it.

The message form, both verified locally:

```
{"code":"bad_request","message":"primaryInk on primary is 3.8:1, needs 4.5:1"}
{"code":"bad_request","message":"mascotColor on ground is 1.6:1, needs 3.0:1"}
```

(The first is the brand red `#EC3013` as text on `#F3F2F2`; the second is the tokens' mascot blue `#7EC8FF`. Both are
why the defaults are the darkened variants.)

The two free-text fields are checked before any colour, in `SafeText`:

| Field | Rule | Limit |
|---|---|---|
| `logoUrl` | must start `https://` — not `http://`, and no `javascript:` or `data:`; no control characters; blank = unset | 2000 characters |
| `appName` | trimmed, no control characters; blank = unset | 60 characters |

Both are served by public routes and land in an `img src`, a page title and a mail subject, so neither is taken on
trust. Verified refusals: `{"logoUrl":"javascript:alert(1)"}` answers *"logoUrl must be an https:// URL"*, a 61-character
`appName` answers *"appName size must be between 0 and 60"*, and a `worldPalettes` key other than the two worlds
answers *"worldPalettes has no world science; it is math and english"*. A refused save changes nothing.

### Reading a theme

```bash
curl -s "$API/schools/$SCHOOL/theme"                                          # public
ETAG=$(curl -s -D - -o /dev/null "$API/schools/$SCHOOL/theme" | awk '/[Ee][Tt]ag:/{print $2}' | tr -d '\r')
curl -s -o /dev/null -w '%{http_code}\n' -H "If-None-Match: $ETAG" "$API/schools/$SCHOOL/theme"   # 304

curl -s "$API/admin/schools/$SCHOOL/theme" -H "Authorization: Bearer $TOKEN"  # theme.read; another school is 404
```

Verified: the public read carried `ETag: "e8216b635af7c32b87b7c9cdfee68013"` and `Cache-Control: max-age=300, public`,
and the conditional request answered `304`. The ETag is the first 32 hex characters of the body's SHA-256, so it
changes exactly when the theme does; weak validators (`W/"…"`) and comma-separated lists are honoured.
`GET /schools/{id}/flags` behaves identically. Five minutes is the whole staleness budget: a theme save or a flag flip
reaches a client within that, with no rebuild and no redeploy.

A `theme.read` caller who is not ADMIN gets **404** for another school, not 403 — the same rule as everywhere else in
§2.

### How the app applies it

`SchoolSession` caches the theme JSON and its ETag per school and restores it **before the first frame**, so a themed
app never flashes the default palette. `schoolThemeOverrides` maps the JSON onto `ThemeOverrides`, and the app root
cross-fades between two of them over **300 ms** (`Motion.themeTransitionMillis`), role by role — a school that
overrides three colours animates only those three. The roles follow the fields' jobs, not their names: `accent` is the
filled action (`primary`/`secondary`), `primary` + `primaryInk` are the brand surface and its ink
(`surface`/`onSurface`), `ground` is the page, `softBorder` the rules. The label on an accent fill is chosen by
luminance, because that is the one pair the server never measures.

Applied: the school's logo in the world-map header (its monogram until the image arrives), the two subject worlds from
`worldPalettes.math` / `.english`, and Pip in `mascotColor`. The four avatar swatches keep their own colours.

**`fontChoice` is carried but not honoured.** `baloo` and `fredoka` are not shipped as font resources, so all three
values resolve to the bundled Nunito. The key is still stored and returned, so shipping a face is the only work left.

### Joining a school

`code` is the six-character A–Z/0–9 join code printed for parents. In Add child, typing the sixth character looks it up:

```bash
curl -s "$API/schools/by-code/ALNOOR"
# {"name":"Al Noor School","logoUrl":null,"curriculumOptions":["british","american"],"gradeOptions":[1,2,3],"theme":{…}}
```

Public (no token), and it carries the **theme** so the app can run its colour transition without a second request. The
school's name and logo fade in and a separate *Join this school* tap confirms it; the theme applies immediately, before
the child exists, so the rest of the form is already in the school's colours, and the curriculum and grade choosers
narrow to `curriculumOptions` / `gradeOptions`. Backing out fades the colours away again. On save,
`CreateChildRequest.schoolCode` goes to the server and **the server** decides which school id the child lands in. An
unknown code is a 404 and shows *"We couldn't find that school code."* A parent with no code fills the form as before.

## Platform settings

§A: the product's own name, short name and logo are a row in `platform_settings` (id `default`, seeded
`Schools Dashboard` / `Schools` by `V5__flags_themes.sql`), not a constant and not an env var.

```bash
curl -s "$API/platform-settings"
# {"name":"Schools Dashboard","shortName":"Schools","logoUrl":null,"supportEmail":null,"defaultTheme":null}

curl -s "$API/admin/platform-settings" -H "Authorization: Bearer $TOKEN"      # platform.manage; every field
curl -s -X PUT "$API/admin/platform-settings" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"QA Dashboard"}'            # platform.write, ADMIN
```

`GET /platform-settings` is public — the sign-in page and the app need it before anyone has a token — and is
deliberately **not** cacheable: a rename has to reach the browser title, the sign-in heading and the footer on the next
page load, not within five minutes. The PUT writes only the fields that are present; a `defaultTheme` in the body goes
through the same §3 contrast validation. The row is cached 60 seconds in the server and dropped on a write.

**Name resolution, everywhere:** the selected school's `theme.appName` → the platform's `name` → the value seeded in
the migration. `GET /me` answers the resolved `platformName`, the app resolves the same order for its own title, and
mail subjects take the platform name. Verified locally: `GET /platform-settings` answered `Schools Dashboard` and
`GET /me.platformName` answered the same for an ADMIN, who has no school.

`ProductNameTest` fails the build if the literal `Homework Quest` or `Schools Dashboard` appears anywhere under
`server/src/main` — including a comment or a model prompt — outside `V5__flags_themes.sql`. That migration is where the
name enters the system; everything else asks `PlatformSettingsService`.

**There is no `PLATFORM_NAME` any more.** P2.1 deleted the `quest.platform-name` property: an env var that silently
beat the Admin's own setting would be a second source of truth. `infra/terraform/main.tf` still sets the variable on
Cloud Run and `deploy/README.md` still lists it; both are inert and are the `infra` worker's to remove.

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
| `DEEPSEEK_API_KEY`, `DEEPSEEK_MODEL`, `DEEPSEEK_VISION_MODEL`, `DEEPSEEK_BASE_URL`, `DEEPSEEK_MAX_TOKENS` | every environment | `LLM_PROVIDER=deepseek` |
| `ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL` | optional | `LLM_PROVIDER=anthropic` |
| `LLM_PROVIDER` | every environment | `deepseek` \| `anthropic` \| `fake` |
| `FIREBASE_CREDENTIALS` | QA, prod | parents' token verification; empty + profile `local`/`h2` = `FAKE_AUTH` |
| `DB_URL`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `CLOUD_SQL_INSTANCE` | every environment | Cloud SQL socket factory in `qa`/`prod` |
| `STORAGE_KIND`, `STORAGE_DIR`, `GCS_BUCKET` | every environment | `local` or `gcs` |
| `PUBLIC_URL` | every environment | base URL the server puts in media links |
| `CORS_ORIGINS` | every environment | only local dev origins matter; the panel is same-origin |
| `PORT`, `APP_VERSION`, `PANEL_DIR`, `SPRING_PROFILES_ACTIVE`, `FAKE_AUTH` | runtime | the image sets `PANEL_DIR` |
| `QUEST_ANYDOC_BIN`, `QUEST_TESSERACT_BIN` | every environment | paths to the two conversion binaries; the image sets both, elsewhere they fall back to `PATH` |
| `QUEST_CONVERT_TIMEOUT_SECONDS`, `QUEST_CONVERT_OCR_PAGE_TIMEOUT_SECONDS`, `QUEST_CONVERT_MAX_MARKDOWN_CHARS` | every environment | total time box per file (120 s), per OCR page (20 s), and the cap on one file's Markdown (400 000 characters, truncation noted in the text) |
| `QUEST_LLM_TIMEOUT_SECONDS`, `QUEST_LLM_CONNECT_TIMEOUT_SECONDS` | every environment | per model call: read (120 s, and 120 s is also the cap) and connect (10 s) — see [Stuck lessons](#stuck-lessons) |
| `QUEST_PIPELINE_DEADLINE_GENERATE_SECONDS`, `QUEST_PIPELINE_DEADLINE_ANALYZE_SECONDS`, `QUEST_PIPELINE_DEADLINE_CONVERT_SECONDS` | every environment | how long one step may run before it is `error`/`timeout` (360 / 240 / 180 s) |
| `QUEST_PIPELINE_WATCHDOG_ENABLED`, `QUEST_PIPELINE_WATCHDOG_INTERVAL_SECONDS`, `QUEST_PIPELINE_WATCHDOG_GRACE_SECONDS` | every environment | the sweep that recovers a job a recycled instance left behind (on, every 60 s, 60 s of slack) |
| `quest.pipeline.convert.allow-builtin-fallback` | `h2`/`test` profiles only | not an environment variable: hard `false` in the base config, `true` only under those two profiles, and the code checks the profile too |

`ADMIN_JWT_SECRET` has a placeholder default in `application.yml` so a developer can boot without one. **Any deployed
environment must set it** — Terraform does, from `random_password.jwt`.

Terraform wires `DB_PASSWORD`, `ADMIN_JWT_SECRET`, `DEEPSEEK_API_KEY` and `ADMIN_PASSWORD` into Cloud Run as required
secrets, plus `ANTHROPIC_API_KEY` and `FIREBASE_CREDENTIALS` once a value exists. `MAIL_PROVIDER`, `RESEND_API_KEY`,
`MAIL_FROM` and `DASHBOARD_URL` have Terraform variables but no value in QA, so QA runs the log mailer and builds
links from `PUBLIC_URL`. Setting them is an `infra` package (phase 5's `infra/mail-push` covers the mail three).

`infra/terraform/main.tf` also still sets a `PLATFORM_NAME` variable on the container. The server ignores it — the
product name is a database row (see [Platform settings](#platform-settings)) — and removing it is an `infra` change.

## Seeding two schools, isolation, flags and themes

**The one-school seed (`SEED_SCHOOL`).** A school large enough to judge the dashboard by — 30 classes (British and
American, grades 1–3, sections A–E), 40 teachers, 60 teaching assignments and 600 children — lives in
`server/src/main/resources/seed/{classes,teachers,assignments,children}.csv` and is loaded by
`quest.server.classes.SchoolSeed` into the default school on start-up. It goes in through the Admin services, so the
rows carry real join codes, real one-time passwords and the one-teacher-per-subject-per-class rule; it is idempotent
(a class is matched by curriculum + grade + name, a teacher by email, a child by her name in her class), so a re-run
logs the counts and writes nothing, and a malformed CSV row stops the load naming its file and line. `SEED_SCHOOL`
(default `false`, `true` in the `qa` and `h2` profiles, no such bean in `prod`) is the switch, and **Terraform should
set `SEED_SCHOOL=true` on the QA Cloud Run service** so a fresh QA database fills itself. `SEED_STAFF_PASSWORD` is
the one password every teacher in `teachers.csv` gets, with `must_change_password` cleared so an e2e run can sign in
as any of them; it is applied on every run, including to the teachers an earlier run created, because QA is usually
seeded before the secret exists. Leave it unset and no password is touched at all — a new teacher keeps her own
generated one, which nothing logs or prints, and the start-up line says only that the variable is unset. The first load costs about 12 seconds (one bcrypt per teacher); later
boots are instant. This school is separate from the Al Noor / Green Valley fixture below, which lives in its own two
schools and is untouched by it.

**Attempts to score (`seed/attempts.csv`).** The dashboard's Results page and gradebook are empty until some child
has actually played something, so `quest.server.grading.AttemptSeed` reads
`server/src/main/resources/seed/attempts.csv` right after the school seed and gives three children of `1A British`
and `1B British` a handful of attempts. It brings its own lesson — one published homework per class named in the
file ("Counting to ten": two single-answer stops and one retell), because a fresh QA database has no lessons until a
teacher writes one — and the lesson id, the stop ids and the attempt ids are all derived from the class id, so a
re-run writes nothing. It runs for the **`full` profile only**: `acceptance` is the owner's own environment, where
the children arrive when he registers in the app and the lessons are the ones he posts himself, and a fixture
putting scores on that board would be inventing work nobody did. A row naming a class or a child the school does not
have stops the load naming the line; as with the school seed, a broken fixture costs QA its seed, never its revision.

## Results, marking and release

`docs/teacher-flow.md` step 9. Three things are worth knowing when QA looks wrong.

**The two flags.** Every route below is behind `gradebook`, and `PUT /teacher/marks` is behind `openStopMarking` as
well. Both are seeded **off** (V7), so a school sees 404 from the whole area until an Admin turns them on:

```bash
curl -X PUT "$API/admin/schools/$SCHOOL/flags/gradebook" -H "Authorization: Bearer $ADMIN" \
     -H 'Content-Type: application/json' -d '{"enabled":true}'
curl -X PUT "$API/admin/schools/$SCHOOL/flags/openStopMarking" -H "Authorization: Bearer $ADMIN" \
     -H 'Content-Type: application/json' -d '{"enabled":true}'
```

**A homework is released when it is published; an exam is not.** §7's release is "default on for homework", so
`POST /teacher/lessons/{id}/publish` stamps `released_at` on every copy it takes live whose `type` is not `exam` —
a parent sees the score her child earned without the teacher remembering a second action. An exam stays unreleased
until `POST /teacher/lessons/{id}/release` (§8 gives it its own release, automatic on close or manual). Rows that
existed before V13 are untouched and stay unreleased until something publishes or releases them.

A release a teacher **withdraws** stays withdrawn: re-publishing that lesson does not put it back in front of the
parents (`lessons.release_withdrawn`), because a default must not overrule an explicit instruction. `{"released":
true}` is how she changes her mind.

The parent's `GET /children/{id}/progress` carries the score, band and comment in `results[]` for released lessons
only. The child never sees a number at all — that is §6's rule and this changes nothing about it. To take a lesson
back off the parent's report:

```bash
curl -X POST "$API/teacher/lessons/$LESSON/release" -H "Authorization: Bearer $TEACHER" \
     -H 'Content-Type: application/json' -d '{"released":false}'
```

**Marking a released lesson is allowed.** §7 says only that a parent sees the score and the comment *after* release;
it does not freeze a released lesson, and since a homework is released the moment it is published, refusing marks on
one would make §7's own marking flow impossible. So `PUT /teacher/marks` always lands, and the new mark reaches the
parent on her next read.

**Two different numbers, on purpose.** The gradebook's per-child `average` is the plain arithmetic mean of her
scored cells in the window on screen — a teacher who adds the row up by hand gets the same number. The child page's
`levelScore` is §7's rolling `ChildLevel`: weighted toward recent lessons, an exam counted twice, the newest ten
of **that subject** (the window is per subject, so a three-subject class still gets three full levels and three
full lines on the chart). It answers "where is she now" rather than "what do her marks come to", and the two can differ by a band.

Scores are computed from the attempts on every read — there is no `homework_scores` table to rebuild, and no cache
to clear. The thresholds behind the four bands (`emerging`, `developing`, `secure`, `exceeding`) are constants in
`server/src/main/java/quest/server/grading/Bands.java`.

`e2e/` holds the fixture and the assertions over it — three Node-and-bash scripts, no dependencies beyond Node 22,
`curl` and optionally `jq`. Full detail in [e2e/README.md](../e2e/README.md).

| Script | What it does |
|---|---|
| `seed/seed.mjs` | creates the two-school fixture and writes the ids to `e2e/.seed.json`; idempotent |
| `isolation.sh` | §2 / §10 cross-school isolation over the API |
| `flags.sh` | §4 flags, §3 themes and §A platform settings; flips state and puts it back |

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
export PATH="$JAVA_HOME/bin:$PATH"   # `java` must be 21: JAVA_HOME alone only steers ./mvnw, not the `java` below
./gradlew :shared-api:publishToMavenLocal -Pquest.serverOnly=true
cd server && ./mvnw -q package -DskipTests
SPRING_PROFILES_ACTIVE=h2 ADMIN_EMAIL=admin@quest.local ADMIN_PASSWORD='<throwaway>' \
  LLM_PROVIDER=fake PORT=8089 java -jar target/server.jar &
cd ..                       # the two lines above leave the shell in server/; the scripts are run from the repo root

export E2E_BASE_URL=http://127.0.0.1:8089
export E2E_ADMIN_EMAIL=admin@quest.local
export E2E_ADMIN_PASSWORD='<the same throwaway>'
export E2E_STAFF_PASSWORD='<anything ≥ 10 chars>'
node e2e/seed/seed.mjs      # idempotent; writes ids to e2e/.seed.json
e2e/isolation.sh            # one PASS / FAIL / BLOCKED line per assertion
e2e/flags.sh                # same conventions; restores everything it flips
```

Against QA (**needs QA credentials**): set `E2E_BASE_URL` to the QA API, take `E2E_ADMIN_PASSWORD` from the
git-ignored `.env` without echoing it, and add `E2E_PARENT_PASSWORD` — off localhost the scripts create the throwaway
parents through Firebase Identity Toolkit in `homework-quest-qa`, with the web key from
`androidApp/src/qa/google-services.json`. Delete those two Firebase accounts when the fixture is no longer wanted.
Wake QA first if it is asleep.

Exit codes for all three scripts: `0` everything held, `1` a failure or an unexpected response, `2` complete as far as
the API allows (each `BLOCKED` line says why).

**Why a run can end in `2`.** Staff passwords. `seed.mjs` tries, in order: signing in with `E2E_STAFF_PASSWORD`;
`POST /admin/schools/{id}/users` when the target's OpenAPI document lists it; then an invite, *if* the response carries
the token. On `develop` today the invite response carries no token and the log mailer swallows the email, so against a
server without the direct-creation endpoint the staff rows exist with no usable password. `isolation.sh` then falls
back to the ADMIN-only **View as** token for the read assertions — enough for every `GET` — and reports `BLOCKED` for
the two that need a write.

The isolation assertions mirror `IsolationTest` over HTTP: a teacher of A gets 404 for B's lessons, children and
media; the header switcher behaves as the table above; a parent never reaches another school's lesson.

### `e2e/flags.sh`

Eight groups of assertions over the same fixture: the 14 seeded defaults and their ETag; ADMIN flipping `certificates`
off for school A and school B staying untouched; the audit row that flip leaves; the route a flag guards answering 404
for A and 200 for B; the flip back on with no restart; `PUT /admin/flags/{key}/all` moving both schools and leaving
**one** audit row with `schoolId` null; a teacher's write being 403 while a managerial caller sees every definition but
only her own school's row; the two theme refusals, a valid theme, its public ETag and its 304; and the platform-name
round trip through `GET /me.platformName` and `GET /platform-settings`.

`certificates` is the flag it flips: it is `default_on` and guards no route yet, so the flip is observable and
harmless. **The route-is-404 assertion is `SKIPPED` today** — nothing on `develop` carries `@FeatureFlag`. The script
decides that from `/v3/api-docs` rather than from the source, so the skip turns into a `FAIL` naming the new route the
moment P3.0 or P4.0 publishes a flagged path; that is the cue to assert the 404 here.

**Everything it changes it puts back, and it proves the restore landed.** A trap on `EXIT`, `INT` and `TERM` restores
`certificates` for both schools, writes school A's theme back exactly as `GET /admin/schools/{A}/theme` answered at the
start, and sets the platform name back. Each restore write is then **read back and compared**: a read-back that agrees
is at most a `note` line, a read-back that disagrees is a `FAIL restore …` naming the thing, what it reads and what it
should read, and forces a non-zero exit whatever the assertions said. The closing "are back as they were" sentence is
printed only when every read-back agreed — so an interrupted run leaves no drift, and `isolation.sh` passes
immediately afterwards.

Two things it cannot take back, both by design: the `flag_audit` rows, which are append-only (that trail is the point
of the audit assertion), and a school that had **no** `theme_json`, which ends holding an explicit copy of the theme it
was already being shown — there is no `DELETE` for a theme and the rendered result is identical.

## Exams

`docs/teacher-flow.md` step 10. An exam **is a lesson** with `type = exam`: the same pipeline, the same cache, the
same review screens, the same publish. Four things differ, and each of them is somewhere QA can look wrong.

**The flag.** Every route is behind `exams`, seeded **off** (V7), so a school sees 404 from the whole area — exports
and printable sheet included — until an Admin turns it on. `gradebook` is needed too, because the exam results page
is the lesson results page with §8's columns beside it:

```bash
curl -X PUT "$API/admin/schools/$SCHOOL/flags/exams" -H "Authorization: Bearer $ADMIN" \
     -H 'Content-Type: application/json' -d '{"enabled":true}'
```

**The window is the server's clock — and the school's calendar.** `POST /teacher/classes/{id}/exams` takes
`opensAt` / `closesAt` as epoch milliseconds, and the child's tablet is never asked. The *day* the exam is filed on
is `opensAt` read **in the school's own timezone** (`SchoolCalendar`: the school row's `timezone`, else the platform
row's, else UTC), which is also the day the Sunday–Thursday teaching-day check is applied to. Reading it in UTC was
the N4.3 bug: in Riyadh (UTC+3) a window set for Sunday 02:14 is Saturday 23:14 UTC, so an ordinary Sunday exam came
back `409 not_teaching_day`. If a teacher reports that refusal for a day her school clearly teaches on, check the
school's `timezone` first — not her browser's. Outside it, three things happen at once: the island is absent
from `GET /children/{id}/map`, an answer upload is `409 exam_closed`, and the teacher's own settings sheet is frozen
(`PATCH /teacher/exams/{id}` is `409 exam_open` once it has opened). Inside it, the island carries `examWindow` and
`GET /lessons/{id}` carries `type`, `hintsOff`, `numbersOff` and the single `examPlay`.

```bash
OPENS=$(( $(date +%s) * 1000 ))
curl -X POST "$API/teacher/classes/$CLASS/exams" -H "Authorization: Bearer $TEACHER" \
     -H 'Content-Type: application/json' \
     -d "{\"title\":\"Autumn test\",\"opensAt\":$OPENS,\"closesAt\":$((OPENS + 1800000)),\"level\":\"mixed\",\"source\":\"manual\",\"releaseMode\":\"auto_on_close\"}"
curl -X POST "$API/teacher/exams/$EXAM/publish" -H "Authorization: Bearer $TEACHER"
```

**One sitting, resumable.** There is no "start the exam" call — the first answer upload creates the `exam_attempts`
row, later ones land on the same row, and the sitting is handed in when every stop of the paper has an answer. A
child who comes back mid-exam carries on; one who has handed it in gets `409 exam_already_taken`. The teacher's way
back in is `POST /teacher/exams/{id}/reopen/{childId}`, **once** per child (a second is `409
exam_already_reopened`); it extends the end of the window for her alone and clears the hand-in, so an absent child
can sit it after the close and an interrupted one keeps her answers.

**Release is never a side effect of publishing.** With `releaseMode: auto_on_close` a sweep releases it within a
minute of the window shutting — at startup too, because on Cloud Run the instance that would have run the timer is
usually gone. With `manual`, or after a teacher has withdrawn a release, nothing automatic touches it:
`QUEST_EXAMS_RELEASE_SWEEP_ENABLED=false` switches the sweep off entirely and
`QUEST_EXAMS_RELEASE_SWEEP_INTERVAL_SECONDS` changes its period.

**The level.** `1`, `2` or `3` is that generated level's play. `mixed` is assembled on the fly, one stop from each
level in turn up to the lesson's practice length; nothing is stored, so changing the level while the window is shut
costs nothing and loses nothing. The scorer is handed the same paper the player downloads, so a mixed exam is
scored over exactly the questions the child was asked — never over one level's third of them.

**The paper is never publicly cached.** `GET /lessons/{id}` answers `public, max-age=31536000` for a homework — it
is immutable per version — but `private, no-store` for an exam. A year-long shared cache is the one thing the window
cannot survive: a proxy, or the tablet's own disk, would hand the paper to a child who asks before it opens, after
it closes, or a second time after she handed it in, and none of those reads would reach the server that refuses
them. If QA sees an exam body served from cache, that header is the thing to look at.

**The tab's list.** `GET /teacher/classes/{id}/exams` answers a row per exam, newest first: the settings, plus
`state` (`draft` · `scheduled` · `open` · `closed` · `released` — `released` wins, and a published exam with no usable
window is `draft`), `roster`, `sat` and `needsMarking`. `GET /teacher/exams/{id}` is one row of the same shape. The
numbers come from one bulk pass (`ExamListQueryCountTest` pins the statement count against a term of exams), so the
Exams tab never needs a `/results` call per row.

`GET /teacher/exams/{id}/results` carries the per-child table (state, stars, percent, band, time taken, last seen,
marks pending), the class average, the distribution over the four bands, the per-question difficulty and the absent
list; `results.csv`, `results.xlsx` and `results/{childId}.pdf` are the same rows. `missedPercent` is out of the
children who **reached** the question, not out of the roster: a question the class ran out of time before is not one
the class got wrong. A re-opening restarts the sitting's clock, so `secondsTaken` is the re-sitting's own time and
never the days between an absence and the second chance.

## Chat

C1 `backend/chat-websocket` (D24): real-time chat between a child's **parent** (the app) and a **teacher** of the
child's section (the dashboard), on the API itself — Spring WebSocket on the same origin with the same tokens, plain
JSON frames, no STOMP, no second service. The contract types are `shared-api/src/commonMain/kotlin/quest/api/dto/Chat.kt`
(`ChatThread`, `ChatMessage`, `ChatFrame`, `ChatCommand`); the frame schema the app and the dashboard validate
against is `shared-api/src/commonMain/resources/schemas/ChatFrame.schema.json`, used the way `Play.schema.json` is.

**The flag.** Everything is behind `chat`, seeded **off** by V15: every REST route answers 404 and the socket
handshake 403 until an Admin turns it on for the school (`PUT /admin/schools/$SCHOOL/flags/chat {"enabled":true}`).
A parent is refused only when *none* of her children's schools has it on; each command is then checked against the
child's own school.

**Who may talk to whom.** One thread per (child, teacher), and only between the child's parent and a teacher who
holds an assignment on the child's section — checked from both ends, the same way the rest of the teacher API is
(`TeacherScope`). A parent asking about a child that is not hers gets 404; a teacher asking about a child on a
section she does not teach gets 403, and about another school's child 404 (the tenant filter). A child on **no
section yet** has no teachers to write to: `409 child_not_placed` from either side, and the app tells the parent to
ask the teacher to place the child. A thread row appears on the first message; the parent's list names every
teacher of the section beforehand with `id: null`, so she can start one; a teacher starts one by posting to
`/teacher/chat/threads/{childId}/messages` (her list shows only threads that exist).

### REST

| Parent (Firebase token) | Teacher (JWT) | What |
|---|---|---|
| `GET /children/{id}/chat/threads` | `GET /teacher/chat/threads` | `ChatThread[]`: unread first, then newest. The teacher's spans all her sections. |
| `GET /children/{id}/chat/threads/{teacherId}/messages?before=&since=&limit=` | `GET /teacher/chat/threads/{childId}/messages?…` | `ChatMessage[]`, **oldest first** within the page. No cursor = the newest page. |
| `POST …/messages {body, clientId?}` | `POST …/messages {body, clientId?}` | 201 `ChatMessage`. |
| `POST …/read` | `POST …/read` | 200 `ChatReadReceipt`; 404 while no thread exists. |

`limit` is 1–200 (default 50). `before=<messageId>` pages backwards from that message; `since=<messageId>` answers
everything after it, oldest first — the reconnect refetch. Either cursor must be a message of that thread (400
otherwise). `body` is 1–2000 characters of **plain text**: trimmed, control characters other than line breaks and
tabs removed, stored and delivered exactly as typed, and **never interpreted as HTML** by any client — render it as
text. More than **30 messages a minute** from one sender (REST and socket together, per instance) is `429
rate_limited`. Support: `GET /admin/chat/threads` and `GET /admin/chat/threads/{threadId}/messages` as ADMIN with
`X-School-Id` (read-only; without the header the Admin reads the flag defaults and the gate answers 404).

### The socket: `/ws/chat`

**Auth.** `Authorization: Bearer <token>` when the client can send headers (the app), else `?token=<token>` (a
browser `WebSocket` cannot send headers — the dashboard). Either carrier takes either kind: a dashboard JWT
(`admin.…`, TEACHER only) or a Firebase ID token, verified by the same code as the request filters. 401 for a
token nobody issued, 403 for ADMIN/MANAGERIAL or a school with the flag off. The token is never logged; on QA the
proxy log shows the path without the query. Allowed origins are `CORS_ORIGINS`; the app sends no `Origin`.

**Frames** are JSON text, discriminated by `type`, at most **8 KB** (bigger → close 1009).

Client → server (`ChatCommand`): a parent names the thread by `childId` + `teacherId`, a teacher by `childId`.

```json
{"type":"message","childId":"…","teacherId":"…","body":"Hello","clientId":"7f3a…"}
{"type":"typing","childId":"…","teacherId":"…"}
{"type":"read","childId":"…","teacherId":"…"}
{"type":"ping"}        {"type":"pong"}
```

Server → client (`ChatFrame`, the DTOs REST uses):

```json
{"type":"message","message":{ChatMessage},"clientId":"7f3a…"}   // clientId only on the sender's own sessions
{"type":"read","threadId":"…","readBy":"teacher","readAt":1758450000000}
{"type":"typing","threadId":"…","from":"parent"}
{"type":"ping"}   {"type":"pong"}
{"type":"error","code":"child_not_placed","message":"…","clientId":"7f3a…"}
```

**The ack.** A send is answered by nothing directly: the message is committed, published on the bus, and comes back
to *every* session of both parties as a `message` frame — the sender's own sessions get it with the `clientId` the
command carried (use a UUID), everybody else's without. That echo is the ack: a client renders its pending bubble
on send and replaces it when a frame with the same `clientId` arrives, so a message never shows twice. A refused
command is an `error` frame with the same `clientId` and the REST error code (`bad_request`, `not_found`,
`forbidden`, `child_not_placed`, `rate_limited`, `internal`). A REST send is announced on the socket the same way.
`typing` is fan-out only — never stored, sent to the other party only, and the first thing dropped when a socket
is slow. `read` goes to both parties (so the reader's other devices clear their badge too).

**Heartbeat and idle.** The server sends `{"type":"ping"}` every 30 s (`quest.chat.heartbeat-seconds`); answer
with `{"type":"pong"}` — any command counts. A socket that sends nothing for 10 minutes (`idle-seconds`) is closed
`1000 idle`. A client that hears no ping for ~90 s should treat the socket as dead and reconnect. A client may send
`ping` itself and gets `pong`.

**Backpressure.** Frames to one socket are written by one thread, in order. A `typing` or `ping` is skipped while
anything is still queued for that socket; a `message` is never dropped — it is queued until the queue passes 64 KB
(`send-buffer-bytes`) or one write has taken longer than 10 s (`send-timeout-seconds`), and then the socket is
closed `1008` and the client reconnects. The invariant the contract actually makes is therefore *no silent loss*,
not *every frame*: **on every (re)connect, refetch** `…/messages?since=<last message id you hold>` per open thread
(or `GET …/threads` for the counts) — that fills any gap from a close, an instance restart, or Cloud Run's request
timeout. Sockets on Cloud Run are HTTP requests and end at the service's request timeout (3 600 s, raised for this
in the Terraform); the client must expect a close every hour and reconnect with the refetch. Reconnect with
exponential backoff (1 s → 30 s) and a fresh token: a dashboard access token lives 15 minutes, so reconnect after
`/auth/refresh`, not with the expired one.

**Across instances.** QA runs up to two instances and a socket lives on whichever took its handshake. A message
committed on one reaches the other's sockets through PostgreSQL `LISTEN/NOTIFY` on channel `chat_events`
(`PostgresChatBus`): every instance holds one pooled connection on `LISTEN` and waits on it 250 ms at a time
(`quest.chat.notify-poll-millis`); a publish is `pg_notify` from a pooled connection *after the commit*. An event
whose payload would pass NOTIFY's 8 000-byte limit goes out without the message body and is loaded by id on
arrival. Under H2 (local, the suite) the same interface is an in-process bus; `QUEST_CHAT_BUS=postgres|memory`
forces one, `auto` (the default) picks by the datasource's product name. If a listener connection drops (a Cloud
SQL restart) it reconnects after a second and logs `chat: listener connection lost`; nothing published in between
is replayed — the client's `?since=` refetch is the recovery. `Tests: PostgresChatBusTest` (Testcontainers tag
`postgres`) proves two buses on one database hear each other.

**Reading it on QA.**

```bash
curl -X PUT "$API/admin/schools/$SCHOOL/flags/chat" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"enabled":true}'
curl "$API/teacher/chat/threads" -H "Authorization: Bearer $TEACHER"
curl -X POST "$API/teacher/chat/threads/$CHILD/messages" -H "Authorization: Bearer $TEACHER" -H 'Content-Type: application/json' -d '{"body":"Welcome to 1A!"}'
# the socket, with websocat: a ping answered with a pong, then the live frames
websocat "wss://${API#https://}/ws/chat?token=$TEACHER" <<< '{"type":"ping"}'
```

`GET /admin/chat/threads -H "X-School-Id: $SCHOOL"` is the support view of every thread in a school. There is no
delete: a thread is part of the school's record, and `DELETE /admin/children/{id}` (the Admin's hard delete, which
removes her threads and messages with her) is the one thing that removes one; `SEED_RESET` wipes them with the rest.

## The app and the contract

**The app's JSON is strict, so app and server ship together.** `SchemaValidator.json` — the one `Json` the app's
network client installs (`shared/.../RemoteContentApi.kt`) and the one the server encodes with — is configured
`ignoreUnknownKeys = false` and `encodeDefaults = true`. Those two together mean a Kotlin default on a new field
buys **nothing** on the wire: the server writes the field into every response whether or not it is set, and an app
binary older than the field throws `SerializationException` on the first body that carries it rather than ignoring
it. A field added to an app-facing DTO is therefore a breaking change for installed apps until the app relaxes
`ignoreUnknownKeys`, which is D16's job and has not shipped.

What this means in practice:

* Do not roll the server forward past an app release that has not gone out. A staged rollout of the app with the new
  server already live is fine; the reverse is not.
* A new field on a `quest.api.dto.*` type belongs in the release notes, next to the minimum app build that reads it.
* `quest.api.dashboard.*` types are not affected — the dashboard's generated TypeScript client ignores unknown keys
  — so the Exams tab's row, `lastSeenAt` and anything else on that side may ship on their own.

App-facing DTOs that have gained fields since `e36c188` (the last release that predates them), all of which an older
binary would now refuse:

| DTO | Fields | Shipped in |
| --- | --- | --- |
| `ProgressResponse` | `results: List<ReleasedResult>` (and the new `ReleasedResult` type) | N4.1 (#104) |
| `PublishedLesson` | `type`, `hintsOff`, `numbersOff`, `examPlay` | N4.3 (#106) |
| `Island` | `examWindow` (and the new `ExamWindow` type) | N4.3 (#106) |

`ApiError` also gained `not_teaching_day`, `exam_closed`, `exam_already_taken`, `exam_already_reopened` and
`exam_open`, but those are constants rather than wire fields — an app that does not know a code shows the server's
message, which is what the unknown-code path already does. `RosterChild` is unchanged since `e36c188`, and is a
dashboard type in any case.

## QA as the owner's acceptance environment

QA has two jobs and they want different data. The automated e2e suite needs the 30-class school and the Al Noor /
Green Valley fixture above; the owner's own acceptance pass needs **two teachers and nothing else**, so that a lesson
Ms Maya posts is the only lesson his child sees. `SEED_PROFILE` picks which, and `SEED_RESET` is the one-shot wipe
that gets from one to the other.

| Variable | Value for the acceptance pass | Notes |
|---|---|---|
| `SEED_SCHOOL` | `true` | unchanged: the switch that lets the seed run at all |
| `SEED_PROFILE` | `acceptance` | `full` (the default) is the 30-class school; **an unknown value fails the start**, with the two valid names in the message — a typo must not quietly refill QA with the 30-class school |
| `SEED_RESET` | `true` for **one** deploy, then back to `false` | refused outright under the `prod` profile: the revision fails to start. **It ignores `SEED_SCHOOL`** — the wipe runs whether or not the seed is switched on, so `SEED_SCHOOL=false` is no protection |
| `SEED_RESET_TOKEN` | unset the first time; any new short string to wipe **again** | the ledger's id. Blank is the original one-shot run (`once`); a value writes `token:<value>` instead, so a value nobody has used runs the wipe once and re-deploying with the same value deletes nothing. Use something you will recognise in the logs, e.g. `2026-09-samples` |
| `SEED_STAFF_PASSWORD` | the shared teacher password, from Secret Manager | never logged, never printed; the seed re-applies it on every boot |

**What the acceptance profile seeds** (`server/src/main/resources/seed/acceptance/*.csv`, into the **default**
school): three sections — `1A British` and `1B British` (british, grade 1) and `1A American` (american, grade 1);
two teachers — **Maya** (math, `maya@test.com`) and **Rami** (english, `rami@test.com`), both signing in with
`SEED_STAFF_PASSWORD` and no first-login password change; three assignments — Maya on 1A + 1B British math, Rami on
1A American english. **No children**: they arrive when the owner registers as a parent in the app. Re-running the
seed changes nothing (sections are matched by curriculum + grade + name, teachers by their lower-cased email).

> The password the owner chose is nine characters, which is under the `MIN_PASSWORD` of 10. That minimum is a
> validation rule on *changing* and *resetting* a password (`AuthService`, `DashboardDto`), and the seed never goes
> through it — it encodes the value straight onto the row — so nothing had to be relaxed for this.

**What `SEED_RESET=true` deletes**, once, before the seed runs, one transaction per school, with a row count logged
per table:

- every lesson of every school and everything hanging off it — steps, source files (including the blob and the
  extracted `.md` in the bucket), page images, skills, plays, stops, parent panels;
- every child and everything keyed by a child — attempts, stop and lesson completions, parent unlocks, stickers,
  streaks, recordings and drawings (blobs included);
- teacher questions and their answers, announcements, sections, teaching assignments, staff invitations;
- every staff account with role TEACHER or MANAGERIAL, and their refresh tokens;
- **every parent account**, because a parent's children are school rows and the row would be left pointing at
  nothing. *The owner and everyone else re-registers in the app after the wipe;*
- then the schools that are not `default` — Al Noor (`ALNOOR`) and Green Valley (`GREENV`) — entirely: their staff,
  their flag overrides, their audit trail and their theme.

**What it keeps:** the `default` school and its theme, join code, own flag overrides and own audit trail; the
platform ADMIN; `platform_settings`; the feature-flag defaults; the `courses` reference rows; and the two permanent
caches (`analysis_cache`,
`generation_cache` — they are keyed by a content hash and a prompt version, not by a school, so keeping them saves QA
a re-analysis of every file uploaded next).

**It is one-shot.** The run writes a `seed_resets` row and every later start with the variable still on finds it and
does nothing — a deploy that forgets to set `SEED_RESET=false` cannot wipe the owner's work on the next revision.
Put it back to `false` anyway. To wipe a second time, deploy with `SEED_RESET=true` **and** `SEED_RESET_TOKEN` set to
a value that has not been used: the token is the ledger's id, so a new one runs once and is written down, and the
same one again does nothing. (Clearing the table by hand still works, but nobody has `psql` against QA.)

**The three §6 sample lessons** — `lesson-counting-by-2s`, `lesson-sh-sound`, `lesson-hot-soup-1` — were in QA
because `ContentSeed` listed the `qa` profile: it is a `CommandLineRunner` ordered *after* the wipe, so it wrote all
three back the moment the wipe had finished, and the owner's acceptance pass opened on three lessons no teacher had
posted. It now runs only under `local`, `dev`, `h2` and `test`. They are `default`-school lessons, so the wipe
already deletes them — but the copies already in QA are still there, and the ledger blocks a repeat: **QA needs one
more deploy with `SEED_RESET=true` and a fresh `SEED_RESET_TOKEN` to be rid of them.** That wipe also takes out
anything the owner has posted since, so do it before the next acceptance pass, not during one.

### The owner's pass, end to end

1. Deploy once with `SEED_PROFILE=acceptance` and `SEED_RESET=true`; check the logs for `seed reset:` counts and
   `school seed default ready: 3 new classes, 2 new teachers, 3 new assignments, 0 new children`.
2. Set `SEED_RESET=false` and deploy again.
3. In the app, register as a parent and add two children, using the **Default school's join code `HQ0001`** — one
   Grade 1 British, one Grade 1 American.
4. Attach each child to her section, so she sees one copy of each lesson rather than one per section of her grade
   (an unattached child is shown every section of her curriculum and grade, and a lesson published to 1A and copied
   to 1B reaches her twice). As the platform ADMIN, against the QA API (**needs QA credentials**):

```bash
API=https://homework-quest-api-625882725080.me-central1.run.app
TOKEN=$(curl -s -X POST "$API/admin/auth/sign-in" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" | jq -r .token)
AUTH=(-H "Authorization: Bearer $TOKEN" -H 'X-School-Id: default' -H 'Content-Type: application/json')

curl -s "${AUTH[@]}" "$API/admin/classes" | jq -r '.[] | "\(.id)\t\(.name)"'      # the three section ids
curl -s "${AUTH[@]}" "$API/admin/children?unassigned=true" | jq -r '.[] | "\(.id)\t\(.name)"'   # on no roster yet

curl -s -X POST "${AUTH[@]}" "$API/admin/classes/$CLASS_ID/roster/attach" -d '{"childId":"'"$CHILD_ID"'"}'
# 200 with the child and her new classId · 409 for another school's child or a curriculum/grade that is not the
# section's · calling it twice writes nothing. DELETE …/roster/$CHILD_ID detaches her again.
```

   Ms Maya can do the same for her own sections while `teacher.rosterEdit` is on:
   `POST /teacher/classes/{classId}/roster/attach` and `DELETE /teacher/classes/{classId}/roster/{childId}`.

5. Sign in as `maya@test.com`, publish a lesson to `1A British`, and it appears for the child on that roster; when
   the child finishes it, her stars appear on Ms Maya's dashboard.

### Stuck lessons

A lesson stays in `analyzing` or `generating` only while a job is running. Two things used to leave one there for
good, and both are now bounded:

- **A model call that hangs.** Every LLM client has a connect timeout of 10 s and a read timeout of
  `quest.llm.timeout-seconds` (`QUEST_LLM_TIMEOUT_SECONDS`, default and maximum **120 s**). A call past it is a
  transient failure: the client retries it up to three times and then fails with `model_unavailable`. It retries
  **only while the step's own deadline still has room for a whole call** (connect + read, 130 s) — three attempts
  plus backoff is 374 s and a generate step is bounded at 360 s, so the third call could only ever be cut off
  mid-flight. Out of room, the client stops and says `model_unavailable` (*"wait a minute and press Retry"*) rather
  than letting the step end as `timeout` (*"retry this"*), which is the more useful of the two messages.
- **A job whose instance is gone.** Cloud Run scales to zero and recycles instances, and the `@Async` job goes with
  the instance — leaving a `lesson_steps` row saying `running` that nothing will ever finish. Each step has a
  deadline (`quest.pipeline.deadline.generate-seconds` **360**, `analyze-seconds` **240**, `convert-seconds` **180**,
  the last also covering Upload and Skills), and a sweep runs at startup and every
  `quest.pipeline.watchdog.interval-seconds` (**60**) plus `watchdog.grace-seconds` (**60**) on top of the deadline.
  Anything past that is marked `error` with code `timeout` and the message *"This step took too long. Retry it."*

Either way the lesson leaves the transient status, the step strip shows where it stopped, and **Retry, Retry this
step and Delete all work again** — "Wait for the current job to finish" is now only ever about a job this instance is
really running. Set `QUEST_PIPELINE_WATCHDOG_ENABLED=false` to turn the sweep off (debugging only).

**"Type the text instead" is a pipeline too.** A lesson generated from typed text used to be one job with no ledger
and no deadline, so a hang there was the one case nothing could end. It now walks the same steps (Upload and Convert
are done by definition, Analyse runs Prompt A on the text, the skills need no confirming, and a level written by hand
is kept rather than regenerated), which means the same deadlines and the same step strip. A hand-written lesson has
no **Retry** — the way out is pressing *Generate from text* again, and the levels already written are not paid for
twice.

**How long is the worst case?** One step at a time: its deadline, times the number of attempts. A step retries a
transient failure **3 times** (`LessonSteps.TRANSIENT_ATTEMPTS`), each attempt bounded by the step's own deadline,
with `quest.pipeline.retry-delay-ms` (2 s) doubling between them — so a generate step is at most
**3 × 360 s + 6 s ≈ 18 minutes**, Analyse **3 × 240 s ≈ 12 minutes** and Convert **3 × 180 s ≈ 9 minutes**. A whole lesson that fails at the last step
is the sum of the steps before it. The sweep's own bound is different and smaller: it only ever waits one deadline +
60 s grace + up to one 60 s interval for a row *nothing in this process is holding*. If a lesson has been
`analyzing`/`generating` for more than 20 minutes, it is not slow — check the logs.

**Tokens of a call that was abandoned.** A step that is interrupted at its deadline while waiting for a model
answer books nothing for that call: no `usage` block ever arrived, so the server does not know what it cost and does
not guess. **The provider's bill will include it and `lessons.token_usage` will not** — the gap is one call per
abandoned step, and the Billing tab is per-school model tokens, not an invoice. Calls that *were* answered are
always booked, including when the attempt after them fails: Prompt A, Prompt B, Prompt C and the stop rewriter all
write what they have to the lesson on the way out.

To see what is stuck right now: `GET /admin/lessons` and look for `status` `analyzing`/`generating` with an old
`updatedAt`, or `currentStep` set. Nothing needs to be done by hand — wait one interval.

### Cleaning up acceptance data

Both are ADMIN, scoped with `X-School-Id`, and both are **hard** deletes:

```bash
curl -s -X DELETE "${AUTH[@]}" "$API/admin/lessons/$LESSON_ID"     # 204 · 409 while published (unpublish first)
curl -s -X DELETE "${AUTH[@]}" "$API/admin/children/$CHILD_ID"     # 204 · 404 for another school's child
```

Deleting a child removes her row and everything that was only ever hers — roster place, attempts, stop and lesson
completions, parent unlocks, stickers, streak, recordings and drawings, and her answers to teacher questions. For a
child who has simply left the school, use `PATCH /admin/children/{id}` with `{"active":false}` instead: she is
retired from the roster and her work is kept. The AI caches are keyed by file hash and survive both.

**The automated e2e suite needs `SEED_PROFILE=full`.** `e2e/` asserts against the 30-class school and the two-school
fixture; run it on the acceptance profile and it fails for want of data. Switching back to `full` re-seeds the
**default school's** 30 classes on the next boot — but **not** Al Noor and Green Valley, which the wipe deleted
outright and no seed re-creates. Run `node e2e/seed/seed.mjs` (see
[Seeding two schools](#seeding-two-schools-isolation-flags-and-themes)) to build that fixture again before relying on
a QA e2e run.

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

Seven workflows — `ci`, `ios`, `deploy-qa`, `deploy-production`, `rollback`, `migration-check`, `actions-cost` — plus
Renovate and Dependabot, which are configuration files rather than workflows ([deploy/README.md](../deploy/README.md)
has the deploy ones). The repository is private on the **GitHub Free** plan: 2,000 Actions minutes a month, a macOS
minute billed as ten Linux minutes, a Windows minute as two, and every job rounded up to the whole minute.

`.github/workflows/ci.yml` runs on every pull request and on the push that lands it on `develop` or `main` — nothing
else, so a branch is tested once per push, through its PR. Its first job, `changes` (ten seconds), reads the changed
paths and decides which of the others run. A push to `develop` or `main` skips that filter and runs everything,
because that run is what the deploy waits for.

| Job | Triggered by | What it runs |
|---|---|---|
| `changes` | always | `dorny/paths-filter`; every other job is gated on its outputs |
| `contract` | `shared-api/**` (or anything that triggers `server`/`app`) | `:shared-api:jvmTest` + publishes the contract to `~/.m2` for the server job |
| `server` | `server/**`, `shared-api/**` | `./mvnw test` twice: `-Dtest.excludedGroups=postgres` (H2, reports first), then `-Dtest.groups=postgres` (Testcontainers) |
| `app` | `shared/**`, `shared-ui/**`, `shared-api/**`, `androidApp/**`, `iosApp/**`, `desktopApp/**`, `webAdmin/**`, `design/tokens.json`, the Gradle files | common-metadata type-check (the iOS-facing sources), `:shared:desktopTest`, Android QA **debug** APK, the Wasm admin panel |
| `dashboard` | `dashboard/**`, `design/tokens.json`, `server/openapi.json`, `permissions.json` | generated API client, lint, Vitest, `pnpm build --configuration=qa`, tokens + fonts drift |
| `scripts` | `e2e/**` | `bash -n` and `shellcheck -S warning` over `e2e/*.sh` |
| `infra` | `infra/**`, `deploy/**`, `scripts/**`, `.github/**`, `Dockerfile` | `terraform fmt -check` + `validate` (no backend, no credentials) and `actionlint` |
| `docs` | nothing but documentation changed | the relative links in every `*.md` must resolve |
| `ci` | always | the aggregate: the single required status check |

`ci` is the only required check. It treats **`skipped` as a pass** — a job that was filtered out was not needed — and
`failure`, `cancelled` and `timed_out` as failures, so a cancelled job never counts as a tested one. A change to
`.github/workflows/ci.yml` itself is in every filter: editing CI runs all of CI.

Three of the `dashboard` filter's paths are outside `dashboard/`, because the dashboard is **generated** from them:
`server/openapi.json` (the API client, `pnpm gen:api`), `server/src/main/resources/permissions.json`
(`permissions.generated.ts`) and `design/tokens.json` (`_tokens.generated.scss`). A server-only change to any of the
three can break the Angular build with nothing under `dashboard/` having moved — which is how P4.0's `/schools/logo`
signature reached the QA image build as a `TS2769` with no CI signal at all.

The **`scripts` job** exists because the e2e shell scripts need a live server and a seeded fixture, so CI cannot run
them: it catches syntax errors and shellcheck warnings instead. `shellcheck` ships on `ubuntu-latest`, no suppressions
are expected, and `bash -n` is run one file at a time (it takes a single script; the rest would become its `$1`).

**Playwright** does not run on pull requests. It needs a browser download and a deployed target, and it runs against
the environment that was actually shipped: `deploy-qa.yml`'s `e2e` job, after the deploy, with `E2E_BASE_URL` set to
`vars.API_URL` (`pnpm e2e:qa` — the same suite against `<API>/dashboard/`, two retries, no dev server; an empty
`API_URL` fails the job rather than quietly starting a dev server on the runner). `deploy-qa.yml`'s `lighthouse` job
measures the same deployment in parallel — performance and accessibility ≥ 90 from `.github/lighthouserc.json`, a hard
gate, with the scores posted in the deploy comment.

Node is pinned by `.nvmrc` (22); pnpm by `dashboard/package.json`'s `packageManager` field, enabled with corepack. The
dev Mac runs Node 25, which only produces an engine warning.

### What a PR costs

Billed minutes, one job per line, measured over the twenty runs before the split (September 2026 numbers on a warm cache):

| Job | Before | After |
|---|--:|--:|
| `app` (Gradle: screenshots, APK, Wasm) | 7–12 | 7–12, and only for `shared/**`, `androidApp/**`, `iosApp/**`, tokens |
| `server` | 3 | 3, and only for `server/**` |
| `contract` | 1 | 1 |
| `dashboard` | 1 | 1, and only for `dashboard/**` |
| `scripts`, `ci`, `changes`, `infra`, `docs` | 2 | 1–3 |
| **A typical single-area PR** | **~16** | **~4–13** |
| **A docs-only PR** | **~16** | **3** (`changes` + `docs` + `ci`) |

The `app` job is where the minutes are: `:shared:desktopTest` renders 51 screenshots, and the Android APK and the
Wasm panel are two more full Kotlin compilations. Everything else together is under five minutes.

Caches, all keyed so a PR reads and only `develop` writes (`cache-read-only: ${{ github.ref != 'refs/heads/develop' }}`):

- **Gradle** — `gradle/actions/setup-gradle`, dependencies and the build cache. `--no-daemon` is deliberately *not*
  passed any more, so the three `./gradlew` invocations in the `app` job share one warm daemon.
- **Maven** — `actions/setup-java` with `cache: maven`. `server/.mvn/maven.config` adds `--batch-mode`,
  `--no-transfer-progress` and `-T1C` to every invocation, CI and local alike.
- **pnpm** — `actions/setup-node` with `cache: pnpm`, keyed on `dashboard/pnpm-lock.yaml`.
- **Docker** — `docker/build-push-action` with `cache-from: type=gha` / `cache-to: type=gha,mode=max` (deploy only;
  PRs never build or push the image).
- **Playwright browsers** — `~/.cache/ms-playwright`, keyed on the lockfile, in the QA e2e job.
- **Terraform providers** — `TF_PLUGIN_CACHE_DIR`, keyed on `.terraform.lock.hcl`.

The Android SDK is *not* cached: `ubuntu-latest` ships the platforms and build-tools this project needs, and a cache of
`$ANDROID_HOME` would be slower to restore than the preinstalled copy. The Gradle **configuration cache** is off, and
`gradle.properties` says why: two ad-hoc tasks (`:shared-ui:generateDesignTokens`, `:webAdmin:generateConfig`) capture
their build script in a closure, which it cannot serialize.

Every job has a `timeout-minutes` (30 for macOS and the image/APK builds, 10–20 for the rest) so a hung run cannot burn
an afternoon, and both `ci.yml` and `migration-check.yml` cancel the previous run of the same ref when you push again.

### iOS, on macOS, never on a PR

`ios.yml` is the only workflow that touches a macOS runner. It runs on pushes to `develop` and `main` and on `v*` tags,
and even then only when the push touched `iosApp/**`, `shared/**`, `shared-ui/**`, `shared-api/**` or the Gradle files —
a ten-second Linux job makes that call before ten macOS minutes are spent. Pull requests instead type-check the
iOS-facing sources on Linux (`:shared:compileCommonMainKotlinMetadata`): Kotlin/Native's Apple targets need a macOS
host, so `:shared:compileKotlinIosSimulatorArm64` does not exist on a Linux runner at all.

```bash
gh workflow run ios.yml --ref <branch>   # on demand, before a risky iOS change lands
gh run watch
```

### Watching the bill

`actions-cost.yml` runs at 06:00 UTC every Monday (and on demand) and writes a per-workflow table of the last seven
days to the run's job summary, projecting the month. Over 1,500 minutes it opens — or comments on — an issue labelled
`infra`. It computes the minutes itself from each job's start and finish, rounded up and multiplied by the runner rate,
because `/actions/runs/<id>/timing` answers `total_ms: 0` on this account.

```bash
gh workflow run actions-cost.yml -f days=30
gh run view --job <id> --log | grep "Cache restored"   # did the caches hit?
```

**If Actions stops running altogether** — every run failing in seconds, or "the job was not started" — it is billing,
not the workflows. On github.com: your avatar → **Settings** → **Billing and licensing** → **Spending limits**, and
either raise the limit or clear the outstanding balance; Actions resumes on the next push, and nothing in the repo
needs changing. The monthly free allowance also resets on the account's billing date.

**Renovate** (`renovate.json`) groups minor and patch bumps into one weekly PR per ecosystem (dashboard npm, server
maven, gradle, github-actions) on `before 6am on monday`, keeps majors ungrouped and labelled `major`, and leaves
Terraform to Dependabot so the two bots never open the same PR. It only runs once the **Renovate GitHub App is
installed on the repository** — until then the file is inert and nothing opens those PRs.

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
