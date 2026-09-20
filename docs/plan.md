# Schools Dashboard — work plan

Planner: Claude Fable 5.1. Workers: Claude Opus 5 agents defined in `.claude/agents/` (`backend`, `dashboard`, `mobile`, `infra`, `test`, `docs`, reviewer `quality-performance`); shared rules in `.claude/AGENT_RULES.md`.
Product prompt: "Schools Dashboard — Angular multi-school platform, themes, feature flags, three roles" (the `docs` worker turns it into `docs/dev-prompt.md` in phase 3 and keeps it true to what shipped).

Every package: ≤ 1 day, one owner, one branch `<owner>/<package>`, one PR into `develop`, CI green, `quality-performance` approved, deployed to QA by the pipeline, verified by `test` when user-facing, docs updated. Shared interfaces (Flyway migrations, `server/openapi.json`, `permissions.json`, `design/tokens.json`, `shared-api/`) change first in a contract package, merge, then get consumed. No two agents edit the same file in the same package. Only the planner merges (`gh pr merge --squash` after CI + reviewer approval). Budgets that need a deployment (Lighthouse, p95, cold start) are checked by the reviewer after the QA deploy; the PR is approved on the local budgets (bundle size, tests, lint) and the post-deploy check is recorded in the Status table.

## Decisions (planner; escalated to the owner where marked ⚠)

| # | Decision | Why |
|---|---|---|
| D1 | Angular **latest stable** as installed by `ng new` — **22.1.6** (zoneless; `@angular/animations` is deprecated in v22, so the motion triggers are CSS-class directives with the same names/timings; `@angular/cdk` deferred to phase 3) rather than the literal "20" | the prompt says "Angular 20 (latest stable)"; latest stable wins, and the reviewer requires latest stable minors anyway |
| D2 ⚠ | The dashboard bundle is served by the API container at `<api>/panel/` (content-hashed assets immutable, `index.html` no-cache, SPA fallback) — **not Firebase Hosting** | the owner removed Firebase Hosting on 2026-09-14 ("keep Firebase auth only"); the API already serves the panel. Cost is the same (free tier either way); trade-off: first paint waits on a Cloud Run cold start when QA has scaled to zero. Owner can reverse this in one infra package |
| D3 | The product prompt is committed verbatim as `docs/prompts/schools-dashboard.md` (the "prompt §N" inputs below refer to it). `docs/dev-prompt.md` does not exist in the repo (the README notes the original prompt was never received; `docs/design.md` is a stand-in). The Archivo / 2px / red system referenced as "design.md parent mode" lives in `shared-ui/.../Tokens.kt` (`Palette.parent*`, `AdminTokens`) and becomes `design/tokens.json` | contract-first: one file both front-ends generate from |
| D4 | File ownership beyond the table in the prompt: `shared-api/` → `backend` (contract-first); `design/tokens.json` → created by `dashboard` in P1.0, consumed by `mobile`; `Dockerfile`, `scripts/`, `settings.gradle.kts`, `.nvmrc`, `renovate.json` → `infra` (P1.0 may create `.nvmrc`; the reviewer edits `renovate.json` only inside its deps package); `shared-ui/`, `desktopApp/` → `mobile`; `e2e/` and `docs/screenshots/` → `test`; `docs/plan.md` and `docs/prompts/` → planner (read-only for every worker) | avoids two agents in one file |
| D5 | Admin **Feature flags** and **Theme** screens move from phase 2 to phase 3 (they need the Angular shell); the Angular shell starts in phase 1, in parallel, because it has no server dependency | dependency order, not scope |
| D6 | `webAdmin/` keeps working through phases 1–2: an Admin request without `X-School-Id` reads across schools and writes to the default school (`school id = default`, the migration target for existing rows) | QA stays usable until parity |
| D7 | Node 22 in CI (`.nvmrc`); the dev Mac runs Node 25 (engine warning only). `pnpm` via corepack | prompt |
| D8 | Existing lessons/children/users migrate into one default school `default` ("Default school", code `HQ0001`, curricula american/british, grades 1–3); the seeded QA admin becomes `ADMIN` with `schoolId = null` | prompt §9.1 |
| D12 | Token economy (owner, 2026-09-16): one agent at a time; packages ≤ ~300 changed lines; workers `dashboard`/`mobile`/`docs`/`test`/`infra` run on **Sonnet 5**, `backend` and `quality-performance` stay on Opus 5; CI is the single full-suite gate, local runs are targeted; quiet tooling; one review round as the target. Remaining large packages are split (P3.2 → list / new lesson / lesson page / phone preview; P3.3 → schools+wizard / users+flags / theme+settings). | plan usage hit its ceiling |
| D9 | Dashboard auth is a new `POST /auth/sign-in` (access + refresh, role + schoolId claims); `POST /admin/auth/sign-in` stays as an alias returning the same shape until `webAdmin/` is removed | compatibility |
| D10 | The Angular dashboard is served by the API at **`/dashboard/`** (`DASHBOARD_DIR`, D2 still applies) while `webAdmin/` keeps `/panel/` until P3.6 — QA never loses the lesson pipeline mid-phase; P3.6 then removes `/panel/` (redirect to `/dashboard/`) | parity before cut-over |

## Phase 1 — Tenancy and roles (+ the Angular workspace in parallel)

| Pkg | Owner | Inputs | Outputs | Acceptance test | Depends on |
|---|---|---|---|---|---|
| P1.1 `backend/tenancy-contract` | backend | prompt §2, §5; current `V1–V3` | Flyway `V4__schools_roles.sql` (schools, classes, users+roles, refresh_tokens, invites, audit_log; `school_id` on users/children/lessons/complaints-ready tables; backfill into `default`); `permissions.json` (every existing endpoint + the new ones); `shared-api` DTOs (`School`, `SchoolClass`, `DashboardUser`, `Role`, `Invite`, `JoinSchoolRequest`, `CreateChildRequest.schoolCode`); `OpenApiExportTest` writing `server/openapi.json` + drift check; ArchUnit + JaCoCo deps; virtual threads on | `./mvnw test` green on H2; migration applied on QA by the pipeline with existing rows in `default`; `git diff --exit-code server/openapi.json` after export | — |
| P1.2 `backend/tenant-isolation` | backend | P1.1 | `TenantContext` from the JWT, Hibernate `@FilterDef("school")` on every tenant entity enabled per request, Admin `X-School-Id` switcher, `Class`-based map assembly (child = school + curriculum + grade → every published lesson of every matching Class), Teacher lesson-creation restriction | `IsolationTest`: for every tenant endpoint a Teacher/Managerial token of school A gets 404/empty for school B rows, parameterised over the endpoint list; `MapService` test with two schools | P1.1 |
| P1.3 `backend/dashboard-auth` | backend | P1.1 | `POST /auth/sign-in` (access 15 min + refresh 30 d, rotation), `/auth/refresh`, `/auth/forgot-password` + `/auth/reset-password` (email abstraction: `Mailer` with `LogMailer` for QA/tests, Resend impl behind `MAIL_PROVIDER`), forced password change on first login (`mustChangePassword`), `POST /admin/schools/{id}/invites` (one-time link), `GET /me`, `GET /me/permissions`, View-as (`POST /admin/users/{id}/impersonate` → read-only token, audit row), `@PreAuthorize` from `permissions.json`, `PermissionsTest` (every endpoint has an entry) | tests for each flow; `PermissionsTest` green; `/admin/auth/sign-in` still works for `webAdmin/` | P1.1 |
| P1.0 `dashboard/workspace-ui-motion` | dashboard | design tokens (`Tokens.kt`), prompt §0, §7 | `dashboard/` Angular workspace (pnpm, strict TS, ESLint/Prettier, Vitest, Playwright); `design/tokens.json` + `pnpm tokens` → `src/styles/_tokens.generated.scss` (CSS custom properties); `src/app/ui/` components (button, input, select, checkbox, toggle, table, tabs, card, red band, step strip, progress bar, skeleton, phone frame); `src/app/ui/motion/` triggers + mixins; Transloco EN/AR with `dir`; ESLint rules `no-product-name-literal`, `feature-flag-reference` (allow-list); a styleguide route rendering every component | `pnpm lint && pnpm test && pnpm build --configuration=qa` green; `dist` ≤ 350 kB gzipped; styleguide screenshots EN + AR attached to the PR | — |
| P1.4 `infra/dashboard-ci` | infra | P1.0 | `ci.yml` `dashboard` job (Node 22, pnpm cache, lint/test/build, tokens drift check), `renovate.json`, `.nvmrc` | CI green with the new job on a dashboard change; unrelated PRs skip it via path filter | P1.0 |
| P1.5 `mobile/tokens-pipeline` | mobile | P1.0 (`design/tokens.json`) | Gradle task generating `DesignTokens.kt` from `design/tokens.json`; `Palette.parent*`/`AdminTokens` read from it; `:shared-ui:checkTokens` drift task wired into CI's app job (via infra follow-up if needed) | `./gradlew :shared-ui:checkTokens :shared:desktopTest` green; screenshot tests unchanged | P1.0 |
| P1.6 `test/qa-seed-two-schools` | test | P1.2, P1.3 | `e2e/seed/seed.mjs` (idempotent; two schools, every role, classes, children, one published lesson per class) and `e2e/isolation.sh` (curl with the teacher tokens); `e2e/README.md` | seed runs against local H2 and QA; isolation script exits 0; the QA links in the report show both schools | P1.2, P1.3 |
| P1.7 `docs/phase1` | docs | merged P1.x | `docs/runbook.md` (roles, invites, school switcher header, seeding, sleep/wake), README section update | commands verified | P1.6 |
| P1.8 `mobile/media-auth` | mobile | reviewers' findings on P1.2/P1.3 | app sends the Firebase bearer on `/media/pages/**` (`AuthedRequests`), seeded shuffles in `MultiStops` | `LessonImagesTest`; screenshots 08/09 deterministic | P1.3 |
| P1.9 `backend/media-cache-users` | backend | P1.6 defect D1, reviewers' notes | `/media/**` authenticated and school-scoped (uniform 404), `POST /admin/schools/{id}/users` (ADMIN, `user.create`), `GET /admin/cache` scoped, reports N+1 removed, BCrypt 12 | `MediaAuthorizationTest`, `CreateUserTest`, `ReportsQueryCountTest` | P1.8 |
| P1.10 `infra/mail-env` | infra | P1.3 env var names | Terraform env `MAIL_PROVIDER`/`MAIL_FROM`/`DASHBOARD_URL`/`PLATFORM_NAME`, `RESEND_API_KEY` optional secret, workflows + bootstrap, README "Auth email" | `terraform validate`; plan 2/1/0 on QA | P1.3 |

Reviewer: `quality-performance` on every PR above.

## Phase 2 — Feature flags and themes

| Pkg | Owner | Inputs | Outputs | Acceptance test | Depends on |
|---|---|---|---|---|---|
| P2.1 `backend/flags-themes-contract` | backend | prompt §3, §4, §A; P1.3 code | `V5__flags_themes.sql` (feature_flags, school_feature_flags, flag_audit, `schools.theme_json`, `platform_settings`); DTOs; `GET /schools/by-code/{code}` (public: name, logo, curricula, grades), `GET /schools/{id}/theme` + `/flags` (public, ETag, Cache-Control), Admin flag matrix endpoints + audit, `PUT /admin/schools/{id}/theme` with contrast validation (reject < 4.5:1, name the pair), `@FeatureFlag` annotation + interceptor (404 when off), ArchUnit "every controller added after this package references a flag" (allow-list), the 14 initial flags seeded (default off except the lesson ones and `levels.three`); `PlatformSettings` (name, shortName, logoUrl, supportEmail, defaultTheme; seeded "Schools Dashboard") + ArchUnit rule on the product-name literal | tests: flag off → 404, on → 200; theme contrast rejection with the failing pair; ETag 304 | P1.3 |
| P2.2 `mobile/join-school-theme-flags` | mobile | prompt §3, §4, §6 app additions; P2.1 endpoints | Join school in Add child (code → name + logo → confirm, colour transition), theme JSON → runtime token override (logo on map header, primary/accent on tabs/buttons, Pip in `mascotColor`), `FeatureGate`, `FlagStore` sync on launch + 6 h, `:shared:checkFeatureGates`, screenshot tests, `FakeContentApi` parity | desktop tests + screenshots; QA APK joins school A and shows its colours | P2.1, P1.5 |
| P2.3 `test/flag-flip-e2e` | test | prompt §10 acceptance 2; P2.1 | script: flip `complaints` off for A → API 404 within one sync, on → 200; theme validation cases | exits 0 against QA | P2.1 |
| P2.4 `docs/phase2` | docs | merged P2.x | runbook: flags, themes, platform settings | commands verified against QA | P2.2 |
| P2.5 `test/seed-themes` | test | §10 acceptance 1 | seed applies a distinct validated theme + placeholder logo per school; `e2e/themes.sh`; `flags.sh` test appName made distinct | local + QA: themes 7/0, isolation 16/0, flags 34/0/1; both QA schools left themed | P2.2 |
| P2.6 `infra/platform-name-cleanup` | infra | P2.1 removed `PLATFORM_NAME` | Terraform/deploy README drop it; CI step title screenshot count | `terraform validate`; plan 0/1/0 | P2.1 |

## Phase 3 — Angular dashboard shell, lesson pipeline port, remove `webAdmin/`

| Pkg | Owner | Inputs | Outputs | Acceptance test | Depends on |
|---|---|---|---|---|---|
| P3.0 `backend/dashboard-endpoints` | backend | prompt §6 (Home, School page, Usage, Billing), §A | Role Home stats (`GET /me/home`), school-level usage, per-school AI cost from `tokenUsage`, `PUT /admin/platform-settings`, school CRUD + wizard endpoints (classes, first Managerial user), all-lessons with school column, `openapi.json` regenerated | MockMvc tests per endpoint; `PermissionsTest` green; p95 < 300 ms for list endpoints on the QA seed (curl ×20 after deploy) | P2.1 |
| P3.1 `dashboard/shell-auth-home` | dashboard | P1.0 ui library; `server/openapi.json` from P3.0; prompt §5, §6 shared screens, §7 | generated API client (`pnpm gen:api`), auth + error interceptors, sign-in (logo fade-in by email domain), forgot password, first-login change, role routes + guards, 240px nav with gliding red rule, page template with `pageEnter`, role Homes (count-up cards, "what needs you"), profile (EN/AR live), guided tour (4 steps per role), skeletons, Undo strip, keyboard shortcuts (`/`, `?`), RTL mirroring, Admin school switcher | Vitest units; Playwright: one login per role lands on its Home, EN/AR switch without reload; Lighthouse performance + accessibility ≥ 90 on QA after deploy; initial JS ≤ 350 kB gzipped per route | P1.0, P3.0 |
| P3.2 `dashboard/lesson-pipeline` | dashboard | `webAdmin/` screens (parity reference), `docs/screenshots/admin-v2`, prompt §7 step strip | All lessons / My lessons, New lesson (PDF, slides, images, manual), step strip animations (pulse, tick draw, shake, band expand), retry, review plays with `phone-preview` per stop type, drag-reorder, parent panel, cache, usage, calendar — parity with `webAdmin/` | Playwright against QA: create → upload → step strip → confirm skills → review → publish; every `webAdmin/` feature has a counterpart (checklist in the PR); before/after screenshots | P3.1 |
| P3.3 `dashboard/admin-schools-flags-theme` | dashboard | prompt §3, §4, §6 Admin screens 4–7; P2.1 endpoints | Schools cards, New school wizard (progress rail, slide steps), School page tabs (Overview, Users, Theme with live phone preview + 300 ms transitions, Feature flags, Classes, Usage, Billing), Users (invite/disable/reset/View as banner), Feature flags matrix (+ audit, enable/disable for all, confirmation strip), Platform settings | Playwright: create a school through the wizard, flip a flag and see the confirmation strip + audit row, save a bad theme and see the failing pair; renaming in Platform settings changes title/heading/footer | P3.1 |
| P3.4a `backend/serve-dashboard` | backend | D10 | `/dashboard/**` served from `DASHBOARD_DIR` (SPA fallback, hashed assets immutable, CSP, nosniff), invite/reset links → `/dashboard/…` | `DashboardStaticTest` | P3.0 |
| P3.4 `infra/serve-dashboard` | infra | D10; `dashboard/` build | Dockerfile `dashboard` stage (Node 22, pnpm) → `/app/dashboard`, `DASHBOARD_CONFIG` build-arg, Lighthouse CI after the QA deploy (≥ 0.9 perf + a11y), announce comment adds the dashboard URL; `server/panel` kept until P3.6 | Deploy QA green; `<api>/dashboard/` serves Angular; Lighthouse scores posted | P3.1 (parallel) |
| P3.5 `test/dashboard-e2e` | test | P3.2–P3.4 on QA; prompt §10 | Playwright suite against QA (login per role, isolation by UI, flag flip, lesson pipeline smoke); screenshot set 1366×768 EN/AR light/dark in `docs/screenshots/phase3/` | suite green against QA twice in a row; screenshots committed | P3.2, P3.3, P3.4 |
| P3.6 `infra/remove-webadmin` | infra | P3.5 verdict | delete `webAdmin/`, its `settings.gradle.kts` include, `scripts/build-panel.sh`, CI/deploy references | full CI green without the module; Deploy QA green | P3.5 |
| P3.7 `docs/dev-prompt-and-phase3` | docs | `docs/prompts/schools-dashboard.md`, merged P3.x | `docs/dev-prompt.md` (the spec, reconciled with what shipped), runbook, README (dashboard replaces webAdmin) | every command verified; no reference to `webAdmin/` remains outside history | P3.6 |

## Phase 4 — Teacher

| Pkg | Owner | Inputs | Outputs | Acceptance test | Depends on |
|---|---|---|---|---|---|
| P4.0 `backend/teacher-contract` | backend | prompt §5 Teacher, §6 screens 11–16, app additions | `V6__teacher.sql` (teacher profiles, teacher_questions, announcements), endpoints (profile, restricted chooser options, my students stats + timeline, questions to students with date window and classes, results, announcements), app endpoints (teacher island on the map, announcements in parent mode), flags `teacherQuestions`/`announcements` enforced | tests: a teacher with subjects [Math], grades [1,2], British can only create British/1–2/Math lessons (403 otherwise); a question appears on the map of children in the chosen classes only; flag off → 404 | P3.0 |
| P4.1 `dashboard/teacher` | dashboard | P4.0 `openapi.json`; prompt §6 screens 11–16 | Teacher Home, My lessons (calendar gaps), New lesson restricted chooser, Questions to students, My students (timeline, retells/drawings), Announcements | Playwright as teacher A: chooser shows only her options; publish → appears in My lessons; send a question; Home cards count up | P4.0, P3.2 |
| P4.2 `mobile/teacher-island-announcements` | mobile | P4.0 endpoints; prompt §6 app additions | "From your teacher" island with photo, Announcements card in parent mode, both gated | desktop screenshot tests; QA APK: the island appears for a child in the chosen class and not for another school | P4.0, P2.2 |
| P4.3 `test/teacher-e2e` | test | P4.1, P4.2 on QA; prompt §10 acceptance 3 | e2e: teacher of A publishes → child map in A only; question reaches the island; announcement shows in parent mode | green against QA | P4.1, P4.2 |
| P4.4 `docs/phase4` | docs | merged P4.x | runbook + dev-prompt: teacher flows | commands verified | P4.3 |

## Phase 5 — Managerial and complaints

| Pkg | Owner | Inputs | Outputs | Acceptance test | Depends on |
|---|---|---|---|---|---|
| P5.0 `backend/complaints-contract` | backend | prompt §8, §6 screens 17–20 | `V7__complaints.sql`, parent + managerial endpoints (§8), attachments (per-school folder, signed URL, 10 MB, magic bytes), notifications (email + push abstraction, assignee email, SLA digest job), stats | tests: round trip parent → staff reply (log mailer captures it) → parent thread; internal notes never in parent responses; 11 MB / wrong-magic attachment rejected; SLA stats | P3.0 |
| P5.1 `dashboard/management` | dashboard | P5.0 `openapi.json`; prompt §6 screens 9, 17–20, §7 | Managerial Home, Complaints inbox + conversation view (reply, internal notes, assign, status, resolve category, SLA badges amber 24 h / red 48 h), School usage, Teachers; Admin All complaints | Playwright as managerial B: open → reply → assign → resolve with category; badges by age; Admin sees all schools read-only | P5.0, P3.1 |
| P5.2 `mobile/help-complaints` | mobile | P5.0 endpoints; prompt §6 app additions | Parent mode Help & complaints (chips, message, screenshot, lesson link), thread view, reply notifications | screenshot tests; QA APK sends a complaint with a screenshot | P5.0 |
| P5.3 `infra/mail-push` | infra | P5.0 needs (`MAIL_PROVIDER`, `RESEND_API_KEY`), Firebase project | secrets by name in Secret Manager + Terraform env; FCM via the existing Firebase project if push is used | `terraform plan` clean; QA sends through the log mailer until a key is provided | P5.0 |
| P5.4 `test/complaint-round-trip` | test | P5.1, P5.2 on QA; prompt §10 acceptance 4 | complaint from the QA APK → inbox ≤ 10 s → reply reaches the app thread and the log mailer | green against QA | P5.1, P5.2 |
| P5.5 `docs/phase5` | docs | merged P5.x | runbook: complaints, SLA digest, mail provider | commands verified | P5.4 |

## Phase 6 — Admin usage & cost, polish, final acceptance

| Pkg | Owner | Inputs | Outputs | Acceptance test | Depends on |
|---|---|---|---|---|---|
| P6.0 `backend/usage-cost` | backend | prompt §6 screen 10, 19 | platform usage (children, plays/day, AI calls, cache hit rate, cost per school), `UsageDaily` rollup job | tests on the rollup; p95 < 300 ms on QA | P5.0 |
| P6.1 `dashboard/usage-cost-and-polish` | dashboard | P6.0; prompt §7 (every animation) | Platform usage & cost charts (ECharts, line draw 500 ms), wizard polish, animation audit vs §7, screen recording | every §7 animation visible in the recording attached to the PR; Lighthouse ≥ 90 | P6.0 |
| P6.2 `test/final-acceptance` | test | prompt §10 | full Playwright suite; screenshot set of every screen for every role (1366×768, light/dark, EN/AR); Lighthouse report | all five §10 acceptance items green against QA | P6.1 |
| P6.3 `docs/final` | docs | merged everything | `docs/dev-prompt.md` reconciled, README, runbook | no drift between docs and code (spot-checked by the reviewer) | P6.2 |
| P6.4 `quality-performance/deps` | quality-performance | lockfiles, `renovate.json` | weekly dependency update PR (manifests + lockfiles only — the one case where the reviewer edits other owners' files) + `docs/quality.md` | full CI green | recurring |

## Carried over from phase 1 (not blocking; folded into later packages)

- **Test hygiene** (backend, into P2.1): `TenancyContractTest` asserts over every lesson row and passes only because of run order; scope it to the seeded lessons or make the other classes clean up.
- **Screenshot determinism** (mobile, into P2.2): `02b-world-map-empty` and `03-journey` still vary between runs because of `rememberInfiniteTransition` (`WorldMapScreen.kt`, `Pip.kt`); freeze under test.
- **Token gaps** (dashboard + mobile, a small tokens package before P3.1): `font.label-*` (14 px) matches no Kotlin label step (15/12 px); `letter-spacing-label` 0.02em vs 0.14em; no tokens for description/navItem/cardTitle/mono and the parent Material steps; `space.*` deliberately unmapped in Kotlin.
- **Sign-in rate limiter is per instance** (Cloud Run may run several) — a shared store or Cloud Armor is a later infra/backend package.
- **`GET /lessons/{id}` without `childId`** still serves any published lesson to any authenticated parent (content is public-by-id, cached per version in the app) — decide in phase 2 with the flags work.
- **`webAdmin/RemoteAdminApi.imageBytes` comment** still says media is public — goes away with P3.6. **README** still says "six GitHub Actions workflows" (five + Dependabot) — fix in P2.4.
- **`ApiError` lacks a `conflict` constant** (used by `user.create` 409) — add when the dashboard matches on it (P3.1).
- **Owner actions**: install the Renovate GitHub App (Dependabot version updates are now off for gradle/maven/github-actions); provide `RESEND_API_KEY` + a verified `MAIL_FROM` and set `mail_provider = "resend"` in `envs/qa.tfvars` when real invite emails are wanted; the 15 open Dependabot PRs (#2–#16, incl. Spring Boot 4 and Flyway 13 majors) are left for P6.4.

## Carried over from phase 2

- `HomeService` still emits the English literal "Untitled lesson" as a `lessonTitle` param — omit it and let the catalogue own the fallback (P3.1/P3.2).
- `flags.sh` assertion (c) is SKIPPED until a callable route carries `@FeatureFlag` (P4.0 makes it a real 404/200 check automatically).
- Seed logos are `placehold.co` placeholders — switch to an upload route when one exists (phase 5+).
- `isolation.sh`'s `json()` helper has the jq `//` null trap (booleans/nulls); `flags.sh` reads them via node — align in the next test package.
- Workers sharing `~/.m2`: `:shared-api:publishToMavenLocal` from one worktree can overwrite another's mid-run (`NoSuchMethodError` on `quest.api.*`) — re-publish and re-run; a per-worktree Maven repo is a possible infra improvement.
- Sign-in rate limiter per instance; `GET /lessons/{id}` without `childId`; token font gaps — unchanged from phase 1.

## P-CI and the repo visibility

- **P-CI `infra/ci-cost`** = PR #54 (owner change request): macOS off PRs (`ios.yml`), path-filtered jobs, docs-only job, caches, Testcontainers singleton, Playwright after the QA deploy, weekly cost report; before = 16.7 billed min/run; after to be measured on its first green run.
- 2026-09-16: card payments failed on the `educationkidsapp-ai` account, so the repo was made **public** on the owner's instruction to keep Actions free; **revert to private at the end of the programme** (`gh repo edit educationkidsapp-ai/homework-quest --visibility private --accept-visibility-change-consequences`). #49, #51, #52 merged on local reviewer verification while Actions was blocked.


## Dashboard-first, one school (2026-09-18) — supersedes the phase order above

Owner prompts: `docs/prompts/dashboard-first-one-school.md` (the build order), `docs/prompts/homework-quest-teacher-v1-prompt.md` (model, screens, gradebook §7, exams §8), `docs/teacher-flow.md` (the reference flow — code and briefs point here). Goal: **the whole dashboard for one school, teacher first, zero mobile work; the web player stands in for the app.**

### Decisions

| # | Decision | Why |
|---|---|---|
| D13 | One school in product terms: `multiSchool` flag (off) hides the Admin school screens and the switcher collapses to the user's school; the tenancy code (filter, `schoolId` on every table, JWT resolution) stays exactly as built. QA keeps its Al Noor / Green Valley fixture rows — they are the "second school proven on QA" of §10. A new ArchUnit rule forbids school-id literals in services/controllers/repositories; the allow-list is exactly `TenantContext.writeSchoolId()` (the D6 `default` fallback for Admin writes), the seed classes and `@Entity` column defaults. | prompt §1 |
| D14 | Classes become **sections**: the existing `classes` table (school, curriculum, grade, subject, teacher) gains `name` ("1A"), `join_code`, `active`; `subject`/`teacher_id` stop being used: V7 runs `ALTER COLUMN subject DROP NOT NULL` (and `children.parent_id DROP NOT NULL`) — column relaxations are allowed by the additive rule; sibling sections keep `subject`/`teacher_id` NULL so the surviving `UNIQUE (school_id, curriculum, grade, subject, teacher_id)` never collides (NULLs are distinct on PG 16 and H2); new `teaching_assignments(teacher_id, class_id, subject)` UNIQUE(class_id, subject); `children.class_id` (parent_id nullable), `lessons.teacher_id`, `lessons.type` (homework/exam). Backfill: one section per existing (school, curriculum, grade) — "<grade>A" — lessons and children re-pointed, assignments derived from the old rows' `teacher_id`. `courses` stays, deprecated. | teacher-v1 §2 within the additive-migration rule |
| D15 | Workers back on **Opus 5** (owner, 2026-09-18); Fable 5.1 plans. The rest of D12 stands: one agent at a time, small packages, quiet tooling, CI as the gate. | owner instruction |
| D16 | No `shared/`, `androidApp/`, `iosApp/` changes in this build; `POST /children` and `GET /classes/lookup?code=` are added server-side so the app can adopt them later. The old P4.1/P4.2/P5.2 mobile packages are dropped from the queue. | prompt §1 |
| D17 | The dashboard is on Angular 22 (D1) although the prompt says 20. | unchanged |
| D18 | Initial-bundle budget raised 420/480 → 500 kB warn / 520 kB error for the TailAdmin restyle (T1 #79, T5 #83); Lighthouse ≥ 0.9 on every QA deploy stays the real performance gate (97/100 on c9fe6f7). | the spec is rebuilt in SCSS without Tailwind, the shell and kit live in the initial chunk |
| D19 | Focus ring: the repo-wide accessible outline (brand-500, ≥ 3:1) plus the spec's `rgba(70,95,255,0.1)` halo; the spec's `#9cb9ff` border (1.94:1 on white) is not adopted. Other deviations are listed in `docs/prompts/tailadmin-spec.md` → Deviations. | accessibility over literal fidelity |
| D20 | QA doubles as the owner's acceptance environment on the `acceptance` seed; the automated e2e suite is parked (`E2E_ON_QA=false`) until a second environment or a self-seeding suite exists. | owner request 2026-09-19 |
| D21 | Initial-bundle budget 540 kB warn / 580 kB error; every feature lazy; the generated API client grows with each route and a later package should find why unused generated services are not tree-shaken. | #107 |

### What already exists (reused as is)
Tenancy + filter + roles (P1.x), auth with refresh/forced change/forgot password (P1.3), flags with `@FeatureFlag`/`*hqFeature`/`FlagService` (P2.1/P3.1), themes + platform settings (P2.1), typed OpenAPI client (P3.0b), dashboard shell/Homes/profile (P3.1), lessons list / new lesson / lesson page with step strip, skills, plays + phone preview, publish (P3.2b–d), 22 stop-type previews (P3.2a), teacher profile/options/questions/announcements/students (P4.0 — the questions/announcements routes stay behind their flags, hidden from this build's navigation).

### Phases and packages (one agent at a time; owner `backend` = Opus, `dashboard` = Opus)

| Pkg | Owner | Outputs | Acceptance | Depends on |
|---|---|---|---|---|
| **N1 Foundations** | | | | |
| N1.1 `backend/sections-assignments` | backend | `V7__sections.sql` per D14 + backfill; `TeachingAssignment` entity + unique; `Child.classId` (+ `parentId` nullable), `Lesson.teacherId/type`; `TeacherScope` (assignment-scoped checks replacing the subject/grade rule) + ArchUnit "every `/teacher/**` handler calls it" + "no school-id literal"; Admin API: classes CRUD + `POST /admin/classes/{id}/join-code` + printable code-card PDF, teachers CRUD (temporary password returned once), `PUT /admin/teachers/{id}/assignments` (409 naming the current teacher), rosters `GET/POST/PATCH /admin/classes/{id}/children` + CSV/XLSX import with preview and duplicate detection; `GET /me` gains `assignments`; public `GET /classes/lookup?code=`; `POST /children` accepts `joinCode`; flags `multiSchool` (off), `webPlayer`, `gradebook`, `openStopMarking`, `exams`, `teacher.rosterEdit`, `join.byList`; platform settings gain `schoolWeek` (Sun–Thu) + `timezone`; seed loader for `qa` from `server/src/main/resources/seed/{classes,teachers,children}.csv` (30 classes, 40 teachers, 600 children) | tests: teacher B cannot read A's class (403), second Math teacher for 1A rejected with the current teacher's name, roster import preview + duplicates, join-code lookup; migration on H2 + PG; `openapi.json` regenerated | — |
| N1.2 `dashboard/admin-classes-teachers-rosters` | dashboard | Admin screens: Classes (create, join code, print card, deactivate), Teachers (create with one-time password, subjects, curriculum, photo; assignment picker that blocks a taken class and names the teacher), Children rosters (per class, CSV/XLSX import preview), Platform settings (name, logo, school week, timezone); Schools/switcher hidden behind `multiSchool` | Playwright: Admin creates 1A/1B + Sara assigned to both; second Math teacher for 1A refused with the name; import a 3-row CSV | N1.1 |
| N1.3 `test/seed-one-school-e2e` | test | seed run on QA (CSV loader), isolation e2e for assignments, screenshots | a seeded teacher signs in and sees only her assignments | N1.2 |
| **N2 Teacher: lessons** | | | | |
| N2.1 `backend/teacher-lessons-api` | backend | `GET /teacher/week?start=` (assignments × days, one query), `GET /teacher/classes`, `/classes/{id}/calendar`, `/classes/{id}/children`, `POST /teacher/lessons` (classId, subject, date, source), `PATCH …/{id}` (move date while unpublished), `POST …/{id}/copy`, `POST …/{id}/publish` `{classIds}` (server copies per class), `/unpublish`, `DELETE`, `analyzedBefore` on the lesson DTO; teacher-scoped pipeline routes (`/teacher/lessons/{id}/retry`, skills, stops) delegating to the existing services | tests incl. publish-to-siblings copies with separate results; p95 < 300 ms on `/teacher/week` and `/teacher/classes` with the seed | N1.1 |
| N2.2 `dashboard/this-week` | dashboard | This week grid (rows = assignments, columns = school week), lesson/exam cards, `+` opens the editor pre-set, drag to move (same row) and drag to copy (sibling row), gap summary strip, prev/next/Today; teacher landing page + navigation `This week · My classes · [class] · Profile` only | Playwright: drag-move a draft; drag-copy to 1B; gap names class + day | N2.1 |
| N2.3 `dashboard/my-classes-class-page` | dashboard | My classes cards; Class page tabs Calendar (month + results column) and Children (roster, add/edit under `teacher.rosterEdit`); Gradebook/Exams tabs stubbed | Playwright per teacher-flow §10 steps 2–3 | N2.1 |
| N2.4 `dashboard/lesson-editor-complete` | dashboard | finish P3.2e (stop editor with schema validation, + Add stop, reorder, attach image, parent-panel editor), class+subject fixed in the editor, publish sheet with sibling classes, re-publish with version, delete draft/error, "Analyzed before · 0 tokens" badge, Preview-as-child button (opens the gallery until N3) | Playwright: teacher-flow §10 steps 3–4 | N2.1, N2.2 |
| N2.5 `test/teacher-lessons-e2e` | test | e2e on QA: Sara uploads, reviews, publishes to 1A+1B, second upload costs 0 tokens | green on QA | N2.4 |
| **N3 Web player** | | | | |
| N3.1 `backend/test-parents-preview` | backend | test-parent accounts per class (`webPlayer` flag), preview attempts marked `previewOf=teacherId` and excluded from results, "record as real" toggle on QA, media upload via the existing endpoints, `previewOf` marker in `Attempt` | tests: preview attempts never in results | N2.1 |
| N3.2 `dashboard/player-core` | dashboard | `/play/:lessonId?as=:childId` — frame, pot + ingredients, stars, wrong-answer sheet, level selector/unlocking, certificate, `speechSynthesis` read-aloud, attempts POSTed through the real endpoints; 11 stop types playable | Playwright: Hot Soup L1 end to end → attempts in Results ≤ 5 s | N3.1 |
| N3.3 `dashboard/player-stops` | dashboard | the other 11 stop types incl. `MediaRecorder` retell, canvas drawing, trace; exam mode hooks | all 22 playable; child-mode rules asserted | N3.2 |
| N3.4 `dashboard/player-gallery` | dashboard | `/player/gallery` — every stop type in every state | screenshots committed | N3.3 |
| **N4 Gradebook, levels, exams** | | | | |
| N4.1 `backend/scoring-marks-levels` | backend | `HomeworkScore` computation, `TeacherMark`, `ChildLevel` bands (`grading/Bands.java`), release flag, results/gradebook/child endpoints, CSV/XLSX exports (POI), `seed/attempts.csv` loader | hand-computed sample matches; gradebook query < 1 s on the seed | N3.1 |
| N4.2 `dashboard/results-gradebook-child` | dashboard | Results page with marking + release, Gradebook tab (grid, bands, overrides, needs-marking filter, exports), Child page (band, trend, chart, comments, saved work) | Playwright per teacher-flow §10 steps 5–6 | N4.1 |
| N4.3 `backend/exams` | backend | `Lesson.type=exam`, `ExamSettings`, window + single resumable attempt (409 on second), `ExamResult`, reopen per child, release modes, results/distribution/difficulty endpoints, CSV/XLSX, per-child PDF sheet | tests for window, single attempt, absent + reopen | N4.1 |
| N4.4 `dashboard/exams` | dashboard | Exams tab, New exam settings, results page, release, exports; player exam mode (no hints, no numbers, resumable) | Playwright per teacher-flow §10 step 7 | N4.3, N3.3 |
| N4.5 `test/gradebook-exams-e2e` | test | e2e on QA + p95/gradebook timing | green | N4.4 |
| **N5 Admin full + Managerial** | | | | |
| N5.1 `dashboard/admin-flags-users-usage` | dashboard | Feature flags (one column while `multiSchool` off), Users (reset, disable, View as + audit), Usage & cost, cache page, audit log | Admin flips `exams` off → tab + API gone within one refresh | N4.4 |
| N5.2 `backend/complaints-staff-logged` | backend | complaints model + endpoints (§8 of the schools prompt) incl. "log on behalf of a parent", SLA digest, notifications via the mailer | round-trip tests with a simulated clock | N4.1 |
| N5.3 `dashboard/managerial` | dashboard | Managerial Home, inbox + conversation, log a complaint, school usage, teachers read-only | Playwright: New → In progress → Resolved with SLA badges | N5.2 |
| **N6 Polish, docs, production** | | | | |
| N6.1 `dashboard/polish` | dashboard | motion pass, EN/AR pass, Lighthouse/perf budgets on every screen | recorded walkthrough per role | N5.3 |
| N6.2 `test/final-e2e` | test | Playwright suites per role on QA; second-school proof (seed row + user; two teachers cannot see each other) | green | N6.1 |
| N6.3 `docs/platform-docs` (P-DOCS, resumed) | docs | the seven area docs + index + `docs-links.yml`/`docs-review.yml` | reviewer spot-checks | N6.1 |
| N6.4 `infra/production` | infra | `homework-quest-prod` project, `deploy-prod.yml` with owner approval, `v1.0.0`; repo back to private | first production deploy | N6.3 |

Superseded: P3.2e (→ N2.4), P3.3 (→ N1.2 + N5.1), P3.5/P3.6 (→ N6.2 / after N6), P4.1/P4.2/P5.x/P6.x old numbering (mobile parts dropped per D16).

### Restyle — the TailAdmin spec, rebuilt without Tailwind (owner, 2026-09-19)

Spec: `docs/prompts/tailadmin-spec.md` (literal values; §5 dark mode is a planner addendum). Report: `docs/reports/tailadmin-restyle.md`.

| Pkg | Owner | Scope | Done when |
|---|---|---|---|
| T1 `dashboard/theme-foundations` | dashboard | ramps/roles/type/radii/shadows/spacing as `--hq-*` over the generated tokens; Outfit (Latin) + Plex Arabic; `DarkModeService` (`dark` class, `localStorage.theme`); styleguide | school theme still overrides at runtime; no light flash; D18 |
| T2 `dashboard/theme-shell` | dashboard | sidebar 290/90 + drawer < 1024 (dialog, inert), header (burger, language, scheme, account menu), content well 1536 | routes/guards/`screens.ts` untouched; RTL; a11y |
| T3 `dashboard/theme-kit-pages` | dashboard | every `hq-*` component to §3; the eight teacher screens; calendar/week overflow fixed | no sideways page scroll at 1366/768/375 EN+AR |
| T4 `test/theme-verification` | test | console/NG0 gate on the suite, fail-fast sign-in, `theme-flow.spec.ts`, styleguide variants, the report | QA green, Lighthouse ≥ 90 |
| T5 `dashboard/theme-polish` | dashboard | page crops fetched with the bearer (`hqPageImage`, LRU cache ended by sign-out), dark AA roles, tabs hover, budgets, D19 | report §4 resolved |

## Status

**Phase 1: done 2026-09-16.** **Phase 2: done 2026-09-16** (server flags/themes/platform settings, app join-school/theme/gates, e2e, docs). QA runs `cb49991`; both QA schools themed. Phase 3 partly done, then replanned (2026-09-18) into N1–N6. **N1–N2 done 2026-09-19: the teacher flow is on QA** (`0b800fc`, e2e 47/0 from the planner's machine; the post-deploy job needs the `qa` secret `E2E_STAFF_PASSWORD` set to the seeded staff password). **Restyle T1–T5 done 2026-09-19** (`2c12c5a`, Lighthouse 97/100). **Change request CR1–CR5 done 2026-09-19** (`2753d2e`; Markdown-first pipeline proven on QA). **QA is the owner's acceptance environment since 2026-09-19**: `SEED_PROFILE=acceptance` (Maya math 1A+1B British, Rami english 1A American, join code `HQ0001`, no children), the automated QA e2e job parked via the repository variable `E2E_ON_QA=false` until QA is back on the full seed; the mobile parent flow passes on the emulator (`docs/reports/mobile-parent-acceptance.md`). **N4 done 2026-09-20** (`docs/reports/n4-verification.md`); the child's side of exams and results in the app still needs D16 lifted. **Paused for the owner's manual test on QA** (owner instruction); next when resumed: exams-tab fan-out cleanup, D16 decision → app package, N5, N6. Open: N3 web player, N4 gradebook/exams, N5, N6, N1.2b; owner decision on the mobile app vs web player; repo back to private at the end.

| Pkg | Branch / PR | State | Notes |
|---|---|---|---|
| setup | #26, #28 | merged | agent definitions + this plan |
| P1.1 | #27 | merged, on QA | V4 migration applied on Cloud SQL; `admin_users` kept (additive-migration rule) |
| P1.0 | #29 | merged | Angular 22.1.6; 82 kB gzipped initial; Lighthouse 100/93 |
| P1.4 | #32 | merged | `dashboard` CI job 1m07s; Renovate config (app not installed yet) |
| P1.5 | #33 | merged | 43 tokens mapped + drift-asserted; screenshots unchanged |
| P1.2 | #30 | merged, on QA | review found fail-open scope for school-less staff → fixed (fail closed, 85 tests) |
| P1.3 | #31 | merged, on QA | review found 5 blockers (XFF rate-limit bypass, unscoped school list, weak coverage test, reset reviving disabled accounts, timing enumeration) → all fixed (107 tests) |
| P1.8 | #34 | merged | app sends bearer on media; seeded shuffles |
| P1.6 | #35, #39 | merged; **QA acceptance green** | seed exit 0 on QA; isolation 16 PASS / 0 FAIL ×2 with real teacher/managerial sessions; QA fixture: Al Noor `ALNOOR` (`5c5bc15a-0e3b-4d87-b3a2-d04f7bc267e2`), Green Valley `GREENV` (`f20151c4-719f-4ee8-9034-16b9e0bead8f`); staff/parent passwords live only in the planner session scratchpad `qa-e2e.env` — reset the accounts if lost |
| P1.9 | #36 | merged, on QA (`f7aa181`) | one review item (uniform 404 body) fixed; 121 server tests |
| P1.7 | #37 | merged | two review items (PATH in fenced blocks, workflow count) fixed |
| P1.10 | #38 | merged, on QA | env applied; `RESEND_API_KEY` container created, not wired until a version exists |
| P2.1 | #41 | merged, on QA | review: theme `logoUrl`/`appName` validation + two N+1s → fixed; 158 tests |
| P2.2 | #45 | merged, on QA | review: `levels.three` gate leaked via the finish screen → closed at all three doors; 51 deterministic screenshots |
| P2.3 | #42 | merged | review: restore trap trusted PUT status → now read-back based; 34/0/1 local + QA |
| P2.5 | #46 | merged | QA schools themed (AN green / GV navy); contrast parity with `Contrast.java` verified |
| P2.4 | #47 | merged | runbook flags/themes/platform settings; matrix 44 × 74 verified cell-by-cell |
| P2.6 | #48 | merged | PLATFORM_NAME dropped from Terraform/deploy README; QA plan: no changes |
| #43 | #43 | merged | unplanned: shellcheck for `e2e/` in CI |
| P3.0 | #44 | merged, on QA | review: unbounded teacher Home, prose in payload, `schoolName` null, dead overload → fixed; 218 tests; 71 OpenAPI paths |
| P3.1/P3.1b/P3.4/P3.4a | #50, #52, #53, #56 | merged | Angular shell, sign-in, screenshots, dashboard served at `/dashboard/` (D10), Lighthouse job |
| P3.0b/P3.2a–d | #59, #57, #58, #60, #61 | merged | lesson pipeline port: list, detail, new lesson, step strip, retry, publish/undo |
| P-CI | #54 | merged | Actions minutes −73 %; path filters; one agent at a time (D12) |
| plan | #55, #62 | merged | token economy; dashboard-first one-school build order (D13–D17) |
| N1.1a/b, N1.4, N1.2a | #63, #64, #65, #66 | merged, on QA | V7 sections/assignments/rosters, `TeacherScope`, `SchoolSeed` (31 classes / 40 teachers / 600 children), Admin classes + teachers |
| N2.1 | #67 | merged, on QA | `/teacher/week`, classes, lessons create/move/copy/publish-to-siblings, V8 lineage, `analyzedBefore` |
| N2.2 | #68 | merged, on QA | This week grid, drag move/copy |
| N2.3 / N2.3b | #69, #70 | merged, on QA | My classes, class page; review found grade-scoped students leak → section-scoped `/students` |
| hotfix | #71 | merged, on QA | `SchoolSeed` reconciles assignments, never aborts startup |
| N2.4a / N2.4b-api / N2.4b | #72, #73, #76 | merged, on QA | stop editor (22 templates, precompiled Ajv — CSP forbids `new Function`), parent panel, role façade; `GET /teacher/lessons` assignment-scoped + last aliases; publish sheet with siblings, lifecycle, badge, `ar` deep-reload fix |
| hotfix | #74, #75 | merged | Docker: shared-api schemas into the dashboard stage; `pnpm schemas` creates its output dir |
| N2.5 | #77 | merged, on QA | QA suite rewritten around the teacher flow; two-school specs retired; `shoot()` render barrier; **teacher flow on QA** |
| N2.6 | #78 | merged, on QA | stray stop properties (`hint`) dropped before the play validator — cold-cache uploads no longer fail 3/4 |
| T1 | #79 | merged, on QA | foundations + dark mode; review: bezel/overlay surfaces in dark → fixed; D18 |
| T2 | #80 | merged, on QA | shell; review: drawer dialog semantics + `aria-controls` → fixed |
| T3 | #81 | merged, on QA | kit + teacher screens; calendar/week overflow fixed |
| T4 | #82 | merged, on QA | verification; found page-crop 401, chip hover specificity, non-route home frames → fixed in T4/T5 |
| T5 | #83 | merged | page crops via bearer, dark AA, LRU media cache ended by sign-out, budgets 500/520 (D18), D19 |
| T-infra | #84, #85, #86 | merged | plan status; styleguide e2e in CI; e2e seed-ready gate |
| CR3 | #87 | merged, on QA | readability tokens: body 16, secondary 14, titles 26/19, well 1760, ink-soft gray-600 |
| CR1 | #88 | merged, on QA | "This week at a glance" removed (rebased after the #87 squash) |
| CR5 | #89 | merged, on QA | stops carry `teacherText`; `POST /teacher/stops/{id}/from-text` (strict schema, one retry, 422 rephrase); prose editor; Raw JSON only for ADMIN via `viewMode` |
| CR2 | #90 | merged, on QA | Add stop is one form (title, question, type, picture, parent tip); template menu retired |
| CR4 | #91 | merged, on QA | `convert` step: anydoc / Tesseract → `.md` beside the upload; Prompt A reads Markdown only; preview + OCR/typed fallbacks; review fixed school-scoped reuse, profile-gated fallback, retention, async OCR |
| acceptance | #92, #93, #94 | merged, on QA | `SEED_PROFILE`/`SEED_RESET` (one-shot, refused on prod, PostgreSQL-tested), roster attach/detach, `GET /admin/children?unassigned=true`; QA wiped and reseeded; e2e job parked |
| mobile check | #95 | merged | parent flow passes; found the generation hang |
| watchdog | #96 | merged, on QA | LLM connect 10 s / read 120 s, step deadlines, 60 s sweep → `error/timeout` + retry; `DELETE /admin/children/{id}` |
| planner | #97 | merged | status rows, D20, `pnpm --dir` rule |
| follow-ups | #98 | merged, on QA | This week shows today on Fri/Sat; budget-aware LLM retry; V12 index; `ContentSeed` off QA; `SEED_RESET_TOKEN`; `DashboardApi` roster methods |
| roster place | #99, #100 | merged, on QA | `GET /teacher/classes/{id}/children/unassigned`; create/move refuse non-teaching days (409); Children tab "Place an existing child" + row ⋯ menu; weekend column takes nothing; `teacher.rosterEdit` enabled for the Default school on QA |
| test clock | #101 | merged | `Clock` bean; `SchoolDataTest` was hour-sensitive after 18:00 UTC; watchdog sweep off in the test profile |
| N4.1 | #104 | merged, on QA | scoring (first-try rule, bands), teacher marks, release (homework auto on publish, exams manual/auto-on-close), results/gradebook/child endpoints, CSV/XLSX, `seed/attempts.csv`; flags `gradebook`, `openStopMarking` on for the Default school on QA |
| N4.2 | #105 | merged, on QA | Results page (marking, override, release, CSV), Gradebook tab, Child page |
| N4.3 | #106 | merged, on QA | exams: V14 settings/attempts, window, single resumable sitting (409), reopen once, release sweep, results/distribution/difficulty, PDF; N4.2 server gaps; flag `exams` on for the Default school on QA |
| D21 | #107 | merged | initial bundle 540 warn / 580 error (generated client grows per route) |
| N4.4 | #108 | merged, on QA | Exams tab, New exam, settings card, exam results page; shared mark panel |
| follow-ups | #109 | merged, on QA | exam day in the school clock, list rows with state/counts, `GET /teacher/exams/{id}`, race → 409, private cache for exam bodies |
| N4.5 | #110 | merged | `n4-flow.spec.ts` (child via the API), p95 gradebook 21 ms local, QA probes; found D1–D6 (`docs/reports/n4-verification.md`) |
| fix D1 | #111 | merged, on QA | results columns per level, child rows on the level she played, `stop_not_played` 409, exam sat/absent/percent, `practiceLength` 5–12 |
