# Schools Dashboard — work plan

Planner: Claude Fable 5.1. Workers: Claude Opus 5 agents defined in `.claude/agents/` (`backend`, `dashboard`, `mobile`, `infra`, `test`, `docs`, reviewer `quality-performance`); shared rules in `.claude/AGENT_RULES.md`.
Product prompt: "Schools Dashboard — Angular multi-school platform, themes, feature flags, three roles" (the `docs` worker turns it into `docs/dev-prompt.md` in phase 3 and keeps it true to what shipped).

Every package: ≤ 1 day, one owner, one branch `<owner>/<package>`, one PR into `develop`, CI green, `quality-performance` approved, deployed to QA by the pipeline, verified by `test` when user-facing, docs updated. Shared interfaces (Flyway migrations, `server/openapi.json`, `permissions.json`, `design/tokens.json`, `shared-api/`) change first in a contract package, merge, then get consumed. No two agents edit the same file in the same package.

## Decisions (planner; escalated to the owner where marked ⚠)

| # | Decision | Why |
|---|---|---|
| D1 | Angular **latest stable** as installed by `ng new` (22.x in Sept 2026) rather than the literal "20" | the prompt says "Angular 20 (latest stable)"; latest stable wins, and the reviewer requires latest stable minors anyway |
| D2 ⚠ | The dashboard bundle is served by the API container at `<api>/panel/` (content-hashed assets immutable, `index.html` no-cache, SPA fallback) — **not Firebase Hosting** | the owner removed Firebase Hosting on 2026-09-14 ("keep Firebase auth only"); the API already serves the panel. Cost is the same (free tier either way); trade-off: first paint waits on a Cloud Run cold start when QA has scaled to zero. Owner can reverse this in one infra package |
| D3 | `docs/dev-prompt.md` does not exist in the repo (the README notes the original prompt was never received; `docs/design.md` is a stand-in). The Archivo / 2px / red system referenced as "design.md parent mode" lives in `shared-ui/.../Tokens.kt` (`Palette.parent*`, `AdminTokens`) and becomes `design/tokens.json` | contract-first: one file both front-ends generate from |
| D4 | File ownership beyond the table in the prompt: `shared-api/` → `backend` (contract-first); `design/tokens.json` → created by `dashboard` in P1.0, consumed by `mobile`; `Dockerfile`, `scripts/` → `infra`; `e2e/` → `test` | avoids two agents in one file |
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

Reviewer: `quality-performance` on every PR above.

## Phase 2 — Feature flags and themes

| Pkg | Owner | Outputs | Acceptance | Depends on |
|---|---|---|---|---|
| P2.1 `backend/flags-themes-contract` | backend | `V5__flags_themes.sql` (feature_flags, school_feature_flags, flag_audit, `schools.theme_json`, `platform_settings`); DTOs; `GET /schools/by-code/{code}` (public: name, logo, curricula, grades), `GET /schools/{id}/theme` + `/flags` (public, ETag, Cache-Control), Admin flag matrix endpoints + audit, `PUT /admin/schools/{id}/theme` with contrast validation (reject < 4.5:1, name the pair), `@FeatureFlag` annotation + interceptor (404 when off), ArchUnit "every controller added after this package references a flag" (allow-list), the 14 initial flags seeded (default off except the lesson ones and `levels.three`); `PlatformSettings` (name, shortName, logoUrl, supportEmail, defaultTheme; seeded "Schools Dashboard") + ArchUnit rule on the product-name literal | tests: flag off → 404, on → 200; theme contrast rejection with the failing pair; ETag 304 | P1.3 |
| P2.2 `mobile/join-school-theme-flags` | mobile | Join school in Add child (code → name + logo → confirm, colour transition), theme JSON → runtime token override (logo on map header, primary/accent on tabs/buttons, Pip in `mascotColor`), `FeatureGate`, `FlagStore` sync on launch + 6 h, `:shared:checkFeatureGates`, screenshot tests, `FakeContentApi` parity | desktop tests + screenshots; QA APK joins school A and shows its colours | P2.1, P1.5 |
| P2.3 `test/flag-flip-e2e` | test | script: flip `complaints` off for A → API 404 within one sync, on → 200; theme validation cases | exits 0 against QA | P2.1 |
| P2.4 `docs/phase2` | docs | runbook: flags, themes, platform settings | — | P2.2 |

## Phase 3 — Angular dashboard shell, lesson pipeline port, remove `webAdmin/`

| Pkg | Owner | Outputs | Acceptance | Depends on |
|---|---|---|---|---|
| P3.0 `backend/dashboard-endpoints` | backend | Role Home stats (`GET /me/home`), school-level usage, per-school AI cost from `tokenUsage`, `PUT /admin/platform-settings`, school CRUD + wizard endpoints (classes, first Managerial user), all-lessons with school column, `openapi.json` regenerated | tests; p95 < 300 ms on the QA seed | P2.1 |
| P3.1 `dashboard/shell-auth-home` | dashboard | generated API client (`pnpm gen:api` from `server/openapi.json`), auth + error interceptors, sign-in (logo fade-in by email domain), forgot password, first-login change, role routes + guards, 240px nav with gliding red rule, page template with `pageEnter`, role Homes (count-up cards, "what needs you"), profile (EN/AR live), guided tour (4 steps per role), skeletons, Undo strip, keyboard shortcuts (`/`, `?`), RTL mirroring, school switcher (Admin) | Vitest units; Playwright login per role; Lighthouse ≥ 90 | P1.0, P3.0 |
| P3.2 `dashboard/lesson-pipeline` | dashboard | All lessons / My lessons, New lesson (PDF, slides, images, manual), step strip animations (pulse, tick draw, shake, band expand), retry, review plays with `phone-preview` per stop type, drag-reorder, parent panel, cache, usage, calendar — parity with `webAdmin/` | Playwright: upload → steps → publish; before/after screenshots vs `docs/screenshots/admin-v2` | P3.1 |
| P3.3 `dashboard/admin-schools-flags-theme` | dashboard | Schools cards, New school wizard (progress rail, slide steps), School page tabs (Overview, Users, Theme with live phone preview + 300 ms transitions, Feature flags, Classes, Usage, Billing), Users (invite/disable/reset/View as banner), Feature flags matrix (+ audit, enable/disable for all, confirmation strip), Platform settings | Playwright: create school, flip flag, save theme (rejection shows the pair) | P3.1 |
| P3.4 `infra/serve-dashboard` | infra | Dockerfile copies `dashboard/dist` (replaces `scripts/build-panel.sh` + `server/panel`), deploy workflows build the dashboard, Lighthouse CI after QA deploy, `ci.yml` drops the `webAdmin` build | Deploy QA green; `<api>/panel/` serves Angular | P3.1 |
| P3.5 `test/dashboard-e2e` | test | Playwright suite against QA (login per role, isolation by UI, flag flip, lesson pipeline smoke); screenshot set 1366×768 EN/AR light/dark | green against QA | P3.2, P3.3, P3.4 |
| P3.6 `dashboard/remove-webadmin` | dashboard | delete `webAdmin/`, Gradle `settings.gradle.kts` include, README/CI references (infra confirms) | build green without the module | P3.5 |
| P3.7 `docs/dev-prompt-and-phase3` | docs | `docs/dev-prompt.md` (the spec, reconciled with what shipped), runbook, README | — | P3.6 |

## Phase 4 — Teacher

| Pkg | Owner | Outputs | Depends on |
|---|---|---|---|
| P4.0 `backend/teacher-contract` | backend | `V6__teacher.sql` (teacher profiles, teacher_questions, announcements), endpoints (profile, restricted chooser options, my students stats + timeline, questions to students with date window and classes, results, announcements), app endpoints (teacher island on the map, announcements in parent mode), flags `teacherQuestions`/`announcements` enforced | P3.0 |
| P4.1 `dashboard/teacher` | dashboard | Teacher Home, My lessons (calendar gaps), New lesson restricted chooser, Questions to students, My students (timeline, retells/drawings), Announcements | P4.0, P3.2 |
| P4.2 `mobile/teacher-island-announcements` | mobile | "From your teacher" island with photo, Announcements card in parent mode, both gated | P4.0, P2.2 |
| P4.3 `test/teacher-e2e` | test | teacher of A publishes → child map in A only; question reaches the island | P4.1, P4.2 |
| P4.4 `docs/phase4` | docs | — | P4.3 |

## Phase 5 — Managerial and complaints

| Pkg | Owner | Outputs | Depends on |
|---|---|---|---|
| P5.0 `backend/complaints-contract` | backend | `V7__complaints.sql`, parent + managerial endpoints (§8), attachments (per-school folder, signed URL, 10 MB, magic bytes), notifications (email + push abstraction, assignee email, SLA digest job), stats | P3.0 |
| P5.1 `dashboard/management` | dashboard | Managerial Home, Complaints inbox + conversation view (reply, internal notes, assign, status, resolve category, SLA badges), School usage, Teachers; Admin All complaints | P5.0, P3.1 |
| P5.2 `mobile/help-complaints` | mobile | Parent mode Help & complaints (chips, message, screenshot, lesson link), thread view, reply notifications | P5.0 |
| P5.3 `infra/mail-push` | infra | `MAIL_PROVIDER`/`RESEND_API_KEY` secrets by name, Terraform env, FCM (Firebase Cloud Messaging via the existing Firebase project) if needed | P5.0 |
| P5.4 `test/complaint-round-trip` | test | complaint from the QA APK → inbox ≤ 10 s → reply reaches the app thread and the log mailer | P5.1, P5.2 |
| P5.5 `docs/phase5` | docs | — | P5.4 |

## Phase 6 — Admin usage & cost, polish, final acceptance

| Pkg | Owner | Outputs | Depends on |
|---|---|---|---|
| P6.0 `backend/usage-cost` | backend | platform usage (children, plays/day, AI calls, cache hit rate, cost per school), `UsageDaily` rollup job | P5.0 |
| P6.1 `dashboard/usage-cost-and-polish` | dashboard | Platform usage & cost charts (ECharts, line draw 500 ms), wizard polish, animation audit vs §7, screen recording | P6.0 |
| P6.2 `test/final-acceptance` | test | full Playwright suite; screenshot set of every screen for every role (1366×768, light/dark, EN/AR); Lighthouse report | P6.1 |
| P6.3 `docs/final` | docs | `docs/dev-prompt.md` reconciled, README, runbook | P6.2 |
| P6.4 `quality-performance/deps` | quality-performance | weekly dependency update PR + `docs/quality.md` | recurring |

## Status

| Pkg | Branch / PR | State | Notes |
|---|---|---|---|
| setup | `planner/setup` | in review | agent definitions + this plan |
