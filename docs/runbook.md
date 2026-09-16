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

`ADMIN_JWT_SECRET` has a placeholder default in `application.yml` so a developer can boot without one. **Any deployed
environment must set it** — Terraform does, from `random_password.jwt`.

Terraform wires `DB_PASSWORD`, `ADMIN_JWT_SECRET`, `DEEPSEEK_API_KEY` and `ADMIN_PASSWORD` into Cloud Run as required
secrets, plus `ANTHROPIC_API_KEY` and `FIREBASE_CREDENTIALS` once a value exists. `MAIL_PROVIDER`, `RESEND_API_KEY`,
`MAIL_FROM` and `DASHBOARD_URL` have Terraform variables but no value in QA, so QA runs the log mailer and builds
links from `PUBLIC_URL`. Setting them is an `infra` package (phase 5's `infra/mail-push` covers the mail three).

`infra/terraform/main.tf` also still sets a `PLATFORM_NAME` variable on the container. The server ignores it — the
product name is a database row (see [Platform settings](#platform-settings)) — and removing it is an `infra` change.

## Seeding two schools, isolation, flags and themes

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
