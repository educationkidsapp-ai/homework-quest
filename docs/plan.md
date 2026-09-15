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
| D9 | Dashboard auth is a new `POST /auth/sign-in` (access + refresh, role + schoolId claims); `POST /admin/auth/sign-in` stays as an alias returning the same shape until `webAdmin/` is removed | compatibility |

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

## Phase 3 — Angular dashboard shell, lesson pipeline port, remove `webAdmin/`

| Pkg | Owner | Inputs | Outputs | Acceptance test | Depends on |
|---|---|---|---|---|---|
| P3.0 `backend/dashboard-endpoints` | backend | prompt §6 (Home, School page, Usage, Billing), §A | Role Home stats (`GET /me/home`), school-level usage, per-school AI cost from `tokenUsage`, `PUT /admin/platform-settings`, school CRUD + wizard endpoints (classes, first Managerial user), all-lessons with school column, `openapi.json` regenerated | MockMvc tests per endpoint; `PermissionsTest` green; p95 < 300 ms for list endpoints on the QA seed (curl ×20 after deploy) | P2.1 |
| P3.1 `dashboard/shell-auth-home` | dashboard | P1.0 ui library; `server/openapi.json` from P3.0; prompt §5, §6 shared screens, §7 | generated API client (`pnpm gen:api`), auth + error interceptors, sign-in (logo fade-in by email domain), forgot password, first-login change, role routes + guards, 240px nav with gliding red rule, page template with `pageEnter`, role Homes (count-up cards, "what needs you"), profile (EN/AR live), guided tour (4 steps per role), skeletons, Undo strip, keyboard shortcuts (`/`, `?`), RTL mirroring, Admin school switcher | Vitest units; Playwright: one login per role lands on its Home, EN/AR switch without reload; Lighthouse performance + accessibility ≥ 90 on QA after deploy; initial JS ≤ 350 kB gzipped per route | P1.0, P3.0 |
| P3.2 `dashboard/lesson-pipeline` | dashboard | `webAdmin/` screens (parity reference), `docs/screenshots/admin-v2`, prompt §7 step strip | All lessons / My lessons, New lesson (PDF, slides, images, manual), step strip animations (pulse, tick draw, shake, band expand), retry, review plays with `phone-preview` per stop type, drag-reorder, parent panel, cache, usage, calendar — parity with `webAdmin/` | Playwright against QA: create → upload → step strip → confirm skills → review → publish; every `webAdmin/` feature has a counterpart (checklist in the PR); before/after screenshots | P3.1 |
| P3.3 `dashboard/admin-schools-flags-theme` | dashboard | prompt §3, §4, §6 Admin screens 4–7; P2.1 endpoints | Schools cards, New school wizard (progress rail, slide steps), School page tabs (Overview, Users, Theme with live phone preview + 300 ms transitions, Feature flags, Classes, Usage, Billing), Users (invite/disable/reset/View as banner), Feature flags matrix (+ audit, enable/disable for all, confirmation strip), Platform settings | Playwright: create a school through the wizard, flip a flag and see the confirmation strip + audit row, save a bad theme and see the failing pair; renaming in Platform settings changes title/heading/footer | P3.1 |
| P3.4 `infra/serve-dashboard` | infra | P3.1 build output; current `Dockerfile`, `scripts/build-panel.sh` | Dockerfile copies `dashboard/dist` (replaces `scripts/build-panel.sh` + `server/panel`), deploy workflows build the dashboard, Lighthouse CI after the QA deploy (thresholds ≥ 90), `ci.yml` drops the `webAdmin` build | Deploy QA green; `<api>/panel/` serves the Angular bundle with hashed assets immutable and `index.html` no-cache; Lighthouse job posts scores on the PR | P3.1 |
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

## Status

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
| P1.6 | #35 | merged | seed + isolation: local 11 pass / 5 blocked until P1.9; QA run pending P1.9 deploy |
| P1.9 | #36 | in review | one item (uniform 404 body) fixed |
| P1.7 | #37 | merged | two review items (PATH in fenced blocks, workflow count) fixed |
| P1.10 | #38 | merged, on QA | env applied; `RESEND_API_KEY` container created, not wired until a version exists |
