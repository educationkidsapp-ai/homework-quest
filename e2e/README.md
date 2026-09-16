# e2e — QA fixture, cross-school isolation, flags and themes

Four scripts, no dependencies beyond Node 22 (built-ins only), `bash`, `curl` and — optionally — `jq`:

| File | What it does |
|---|---|
| `seed/seed.mjs` | Creates the two-school fixture (schools, **a distinct theme per school**, staff, one published manual lesson per school, a parent and a child per school) and writes the ids to `e2e/.seed.json`. Idempotent. |
| `isolation.sh` | Reads `e2e/.seed.json` and asserts prompt §2 / §10 isolation over the API. One `PASS` / `FAIL` / `BLOCKED` line per assertion. |
| `flags.sh` | Reads the same file and asserts §4 feature flags, §3 school themes and §A platform settings. Flips state and puts it back. |
| `themes.sh` | Reads the same file and asserts the two seeded themes on the **public** routes. Read-only: no token, nothing to restore. |

The fixture:

| | School A | School B |
|---|---|---|
| name / code | Al Noor School · `ALNOOR` | Green Valley School · `GREENV` |
| curricula / grades | british, american · 1–3 | british · 1–2 |
| theme `appName` / font | "Al Noor" · `nunito` | "Green Valley" · `fredoka` |
| teacher | `teacher.a@alnoor.test` — math, british, grades 1–2 | `teacher.b@greenvalley.test` — english, british, grade 1 |
| managerial | `manager.a@alnoor.test` | `manager.b@greenvalley.test` |
| lesson (manual, published) | british/1/math "Counting by 2s — Al Noor" | british/1/english "The sh sound — Green Valley" |
| parent · child | `parent.a@alnoor.test` · Aya (british, grade 1) | `parent.b@greenvalley.test` · Bilal (british, grade 1) |

Classes are not created directly: `AdminLessonService.create()` puts every new lesson in the class for its
(school, curriculum, grade, subject) and makes one when the school has none — before the lesson row is even saved.
`publish()` touches no class.

## Environment

Nothing below is ever printed by any of the four scripts. Pass them in the shell; do not commit them.
(`themes.sh` reads only `E2E_BASE_URL`, `E2E_SEED_OUT` and `E2E_NO_JQ`: it needs no credential.)

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
| `E2E_RESEED_THEMES` | unset | set to `1` to make `seed.mjs` `PUT` each school's theme again instead of skipping it — use it after editing a palette in `seed.mjs` |
| `E2E_NO_JQ` | unset | set to `1` to force the node JSON reader in `isolation.sh`, `flags.sh` and `themes.sh` even where `jq` exists |

Exit codes — all four scripts: `0` everything held, `1` a failure or an unexpected response, `2` complete as far as
the API allows (each `BLOCKED` line says why).

`flags.sh` needs `node` whichever reader is chosen: jq's `//` is "alternative", not "default", so `.enabled // empty`
answers empty for a flag that is switched **off** and `.schoolId // empty` cannot tell §4's "for all schools" row
(`school_id` null) from a row that is not there. Every boolean and nullable field is read with a small node helper.

## Against a local H2 server

```bash
export JAVA_HOME=/Users/kareemshehab/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"   # `java` must be 21: JAVA_HOME alone only steers ./mvnw, not the `java` below
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
e2e/themes.sh
e2e/isolation.sh
e2e/flags.sh
```

Run `themes.sh` outside `flags.sh` rather than during it: `flags.sh` deliberately writes a throwaway theme over school
A's for the length of its assertion (g), and puts the seeded one back afterwards.

The H2 database is in memory: restart the server and the fixture is gone, so run the seed again. Re-running it against
a live server is safe — every step finds its row and skips.

## Against QA

QA is Cloud Run and may have scaled to zero; the first request then waits for a cold start. All four scripts retry a
transport error (curl exit 6, 7, 28, …) or a 502/503/504 four times with a growing backoff — `seed.mjs` in `call()`,
`isolation.sh`, `flags.sh` and `themes.sh` in `req()` — so one cold start is not a failure. Waking QA (`infra/env.sh qa wake`) is
the planner's call; do not run it from here. If QA still answers 5xx or times out after that, report it and finish the
local run.

```bash
export E2E_BASE_URL=https://homework-quest-api-625882725080.me-central1.run.app
export E2E_ADMIN_EMAIL=admin@quest.local
export E2E_ADMIN_PASSWORD="$(grep -E '^ADMIN_PASSWORD=' .env | cut -d= -f2-)"   # git-ignored .env, never echoed
export E2E_STAFF_PASSWORD='<a throwaway you generate>'
export E2E_PARENT_PASSWORD='<a throwaway you generate>'

node e2e/seed/seed.mjs
e2e/themes.sh
e2e/isolation.sh
e2e/flags.sh
```

Parents are Firebase Auth accounts, not dashboard users. Off localhost the scripts register (or sign in) the seed's
throwaway parent through the Identity Toolkit REST API with the web key in `androidApp/src/qa/google-services.json` —
the same path the Android app takes. The accounts it creates are `parent.a@alnoor.test` and `parent.b@greenvalley.test`
in the `homework-quest-qa` Firebase project; delete them there when the fixture is no longer wanted.

Every script refuses early, with the deployed commit in the message, when the target is too old for what it asserts.
`seed.mjs` and `isolation.sh` want P1.3 `backend/dashboard-auth` — `GET /auth/sign-in` answers 405 wherever that route
is mapped and 401/403/404 where it is not. `flags.sh` wants P2.1 `backend/flags-themes-settings` and probes
`GET /schools/{A}/flags`, which is a 404 before it and a 200 after.

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

## Flags and themes

`e2e/flags.sh` covers §4 (the feature matrix), §3 (per-school white label) and §A (the platform's own name) against
the same fixture. It needs the same environment as `isolation.sh` — `E2E_BASE_URL`, `E2E_ADMIN_PASSWORD`,
`E2E_STAFF_PASSWORD` (teacher A and managerial A), and a parent token, which is used for one assertion: that a
parent reads the same public flag set an anonymous caller does. Without one that assertion is `BLOCKED` and the rest
of the run is unaffected:

```bash
node e2e/seed/seed.mjs      # idempotent; run it first, the script reads e2e/.seed.json
e2e/flags.sh
```

What it asserts, in order — one `PASS` / `FAIL` / `BLOCKED` line each:

| | Assertion |
|---|---|
| a | `GET /schools/{A}/flags` is 14 keys with the seeded defaults — the 10 on and 4 off of `V5__flags_themes.sql`, which are also `DEFAULT_FLAGS` in `shared-api` — and carries an `ETag`; parent A reads the identical set with her own token |
| b | ADMIN flips `certificates` **off** for A: the next public fetch says `false`, school B is untouched, and `GET /admin/flags/audit` gains a row with the ADMIN actor and `schoolId == A` |
| c | a route the flag guards is 404 for A's users and 200 for B's |
| d | flipped back **on**: 200 again, no restart and no rebuild |
| e | `PUT /admin/flags/certificates/all {false}` turns both schools off and leaves exactly **one** audit row with `schoolId` null; `{true}` puts them back |
| f | teacher A's `PUT …/flags/{key}` is 403, and managerial A's `GET /admin/flags` is all 14 definitions but only her own school's row |
| g | theme: `primaryInk #EC3013` on `primary #F3F2F2` is a 400 naming the pair and the ratio; `logoUrl: "javascript:alert(1)"` is a 400; a valid theme is 200, reaches the public `GET /schools/{A}/theme` with an `ETag`, and a second request with `If-None-Match` is 304 |
| h | `GET /platform-settings.name` is `Schools Dashboard`; teacher A's `GET /me.platformName` is the school theme's `appName` while one is set and falls back afterwards; `PUT /admin/platform-settings {name}` changes the public name |

**(c) is `SKIPPED` today** — `SKIPPED (no flagged route yet — P4.0 adds teacherQuestions/announcements)`. Nothing in
the deployed API carries `@FeatureFlag`: `FeatureFlagCoverageTest` exempts the eleven phase-1 controllers and lists
`FlagController`, `ThemeController` and `PlatformSettingsController` as infrastructure, and the only annotated
handlers in the tree are in `FeatureFlagInterceptorTest`'s own test controller, which is not part of the application.
The script decides this from `/v3/api-docs` rather than from the source, so the skip turns into a `FAIL` naming the
new route the moment a flagged path is published — that is the cue to assert the 404 here.

**It puts everything back, and proves it did.** An `EXIT`/`INT`/`TERM` trap restores `certificates` for both schools
to the value they had when the run started, writes school A's theme back exactly as `GET /admin/schools/{A}/theme`
answered at the start, and sets the platform name back — so an interrupted run leaves no drift either, and
`isolation.sh` still passes afterwards.

Each restore write is then **read back and compared**, because a PUT's status alone proves nothing:

- read-back matches what was wanted → a `note` line at most, and the run keeps the exit code its assertions earned.
  A restore write that failed while the value is already right (the write that would have dirtied it failed too) is
  not a dirty environment and is not reported as one;
- read-back differs → a `FAIL restore …` line naming the thing, what it reads now and what it should read, the
  closing line says `IS NOT BACK AS IT WAS`, and the run exits non-zero **whatever the assertions said**. The
  "are back as they were" sentence is printed only when every read-back agreed.

Two things it cannot take back, both by design:

- the `flag_audit` rows, which are append-only — that trail is what assertion (b) is about;
- a school that had **no** `theme_json` ends with an explicit copy of what it was already being shown (the platform
  default). There is no `DELETE` for a theme; the rendered result is identical.

The theme (g) writes is a coherent dark set — `primary`/`ground` `#1F2A44`, `primaryInk` `#FFFFFF`, `accent`
`#FF8A65`, `mascotColor` `#7FB3D5`, `appName` "Al Noor QA Flip" — not `primary`/`primaryInk` on their own. §3 measures
`primaryInk` on **both** `primary` and `ground` (see `ThemeDto.SchoolTheme`: "primary is a light brand surface rather
than a saturated fill"), so white ink over a dark navy surface is only valid when the ground goes dark with it, and
on that ground the default red accent is 2.7:1 and the default mascot blue is under the 3:1 non-text bar.

That `appName` is deliberately **not** school A's seeded one ("Al Noor"): assertion (h) is that `GET /me.platformName`
follows the theme's name, and a test name equal to the seeded name would hold whether or not the save did anything.

## School themes (§3, §10 acceptance 1)

`seed/seed.mjs` gives each school its own theme with a `PUT /admin/schools/{id}/theme` right after the school exists —
"two schools in QA with different logos and colours". They share no colour, no font and no world palette:

| | School A — `ALNOOR` | School B — `GREENV` |
|---|---|---|
| `appName` | Al Noor | Green Valley |
| `fontChoice` | `nunito` | `fredoka` |
| `ground` / `primary` | `#0E3B2E` / `#145C46` (deep green) | `#0B2A45` / `#123C5F` (navy) |
| `primaryInk` | `#FFFFFF` | `#FFFFFF` |
| `accent` | `#F5B971` (warm amber) | `#7FD3E8` (teal) |
| `softBorder` | `#2A6B57` | `#21506E` |
| `mascotColor` | `#7FD1AE` | `#4FB3C9` |
| `worldPalettes.math` | green — `#3FAE8B` / `#1E7A5E` / `#E3F5EE` / `#123B2E` | cyan — `#5EC8D8` / `#2A8FA5` / `#E5F6FA` / `#0E3542` |
| `worldPalettes.english` | amber — `#F0A868` / `#C2762F` / `#FDF1E3` / `#4A2C10` | periwinkle — `#9AA7FF` / `#5566D8` / `#ECEEFF` / `#1C2250` |
| `logoUrl` | `https://placehold.co/256x256/0E3B2E/FFFFFF.png?text=AN` | `https://placehold.co/256x256/0B2A45/FFFFFF.png?text=GV` |

**The logos are public `placehold.co` placeholders**, which is acceptable for a QA fixture and nothing else: they are
https (the only scheme `SafeText` accepts), they are stable, and they need no bucket, no upload route and no
credential. A real school's logo is uploaded by its Admin. Both URLs are well inside the 2000-character cap, and the
two are visibly different — the dark rectangle carries "AN" and "GV" respectively — so a screenshot set makes a
cross-tenant theme leak obvious without reading ids.

Both palettes were chosen dark-on-dark deliberately, for the same reason `flags.sh`'s test theme is: §3 measures
`primaryInk` on **both** `primary` and `ground`, so a dark brand surface forces a light ink, which forces a dark
ground, which forces a light accent and mascot.

**The seed computes the contrast itself and refuses to send a theme that would fail.** `assertThemeValid()` in
`seed.mjs` restates the WCAG 2.2 §1.4.3 relative-luminance formula — the same arithmetic as the server's
`quest.server.platform.Contrast` — and checks the colour syntax, the `SafeText` caps (`logoUrl` https-only ≤ 2000,
`appName` ≤ 60) and all six pairs in the order `ThemeService` reports them:

| Pair | Bar | School A | School B |
|---|---|---|---|
| `primaryInk` on `primary` | 4.5:1 | 7.9:1 | 11.4:1 |
| `primaryInk` on `ground` | 4.5:1 | 12.5:1 | 14.7:1 |
| `accent` on `ground` | 4.5:1 | 7.2:1 | 8.7:1 |
| `mascotColor` on `ground` | 3:1 (non-text, WCAG 1.4.11) | 6.9:1 | 6.0:1 |
| `math.ink` on `math.soft` | 4.5:1 | 11.0:1 | 11.8:1 |
| `english.ink` on `english.soft` | 4.5:1 | 11.4:1 | 13.1:1 |

So a palette edited above the bar fails **locally, before a request goes out**, naming the pair and the ratio the way
the server would:

```
FAILED: school A theme: accent #2E5C46 on ground #0E3B2E is 1.6:1, needs 4.5:1
```

**Idempotence.** The theme step is a single `GET /admin/schools/{id}/theme` on a re-run: when the school already
answers this `appName`, the `PUT` is skipped and the run prints `theme ALNOOR: found "Al Noor"`. After editing a
palette, force the write with `E2E_RESEED_THEMES=1`. The applied `appName`, `primary`, `accent`, `ground`,
`mascotColor`, `logoUrl` and `fontChoice` are written to `e2e/.seed.json` and printed in the id table, which is where
`themes.sh` reads its expectations from — the palettes live in exactly one place.

### `e2e/themes.sh`

Public routes only, so it needs no password at all — just `E2E_BASE_URL` and a seeded `e2e/.seed.json`:

```bash
node e2e/seed/seed.mjs      # idempotent
e2e/themes.sh
```

| | Assertion |
|---|---|
| a | `GET /schools/by-code/ALNOOR` carries school A's `theme.appName`, `theme.primary` and `theme.logoUrl`, and `…/GREENV` carries school B's — `JoinSchoolInfo.theme` travels with the by-code answer so the app's join step can run its colour transition without a second request |
| b | the two schools' `appName`, `primary` and `logoUrl` all differ |
| c | `GET /schools/{A}/theme` and `GET /schools/{B}/theme` are each the seeded theme, each carries an `ETag`, and each answers 304 to a second request with `If-None-Match` |
