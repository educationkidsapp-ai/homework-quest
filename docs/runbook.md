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

### What QA holds: the owner's school, no seed

Since 2026-10-03 the product serves **one school**, and QA holds the owner's real one — **Learning International
School**, created through the setup wizard after the acceptance seed was wiped. Treat QA data as the owner's:

- **No seed.** `envs/qa.tfvars` sets `seed_school = false`, so `SchoolSeed` and `AttemptSeed` do nothing on boot. The
  line is load-bearing: the `qa` Spring profile defaults `SEED_SCHOOL` to `true`, so removing it would turn the seed
  back on (and into a `default` school that no longer exists). `seed_profile` stays in the file but is inert.
- **`SEED_RESET` must never be used on QA again without the owner.** It ignores `SEED_SCHOOL`, and it deletes every
  school that is not `default` *entirely* — which is now the owner's school, with its staff, children and lessons.
- **Verify features locally**, not on QA: `server/run-local.sh` (H2, the `h2` profile seeds the 30-class school) and
  the scripts in [e2e/README.md](../e2e/README.md) — "Against a local H2 server", and `e2e/local/parent-flows.sh` for
  the staff side of a manual app pass. The Playwright job against QA stays parked (`E2E_ON_QA=false`); never create
  e2e fixture accounts or schools on QA.

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

Four dashboard roles — `ADMIN`, `TEACHER`, `MANAGERIAL`, `COORDINATOR` — plus `PARENT` (Firebase) and `PUBLIC`
(no token).

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

### The coordinator (R2)

A **coordinator** supervises one subject — `math`, `english`, `french`, `science`, `religion` or `arabic` — for one
curriculum track (`american` / `british`) or for both, across every grade and every class of her school. She is
**read-only on teaching data**: her area is `/coordinator/**` and it serves nothing but `GET`s
(`CoordinatorScopeArchitectureTest` fails the build on the first write added under it). Her writes are communication
— chat, complaint status, announcements — and arrive with R4.

- **Her scope is rows, not a claim.** `staff_scopes (user_id, subject, curriculum)` — `curriculum` NULL meaning both
  tracks — and a person may hold several. `CoordinatorScope` reads them by the caller's own user id and never from a
  request, so a section is hers only when somebody teaches one of her subjects in it and the track matches. A section,
  lesson or child outside that is **403**; another school's is **404**, as everywhere else.
- **The same table carries the department managers.** A MANAGERIAL row has `subject` NULL and `curriculum` set, which
  is a department (British / American). The seed writes those rows; the manager's own reads are RM1.
- **Routes**: `GET /coordinator/me` (scope + counts), `/coordinator/teachers`, `/coordinator/classes`,
  `/coordinator/calendar?from&to` (every class in scope, day by day, ≤ 62 days), `/coordinator/lessons` and
  `/coordinator/lessons/{id}` (the teacher's own lesson view, read-only; `status` is `draft`, `ready`, `published` or — S1 — any lesson status word
  such as `needs_review`, `error` or `review`, each meaning itself; only an unknown word is 400)
  and `/coordinator/lessons/{id}/status` (R3 — E1's poll, the same `LessonStatusView` the teacher's and the Admin's
  `/status` routes answer, so her read-only lesson page never ticks against a `/teacher` route her role is refused at
  the matcher; `coordinator.lesson.read`, no flag, because the subject of the route is the lesson itself).
- **Keys**: `coordinator.read` and `coordinator.lesson.read` (ADMIN + COORDINATOR), `coordinator.manage` (ADMIN only —
  a coordinator cannot widen her own scope). She also holds `me.*`, `auth.changePassword`, `notifications.*`,
  `chat.socket` and the two `media.*` reads, and no `*.write` key of the teacher's at all.
- **Accounts**: `POST /admin/coordinators {email, fullName, scopes:[{subject, curriculum?}]}` answers the one-time
  password in the body and nowhere else, `PUT /admin/coordinators/{id}/scopes` replaces her whole set, and
  `GET /admin/coordinators` lists them. Both writes leave an `audit_log` row (`coordinator.create`,
  `coordinator.scopes`).

> The role was added to the `users.role` CHECK by `db/vendor/{postgresql,h2}/V19__coordinator_role.sql` — the one
> migration outside `db/migration`, because the constraint `V4` created is anonymous and the two engines need
> different dynamic SQL to find it. `spring.flyway.locations` carries Flyway's `{vendor}` placeholder for it; put
> ordinary migrations in `db/migration`. `migration-check.yml` checks both directories alike — no edit to a merged
> file, a copy for every engine, no version shared with `db/migration` — and applies the PostgreSQL copy on real
> PostgreSQL 16; a vendor file may drop a *constraint* (rollback-safe) and still never a table, a column or a name.

Neither coordinator controller carries a `@FeatureFlag`: both are listed in `FeatureFlagCoverageTest.INFRASTRUCTURE`
beside `TeacherController` and `TeacherAdminController`, because `/coordinator` is the dashboard of a role rather than
one feature of it, and the Admin half is how a coordinator comes to exist at all. Her *features* stay flagged where
they live (R3's `gradebook` and `exams`, R4's `chat`, `complaints` and `announcements`).

**R3 — the teacher's numbers, read through her scope.** `GET /coordinator/classes/{id}/attendance?from&to` (one
per-day register per day of the window; the week ending today when both bounds are absent, ≤ 62 days),
`/coordinator/classes/{id}/results` and `/coordinator/lessons/{id}/results` (§7's gradebook grid and per-lesson
results), `/coordinator/children/{id}` (§7's child page) and `/coordinator/classes/{id}/exams` +
`/coordinator/exams/{id}/results` (§8's Exams tab and results). Each one resolves the id it names through
`CoordinatorScope` — `requireSection` for a class, `requireLesson` for a lesson **or an exam**, so an english exam
parked in a section the maths coordinator supervises is still a 403, `requireChild` for a child — and then hands the
resolved row to the very service the teacher's route uses, so there is no second gradebook to drift out of step with
§7's and `CoordinatorReadsApiTest` asserts the two bodies are the same JSON. The keys continue R2's family:
`coordinator.attendance.read`, `coordinator.results.read` and `coordinator.exams.read` (ADMIN + COORDINATOR, no
`write` sibling), so an owner can close the gradebook to coordinators without closing the area. The five flagged reads
carry `gradebook` and `exams` on the handler — the same keys the teacher's `GradingController` and `ExamController`
carry, so a school with a feature off answers 404 to both roles — and the register carries none, because
`AttendanceController` carries none and `FlagKeys` has no key for taking a register.

**Her writes (R4, DR3/DR4).** `/coordinator` stopped being read-only with the communication half, and with exactly
five writes: `POST /coordinator/chat/threads` (her thread with the manager of her department),
`POST /coordinator/chat/threads/{id}/messages`, `POST …/read`, `PATCH …/status` (`open` / `resolved` on a complaint)
and `POST /coordinator/announcements`. The keys are `coordinator.chat`, `coordinator.complaints` and
`coordinator.announce` (ADMIN + COORDINATOR); the chat four carry the `chat` flag and the announcement two carry
`announcements`, the very keys the parent's and the teacher's halves of those features carry.
`CoordinatorScopeArchitectureTest` names those five routes in an allow-list, so a sixth write under `/coordinator`
fails the build until somebody argues for it.

**Three small things RM1 added to her half** (the dashboard's Messages and Complaints screens need them):
`GET /coordinator/managers` answers the managers whose department intersects her scope, each with that department
(`{userId, displayName, curriculum}`, key `coordinator.chat`, behind the `chat` flag) — the list a `managerUserId` for
`POST /coordinator/chat/threads` has to come from, so a coordinator of both tracks is offered both managers and the
platform ADMIN reading her area is offered nobody. `ChatThread` gained **`parentName`**, so a Complaints inbox can name
who wrote: a parent signs in through Firebase and the `parents` table holds no display name, so it is her registered
address, falling back to the one a teacher typed on the roster (`children.parent_email`) and absent on a staff-to-staff
thread; it is resolved in one statement per page of rows, never one per thread. And `CoordinatorDto`'s
`CreateAnnouncementRequest` now carries `@Schema(name = "CoordinatorCreateAnnouncementRequest")`: springdoc keys
`components/schemas` by *simple* name, so it collided with the teacher's record of the same name and `openapi.json`
documented `POST /coordinator/announcements` with the teacher's `classId` instead of her `classIds`.
`OpenApiContractTest.the_two_announcement_bodies_are_two_schemas` fails the build if either name is dropped.

**Scoped twice: a section is not a subject.** `requireSection` only proves that *somebody* teaches one of her subjects
in a section, so in a section that teaches two the teacher's own body carries both — every published lesson of it, an
exam of each, a `ChildLevel` per subject. The three reads that answer for a whole section (`classes/{id}/results`,
`classes/{id}/exams`, `children/{id}`) therefore narrow again through `CoordinatorScope.subjectsIn`, which reduces the
section's teaching assignments by the same rule `requireLesson` applies to one lesson, and pass the result to the
delegate as a `CoordinatorScope.Subjects`. So the maths coordinator of a maths-and-english section sees no english
column, no english exam in the tab (the tab lists exactly the exams she may open) and no english level on a child
page; the label on her gradebook comes from her scope rather than from `TeacherScope.subjectOf`'s "the section's first
assignment" guess, which could otherwise name a subject she does not coordinate. Every other caller passes
`Subjects.ALL`, so a teacher's, a manager's and the platform ADMIN's bodies are byte for byte what they were.
The register is not narrowed — a register is per child per day and has no subject.

### The department manager (RM1)

A **manager** runs one department — a curriculum, `british` or `american` — across **every grade** of her school: every
class, every subject, every teacher and every coordinator of that track, and nothing of the other one. She is
`Role.MANAGERIAL`, which existed before RM1 as the School/Teachers/Complaints role; RM1 gives her the department scope
and the area at `/management/**`. `ManagerScope` is `CoordinatorScope` one axis over — a coordinator is narrow in
subject and wide in grade, a manager wide in subject and narrow in track — and RM1 is read-only, so
`ManagerScopeArchitectureTest` fails the build on the first write added under `/management` that is not on its
allow-list (RM5's staff register is on that list with the argument for it; RM2's broadcasts and chat join it).

- **Her scope** is the `staff_scopes` rows with **no subject**: `subject NULL, curriculum = <department>`, the same table
  a coordinator's `(subject, curriculum)` rows live in and the very rows `SchoolSeed` writes from `managers.csv`.
  `ManagerScope.departments` reads them by the caller's own user id, never from a parameter, and a section is hers when
  its curriculum is one of them — so every delegate is handed `Subjects.ALL`, because she manages every subject of the
  track. A coordinator is hers when one of her subject rows names a department of hers **or names no track at all**: a
  both-tracks coordinator reports to both managers (DR5).
- **Routes**: `GET /management/me` (departments + five counts), `/management/coordinators`, `/management/teachers`,
  `/management/classes` (grouped by grade), `/management/calendar?from&to` (≤ 62 days), `/management/lessons`,
  `/management/lessons/{id}` and `/management/lessons/{id}/status` (the teacher's own read-only lesson view), the six
  numbers reads (`/management/classes/{id}/attendance`, `…/results`, `…/exams`, `/management/lessons/{id}/results`,
  `/management/exams/{id}/results`, `/management/children/{id}`) and `GET /management/stats?from&to`.
- **Keys**: `management.read`, `management.lesson.read`, `management.attendance.read`, `management.results.read` and
  `management.exams.read` (ADMIN + MANAGERIAL, no `write` sibling), plus `manager.manage` (ADMIN only — a manager cannot
  widen her own department). `SecurityConfig` matches `/management/**` to ADMIN + MANAGERIAL, so a TEACHER or a
  COORDINATOR is turned away at the matcher rather than by a key.
- **Accounts**: `POST /admin/managers {fullName, email, curriculum}` answers the one-time password in the body and
  nowhere else, `PUT /admin/managers/{id}/scopes {curricula:[…]}` replaces her whole set of departments (her subject
  rows, if a school ever writes one, are left alone) and `GET /admin/managers` lists them. Both writes leave an
  `audit_log` row (`manager.create`, `manager.departments`). Before RM1 the seed was the only way a MANAGERIAL account
  could come into being.
- **`GET /me`** carries `departments` for a MANAGERIAL caller — `assignments`' sibling for the third role, so the
  Management area can label itself without a second request. Her *numbers* stay on `/management/me`.
- **Her people, and the one thing she writes (RM5).** `GET /management/staff-attendance?day=` is the staff register:
  the teachers (`teachersOf`) and coordinators (`coordinatorsOf`) of her department for one day, each with that day's
  status — `present`, `absent`, `late` or `leave`, and **absent from the row while nobody has marked her**, never
  `present` by default. `PUT /management/staff-attendance?day=` upserts `[{userId, status, note?}]` into
  `staff_attendance` (V21, unique on `(user_id, date)`) and answers the whole roster back. **The day rule:** a day may
  be marked only when it is a teaching day of the school (`SchoolCalendar`, so a school that runs Monday–Friday is not
  measured against the Gulf week) and is **not after today in the school's own zone** — today included, so the register
  is taken on the morning it belongs to; both refusals are 400 and the roster's own `editable` flag is what a screen
  offers the marking on. A `userId` of the other department is a 403 and writes nothing. `…/summary?month=YYYY-MM`
  counts each person's four statuses, the teaching days nobody marked and a rate (`(present + late) / marked`, absent
  where nothing was marked, and the window stops at today in the month still running); `…/{userId}?from&to` is one
  person's marked days, capped at 62. The directory is `GET /management/people/children?classId&q`, `…/teachers` and
  `…/coordinators`, all department-scoped and paged (`page`, `size` ≤ 100, with a `total`), `q` matching a name or an
  address. A child's row carries **two** addresses — the account her parent signed up with and the roster's own
  `children.parent_email` — plus, since MH1, `parentPhone` and `parentId` (see "Telephone numbers" below). Keys:
  `management.staff.attendance` (the read and the write share one) and `management.people`, both ADMIN + MANAGERIAL.
  `PUT /management/staff-attendance` is the only write in the whole namespace and is named in
  `ManagerScopeArchitectureTest.ALLOWED_WRITES`; `ManagementPeopleController` joins the two INFRASTRUCTURE names below
  for the register's reason — taking register is not an optional feature and `FlagKeys` has no key for it.
- **The numbers are the teacher's numbers.** `ManagementService` resolves the id through `ManagerScope` and hands the
  resolved row to the service the teacher's own screen reads; `ManagementApiTest` asserts the bodies are the same JSON,
  register included. `ManagementController` and `ManagerAdminController` are in
  `FeatureFlagCoverageTest.INFRASTRUCTURE` for the coordinator pair's reason; the flagged half is
  `ManagementReadsController`, where every handler names `gradebook` or `exams`.

### Telephone numbers (MH1, V24)

The owner's second manager list opens with a number beside every name: her coordinators (item 3), her teachers (item 4)
and her children's parents (item 5), each with a direct message. Until V24 no table held one at all, which is why RM5's
directory said so in its own contract.

`users.phone` and `parents.phone` are nullable and 20 characters — E.164's own maximum, `+` and at most fifteen digits.
**What is stored is the normalised number, never what was typed:** `quest.server.platform.Phones` drops the separators a
person uses (spaces, dashes, brackets, dots), reads a leading `00` as `+`, and refuses anything else — 7 to 15 digits, a
400 otherwise. An **empty string clears** the number; `null` means "not in this request", as it does for every other
field of a PATCH. Nothing here knows which countries exist: the school types half a dozen formats and a server that knew
each of them would refuse the one it had not heard of.

| Where a number is set | Who |
|---|---|
| `PATCH /me {phone}` | any dashboard user, her own (`me.update`) |
| `PATCH /admin/users/{id} {phone}` | the Admin, on anybody's — the only update route a coordinator's or a manager's account has |
| `POST /admin/teachers`, `PATCH /admin/teachers/{id}` | with the teacher's account |
| `POST /admin/coordinators`, `POST /admin/managers`, `POST /admin/schools/{id}/users` | with the account |
| `PATCH /admin/coordinators/{id}`, `PATCH /admin/managers/{id}` | the Admin, on either account (MA1) |
| `POST /admin/workers`, `PATCH /admin/workers/{id}` | a worker's own number — she has no account to carry one (MA1) |
| `POST /admin/children`, `PATCH /admin/children/{id} {parentPhone}` | the parent's, from the Children & parents page (MA1) |
| `seed/teachers.csv`, `managers.csv`, `coordinators.csv` | an **optional trailing `phone` column** — a file without it loads exactly as before |
| `PATCH /parent/me {phone}` | the parent, hers and only hers (`parent.me.write`) |

**The seed owns the numbers it names.** `SchoolSeed` re-applies the `phone` column on **every boot**, exactly as it
re-applies `SEED_STAFF_PASSWORD`, so a staff number edited on QA is put back to the fixture value on the next deploy.
That is deliberate — the acceptance seed is the owner's known-good starting point and a half-edited fixture is worse
than a restored one — and the way out is the same as for every other seeded field: a file with **no** `phone` column
touches nobody's number, so drop the column from the three CSVs when QA's own numbers are the ones that matter.

`GET`/`PATCH /parent/me` is new: a parent had no route of her own, because she is a Firebase identity with a `parents`
row rather than a `users` row and `/me` is dashboard roles only. It answers `{parentId, email, phone}` and `phone` is
the one field she may change — the address is her sign-in identity. `SecurityConfig` gives `/parent/**` to `PARENT`
alone.

Read back on `GET /management/coordinators`, `/management/teachers`, `/coordinator/teachers` (each `+phone`) and
`GET /management/people/children` (`parentPhone`, and `parentId` — **absent exactly when nobody has registered for that
child**, which is the one flag the "message the parent" button needs). `/management/teachers` also gains
`coordinators: [{userId, displayName, subject}]`: the coordinator(s) above that teacher, matched slot by slot — a
coordinator covers a (section, subject) when her `staff_scopes` row names that subject and either the section's track or
no track at all. It costs no extra statement and is empty on `/coordinator/teachers`, where the reader is the person the
list would name.

**The manager writes to a parent** with `POST /management/chat/threads {childId}` (MH1, item 5) — the fourth id that
body may carry, beside `teacherUserId`, `coordinatorUserId` and `adminUserId`, read in the order child, teacher,
coordinator, admin. It is the same `chat_threads` row the parent's own first message creates (`staff_role MANAGERIAL`,
`child_id` the child), so it appears in her app list beside her coordinator threads and in `GET /management/chat/threads`
beside everything else of hers. A child of the other department is the usual 403; a child no parent has registered for
is **404 `no_parent`**.

The roster CSV (`POST /admin/classes/{id}/roster`) still carries `name, parentEmail` and **no phone**: a roster column
would mean a third place a number lives, and the parent's own account is the one the app keeps current.

**`GET /management/stats?from&to` — DR5's statistics.** A row per grade and the department's own total (that row carries
`grade` 0 and no `curriculum`): children, sections, attendance rate, lessons published and played, the number of exams
with their average and pass rate, and the teachers who published nothing in the window. Both bounds absent is the month
ending today, and the window is capped at a term (186 days). **Seven statements, whatever the size of the department** —
the scope's two, the child counts, the lessons of the window, one grouped read of `attendance` (`(present + late) /
marked`, `AttendanceService`'s own formula; *null* rather than 100 when nothing was marked), one grouped read of the
players per lesson, and one grouped read of the best stars per (exam, child, stop) — so a grade costs nothing to add and
there is no loop over children anywhere. A teacher counts as having published in a grade when a published lesson sits in
one of that grade's (section, subject) cells she holds the assignment for, which is the join `HomeService`'s
`teacher.quiet` card already does.

> **What the exam average is, and is not.** A child's percentage on a paper is the mean of §7's stars-to-score mapping
> (3 = 100, 2 = 70, 1 = 40) over the stops she answered, the grade's average is the mean over her children, and the pass
> rate is the share of them at `secure` (60) or better. It is a statistics screen's number: the full scorer additionally
> reads a teacher's mark for an open stop, a single-answer stop's first try and a lesson-level override, none of which a
> grouped query can reach, so an exam with hand-marked questions can sit a point or two from the released class average.
> The exact figure is one click away — `GET /management/exams/{id}/results` is the very body the teacher reads.

**`GET /management/usage?from&to` — her department's School usage (MG1).** The same `SchoolUsage` shape as
`GET /school/usage` and `GET /admin/schools/{id}/usage` — children, active families, plays a day, lessons published a
week, and `teacherConsistency` — but scoped: every statement names her sections (`ManagerScope.sectionsOf`) as well as
the school, and the teacher rows are the ones she manages (`ManagerScope.teachersOf`), so the British manager is never
shown the American department's plays or the other track's teachers beside her own. Key `management.read`, no flag,
five grouped statements, and the window is `Reports.window`'s: both bounds absent is the last 30 days, capped at 400.
The dashboard's manager screen should call this instead of `/school/usage`, which stays the whole-school answer.

### The Admin's people pages (MA1, V25)

The owner's admin-role list of 2026-09-30 is one area in five screens: **Home** leads with her counts, **Teachers**,
**Coordinators** and **Managers** are the same page three times, **Workers** is the staff who do not teach, and
**Children & parents** is the one that creates a login the server does not own.

**Home (item 1).** `GET /me/home` for an ADMIN now answers **eight** cards, hers first: `managers`, `coordinators`,
`teachers`, `children`, `classes`, `workers`, then P3.0's `schools` and `lessonsThisWeek`. No new route — the Home is
what the dashboard already reads, and a key the catalogue has no string for renders as nothing rather than as the raw
id, so the server may add one before the dashboard has wording for it. The five new figures are **one** statement of
five scalar subqueries (`HomeService.people`), which is what keeps `HomeQueryCountTest` true. A head count is
`status <> 'disabled'`: an account created this morning that nobody has signed in to yet is still a teacher the school
has. With no `X-School-Id` the counts span every school, as this Home's other cards do.

**Coordinators and Managers (item 3).** Both pages now have the Teachers page's two edits:
`PATCH /admin/coordinators/{id}` and `PATCH /admin/managers/{id}` (`fullName`, `phone`, `active`), and
`POST …/{id}/reset-password`, which answers a one-time password **once**. Disabling revokes the account's refresh
tokens, so a session already open dies with it, and nobody may disable her own account
(`quest.server.classes.StaffAccounts`, shared by both). Her scope and her departments stay on the `PUT …/scopes` routes
they were already on. Keys unchanged: `coordinator.manage` and `manager.manage`, ADMIN only.

**Workers (item 4).** `GET|POST /admin/workers`, `PATCH /admin/workers/{id}`, `DELETE /admin/workers/{id}` — full name,
job (free text), mobile, and `active`. **No account comes into being:** no `users` row, no password, no role, because
an account nobody uses is an account nobody rotates. The DELETE **retires** her (`active = false`) and deletes nothing.
`worker.read` is ADMIN + MANAGERIAL (one filtered statement, and the department manager's directory wants it beside her
teachers); `worker.write` is ADMIN.

**Children & parents (item 5).** `POST /admin/children` writes three things in one request: the Firebase login, the
`parents` row, and the child on the section's roster.

```bash
curl -s -X POST "$API/admin/children" -H "Authorization: Bearer $ADMIN" -H "X-School-Id: $SCHOOL" \
  -H 'Content-Type: application/json' -d '{"name":"Hala Ahmed","classId":"…","grade":1,"curriculum":"british",
       "parentName":"Ahmed Ali","parentPhone":"0501002030","parentEmail":"ahmed@example.com",
       "parentInitialPassword":"…"}'
# 201 { childId, parentId, parentCreated, passwordApplied }
```

`grade` and `curriculum` are the section's own and may be sent for confirmation only — a disagreement is a 400, because
a Grade 1 British child in a Grade 1 American class would be shown lessons for a syllabus she is not taught. The
password is **at least eight characters**, is never the address itself, is trimmed once (the trimmed value is what the
provider is handed), is the Admin's to read out in the room, and is stored nowhere on the server: not in the audit row,
not in a log line, not in a mail. An address the school already has is **reused** (a second child of one family is one
account, `parentCreated: false`); an address whose family belongs to **another school** is a 409.

**`passwordApplied` is the field the screen must read out loud.** It is true only when *this* call minted the login. A
login that already exists keeps the password its owner chose — overwriting a stranger's password is not this route's job
— so `passwordApplied: false` means "she already has an account; her existing password still works", and the paper the
Admin has just written the typed password on is worthless. `parentCreated` answers a different question (the local
`parents` row), which is why there are two flags and not one.
A Firebase account that exists but has no `parents` row — a parent who signed up in the app before the school typed her
in — keeps its uid and its own password; `POST /admin/children/{id}/parent/reset-password` is the only route that
changes one.

**The parent's name (S1).** `POST /admin/children` answers `parentName` beside the ids, `PATCH /admin/children/{id}
{"parentName":"…"}` changes it (1–80 characters; 400 while no parent is linked, as `parentPhone`) and answers it, the
search rows and the manager's `GET /management/people/children` rows carry it, and both `q`s match it. Chat rows'
`parentName` is that name too, falling back to her address.

**What the app sees (S1, owner's item 7).** A parent the Admin created signs in with that email and password and
`GET /children` answers **every** child admitted under her address — no join code, no "add child" step — as the plain
`Child` list the app already reads (`AdminPeopleApiTest`).

The page is `GET /admin/children/search?q&page&size` → `{page, size, total, rows}`, matched on the child's name, either
address the school holds, or the parent's name and telephone number. **Not** `GET /admin/children`, which has answered a
flat array of roster rows since §3 and is what the attach picker reads with `?unassigned=true`. The edit stays on
`PATCH /admin/children/{id}`, which MA1 widens by `parentPhone`. Keys `admin.children.read` / `admin.children.write`,
ADMIN only.

#### What QA and production need configured for parent accounts

A parent's login lives in **Firebase Auth**, not in this database, so `POST /admin/children` and the parent
reset-password route call Firebase Admin — `getUserByEmail`, `createUser`, `updateUser`. They go through the
`quest.server.auth.ParentAccounts` port, and which implementation runs is decided by the one setting that already
decides whether a parent's *token* is real:

| `quest.auth.fake` | Implementation | Where |
|---|---|---|
| `true` | in-memory stand-in, no network | the `test` and `h2` profiles, local development, and the seed — **the seed never needs Firebase** |
| `false` | Firebase Admin | `qa`, `prod` |

**What QA has:** `FIREBASE_CREDENTIALS` (Secret Manager, the service-account JSON or a path to it), used today by
`FirebaseTokenFilter` to *verify* parent ID tokens. **What must be added:** nothing new, *if* that service account also
carries user-management rights — token verification needs only the project's public keys, while creating a user and
setting a password need the Identity Toolkit. The credential to check is the one behind `FIREBASE_CREDENTIALS`: a
service account with the **Firebase Authentication Admin** role (or `identitytoolkit.*` on the project) can do both. If
it cannot, add a second service account with that role and point `FIREBASE_CREDENTIALS` at it — the same variable, a
different value, and no code change. Never put the value in a file in this repository; the name is what travels.

A deployment with `quest.auth.fake=false` and no usable Firebase app answers **503
`Parent accounts are not configured`** on those two routes and nothing else changes: the rest of the Admin's People area
is local rows and keeps working, which is the same way token verification already degrades.

### The matrix

Generated from `permissions.json` on `develop` (`152f2c8`), before `COORDINATOR` existed — the three keys above are
the role's whole grant and the column is added the next time this table is regenerated. `✓` = granted; the last
column counts the endpoints the key guards.

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

**The dashboard's side of that rule (T2 item e).** A browser profile has one `localStorage`, so two
tabs are two views of one session, and both ways that can go wrong ended in the same revocation:

- **Two tabs of one account** each refreshed with the one single-use token, and the loser presented
  a rotated one. `AuthService.refresh` has always been single-flight *inside* a tab; T2 made it
  single-flight *across* tabs with `core/auth/refresh-lock.ts` — the Web Locks API where it exists
  (`navigator.locks`), a stamped `localStorage` key where it does not — and the waiting tab re-reads
  the token from storage inside the lock, so it spends the live one instead of the spent one. The
  `storage` listener in `core/auth/session-sync.service.ts` is what keeps that value current.
- **A second role in a second tab** overwrote the first tab's token and its owner stamp
  (`hq.session.owner`, `<userId>:<role>`, written when `/me` lands). The first tab used to carry on
  silently: its next refresh spent a foreign token, the pair was revoked, and the person was signed
  out "after a few minutes" with no explanation — and in between it was running the other role's
  socket. It is now signed out at once, on the sign-in screen, with *"You signed in as <role> in
  another tab of this browser"* (`?ended=takenOver&as=<role>`). A sign-out in another tab revokes the
  shared token, so that tab ends too (`?ended=signedOutElsewhere`).

**And only a refused *token* signs out.** `AuthService.refresh` used to `forget()` on *any* failure,
so a status 0, a 502/503/504 or a timeout — a cold start, a deploy, one lost second of Wi-Fi — read
as an expiry. It now forgets only on a **401 or 403 from `/auth/refresh`**; anything else keeps the
session, retries once after 600 ms (the retry retakes the cross-tab lock and re-reads the token) and
shows a `notice` band, *"Reconnecting…"*. The chat socket asks with
`refresh({endsSession: false})`, which never ends the session whatever the answer: Cloud Run closes
that socket every hour and every blip reopens it, so it must not be the thing that signs her out — a
401 there costs another backoff, and the next request she actually makes is what discovers a session
that is really over. A request that is *waiting* on a shared refresh upgrades it, so a real 401 in
front of a person still signs out.

Neither is a server change: the access token still lives 15 minutes and the refresh token is still
single-use. What changed is that one tab no longer spends another tab's token, and one failed
request no longer ends a live session.

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
recording, open-answer drawing, the Arabic parent panel, certificates, level 3, chat and — since RM4 — `announcements`,
which **since MH3 gates two parent screens**, each with its own unread badge on the parent home: **Weekly plan**
(`Routes.WeeklyPlan`, `GET /children/{id}/weekly-plans` — this week's plan for the child's grade as the image, earlier
weeks collapsed below, pull to refresh) and **Announcements** (`Routes.Broadcasts`, the RM4 feed with the weekly plans
filtered out client-side, since `GET /children/{id}/broadcasts` still carries them). An image attachment is downloaded
through the app's Ktor client with the parent's bearer (`GET /media/attachments/{id}`) and cached under the
`attachments` row id in the app's private media directory, so the last plan she opened is still there offline; tapping
it opens a full-screen pinch-and-pan viewer. Her own mobile number sits in parent **Settings** (`GET|PATCH /parent/me`),
validated client-side by `quest.feature.parent.domain.Phones`, the mirror of the server's `Phones` rule.

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
  "primary": "#FFFFFF", "primaryInk": "#201E1D", "accent": "#0762BF",
  "ground": "#F3F2F2", "softBorder": "#D9D6D2", "mascotColor": "#598FB8",
  "worldPalettes": { "math":    { "primary": "#6FC3FF", "deep": "#3F9BE0", "soft": "#EAF4FF", "ink": "#201E1D" },
                     "english": { "primary": "#B69CFF", "deep": "#7E63D8", "soft": "#F1ECFF", "ink": "#201E1D" } },
  "fontChoice": "nunito" }
```

That is the shipped default, as `GET /schools/by-code/ALNOOR` returns it for a school that has not been themed.
`worldPalettes` has exactly the two worlds `math` and `english`.

**Palette source of truth (B2).** The colours are the MySchool logo's, and they flow one way: `docs/brand/palette.md`
→ `design/tokens.json` (and its byte-identical copy `server/src/main/resources/design/tokens.json`) → the server's
default theme (`DesignTokens`, mirrored by `quest.api.dto.SchoolTheme()`), the dashboard's `_theme.scss` /
`_tokens.generated.scss` and the app's generated `DesignTokens`. In the tokens `color.accent` is `#0774C9` (the fill
white text clears AA on, and the dark-mode accent), `color.accent-strong` is `#0762BF` (the default theme's `accent`,
5.4:1 on the ground), `color.accent-soft` is `#E6F5FC`, and `color.gradient-from` / `gradient-to` / `secondary` /
`tertiary` carry the gradient's `#089CDF → #0754B7`, the magenta `#B8047A` and the orange `#FB9B0A`; the tokens have
no dark slots, so dark values stay in `palette.md` and the front-ends. `V28__default_theme_logo_palette.sql` moved the
accent of every stored theme that still held the old default's colours verbatim (`#CC2A0F` with the default surface,
ink, ground, rule, mascot and worlds) to `#0762BF`; a theme with any colour of its own was left as it was.

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

(The first is the old brand red `#EC3013` as text on `#F3F2F2`; the second is the tokens' mascot blue `#7EC8FF`. Both
are why the defaults are the darkened variants.)

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

### The school logo as an upload (S1, V27)

```bash
curl -s -X PUT "$API/admin/schools/$SCHOOL/logo" -H "Authorization: Bearer $TOKEN" -F file=@crest.png   # school.write
curl -s -o crest.png "$API/schools/$SCHOOL/logo"                                                        # public
curl -s -X DELETE "$API/admin/schools/$SCHOOL/logo" -H "Authorization: Bearer $TOKEN"                   # 204
```

One JPEG, PNG or WebP of at most 1 MB, sniffed from its bytes like an attachment, stored in the `FileStore` under
`school-logos/{schoolId}/…` and named on the school row (`logo_path`, `logo_type`, `logo_updated_at`). Nothing new is
read by the clients: `ThemeService.themeOf` puts `<PUBLIC_URL>/schools/{id}/logo?v=<uploaded-at>` into the theme's
existing **`logoUrl`**, so the public theme, `POST /schools/logo` (the sign-in page), the join lookup and
`/me/home.schoolLogoUrl` all carry it, and an upload wins over a typed URL. `GET /schools/{id}/logo` is public,
`Cache-Control: public, max-age=86400`, `nosniff`, addressed by the school id alone and able to answer only the three
image types — 404 for a school with no upload. A replacement is a new `?v=`; `DELETE` clears the upload **and** any
typed `logoUrl`. A theme save that sends the derived URL back does not freeze it into `theme_json`.

**In the dashboard (D1, list 3).** The Admin's Home carries a small **School** card
(`features/admin/school-card.component.ts`): the logo, the school's name, **Upload logo** / **Replace logo** and —
behind a red confirm band — **Remove logo**, all gated by `school.write`. A file that is not JPEG, PNG or WebP, or is
over 1 MB, is refused in the card with the reason and never sent. After a write the card calls `ThemeService.reload()`
— the shell's logo block reads the same signal, so the rail repaints with no page reload — and the sign-in page asks
`POST /schools/logo` afresh each time an email is typed. The card is on the Home because the same list took Schools
out of the rail (below) and Platform settings went with MA0: there is no settings screen left to reach.

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

**The product is named MySchool (Arabic مدرستي) since N1, 2026-10-02.** `V29__product_name_myschool.sql` renames the
row to `MySchool` / `MySchool` only where it still holds the seeded defaults (a name an Admin set by hand is kept).
Repository, package, Cloud Run service, database and bundle identifiers keep their old names on purpose.

```bash
curl -s "$API/platform-settings"
# {"name":"MySchool","shortName":"MySchool","logoUrl":null,"supportEmail":null,"defaultTheme":null}

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
`server/src/main` — including a comment or a model prompt — outside `db/migration`. `V5__flags_themes.sql` is where the
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
| `EXAM_PAPER_SECRET` | QA, prod | B3: the key of a sealed exam paper's opaque ids, separate from `ADMIN_JWT_SECRET` so rotating that never changes the ids of a downloaded paper. Terraform generates it (`random_password.exam_paper`). **Fatal if missing in `prod`**; in `qa` an error line and a key derived from `ADMIN_JWT_SECRET`; elsewhere a fixed development key. Do not rotate it while an exam is open |
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
| `QUEST_PUSH_ENABLED` | QA, prod | B4: send parents' push through Firebase Cloud Messaging. Defaults to on under the `qa` and `prod` profiles and off everywhere else (sends are recorded, not sent) — see [Push notifications](#push-notifications-b4) |
| `quest.pipeline.convert.allow-builtin-fallback` | `h2`/`test` profiles only | not an environment variable: hard `false` in the base config, `true` only under those two profiles, and the code checks the profile too |

`ADMIN_JWT_SECRET` has a placeholder default in `application.yml` so a developer can boot without one. **Any deployed
environment must set it** — Terraform does, from `random_password.jwt`.

Terraform wires `DB_PASSWORD`, `ADMIN_JWT_SECRET`, `EXAM_PAPER_SECRET`, `DEEPSEEK_API_KEY` and `ADMIN_PASSWORD` into Cloud Run as required
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
set `SEED_SCHOOL=true` on the QA Cloud Run service** so a fresh QA database fills itself. *(Superseded 2026-10-03:
QA runs the owner's school and sets `SEED_SCHOOL=false` — see [What QA holds](#what-qa-holds-the-owners-school-no-seed).
The seed is now for the local `h2` profile and the tests.)* `SEED_STAFF_PASSWORD` is
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

**The server grades, and the result is sealed until release (B3).** An exam answer's `correct`, `stars`,
`attemptNumber` and `mistakes` from the app are ignored: `AnswerKey` grades the uploaded answer (the player's own
format — an option id, picked ids joined by `,`, `left=right` pairs, the placed order) against the stop of the paper
the server derives, three stars or none. A retell, an open answer, free writing or a tracing has no key and is stored
unscored, waiting for the teacher's mark (`openStopMarking`, as for homework); a second answer to a question already
answered and an answer to a stop not on the paper are dropped (not counted in `accepted`). Homework keeps the app's
values; a single-answer one whose answer disagrees with the key is logged as `attempt … the app reports correct=…`.
Today's app answers an exit ticket with one attempt on the ticket's own id and an empty answer. Each question whose
answer that attempt carries (`answerJson` = `{"<questionId>":"<answer>",…}`) is graded as its own attempt
(`<attemptId>:<questionId>`); each one it does not carry is stored with `AnswerKey.PENDING` as its answer and waits
for the teacher's mark exactly like an open stop (`needsMarking`, left out of the score — never a zero for the
child, and left out of the class's per-question difficulty). An app that sends one attempt per question completes
the ticket by itself. The first answer wins under concurrency as well: `attempts.exam_key` (V31,
`child|lesson|stop`) is unique and an exam answer is inserted with `ON CONFLICT DO NOTHING`.

**The paper is sealed until release.** `GET /lessons/{id}` for an exam with no `released_at` answers under the ETag
`"<id>-v<n>-sealed"` with every play (`plays`, `variant`, `examPlay`) marked `"sealed": true` and **every answer-key
field meaningless**, in the shape every installed app decodes (`SealedPaperTest` runs a sealed copy of all 22 stop
types through `Play.schema.json` and the shared-api decoder):

- every option, tile, item, pair and hotspot id is opaque — `x` + 16 hex of an HMAC (keyed by
  `EXAM_PAPER_SECRET`) over parent, exam, stop and id — and the lists are in the order of those ids. They are the
  same for the same parent and exam (`GET /lessons/{id}` names no child), so a resumed sitting and a re-download agree,
  and the server maps them back when it grades (`PaperSeal`); nothing is stored;
- `correctOptionId` and `correctIds` are the first option / hotspot sent (a multiSelect's first `pick`),
  `trueFalse.answer` is `false`, `correctOrder` is the items in the order sent, a word-tile writeSentence's `answer` is
  its first word, and a match stop's right-hand tiles are given to the pairs by a second keyed permutation;
- `hint`, `modelAnswer` and `parentTip` say `…`; `numberLine.highlight`, `teacherText`, `parentPanel.stopTips` and
  `modelAnswers` are empty.

The player answers with the ids it was sent. An answer in a stored id (a copy downloaded after the release) still
grades, and a match answer is graded against the shuffle only when it is given in opaque ids. A homework and a
released exam are sent exactly as stored, without `sealed`.

Until `released_at` is set, nothing derived from an exam's answers reaches a parent or child route: the island on
`GET /children/{id}/map` is `done` but has no `starsEarned` / `starsTotal`, its skill contributes no first tries to
`/progress` (`attempts` 0, no band, no review island), and `results` omits it. Releasing shows all of it at once.

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

## Chat and notifications

C1 `backend/chat-websocket` (D24): real-time chat between a child's **parent** (the app) and a **teacher** of the
child's section (the dashboard), on the API itself — Spring WebSocket on the same origin with the same tokens, plain
JSON frames, no STOMP, no second service. The contract types are `shared-api/src/commonMain/kotlin/quest/api/dto/Chat.kt`
(`ChatThread`, `ChatMessage`, `ChatFrame`, `ChatCommand`); the frame schema the app and the dashboard validate
against is `shared-api/src/commonMain/resources/schemas/ChatFrame.schema.json`, used the way `Play.schema.json` is.

**The flag.** Everything is behind `chat`, seeded **off** by V15: every REST route answers 404 and the socket
handshake 403 until an Admin turns it on for the school (`PUT /admin/schools/$SCHOOL/flags/chat {"enabled":true}`).
A parent is refused only when *none* of her children's schools has it on; each command is then checked against the
child's own school.

**Who may talk to whom (R4 widened this).** A thread's staff side is a **teacher**, a **coordinator** or — with no
child on it at all — a **manager**, in `chat_threads.staff_role` (V20; `TEACHER` for every row written before R4).
`teacher_id` is that staff peer whatever the role, `peer_user_id` is the second staff member of a
coordinator ↔ manager thread, and `teacher_unread` / `parent_unread` are the two badges: the staff peer's, and the
counterpart's (the parent, or the manager). A thread also carries a `topic` and a `status`; since **B6** every
Messages thread is `question` / `open`, and a `complaint` row is a conversation of its own — see
[Complaints (B6)](#complaints-b6).

- **Parent ↔ coordinator.** `GET /children/{id}/coordinators` answers the coordinators whose `staff_scopes` cover a
  subject taught in the child's section, as `ChatThread` rows with `id: null` until she writes — the same shape the
  teacher rows have. She then posts to `/children/{id}/chat/threads/{staffUserId}/messages`; a coordinator of another
  subject or the other track is 404, exactly as a teacher who does not teach the section is. **B6:** a send with
  `{"topic":"complaint"}` — S1's way of turning a thread into a complaint — is `400 complaint_moved`; a complaint is
  opened with `POST /children/{id}/complaints` ([Complaints (B6)](#complaints-b6)).
- **Coordinator → teacher (S1).** `POST /coordinator/chat/threads {"teacherUserId":"…"}` — exactly one of
  `managerUserId` and `teacherUserId`. It is the row T1b's `POST /teacher/chat/staff-threads {coordinatorUserId}`
  creates from the other end (teacher on `teacher_id`, coordinator on `peer_user_id`, `staff_role` `COORDINATOR`);
  a teacher outside her subjects is 404.
- **Admin direct messages (S1).** `POST /admin/chat/threads` takes **exactly one** of `managerUserId`,
  `coordinatorUserId`, `teacherUserId` and `childId`, with `X-School-Id`. Staff rows keep the one rule — subordinate
  on `teacher_id`, the admin on `peer_user_id` — with `staff_role` `MANAGERIAL` for a manager (RM2's row) and `ADMIN`
  for a coordinator or a teacher; `childId` is the parent thread (child on `child_id`, the admin on `teacher_id`,
  `staff_role` `ADMIN`; 404 `no_parent` when nobody registered). On the wire `staffRole` stays `MANAGERIAL` (a
  released app decodes the enum strictly) and **`withAdmin: true`** marks the row. Each side answers through its own
  routes: `/management/chat/**`, `/coordinator/chat/**`, `/teacher/chat/staff-threads/**`, and the app's
  `/children/{id}/chat/threads/{adminUserId}/…`, where `teacherName` is "School administration". A parent cannot start
  that thread. `GET /admin/chat/threads?mine=true` is the admin's own inbox (her threads, her unread); `chat.message`
  rows link each recipient to her own Messages screen.

**`ChatThread.peerRole` (N1)** — optional, on every thread list and thread POST: the role of the *other* party relative to the
caller (`TEACHER`, `COORDINATOR`, `MANAGERIAL`, `ADMIN`, `PARENT`), from that account's role; absent on the Admin's read-only
support list (`GET /admin/chat/threads` without `mine=true`). The dashboard's role line and inbox chips read only this.
- **Coordinator ↔ manager.** `POST /coordinator/chat/threads {"managerUserId":"…"}` — one thread per pair, however
  many times either side asks for it, limited to a manager whose department (her `curriculum` scope) meets hers; any
  other manager is 404. `childId` is empty on those rows. RM2 gave the manager the other end of it:
  `POST /management/chat/threads {"coordinatorUserId":"…"}` opens the same row from her side.
- **Parent ↔ manager (RM2, DR5).** `GET /children/{id}/managers` answers the manager of the department the child's
  section is in, as `ChatThread` rows with `id: null` until the parent writes — `GET /children/{id}/coordinators`'
  mirror. She then posts to `/children/{id}/chat/threads/{managerUserId}/messages`; a complaint to her (about a
  coordinator, say) is a [complaint](#complaints-b6). A manager of the other department is 404.
- **Manager ↔ admin (RM2, DR5).** "The manager reports to and chats with the admin":
  `POST /management/chat/threads {"adminUserId":"…"}` from her side (the ids come from `GET /management/admins`) or
  `POST /admin/chat/threads {"managerUserId":"…"}` from the Admin's, with `X-School-Id`. One row either way. The
  manager holds the `teacher_id` side of it, and the coordinator holds it on a coordinator ↔ manager thread, so
  `findForStaff` always finds a person's threads whichever pair she is in.

One thread per (child, teacher), and only between the child's parent and a teacher who
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
| `POST …/messages {body, clientId?, attachmentIds?}` | `POST …/messages {body, clientId?, attachmentIds?}` | 201 `ChatMessage`. B5: `body` may be empty when `attachmentIds` names a file. |
| `POST …/read` | `POST …/read` | 200 `ChatReadReceipt`; 404 while no thread exists. |

The coordinator's half is keyed by **thread**, not by child, because one of her threads has no child on it:
`GET /coordinator/chat/threads?status=`, `GET|POST /coordinator/chat/threads/{id}/messages`,
`POST /coordinator/chat/threads/{id}/read` and `POST /coordinator/chat/threads`. B6 moved her Complaints page to
`/coordinator/complaints/**` ([Complaints (B6)](#complaints-b6)) and removed `PATCH /coordinator/chat/threads/{id}/status`.
A parent thread leaves her list when the child leaves her scope, the way a teacher's does when the assignment goes.

The **manager's** half is keyed by thread for the same reason (RM2): `GET /management/chat/threads?status=`,
`GET|POST /management/chat/threads/{id}/messages`, `POST /management/chat/threads/{id}/read` and
`POST /management/chat/threads {teacherUserId | coordinatorUserId | adminUserId}`, all behind `management.chat` and the
`chat` flag.
Her list is the coordinator's one scope wider: the parents who wrote to her about a child of her department, her
coordinators, and the admin. A parent thread leaves it when the child leaves the department; a thread she is not on is
404. The Admin writes only in **her own** threads — `POST /admin/chat/threads`, `POST /admin/chat/threads/{id}/messages`
and `POST /admin/chat/threads/{id}/read`, behind `admin.chat` with `X-School-Id` — while `GET /admin/chat/threads`
still reads every thread of the school for support.

**The teacher's staff threads (MG1).** `/teacher/chat/threads` is keyed by **child** and always will be — it is her
conversations with parents. Her conversation with her department manager has no child on it, so it lives on its own
thread-keyed routes: `GET /teacher/chat/staff-threads`, `GET|POST /teacher/chat/staff-threads/{id}/messages`,
`POST /teacher/chat/staff-threads/{id}/read` and `POST /teacher/chat/staff-threads {"managerUserId": …}`, all behind
`teacher.chat` and the `chat` flag. `GET /teacher/managers` is the chooser: the managers of the departments she
teaches in (a `staff_scopes` row with no subject whose `curriculum` is one of her sections'), each with that
department. A manager of another department is **404** from either end — she may not open one, and
`POST /management/chat/threads {teacherUserId}` refuses a teacher outside the manager's own department.

**The staff directory (T1).** The owner's list of 2026-10-01: *"teachers get Coordinator and Manager pages — name,
phone, email, job, direct message; coordinators get a Manager page."* Three routes answer one shape, `StaffContact`
(`shared-api/src/commonMain/kotlin/quest/api/dashboard/Coordinator.kt`), because they are one screen asked from two
roles — and it is RM1's `CoordinatorManager` grown up, so `userId`, `displayName` and `curriculum` still read the same:

| Route | Permission | What |
|---|---|---|
| `GET /teacher/coordinators` | `teacher.chat` | T1: the coordinators whose `staff_scopes` cover a (subject, track) pair **she teaches**, name order. |
| `GET /teacher/managers` | `teacher.chat` | The managers of the departments she teaches in — MG1's chooser, now with the contact details. |
| `GET /coordinator/managers` | `coordinator.chat` | The managers whose department intersects her scope (RM1 addendum), likewise. |

A row is `{userId, displayName, email, role, job, jobParts, phone?, curriculum?, subjects?, online}`. **`job` is the
English fallback; `jobParts` is the same thing localisable** — a **list** of `{kind: "coordinator" | "manager", grades:
[1], subject, curriculum}` — so the dashboard writes the sentence in the reader's language instead of translating one. A
coordinator's parts are the ones that put her on *this* caller's list: Maya teaches grade 1 maths in the British track,
so Lina reads "Coordinator · Grade 1 · Math · British", and a coordinator who covers two of Maya's grades *in one track*
is named with both (`grades: [1, 2]`, "Coordinator · Grades 1, 2 · …"). **One entry per track, and grades are never
flattened across tracks**: a coordinator of both tracks who covers Zaid's British grade 1 and his American grade 3 is
two entries, because "Grades 1, 3" of either would name a grade she does not coordinate for him; `job` joins the
sentences with "; ", and `curriculum` / `subjects` carry the first track and the union for the RM1 client that reads one
word of each. A manager's parts are her department alone — "British department manager", `grades` empty, one entry.
`online` is presence (below).

**Matched per section, never crossed.** The teacher's own assignments become (subject, track, grade) triples and each
triple is matched whole against a coordinator's scope rows, so a teacher of British maths and American drama is never
handed the British drama coordinator. The reach is the chat reach — `ChatPeers.managersForTeacher`,
`managerOptionsFor`, `coordinatorsOn`'s rule read backwards — so the directory can never offer somebody the
"direct message" button would then refuse. No new permission key: the lists exist so she can write to them, and
`teacher.chat` / `coordinator.chat` are the keys her Messages screen already holds.

**The direct message (T1b).** `POST /teacher/chat/staff-threads` takes **exactly one** of `{managerUserId}` and
`{coordinatorUserId}` — 400 for both or neither, and 404 for anyone the matching directory page above does not list, so
the button can never open a thread the read would refuse. A coordinator thread is the ordinary staff row: the teacher on
`teacher_id`, the coordinator on `peer_user_id`, `staff_role` **`COORDINATOR`** (it names the peer, as everywhere else),
so `teacher_unread` is the teacher's badge and `parent_unread` the coordinator's. The coordinator lists it, reads it and
answers it through her existing `/coordinator/chat/threads`, `…/{id}/messages` and `…/{id}/read` — the reach check on her
side is the same subject-and-track rule read backwards, so a thread can never exist that one of its two parties cannot
open — and her `chat.message` bell links to `/coordinator/messages?thread=…`, the teacher's to `/teacher/chat?thread=…`.

**Which end of a staff thread is which.** On every staff-to-staff thread the *subordinate* holds `chat_threads.teacher_id`
and the *supervisor* holds `peer_user_id`, and `staff_role` is `MANAGERIAL` — it names the peer. So: teacher → manager,
coordinator → manager, manager → admin. One rule means `findForStaff` finds a person's threads whichever pair she is
in, and whichever side opens the conversation there is one row. `teacher_unread` is therefore always the subordinate's
badge and `parent_unread` the supervisor's.

`POST /teacher/messages/coordinator` (the teacher's note to the office) keeps its notification to every manager of her
school **and**, since MG1, appends the sentence to the staff thread she shares with the manager(s) of a department she
teaches in, so it can be answered. The notification's `link` then opens that thread.

Her announcement is `POST /coordinator/announcements {bodyEn, bodyAr?, classIds?, expiresAt?}` — one `announcements`
row per class (every section in scope when `classIds` is absent, each named one checked through `requireSection`
otherwise), which the parent reads through the existing `GET /children/{id}/announcements`. Parents have no bell of
their own, so there is no notification row for them: the app's announcements screen is the delivery.
`GET /coordinator/announcements` lists hers. Since RM2 that route is a **door onto Broadcasts** (below): the same call
writes a `broadcasts` row too, so what she posts appears in both her feeds and nothing about the app screen changes.

`limit` is 1–200 (default 50). `before=<messageId>` pages backwards from that message; `since=<messageId>` answers
everything after it, oldest first — the reconnect refetch. Either cursor must be a message of that thread (400
otherwise). `body` is 1–2000 characters of **plain text**: trimmed, control characters other than line breaks and
tabs removed, stored and delivered exactly as typed, and **never interpreted as HTML** by any client — render it as
text. More than **30 messages a minute** from one sender (REST and socket together, per instance) is `429
rate_limited`. Support: `GET /admin/chat/threads` and `GET /admin/chat/threads/{threadId}/messages` as ADMIN with
`X-School-Id` (read-only; without the header the Admin reads the flag defaults and the gate answers 404).

### Complaints (B6)

Owner, 2026-10-03: "Complaints must be separate from messages." Contract: `shared-api/…/dto/Complaints.kt`.

**A complaint is its own conversation.** The parent opens it about one child, to one staff member — a teacher of the
child's section, a coordinator of one of its subjects or the manager of its department (`GET
/children/{id}/complaints/recipients`; anyone else, the Admin included, is 404) — with a `title` (1–120 characters) and a
first message. It is a `chat_threads` row with `topic` `complaint` and `thread_key` = its own id (V34), so it sits
**beside** her Messages thread with the same person and she may hold several. Its messages are ordinary `ChatMessage`s
(rate limit, paging, socket `message` frames named by the complaint's id; B5's attachments: `attachmentIds` on the create and on every reply, bytes readable by the parent and the recipient only). No Messages
list (`/children/{id}/chat/threads`, `/teacher/chat/**`, `/coordinator/chat/**`, `/management/chat/**`,
`/admin/chat/**`) shows a complaint, no Messages route opens one (404), and no Complaints route opens a Messages
thread. Replies, reads and status changes are REST only — the socket's commands address Messages threads.

| Who | Routes | Sees |
|---|---|---|
| Parent (`child.complaints`) | `GET\|POST /children/{id}/complaints`, `GET …/recipients`, `GET …/{complaintId}?before=&since=&limit=`, `POST …/{complaintId}/messages`, `POST …/read`, `PATCH …/status {"status":"open"}` | every complaint about her own child |
| Teacher (`teacher.complaints`) | `GET /teacher/complaints?status=`, `GET …/{id}`, `POST …/{id}/messages`, `POST …/read`, `PATCH …/status` | those addressed to her, while the child is in one of her sections |
| Coordinator (`coordinator.complaints`) | the same five under `/coordinator/complaints` | addressed to her, and — read-only — those to a teacher of her subjects on a section in her reach |
| Manager (`management.complaints`) | the same five under `/management/complaints` | addressed to her, and — read-only — those to the teachers and coordinators of her department |
| Admin (`admin.complaints`, `X-School-Id`) | `GET /admin/complaints?status=`, `GET /admin/complaints/{id}` | every complaint of the school, read-only |

Lists answer `ComplaintList {complaints, open, resolved}` — `?status=open|resolved|all` (default all) filters the rows,
never the counts. A detail is `ComplaintDetail {complaint, messages, events}`. Anything out of scope — another school,
another parent, another department, another teacher — is **404**. `Complaint.canReply` is true for the parent and the
recipient; a supervisor or the Admin gets **403** on `…/messages` and `…/read`.

**Status.** `open` → `resolved` by the recipient or a supervisor in scope; `resolved` → `open` by the recipient, a
supervisor or the parent (`resolved` from the parent is 403). A message on a resolved complaint does **not** reopen it
("thank you" is the usual last word). Every real change writes a `complaint_events` row (who, when — the client draws
"Resolved by Nour · 3 Oct" from `ComplaintEvent`), sends both parties the socket `status` frame, and rings the other
side: the parent's `complaint.status` row + push when staff moved it, the recipient's `complaint.status` row when the
parent or a supervisor did. Re-setting the same status changes nothing and tells nobody. The move is one conditional
`UPDATE … WHERE status <> ?`, so two people resolving at once make one change (one event, one bell, one push), and the
row always holds the newest event's status.

**Bells.** A new complaint → the recipient's `complaint.new` (body = title, link `/{area}/complaints?open={id}`). A
parent's message → the recipient's `complaint.message` (one unread row per complaint). The recipient's reply → the
parent's `complaint.message` row + push (`complaintId`, link `/children/{childId}/complaints/{id}`, collapse key
`complaint:{id}`). Reading the complaint clears its `complaint.message` row. A complaint never writes `chat.message`.

**Data written before B6.** V34 turns every `topic = complaint` row into a complaint as it is — messages, status and
all, even one that began as a question thread: it gets its own `thread_key`, a title (the first 120 characters of its
first message) and, when resolved, its resolution as an event. It leaves the Messages lists; the parent's next message
to that person opens a fresh Messages thread. Flag: still `chat` (a complaint is stored and delivered as a
conversation); the `complaints` key stays N5.2's.

### The socket: `/ws/chat`

**Auth.** `Authorization: Bearer <token>` when the client can send headers (the app), else `?token=<token>` (a
browser `WebSocket` cannot send headers — the dashboard). Either carrier takes either kind: a dashboard JWT
(`admin.…`, any of ADMIN / TEACHER / MANAGERIAL / COORDINATOR) or a Firebase ID token, verified by the same code as the request
filters. 401 for a token nobody issued; 403 for a dashboard principal with no school (ADMIN excepted — she has
none by design) and for a parent none of whose children's schools has the flag on. **Since E2 (D26) the flag no
longer decides the handshake**: every dashboard role is admitted whether `chat` is on or off, because the socket
also carries notifications. What the flag still decides is the *chat commands* — a `message`, `typing` or `read`
from a peer who is not a TEACHER or COORDINATOR (R4) of a flag-on school, or a parent, comes back as an `error` frame with code
`forbidden`, and chat REST 404s for her exactly as before. The API never logs the token
(`RequestLogging` prints the path only), but Cloud Run's own request log records the full URL — so send the header
wherever the client can (the app), and remember a dashboard access token in the query is worth 15 minutes at most.
Allowed origins are `CORS_ORIGINS`; the app sends no `Origin`.

**Frames** are JSON text, discriminated by `type`, at most **8 KB** (bigger → close 1009).

Client → server (`ChatCommand`): a parent names the thread by `childId` + `teacherId`, a teacher by `childId`, and a
**coordinator, a manager or an admin by `threadId`** (R4, RM2) — `childId` is optional for that reason. Since MG1 a
**teacher** may send `threadId` too, and it is then her staff thread with her department manager (a thread that is not
hers is `not_found`); with no `threadId` her commands still name a `childId`, exactly as before.

```json
{"type":"message","childId":"…","teacherId":"…","body":"Hello","clientId":"7f3a…"}
{"type":"message","threadId":"…","body":"Hello","clientId":"7f3a…"}   // a coordinator, a manager, or a teacher's staff thread
{"type":"typing","childId":"…","teacherId":"…"}
{"type":"read","childId":"…","teacherId":"…"}
{"type":"typing","threadId":"…"}     {"type":"read","threadId":"…"}
{"type":"ping"}        {"type":"pong"}
```

Server → client (`ChatFrame`, the DTOs REST uses):

```json
{"type":"message","message":{ChatMessage},"clientId":"7f3a…"}   // clientId only on the sender's own sessions
{"type":"read","threadId":"…","readBy":"teacher","readAt":1758450000000}
{"type":"typing","threadId":"…","from":"parent"}
{"type":"notification","notification":{NotificationView}}       // E2, dashboard only
{"type":"status","threadId":"…","status":"resolved","at":1758450000000}   // R4, both parties
{"type":"presence","online":true,"userId":"…"}                  // T1; `parentId` instead for a parent
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
`1000 idle`, and — **T1** — one that has sent nothing for 75 s (`quest.chat.pong-timeout-seconds`, two heartbeats) is
closed `1000 unresponsive`: a crashed tab answers no `pong`, and presence must not go on calling it "Live" for the ten
minutes the idle rule allows a socket that is alive but quiet. Both clients already pong, so neither is affected — and
the same `pong` renews the presence lease (below), which is why the two numbers are set together. A client that hears no ping for ~90 s should treat the socket as dead and reconnect. A client may send
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

**What the dashboard does with the two newest frames (T2).**

- **`presence`** is the only source of "is the other person there". Before T2 the conversation
  header's pill read the tab's *own* `connectionStatus` and said **Live**, so a manager who had
  signed out went on reading as present in the teacher's tab — her own socket was fine. It now reads
  the `presence` frames, falling back to the thread row's `peerOnline` from the last `GET …/threads`
  (`core/chat/chat.service.ts`, `activePeerOnline`); the peer is found by dropping the viewer's own id
  from the ids the row names, so no branch on her role is needed. A peer nobody has reported on draws
  **no pill at all**. `disconnect()` empties the map, and sign-out calls it before
  `POST /auth/sign-out` goes out rather than leaving the socket alive for the length of that request.
- **`chat.message`** goes into the bell, the badge on the rail's Messages row and the badge on the
  header's chat icon exactly as every other kind does. The one rule of its own is the **toast**: it is
  suppressed when the notification's `?thread=` is the conversation already on screen, because the
  bubble arrives in the same second and the two would be one sentence twice. The link needs no case in
  `core/notifications/notification-target.ts` — a `/<area>/messages?thread=…` written for somebody
  else's area is already rewritten to the reader's own list by thread id.

**Opening one thread from another screen (D2, list 3).** "Message" on a manager's Children, Coordinators and Teachers
rows — and on a coordinator's Teachers rows — is `POST /<area>/chat/threads` followed by a navigation to
`/<area>/messages?thread=<id>` (`features/management/staff-thread.service.ts`). Three client rules make that land on
the conversation rather than on the list:

- The thread the POST answered is given to `ChatService.adopt` **before** the navigation. The threads list is read
  once at sign-in, so a conversation created a second ago is not in it, and a list read still in flight would overwrite
  a row put there by hand — so the row is kept in the list *and* as the pending one, and `loadThreads` keeps it when the
  server's answer does not name it yet.
- `ChatPage` follows `?thread=` reactively. A key the service does not hold is **re-read once** (`loadThreads`); if it
  is still not hers the id is removed from the URL (`replaceUrl`) and the list is the screen, with no line and no band.
  "Not in your list any more" is said only when the conversation she was *viewing* left the list
  (`ChatService.activeGone`). Following a link also clears the
  search box and the correspondent chip, so the row it selects is visible. This is the shared page, so a bell link on
  the teacher's, the coordinator's and the admin's transports gets the same treatment.
- **The manager's Complaints** (`/management/complaints`) — B6 replaced S1's inbox routes with
  `/management/complaints/**` ([Complaints (B6)](#complaints-b6)); `PATCH /management/chat/threads/{id}/status` is gone.
  A thread with `withAdmin: true` is titled "School administration" for a teacher and a coordinator.
- **A remembered thread id never reaches the server.** `ChatService` is a root singleton: its `activeKey`, list and
  messages now end with the account (`forgetAccount`, on a change of `auth.user().id`), a key the freshly read list
  does not hold is dropped rather than refetched, and the socket's `onopen` refetches only a conversation the list
  still names. Every list and message read captures an account **epoch** when it is asked and is ignored if the
  account has changed by the time it answers, so one account's rows can never land in the next one's screen. An adopted
  thread stops being pending at the first list read that names it, or when she leaves it. Before this, signing in as a coordinator in a tab where another account had a conversation open asked
  `GET /coordinator/chat/threads/<that id>/messages` on connect — the "That did not work — thread not found" band on
  her first screen. A list read that *failed* drops nothing: no answer is not "no threads".

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

### Attachments and typing (B5)

The owner's report of 2026-10-03: *"an image or a PDF sent from the dashboard shows only its NAME in the app"* and
*"the parent's typing shows on the dashboard, the manager's typing shows nothing in the app"*.

**Files on a message.** Before B5 an attachment was a `[attachment:id:type:name:size]` tag the sender's client wrote into
the body; nothing was uploaded, so the other side could only print the name. Those bodies are left exactly as they are —
plain text, no migration — and a client that still parses the tag can show the name chip it always did (the id in it is
the sender's browser-local id, never an `attachments` row). From B5 a message carries real files:

1. **Upload** — multipart `file` to **`POST /children/{id}/chat/attachments`** (the parent, permission `child.chat`) or
   **`POST /media/chat-attachments`** (any dashboard role, `media.attachment.write`; the Admin sends `X-School-Id`), both
   behind the `chat` flag. They are routes of their own rather than a parameter on MH1's `POST /media/attachments`, which
   keeps its exact shape: a new parameter there would move the generated dashboard client's positional arguments. MH1's
   limits and sniffing, unchanged: JPEG / PNG / WebP ≤ 5 MB, PDF ≤ 10 MB, the type from the bytes (`400` otherwise, `413
   too_large`). The row is filed under the caller's school — a parent's is the school of the child in the path (`404` for a
   child who is not hers) — and only while that school has `chat` on (`404` otherwise). The reply is MH1's
   `AttachmentRef {id, name, type, sizeBytes, width?, height?}`; an image's `width` and `height` are as the viewer sees it
   (an EXIF-rotated phone photo is measured upright). **Limits checked before anything is parsed or held:** a body
   whose `Content-Length` passes 10.5 MB is `413 too_large` and one without a `Content-Length` is `411` (filter
   `ChatUploadLimit`, ahead of the multipart parser, matched on the decoded path so `%2D` or a trailing slash does not slip
   past it — the application's own multipart limits are the lesson pipeline's 25 MB / 120 MB, and
   `spring.servlet.multipart.resolve-lazily` means no body is parsed before a handler asks for it); the type is sniffed from the first bytes and the per-type size checked before the file is read into
   memory; an image past 8192 px on a side or 40 megapixels is `400 image_too_large`; and the stored name loses line
   breaks and every bidi/format character (`Cf`), so no RTL override can disguise it.
2. **Send** — `attachmentIds: [id…]` (at most 5) on any `POST …/messages` body or the socket's `message` command. Each must
   be the sender's own `chat` upload in the thread's school — anything else, a broadcast's upload included, is one `400` —
   and not sent before: `409 attachment_already_sent`. The bind is one conditional `UPDATE … WHERE message_id IS NULL`
   whose row count must match, so two sends racing for one file cannot both have it; the loser writes no message. With a file the text may be empty (`body: ""`). The file is bound to that message
   (`attachments.message_id`, V33) and its description written onto the message row (`chat_messages.attachments`), so
   every `ChatMessage` — REST history, `lastMessage`, the `message` frame — carries
   `attachments: [{id, contentType, name, size, width?, height?}]` (the key is absent when there are none). A chat upload
   can never be attached to a broadcast either.
3. **Read** — `GET /media/attachments/{id}` with the same token answers a chat file to the thread's **participants only**:
   the staff member on `teacher_id`, the one on `peer_user_id` (so the Admin on the threads she is on, not on the ones she
   merely reads for support), and the parent of the child the thread is about; the uploader also reads her own file before
   it is sent. Everybody else — another parent, a teacher of the same child who is not on the thread, another school —
   gets the 404 an unknown id gets. `Content-Disposition: inline` with a sanitised file name, `X-Content-Type-Options:
   nosniff`, `Cache-Control: private, max-age=31536000, immutable` (an id names one file for ever). **`?w=<px>`** is
   rounded up to 320, 640 or 1280 (anything wider is 1280) and answers a JPEG that wide, turned upright — the thumbnail
   for a bubble. Each width is made once and stored beside the original (`<path>.w320.jpg`), deleted with it; a PDF, a
   WebP or an image already that narrow answers the original. `w` is left
   out of the OpenAPI document for the same reason as above, so a generated client appends it to the URL itself.
   Weekly-plan attachments keep MH1's rule.
4. **Retention** — an upload never sent, or whose message has gone with its thread, is swept after 24 hours with MH1's
   orphans (`UploadRetention`), with its `?w=` copies. The database picks the orphans 200 at a time (at most 50 pages an
   hour), rather than the sweep loading every row.

**Previews.** A message that is files alone has an empty body, so a one-line preview is written from `attachments`:
"📷 Photo" (Arabic "📷 صورة") for an image, "📄 <name>" for a PDF. The server writes exactly that into the `chat.message`
notification row and the parent's push (English row, Arabic push on an Arabic phone); the clients write the same for a
thread's `lastMessage`.

**Typing — what was wrong.** The server already fanned a teacher's, a coordinator's and a manager's `typing` to the
parent's socket (`parent:<parentId>`) as `{"type":"typing","threadId":"…","from":"teacher"}`. The **Admin's** never left:
her token names no school, so the handshake marked her socket chat-less and every chat command of hers came back as an
`error` frame `forbidden` — and `staffTyping` had no Admin branch anyway. On QA the owner answers parents as the Admin. Her
socket commands are now scoped by the thread they name (a thread she is not on is `not_found`; the `chat` flag of its
school is checked as for her REST writes), so her `typing`, `message` and `read` all work on the socket.

What each client must do:

- **Send** `{"type":"typing","childId":"…","teacherId":"…"}` (parent), `{"type":"typing","childId":"…"}` (a teacher on a
  parent thread) or `{"type":"typing","threadId":"…"}` (everybody else, and a teacher's staff thread), at most every 2–3 s
  while the composer changes. It is never echoed to the sender's own sessions.
- **Show** a `typing` frame whose `threadId` is the conversation on screen, **whatever `from` says**, for ~4 s. On a
  staff-to-staff thread both ends are staff and `from` is `teacher` either way; the frame only ever reaches the *other*
  party, so matching the thread is enough. (The dashboard used to show only `from: "parent"`, which hid staff ↔ staff
  typing.) A parent's row with `id: null` has no thread yet, so nothing can be typed into it.

### Presence (T1)

**Who is online, and how anyone knows.** A person is online iff she holds at least one live `/ws/chat` socket. It is
read in three places and they are all one source (`ChatPresence`):

- **`ChatThread.peerOnline`** on every thread row — the *other* end of that conversation: the child's parent on a parent
  thread, the colleague on a staff one. Null-safe by construction: a row with nobody to be online about is `false`.
- **`StaffContact.online`** on the three directory routes above.
- **the `presence` frame** `{"type":"presence","online":true,"userId":"…"}` (or `parentId` for a parent), fanned out on
  **connect and disconnect** to the people who share a thread with her — and to nobody else, because presence is not a
  staff register. Her own sessions are not told; they know.

**Not a table.** Presence is worth exactly as much as the socket it describes, so nothing is stored: a connect and a
disconnect publish a `presence` event on the same `chat_events` bus as everything else, and each instance keeps what it
heard in memory. Nothing is replayed: on (re)connect, the thread list and the directory carry the current truth, and the
frames keep them fresh while the screen is open.

**The lease is short and renewed.** An entry another instance wrote expires after `quest.chat.presence-ttl-seconds`
(**120 s** — the 75 s pong deadline plus a grace), so an instance that dies without saying goodbye stops making its
peers look online within about two minutes. A live socket renews its own lease: the server re-publishes `presence` for
a peer whose last announcement is older than a third of the lease, on the `pong` the heartbeat asks for every 30 s (any
command counts). A `presence` **frame** still goes out only when the state actually changes, so a renewal is bus traffic
and nothing the clients see.

**Going offline actually happens** — the owner's second bug was a signed-out manager still shown as "Live". Four things
end a session and all four run through `ChatSessions`: the socket closing (the tab, a navigation, the hourly Cloud Run
cut), the heartbeat sweep closing one that answered no `pong` for 75 s, the dashboard closing its own socket on logout,
and **a revoked refresh token** — `POST /auth/sign-out`, a password change, a password reset, a replayed token. The last
one is server-side and **crosses the bus**: `RefreshTokenService` publishes a `SessionsRevoked` application event,
`ChatPresence` turns it into a `signout` event on `chat_events`, and *every* instance closes whatever sockets of hers it
holds with `1000 signed out` — her tabs are spread over the instances and only one of them served the sign-out. Each
close then publishes the offline event through the ordinary path. So signing out ends presence even when the client never
gets the chance to, and wherever it was connected.

**Reading it on QA.**

```bash
curl -X PUT "$API/admin/schools/$SCHOOL/flags/chat" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"enabled":true}'
curl "$API/teacher/chat/threads" -H "Authorization: Bearer $TEACHER"
curl -X POST "$API/teacher/chat/threads/$CHILD/messages" -H "Authorization: Bearer $TEACHER" -H 'Content-Type: application/json' -d '{"body":"Welcome to 1A!"}'
# the socket, with websocat: a ping answered with a pong, then the live frames
websocat "wss://${API#https://}/ws/chat?token=$TEACHER" <<< '{"type":"ping"}'
```

### Notifications (E2, D26)

The dashboard bell is server-side: a row in `notifications` (V17) plus a `notification` frame on the socket above.
Parents have their own rows since B3 (below). The contract types are `shared-api/src/commonMain/kotlin/quest/api/dto/Notifications.kt`
(`NotificationView`, `NotificationKind`, `UnreadCount`) and the frame is in `ChatFrame.schema.json` beside the
chat ones.

| Route (any dashboard role) | Permission | What |
|---|---|---|
| `GET /me/notifications?unread=true\|false&limit=` | `notifications.read` | `NotificationView[]`, newest first. `limit` 1–100, default 20. |
| `GET /me/notifications/unread-count` | `notifications.read` | `{"count": 3}` — the badge on its own. |
| `POST /me/notifications/{id}/read` | `notifications.write` | Marks one row read (idempotent); **404** for another user's id, not 403. |
| `POST /me/notifications/read-all` | `notifications.write` | Marks every unread row of the caller read; answers `{"count": 0}`. |

**Parents (B3).** The same four routes answer a parent's Firebase token with *her* rows (recipient
`parent:<parentId>`, V30; a staff row's id is a 404 to her) — `child_id` names the child, `link` is an app path:

| `kind` | When | `link` |
|---|---|---|
| `chat.message` | a teacher, coordinator, manager or Admin wrote in her child's thread — one unread row per thread, read when she calls `…/chat/threads/{staffId}/read` | `/children/{childId}/chat/{staffId}` |
| `exam.released` | the teacher released an exam (by hand or by the close-of-window sweep), one row per child of the section, once | `/children/{childId}/progress` |
| `homework.published` | a homework was published to the child's section (its results are released with it), once | `/children/{childId}/map` |
| `exam.published` | B4: an exam was published to the child's section — its title and window (in the school's zone), never its content — once | `/children/{childId}/map` |
| `broadcast.posted` | B4: a manager's or a coordinator's weekly plan, announcement or event reached the child's feed (`/management/broadcasts`, `/coordinator/broadcasts` or the older `/coordinator/announcements`), once per parent | `/children/{childId}/broadcasts?open={broadcastId}` |
| `announcement.posted` | B4: a teacher's class note (`POST /teacher/announcements`) reached the child's course, once per parent | `/children/{childId}/announcements?open={id}` |
| `question.sent` | B4: a teacher sent the child a question to answer (`POST /teacher/questions/{id}/send`), once | `/children/{childId}/teacher-questions/{id}` |
| `complaint.status` | B4/B6: a staff member resolved her complaint or opened it again (title "Complaint resolved" / "Complaint reopened") — one row per real change | `/children/{childId}/complaints/{complaintId}` |
| `complaint.message` | B6: the recipient replied in her complaint — one unread row per complaint, read when she calls `…/complaints/{id}/read` | `/children/{childId}/complaints/{complaintId}` |

Every parent row except `chat.message` is written after the release, publish, post or status change has committed, in a
transaction of its own — a failure there is logged and never undoes the release — and only once per recipient, lesson and
child: `notifications.once_key` (V31) is unique and the row is inserted with `ON CONFLICT DO NOTHING`, so two instances
sweeping the same exam cannot both tell her.

Broadcasts and notes stay readable on `GET /children/{id}/broadcasts` and `…/announcements`; since B4 they are rows here
too. The `notification` frame reaches her sockets as well (her socket is admitted while one of her children's schools has
`chat` on). With the app in the background or closed, each of these rows is also a push to her phone:
[Push notifications](#push-notifications-b4).

Two keys rather than one because the dashboard derives "what a read-only View-as session must hide" from the
methods behind a key (`pnpm gen:permissions`): a single key covering the GETs and the POSTs would make the whole
bell a write and take it off the screen during View-as.

A row is `{id, kind, title, body, link, lessonId, readAt, createdAt}`. `kind` is `lesson.needs_skills`,
`lesson.ready`, `lesson.failed`, `teacher.message`, `broadcast.posted` (RM2) or `chat.message` (T1) — **localise from
`kind`**; `title` and `body` are English server strings to fall
back on. `link` is a dashboard *path*, never a URL, and since MG1 **every row carries one, correct for the recipient's own
role** — a coordinator sent to `/teacher/…` reaches a screen she has no route to:

| `kind` | `link` |
|---|---|
| `lesson.needs_skills`, `lesson.ready`, `lesson.failed` | `/teacher/lessons/{lessonId}`, or `/admin/lessons/{lessonId}` for an ADMIN recipient |
| `broadcast.posted` | `/<area>/broadcasts?open=<broadcastId>`, `<area>` = `teacher` \| `coordinator` \| `management` by the **recipient's** role |
| `teacher.message` | `/management/messages?thread=<threadId>`, or `/management/messages` for a manager who holds no department of the sender's |
| `chat.message` | the recipient's own inbox on that thread: `/teacher/chat?thread=…`, `/coordinator/messages?thread=…`, `/management/messages?thread=…`, `/admin/messages?thread=…` — the four dashboards do not agree on the path, so the link is the one the **recipient's** router has |

The entity id the link points at is also on the row: `lessonId` for the three lesson kinds, the broadcast id for
`broadcast.posted` (which is how a replaced weekly plan's bell entries are withdrawn with it) and — T1 — the **thread**
id for `chat.message` (which is what its throttle and its read-clear key on).

**`chat.message` (T1).** The owner's reported gap was *"no notification when a teacher messages a manager"*, and it was
exact: until T1 a message was published on the socket and nowhere else, so the recipient had the thread's unread counter
and a live `message` frame if her tab happened to be open, and the bell said nothing at all — a manager whose dashboard
was closed, or who was on another screen, learned nothing. Now **every** message on a staff thread (teacher ↔ manager,
coordinator ↔ manager, manager ↔ admin) or a parent thread writes the recipient a `chat.message` row, delivered live on
the existing `notification` frame. The recipient is whoever is not the sender; a staff member writing **to a parent**
notifies nobody, because parents have no bell — they have the app. `title` is "Message from &lt;name&gt;" and `body` the
first 120 characters of what was written.

**One row per thread, not per message — and the database says so.** At most one *unread* `chat.message` row per thread
per recipient, enforced by **V26**'s unique index over the unread `chat.message` rows (a partial index on PostgreSQL,
an indexed computed column on H2, which has no partial index; the pair lives in `db/vendor/{vendor}` for that reason,
and only `chat.message` is constrained because the lesson kinds legitimately hold two unread rows for one lesson). The
write is an upsert in a transaction of its own, so two messages landing at the same moment cannot both decide she has
none unread. A second message she has not looked at yet updates the row she already has — new body, new time, the same id — so a conversation
of twenty messages is one bell entry showing the latest line rather than twenty she has to clear. Reading the thread
(`POST …/read`, over REST or the socket) marks that row read, and the next message after that rings again. A bell that
cannot be written is logged and never fails the send. One exception, so that one note does not ring twice:
`POST /teacher/messages/coordinator` appends its sentence to the staff thread (MG1) but writes **no** `chat.message`
row, because the manager already gets a `teacher.message` row for it.

**Who gets one, and when.** Only the lesson's creator (`lessons.created_by`, resolved to a `users` row; a lesson
created by the seed notifies nobody), and only on a real status *transition* written by `LessonState`:
`needs_review` → "Skills to confirm", `review` → "Questions ready", `error` or `paused` → "Generation stopped"
with the ledger's error message as the body. `analyzing` and `generating` notify nobody, a status re-asserted by a
batch notifies nobody, and polling `/status` writes no status at all and so can never make a row. A retry that
reaches `review` after an `error` *is* a new transition and does notify — she wants to know it finished the second
time too.

**Delivery.** The row is written inside the transaction that moved the lesson; the frame is published on the same
bus as chat (`chat_events`) *after that commit*, so a frame never arrives before REST can explain it. The socket
key is `user:<userId>` for every dashboard role (parents keep `parent:<parentId>`), and the hub writes only to the
sessions whose school matches the event's — an ADMIN's session carries no school and is never filtered out, since
she reads across schools anyway. Nothing is replayed after a missed frame: on reconnect, refetch
`/me/notifications/unread-count`.

**The client** (E3, `dashboard/src/app/core/notifications/notifications.service.ts`). The dashboard
opens `/ws/chat` for every role it signs in as — ADMIN, MANAGERIAL and TEACHER, `chat` flag or not —
because the socket is also how the bell hears anything. A `notification` frame goes straight into the
bell's list, the badge and one app-wide toast (plus a desktop notification, but only while the tab is
hidden and only if she granted it from the "Notify me" click). `link` is followed as a router path.
Because nothing is replayed, every (re)connect refetches `/me/notifications/unread-count` — and the
list too, if the bell had it open — and while there is no socket at all the count alone is polled every
30 s. The two POSTs are behind `notifications.write`, so a read-only "View as" session marks nothing
read and never takes a 403 for opening a row.

```bash
curl "$API/me/notifications?unread=true&limit=20" -H "Authorization: Bearer $TEACHER"
curl -X POST "$API/me/notifications/read-all" -H "Authorization: Bearer $TEACHER"
```

`GET /admin/chat/threads -H "X-School-Id: $SCHOOL"` is the support view of every thread in a school. There is no
delete: a thread is part of the school's record, and `DELETE /admin/children/{id}` (the Admin's hard delete, which
removes her threads and messages with her) is the one thing that removes one; `SEED_RESET` wipes them with the rest.

**R8, the app's coordinator side** (`docs/reports/mobile-r8.md`): the parent's thread list groups teachers and
coordinators and shows the R4 fields as chips (Complaint, Open/Resolved); `+ Message a coordinator` lists
`GET /children/{id}/coordinators` (which answers `ChatThread` rows, not a coordinator DTO) and a `This is a complaint`
switch puts `topic: "complaint"` on the **thread-creating** message only — `400 complaint_needs_coordinator` if the
peer is a teacher. The `status` frame carries **`at`**, not `resolvedAt`, and moves the row and the conversation's
banner without a refetch; a resolved thread still accepts the parent's reply, so the composer stays live. **B6** retires that
switch: the server answers it `400 complaint_moved`, and the app moves complaints to a Complaints page of its own
([Complaints (B6)](#complaints-b6)).

### Push notifications (B4)

A parent is pushed what was just written for her, on every phone she registered, through **Firebase Cloud Messaging**.
**Android only for now** — iOS needs the Apple steps at the end, and no code change. Contract:
`shared-api/src/commonMain/kotlin/quest/api/dto/Push.kt` (`RegisterDeviceRequest`, `DevicePlatform`, `PushMessage`).

| Route (parent's Firebase token) | Permission | What |
|---|---|---|
| `POST /me/devices` `{token, platform: ANDROID\|IOS, appVersion?, locale?}` | `parent.me.write` | **204.** Upsert by token: called after sign-in and whenever Firebase rotates the token. A token another parent registered **moves** to the caller (a shared phone shows only the signed-in parent's news). At most **10** phones per parent; the one seen longest ago is dropped. |
| `POST /me/devices/unregister` `{token}` | `parent.me.write` | **204**, on sign-out — also for a token she does not hold, which is left alone. The token is in the body, never the path: request logs (Cloud Run's and `RequestLogging`) record every path. |

Rows live in `parent_devices` (V32): not a tenant table (a parent belongs to no school; her children do), every read
starts from the parent id in her token, and the rows go with the parent (`ON DELETE CASCADE`, so `SEED_RESET` clears
them too).

**What is pushed, and when.** **Every staff → parent event is a `/me/notifications` row and a push** — the parent rows
in [Notifications](#notifications-e2-d26) above. The push leaves after the row's transaction **commits** (`ParentPush`,
`@TransactionalEventListener(AFTER_COMMIT)`), so a push never goes out for work that rolled back and never slows or
fails the request that caused it. A fan-out (a broadcast, a release) is **one** event and **one** read of all its
parents' phones (`findByParentIdIn`); the sends then run on the push pool's own threads:

| `kind` | From | `collapseKey` |
|---|---|---|
| `chat.message` | a teacher, coordinator, manager or the Admin writing in her child's Messages thread — a **new** unread row only (the bell's throttle: five messages before she opens the thread are one push; after she reads it, the next one pushes again) | `chat:{threadId}` |
| `complaint.message` | B6: the recipient replying in her complaint — the same throttle; the push carries `complaintId` | `complaint:{complaintId}` |
| `complaint.status` | a staff member resolving or reopening her complaint; carries `complaintId` | `complaint:{complaintId}` |
| `broadcast.posted` | a manager or coordinator: weekly plan (image or PDF), announcement, event — the feed's own predicate decides who | `broadcast:{broadcastId}` |
| `announcement.posted` | a teacher's class note | `announcement:{id}` |
| `question.sent` | a teacher's question to the child | `question:{id}` |
| `homework.published`, `exam.published`, `exam.released` | a teacher publishing a homework or an exam, releasing an exam's results (by hand or by the sweep) | `lesson:{lessonId}` |

Found and **not** pushed: the attendance register (`/children/{id}/attendance`) is a record of every child every day,
not a message, and pushing it would ring every parent every morning — an absence-only alert is a product decision.
Lessons themselves reach the map through `homework.published`/`exam.published`.

**The payload** is the FCM *data* map of `PushMessage`: `kind`, `title`, `body`, `notificationId` (the row), `childId`,
`link` (the row's app path), `broadcastId` (on `broadcast.posted`), `opensAt`/`closesAt` (epoch ms, on `exam.published`)
and `collapseKey`. On **Android it is data-only at high priority** (TTL two days, FCM `collapse_key` =
`collapseKey`): `onMessageReceived` runs in the foreground, the background and after a swipe-away alike, and the app draws
the notification itself — its own channel, wording localised from `kind` in the phone's current language, `collapseKey`
as the tag so a newer push replaces the older one, a tap that opens `link`. A notification message would be drawn by the
system tray with none of that. `title`/`body` are Arabic when the device's `locale` starts with `ar` **and** the server
has Arabic (a broadcast's or note's `bodyAr`, an untitled broadcast's kind, a complaint's status), English otherwise;
the row itself is always English. A force-stopped app receives nothing
until it is opened again — Android's rule, not ours.

**Failures.** FCM's `UNREGISTERED`, `INVALID_ARGUMENT` and `SENDER_ID_MISMATCH` delete the token. `UNAVAILABLE`,
`INTERNAL` and `QUOTA_EXCEEDED` are retried — three attempts, 1 s then 4 s apart, each ±50 % jitter so a quota error does
not bring a whole broadcast back at once (`quest.push.max-attempts`, `quest.push.backoff-millis`); a retry is
rescheduled, never slept on — and then dropped; the row is still in `/me/notifications`.

**Bounded.** At most `quest.push.concurrency` (8, capped at 16) sends run at once, on platform threads of their own —
not the virtual-thread `applicationTaskExecutor`, which has no limit — and at most `quest.push.queue-capacity` (2000)
wait, retries included. A fan-out that meets a full queue drops what does not fit and logs `push: queue full … N push(es)
dropped`; the rows are written regardless. On shutdown the sends already running finish (10 s at most) and the ones still
waiting are dropped with `push: shutting down — N waiting send(s) dropped`. Nothing is logged at INFO but
"a dead device token was removed": never a token, never a title or body. A row that could not be written is logged
and never fails the post, the message or the release.

**Configuration.** `quest.push.enabled` (`QUEST_PUSH_ENABLED`) is on under `qa` and `prod`, off elsewhere: H2, the test
profile and a laptop use `RecordingPushSender`, which keeps the last 500 sends in memory (tests read and script it).
The sender is the default Firebase app `FirebaseTokenFilter` initialises from **`FIREBASE_CREDENTIALS`** — the Firebase
Admin key of `firebase-adminsdk-fbsvc@<project>.iam.gserviceaccount.com`, which already holds
`roles/firebase.sdkAdminServiceAgent` and so `cloudmessaging.messages.create`, what `send` needs. **No project IAM change
is needed**, and Terraform makes none: the CI deployer (`roles/editor`) may not set project IAM policy, which is why a
`roles/firebasecloudmessaging.admin` grant on the runtime service account (B4's first cut) failed the QA apply with
`Policy update access denied`. Without `FIREBASE_CREDENTIALS` the app falls back to Application Default Credentials —
the runtime service account, which can verify parents' tokens but holds no FCM role — so FCM refuses every send (retried,
then dropped; the rows are still written). To turn push on in an environment, put the key in its GitHub environment
(`gh secret set FIREBASE_CREDENTIALS --env qa < firebase-adminsdk.json`, the JSON from *Firebase console → Project settings
→ Service accounts → Generate new private key*); the next deploy copies it into Secret Manager (`infra/secrets.sh`) and
Terraform wires it into Cloud Run. Terraform also lists `fcm`, `fcmregistrations` and
`firebaseinstallations.googleapis.com`, which Firebase had already enabled on QA. With `FAKE_AUTH=true` there is no
Firebase app, and an enabled sender logs one warning per push and sends nothing.

**Testing on QA.** Sign the Android app in as a QA parent whose child sits in a section (the app registers its token); put
it in the background; from the dashboard, as a teacher of that section, write in the child's thread. One notification
arrives; a second message before opening the thread does not ring again. Releasing an exam or posting an announcement to
the child's grade does the same. Without the app, with a token copied from a debug build:

```bash
curl -X POST "$API/me/devices" -H "Authorization: Bearer $PARENT_ID_TOKEN" -H 'Content-Type: application/json' \
  -d '{"token":"'"$FCM_TOKEN"'","platform":"ANDROID","locale":"en"}'          # 204
curl -X POST "$API/me/devices/unregister" -H "Authorization: Bearer $PARENT_ID_TOKEN" -H 'Content-Type: application/json' \
  -d '{"token":"'"$FCM_TOKEN"'"}'                                                            # 204
```

No route carries a token in its path, and no log line holds a token or a request body.

**iOS, when the Apple developer account exists.** No server change — the sender already builds an APNs alert (title,
body, `apns-collapse-id`, `mutable-content`) beside the same data for `platform: IOS`, because iOS does not wake a closed
app for a data-only push. The steps:

1. In the Apple developer account: *Certificates, Identifiers & Profiles → Keys → +*, enable **Apple Push Notifications
   service (APNs)**, download the `.p8` key once, note its Key ID and the Team ID.
2. Enable the *Push Notifications* capability on the app's identifier (`app.homeworkquest`) and in Xcode (*Signing &
   Capabilities*, plus *Background Modes → Remote notifications*).
3. Firebase console → project `homework-quest-qa` (then production) → *Project settings → Cloud Messaging → Apple app
   configuration* → upload the `.p8` with the Key ID and Team ID. One key serves sandbox and production.
4. Add the iOS app to the Firebase project if it is not there yet, and ship `GoogleService-Info.plist` with the app; the
   app registers with `platform: "IOS"`.

Until then an iOS registration is accepted and its sends fail with `THIRD_PARTY_AUTH_ERROR`, which is logged and dropped.

### Broadcasts (RM2, DR6)

The weekly plan, announcements and events are **one feature with two composers**, in `broadcasts` (V22). A row carries
its own audience, so the manager and the coordinator write the same shape and every reader asks it the same question.
Contract: `shared-api/src/commonMain/kotlin/quest/api/dto/Broadcasts.kt` (`BroadcastKind`, `BroadcastAudience`,
`BroadcastView`, `BroadcastFeed`, `CreateBroadcastRequest`). Behind the **`announcements`** flag — the key the feature
it supersedes already carries — so a school with it off answers 404 to composer and feed alike.

| Route | Permission | What |
|---|---|---|
| `POST /management/broadcasts` | `management.broadcast` | `kind` `weekly_plan` \| `announcement` \| `event`, `audience` a non-empty subset of `parents` / `teachers` / `coordinators`, `sectionIds` empty = the whole department, `grade` (MG1) = one grade of it. A `weekly_plan` **requires `grade` and `attachmentId`** (MH1). 201 `BroadcastView`. |
| `GET /management/broadcasts` | `management.broadcast` | What she posted, newest first, expired rows included. |
| `GET /management/weekly-plans?from=&to=&grade=` | `management.broadcast` | MG1: the archive of her department(s) — `WeeklyPlanArchive`, newest week first, each entry with `readBy`. |
| `GET /me/weekly-plans?from=&to=` | `broadcast.read` | The same archive for a teacher, coordinator or manager: the plans whose audience includes her. |
| `GET /children/{id}/weekly-plans?from=&to=` | `child.broadcast.read` | The app's archive for that child's section, with her own `unread` (MH1). |
| `POST /coordinator/broadcasts` | `coordinator.broadcast` | `announcement` or `event` for the parents of the classes she coordinates; `weekly_plan` is 400 (the plan is the department's). |
| `GET /coordinator/broadcasts` | `coordinator.broadcast` | Hers, newest first. |
| `GET /me/broadcasts` | `broadcast.read` | `BroadcastFeed` — what this teacher, coordinator or manager is an audience of, with her own `unread`. |
| `POST /me/broadcasts/{id}/read` | `broadcast.write` | Marks one row read; 404 for a row she is not an audience of. |
| `GET /children/{id}/broadcasts` | `child.broadcast.read` | The app's feed for that child, with the child's `unread`. |
| `POST /children/{id}/broadcasts/{broadcastId}/read` | `child.broadcast.write` | The parent's read mark. |
| `POST /media/attachments` | `media.attachment.write` | MH1: one image (JPEG/PNG/WebP, ≤ 5 MB) or, S1, one PDF (≤ 10 MB) as `multipart/form-data` under `file`. 201 `{id, name, type, sizeBytes}`. |
| `GET /media/attachments/{id}` | `media.attachment.read` | The bytes, to whoever may read a broadcast carrying them — or to the uploader. 404, never 403. |

Each feed has **two keys**, as the bell does: the GET is `broadcast.read` / `child.broadcast.read` and the read mark
`broadcast.write` / `child.broadcast.write`, so a read-only "View as" session still sees the feed instead of losing it
to a single key that counted as a write.

**When it is evaluated: at read time, every time.** A row stores *who it was addressed to*, not the list of people it
reached, and each feed answers it against the caller's scope as it is now. So a child placed into a British section
after the British plan was posted sees that plan, and a teacher who leaves the section loses it from her feed while her
read mark survives — nothing is back-filled and nothing is frozen. The bell is the one exception in kind: a
notification is written once, at post time, from the same predicate the feed uses, so it records who the row reached
when it was sent.

**Who receives one.** The audience is resolved from `staff_scopes` and never from the request: a manager's row names her
department (`curriculum`) or the sections she named (each checked through `ManagerScope.requireSection` — the other
department is 403, another school 404), and a coordinator's always names the classes she coordinates. A reader is in the
audience when her role is in `audience_roles` **and** the row touches her: one of her sections when `section_ids` is
set, her track — and, since V23, her **grade** — when it is not. A parent's rule is the same from the child's side — the
row is for `parents` and names her child's section, or her child's track and grade.

**`grade` (MG1, V23).** A manager's department-wide row may name one grade: `{"grade": 3}` means the sections of grade 3
in her department and nobody else, and `grade` absent (the pre-V23 meaning of every existing row) means every grade of
it — which an announcement or an event may still be, and a weekly plan may not (MH1). It may **not** be sent with `sectionIds` — those already say which grade is meant — and a grade she manages no class
in is 400 rather than a broadcast with no audience. One predicate decides all of it, so the bell can never announce a
plan the feed will not show.

Each feed answers the **newest 50 live rows of the school**, filtered to the caller; an expired row (`expiresAt` in the
past) drops out of every feed and stays in the composer's own list.

**Delivery.** A dashboard recipient gets a `notifications` row and the `broadcast.posted` frame on `/ws/chat` (the
author gets neither — she wrote it), and reads `GET /me/broadcasts`. A parent has no bell: the app polls
`GET /children/{id}/broadcasts`, and a coordinator's **announcement** additionally still writes the `announcements`
rows the app's existing screen reads, because `POST /coordinator/announcements` is now a second door onto the same
service. `unread` is `broadcast_reads`: one row per (broadcast, reader), the reader being a `users` id on the dashboard
and a `parents` id in the app.

**The weekly plan** is `kind=weekly_plan` with `weekStart` — any date in the week; the server snaps it back to the
Sunday. There is **one per week per department and grade** (V23): posting the same `(weekStart, department, grade)`
again deletes the previous row, its read marks and its bell entries, so the replacement arrives unread — while two
grades' plans for the same week are two rows that coexist. `weekStart` is required for a plan and refused on the other
two kinds. Since MH1 a plan **always** names a grade, so the all-grades plan V23 allowed beside them is gone (see "the
weekly plan is one grade's week as an image" below); rows QA wrote before MH1 keep their null `grade` and go on meaning
every grade of the department.

**The archive (MG1, owner's "see all weekly plans").** The three `weekly-plans` routes above answer
`WeeklyPlanArchive`: `weeks[]` **newest week first**, each with its `items[]` sorted all-grades first then by grade,
and each item a `{plan, readBy?}`. Absent `from`/`to` mean the **last twelve weeks** ending this one; both ends are
snapped to their Sunday, `from` after `to` is 400 and a window wider than 104 weeks is 400; one page is 200 plans.
It is the feed read backwards, with one deliberate difference: **an expired plan and a past week are still there** —
the feeds drop an expired row and the archive is the screen that must not. `readBy` (how many people opened it) is
answered on the manager's archive only, from one grouped statement; there is **no `audienceSize`** beside it, because
an audience is resolved per reader out of `staff_scopes` and counting one would be a statement per plan.

**Attachments are an upload now (MH1, V24).** RM2 could only point at bytes that already existed, so nothing could say
whether the recipient was allowed to read them. `POST /media/attachments` takes one image as `multipart/form-data` under
`file` — **JPEG, PNG or WebP, at most 5 MB, or (S1) a PDF, at most 10 MB, sniffed by `%PDF-`** — stores it in the `FileStore` under the school and answers
`{id, name, type, sizeBytes}`. The media type is **sniffed from the bytes' own magic number**, never the `Content-Type`
the client sent: a PDF announced as a PNG would otherwise be served back to a whole department as one. Keys:
`media.attachment.write` (ADMIN, TEACHER, MANAGERIAL, COORDINATOR) and `media.attachment.read` (those four and PARENT).
**S1: a weekly plan may be a PDF** — `attachment.type` is then `application/pdf` (and `attachment.name` the file name),
which is how a client chooses a PDF link over an image view; `GET /media/attachments/{id}` serves it
`Content-Disposition: inline` with that type. An announcement or an event with a PDF `attachmentId` is 400.

A composer then sends `attachmentId` on `POST /management/broadcasts` or `POST /coordinator/broadcasts`, and **only her
own upload**: anybody else's id is a 400, the same answer an id that never existed gets. The row keeps
`attachment_id`, `attachment_name` and `attachment_type` beside `attachment_url`, which becomes
`/media/attachments/{id}`, so `attachment` reads `{id, url, name, type}` and a client that only knows how to render a URL
needs no change. V22's free-text `attachment` still works, which is what the rows QA already has carry.

`GET /media/attachments/{id}` serves the bytes to **whoever may read a broadcast that carries them** — the very
predicate the feeds, the archives and the bell share, so a plan's image can never reach somebody the plan does not —
plus the uploader herself, who has to see it in the composer before she posts. Everything else is **404, never 403**,
`MediaAccess`'s rule for every id-addressed route: an id in another department must look exactly like one that never
existed. The response carries `X-Content-Type-Options: nosniff` and `Content-Disposition: inline` with the stored name
reduced to `[a-z0-9._-]`. Chat attachments are **not** in MH1: `chat_messages` has no attachment column.

**An attachment nothing points at is reclaimed.** Two places, because a re-posted weekly plan is the common case and a
sweep is the safety net. `BroadcastService.replacePlan` deletes the superseded plan's image — row **and** bytes —
**immediately**, unless the replacement names the same `attachmentId` or another broadcast still points at it.
`UploadRetention.sweep()` (hourly) then drops every `attachments` row that no broadcast references and that is more than
**24 hours** old: the grace period is what protects an upload a composer is still holding, and the reference set is read
across the whole platform (the sweep runs from the scheduler with no `school` filter enabled, so one school can never
decide another's image is an orphan).

**The weekly plan is one grade's week as an image (MH1, the owner's item 6).** `POST /management/broadcasts` with
`kind=weekly_plan` now **requires `grade`** — an all-grades plan is a 400, so RM2's department-wide plan is gone — and
**requires `attachmentId`**. `title` and `bodyEn` are optional there and the server writes
`Weekly plan · Grade N · week of <date>` into both when they are absent (`broadcasts.body_en` is NOT NULL and the bell
needs a headline), and `audience` is **ignored**: a plan always goes to the parents, the teachers and the coordinators
of that grade. Everything else is unchanged — the replace key is still (school, week, department, grade), and the
read-time predicate is still `touches`. An **announcement** or an **event** from a manager still takes `grade` for one
grade of her department or none for the whole of it, and a coordinator's is still her own classes.

**The dashboard and a PDF plan (D2, list 3).** The compose sheet accepts JPEG/PNG/WebP up to 5 MB **or a PDF up to
10 MB** (`core/broadcasts/plan-rules.ts`, checked before the upload) and names the chosen file under the drop target; a
picture also gets its preview. A plan whose attachment is `application/pdf` (by `attachment.type`, else a `.pdf`
name) is drawn by `hq-plan-pdf` wherever a picture would be — the manager's glance
cards and the archive, and the Weekly plans tab a teacher and a coordinator read — as the file name and **Open**. Open
reads `GET /media/attachments/{id}` with the bearer and shows the bytes in a new tab from a `blob:` URL; the tab is
opened inside the click and filled when the bytes arrive, and a browser that refuses the tab gets a download instead.
There is **no inline preview**: the dashboard's CSP is `default-src 'self'` with no `blob:` for frames or objects, so an
`<iframe>`/`<object>` of the blob is refused. Nothing is fetched for a PDF until Open is pressed.

**A parent has no bell, so a feed's `unread` is her notification.** `GET /children/{id}/broadcasts` already carried one;
MH1 adds `unread` to `WeeklyPlanArchive`, so `GET /children/{id}/weekly-plans` (and the two staff archives) say how many
plans in the window the caller has not opened. Dashboard recipients keep the `broadcast.posted` row and the
`?open=` link into their own area, unchanged.

```bash
ATT=$(curl -s -X POST "$API/media/attachments" -H "Authorization: Bearer $MANAGER" -F file=@plan.png | jq -r .id)
curl -X POST "$API/management/broadcasts" -H "Authorization: Bearer $MANAGER" -H 'Content-Type: application/json' \
  -d "{\"kind\":\"weekly_plan\",\"weekStart\":\"2026-09-27\",\"grade\":3,\"attachmentId\":\"$ATT\"}"
curl "$API/me/broadcasts" -H "Authorization: Bearer $TEACHER"
```

### The dashboard: what a browser remembers, and the one-school Admin (D1, list 3)

**The red "class not found" / "thread not found" on first open.** QA had been re-created as a new school and the
owner moves between roles in one browser. Two things outlived the account that made them, and the next account's
requests for those rows were answered with an honest 404 that the error interceptor painted as a band:

- **`/sign-in?returnTo=…`** is written when a session dies under a screen — a reload after the database was
  re-created is exactly that — and named the *previous* account's screen, typically a teacher's
  `/teacher/classes/{classId}`. Whoever signed in next was sent to it; an Admin passes every `roleGuard`, so she
  landed on a class page for a class that no longer exists. `returnTo` is now followed only by the account it was
  kept for: `SessionStore.lastOwner()` (the tab's own record in `sessionStorage` under `hq.session.last`, else the
  `hq.session.owner` stamp found at load) against the identity `/me` answers, exposed as
  `AuthService.continuesLastSession`. Anybody else goes to her own Home.
- **`ChatService` is a root singleton**, so the open conversation survived a sign-out and the next account's socket
  `onopen` refetched it on *her* routes. Its state now ends with the account (D2 carries the same fix for the
  coordinator, and the two are one implementation).

The rule both follow: **a 404 for a remembered or defaulted id never raises the band** — the id is dropped and the
screen falls back to its list — while a 404 for something she just clicked still does. So the teacher's class page
makes its first request with `quietNotFound()` — quiet about the 404 and nothing else, so a dead session still goes
to sign-in with `returnTo` — and, on a 404, replaces itself with My classes; nothing else on it (roster,
register, gradebook, exams) is asked for until that first answer has confirmed the class is hers.
`forgetRememberedState` (`core/auth/remembered-state.ts`) erases what an account left in storage — `hq.school`,
`hq.flags.*`, staged chat attachments — on sign-out and whenever the session's owner changes. The language, the
colour scheme and the rail's width are the browser's and stay; `hq.course.<userId>` is keyed by its user and holds
no row id.

**One switch for the multi-school surfaces.** `NAV_CONFIG.schoolSurfaces` (`core/nav/nav-config.ts`, default
`false`) hides — in design only — the header's "All schools" switcher and the rail's **Schools** and **Users** rows.
Their routes, guards and screens are untouched; turning the build back on is that one boolean. Because nobody can
then *pick* a school, the Admin is **pinned to the only one there is**: `SchoolScopeStore.schoolId()` answers the
single row of `GET /admin/schools` (resolved by `ChatRoutes`, never read from storage), so every Admin request carries
`X-School-Id`, her flags are her school's rather than the platform defaults, the new-lesson wizard has its school and
`/admin/chat/**` has its header. A stored `hq.school` is never read in this configuration and is erased at boot — an
id from before the database was re-created answers `404 school not found` to `/me` itself. A deployment that answers
**more than one** school pins her to the **first active one** in the server's list (the first row when none is
active) for as long as the switcher is hidden — no scope at all would be `400 Send X-School-Id` from her own Messages
with nothing on screen to fix it. Reaching another school means turning `schoolSurfaces` back on.

**Message, from the Admin's people screens.** Teachers, Coordinators, Managers and Children & parents each carry a
**Message** action in the row menu (shown with `chat` on and `admin.chat` held). It posts exactly one id —
`{teacherUserId}`, `{coordinatorUserId}`, `{managerUserId}` or `{childId}` — to `POST /admin/chat/threads`, hands the
answered row to `ChatService.adopt`, and opens `/admin/messages?thread=<id>` on that conversation. Admin Messages
reads `GET /admin/chat/threads?mine=true`: her own inbox and her own unread, not the school's whole chat. Children &
parents also edits the **parent's name** (`parentName` on `PATCH /admin/children/{id}`).

**Gone from every account menu:** "Show me around" (the tour, its service, component and strings) and the Admin's
"Show the raw JSON" (the view mode, the stop editor's Raw panel and the validator lines it showed).

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

> **History (2026-09-19 → 2026-10-03).** QA no longer seeds: it holds the owner's real school with `SEED_SCHOOL=false`
> ([What QA holds](#what-qa-holds-the-owners-school-no-seed)). This section records how the acceptance seed worked and
> still describes what `SEED_PROFILE=acceptance` loads on a local server; **do not run the steps below against QA** —
> `SEED_RESET` there would delete the owner's school.

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
`SEED_STAFF_PASSWORD` and no first-login password change; **two Management (MANAGERIAL)** accounts, one per
department — **Nour** (`manager@test.com`, British) and **Sami** (`manager2@test.com`, American); **two coordinators**
— **Lina** (`coord.math@test.com`, math / British) and **Omar** (`coord.english@test.com`, english / American); three
assignments — Maya on 1A + 1B British math, Rami on 1A American english. Every staff account signs in with
`SEED_STAFF_PASSWORD` — the value lives in Secret Manager and is never printed here or in a log. **No children**: they arrive when the owner registers as a parent in the
app. Re-running the seed changes nothing (sections are matched by curriculum + grade + name, teachers and managers by
their lower-cased email).

> **Why the Management account is seeded at all.** Nothing in the API creates a MANAGERIAL user — there is no
> `POST /admin/managers` — so QA had the role in `permissions.json` and nobody holding it, and every teacher's
> `POST /teacher/messages/coordinator` came back 409 `no_coordinator`. `managers.csv` (`fullName,email,curriculum`,
> **two rows** since R2 — one Management account per department) is read by the seed's own `managers` phase, which
> writes the row directly for that reason and skips an address that already belongs to somebody, at WARN. The `full`
> profile has two as well: **Huda Salem** (`manager.a@school.test`, British) and **Faris Nabhan**
> (`manager.b@school.test`, American). `coordinators.csv` (`fullName,email,subject,curriculum`) is loaded the same
> way and for the same reason — `POST /admin/coordinators` exists, but it answers a one-time password a seed would
> have to hold in order to throw away. The `full` profile seeds one coordinator per subject the platform has, each
> across both tracks (blank curriculum): math, english, science, french, religion and arabic; the two of them whose
> subject the school actually teaches see classes, and the rest exist so the role can be signed in as and read on QA.
> Each staff row also gets its `staff_scopes` row — a manager's is her department, a coordinator's her subject.
> With `SEED_STAFF_PASSWORD` unset the accounts exist but nobody can sign in as them, exactly like a seeded teacher.

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

**One level on request is one step (E5).** *Add level → Let the assistant write it* in the hand-written flow posts to
`/teacher|admin/lessons/{id}/plays/{level}/generate` (`level` = `2`, `3` or `again`, `?replace=true` to write over a
level that has questions) and runs **only** that level's step: the row is reset, the rest of the strip is left where
it is, and the lesson goes `generating` → `review` (or `error`). The analysis it reads is either already done or
derived first — Prompt A over the lesson's title and its Level 1 stops rendered as text, cached by that text's hash,
skills auto-confirmed — and it is re-derived whenever Level 1 has changed since, so the `analyze` row in the strip
counts one attempt per version of her Level 1 and an unchanged one costs nothing. Same deadlines and the same
watchdog as any other step. **A hand-written lesson still has no *Retry this step*** (`AdminLessonService.retryStep`
refuses one): the way out of a failed level is pressing *Add level → Let the assistant write it* again, which works
because `error` is editable and the failed level has no stops to refuse over. 409 `generating` is the answer while a
job for that lesson is live. The parent panel is not part of it (publish fills it).

**The generate steps run two at a time, not five in a row (D25).** Levels 1, 2 and 3 read the same analysis and the
same confirmed skills and write three different plays, so they run **together**; Again needs the stored Level 1 (it
excludes its stop ids) and the parent panel needs all three, so those two wait for the levels and then run together
in turn. Each of the five keeps its own ledger row, its own deadline, its own retry budget and its own error — a
step that fails or hangs inside a batch ends exactly as it would alone while its siblings finish, so a failure at
`generate_L2` now leaves `generate_L1` **and** `generate_L3` `done`. `lessons.current_step` shows the **lowest** step
still running while a batch is in flight. The practical effect: the generate phase's worst case is **two step
deadlines, not five**.

**How long is the worst case?** One step at a time: its deadline, times the number of attempts. A step retries a
transient failure **3 times** (`LessonSteps.TRANSIENT_ATTEMPTS`), each attempt bounded by the step's own deadline,
with `quest.pipeline.retry-delay-ms` (2 s) doubling between them — so a generate step is at most
**3 × 360 s + 6 s ≈ 18 minutes**, Analyse **3 × 240 s ≈ 12 minutes** and Convert **3 × 180 s ≈ 9 minutes**. A whole
lesson that fails at the last step is Upload + Convert + Analyse plus **two** generate batches rather than five
generate steps. The sweep's own bound is different and smaller: it only ever waits one deadline +
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

**Polling one lesson: `GET /teacher/lessons/{id}/status` and `GET /admin/lessons/{id}/status`.** The editor ticks
every 2.5 s while a lesson is running, and the full lesson is the wrong thing to ask for that often — it decodes
every play and every stop, writes each one's prose description and backfills the ledger. `/status` answers
`{ status, currentStep, errorCode, errorMessage, steps, files, plays, panel, tokenUsage, updatedAt }`
(`quest.api.LessonStatusView`) from four small reads, writes nothing, and takes the same permission (`lesson.read`)
and the same scope check as the lesson itself. It does **not** backfill a pre-ledger lesson: every job writes the
ledger before its first step, so anything actually running has one. Read the full lesson once, when the status
becomes terminal.

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

### The dashboard palette is the logo's

The dashboard's brand colours are sampled from the MySchool logo and are written in one file,
`dashboard/src/styles/_theme.scss`: the blue ramp `--hq-color-brand-*` (the logo's light blue is `brand-400`, its deep
blue `brand-700`), the magenta ramp `--hq-color-magenta-*` (secondary), the orange ramp `--hq-color-orange-*` (accent),
the brand gradient (`--hq-gradient-brand`, light blue to deep blue at 135°, and `--hq-gradient-brand-fill` for
surfaces that carry white text) and the dark-scheme roles in the `html.dark` block. Warning is yellow so that it never
reads as the orange accent. [docs/brand/palette.md](brand/palette.md) is the published table the mobile app adopts.

To change a colour: edit the ramp step in `_theme.scss`, update the same row of `docs/brand/palette.md`, and run

```bash
corepack pnpm --dir dashboard exec ng test --watch=false --include src/styles/palette.spec.ts
corepack pnpm --dir dashboard e2e      # the styleguide, EN/AR, light/dark — also re-measures the contrast row
```

`palette.spec.ts` fails when the two disagree, when a text/background pair drops under 4.5:1 in either scheme, or
when the focus ring drops under 3:1. No other stylesheet may hold a brand hex: feature styles read the ramps, the
roles (`--hq-color-brand-ink`, `--hq-color-secondary-ink`, …) or the named gradients (`--hq-gradient-blue`,
`-magenta`, `-orange`, `-neutral`, …).

`design/tokens.json` is **not** where these live. It still carries the parent-mode palette that the app's
`TokensDriftTest` and the server's default theme read, and the server ships a byte-identical copy of it.

The mark is `dashboard/src/assets/brand/myschool-mark.svg` (`myschool-mark-mono.svg` paints in `currentColor`). The
favicon, the PNG fallbacks and the maskable manifest icons in `dashboard/public/` are rendered from it. It is shown on
the sign-in page and in the sidebar only when neither the school nor the platform has a logo of its own. A school's
theme still overrides `--hq-color-accent`, surface, ink, ground and rule as before; the gradient, the accent tint and
the accent-as-text stay the logo's blue under every school.

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
