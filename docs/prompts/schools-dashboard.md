# Schools Dashboard — prompt: Angular multi-school platform, themes, feature flags, three roles

Paste everything below this line into Claude Code in the `homework-quest` repo. The product spec is `docs/dev-prompt.md`; this prompt **replaces the Compose Multiplatform web admin (`webAdmin/`) with an Angular dashboard (`dashboard/`)**, grows it into a **multi-school platform**, and changes how lessons reach children. Where this prompt conflicts with the spec, this prompt wins. Update `docs/dev-prompt.md` at the end so the two agree, and delete `webAdmin/` once the Angular dashboard reaches feature parity in QA.

## A. Naming — everything is dynamic

- The web product is called **Schools Dashboard** by default. That string, the browser title, the logo, the sign-in page heading, email subjects and the footer all come from one place: `PlatformSettings(name, shortName, logoUrl, supportEmail, defaultTheme)` in the database, editable by Admin under **Platform settings**, with `Schools Dashboard` as the seeded default. Nothing in the code base hard-codes a product name; an ESLint rule and an ArchUnit test fail the build on the literal `"Homework Quest"` or `"Schools Dashboard"` outside the seed and the tests.
- Per school, `School.theme.appName` overrides the display name inside that school's scope (dashboard header when a school is selected, the mobile app, parent emails). Resolution order: school appName → platform name → default.
- The mobile app keeps its own `appName` from the school theme; its store listing name is a build-time value and is not affected.
- Repository, package and module names stay `homework-quest`; only user-facing strings are dynamic.

## B. How to work — planning model and worker agents

Run this project as an orchestrated team of Claude Code agents, defined in `.claude/agents/*.md` and driven by the plan below. Commit the agent definitions so the setup is reproducible.

**Planner — Claude Fable 5.1 (this session).** The planner does not write application code. It:
1. reads this prompt and `docs/dev-prompt.md`, and writes `docs/plan.md`: work packages of at most one day each, with owner agent, inputs, outputs, acceptance test, and dependency order;
2. spawns worker agents per package, in parallel where dependencies allow, giving each a self-contained brief (the package text, the files it may touch, the interfaces it must respect);
3. reviews every returned package against the acceptance test, merges through the pipeline (`gh pr create` → CI → `gh pr merge`), updates `docs/plan.md`, and reports progress to me at the end of every phase with what shipped, what is blocked, and the QA links;
4. never lets two agents edit the same file in the same package; shared interfaces (OpenAPI spec, `tokens.json`, `permissions.json`, Flyway migrations) are changed first in a dedicated package, merged, and only then consumed.

**Workers — Claude Opus 5, one per role** (`model: claude-opus-5` in each agent file):

| Agent | Scope | May touch |
|---|---|---|
| `quality-performance` | **Reviews every PR before merge and has veto.** Checks: the code uses current idioms for the exact framework versions in the repo (Angular 20 signals/standalone/control flow, Spring Boot 3.x virtual threads, Kotlin 2.x); no deprecated APIs; dependencies on the latest stable minor with the lockfile updated and `pnpm audit` / OWASP dependency-check clean; performance budgets met — dashboard initial JS ≤ 350 kB gzipped per route, LCP < 2.5 s on QA, no N+1 queries (Hibernate statistics in tests), p95 API latency < 300 ms for list endpoints on the QA seed, Cloud Run cold start < 4 s, Lighthouse performance and accessibility ≥ 90; test coverage does not drop; no secrets, no TODOs, no dead code. It writes its review as a PR comment with a checklist; anything red goes back to the owner agent, not fixed by the reviewer. It also runs a weekly dependency-update package. | review comments, `docs/quality.md`, `renovate.json` |
| `backend` | Spring Boot: tenancy, roles, flags, themes, complaints, teacher questions, OpenAPI, migrations, ArchUnit tests | `server/` |
| `dashboard` | Angular: ui library, motion system, all screens for the three roles, i18n, Playwright | `dashboard/` |
| `mobile` | KMP app: join school, theme application, flags, complaints screen, teacher island, announcements | `shared/`, `androidApp/`, `iosApp/` |
| `infra` | Terraform, workflows, secrets, Firebase, Lighthouse CI, QA/prod deploys, rollback | `deploy/`, `.github/` |
| `test` | Cross-cutting e2e: isolation, flag flips, complaint round trip, theme validation; QA seed data for two schools and every role; screenshot sets | `e2e/`, seed scripts |
| `docs` | Keeps `docs/dev-prompt.md`, `docs/runbook.md`, `docs/plan.md` and the README true to what shipped | `docs/`, `README.md` |

Rules for every worker: work on a branch named `<agent>/<package>`; run the relevant tests locally before returning; return a short report (what changed, how to verify, open questions) rather than the diff; ask the planner, not me, when a brief is ambiguous. The planner escalates to me only for decisions that change scope, cost or security.

Definition of done for a package: acceptance test green in CI, `quality-performance` approved, deployed to QA by the pipeline, verified by `test` where the package is user-facing, `docs` updated.

## 0. Front-end stack

- **Angular 20** (latest stable), standalone components, signals for state, `@angular/router` with lazy-loaded feature routes per role, strict TypeScript, ESLint + Prettier. Workspace at `dashboard/`, built with the Angular CLI; Node 22, `pnpm`.
- **No UI kit.** The design system from `docs/design.md` (Archivo, square corners, 2px rules, red accent) is implemented as our own component library in `dashboard/src/app/ui/` (button, input, select, checkbox, toggle, table, tabs, card, red band, step strip, progress bar, skeleton, phone frame). Use **Angular CDK** only for overlays, drag-and-drop, focus trapping and a11y — never Angular Material styles.
- Styling: SCSS with **design tokens as CSS custom properties** on `:root`, generated from a single `tokens.json` that the mobile app's `shared/` module also consumes (a Gradle task and an npm script both read the same file, and CI fails if the two outputs drift). Theming per school = overriding those custom properties at runtime.
- API client generated from the Spring `openapi.json` with `openapi-generator` (typescript-angular) in CI; hand-written calls are not allowed. Auth interceptor adds the JWT and refreshes it; an error interceptor maps server errors to the red band.
- i18n with **Transloco** (EN/AR, live switch without reload), `dir` toggled on `<html>`, RTL handled with CSS logical properties.
- Charts: **ECharts** via `ngx-echarts`, styled with the tokens.
- Animations: Angular's `@angular/animations` for route and list transitions, CSS transitions for micro-interactions, `prefers-reduced-motion` respected.
- Tests: Vitest + Angular Testing Library for units, Playwright for e2e against QA (login per role, isolation, flags), and Playwright screenshots for the visual set in §10.
- Build/deploy: `pnpm build --configuration=qa|production` (environment files carry `apiBaseUrl` and the Firebase project), output to Firebase Hosting with SPA rewrites and long-cache headers for hashed assets; `ci.yml` gets a `dashboard` job (install, lint, test, build), `deploy-qa.yml` and `deploy-prod.yml` deploy the built bundle. Lighthouse CI runs in the QA deploy with a ≥ 90 accessibility threshold.

---

## 1. What changes

The platform now serves **many schools from one system** under the dynamic name in §A (default **Schools Dashboard**). Each school is a tenant with its own logo, colours, name and enabled features. The web app becomes a **dashboard** with three kinds of users:

| Role | Who | Sees |
|---|---|---|
| **Admin** | the platform owner (me) | everything, across all schools: schools, users, feature flags, usage, cost, all lessons, all complaints |
| **Teacher** | a teacher at one school, with a profile: name, subjects, curriculum, grades she teaches | her school only; adds lessons (PDF, slides, images, manual questions) for her subjects and grades; sends questions to all her students; sees how they did |
| **Managerial** | a school's management (principal, coordinator) | her school only; receives and handles every complaint or message sent by parents; sees school-level usage |

The mobile app changes with it: a parent joins a **school** with a school code, and the child's map is built from lessons published by that school's teachers for the child's curriculum and grade. Parents can send a complaint or message from the app; it lands in the managerial inbox.

## 2. Tenancy

- `School(id, name, code, curriculumOptions[], gradeOptions[], theme, featureFlags, status, createdAt)`. `code` is a 6-character join code printed for parents.
- Every tenant table gets `schoolId`: `User`, `Teacher`, `Child`, `Lesson`, `Complaint`, `Announcement`, `UsageDaily`. `AnalysisCache` and the play cache stay **global** (keyed by file hash) so the same PDF uploaded by two schools is analysed once.
- Row-level isolation is enforced in the server, not the UI: every repository query is scoped by the caller's `schoolId` through a Hibernate filter set from the JWT; Admin has `schoolId = null` and an explicit **school switcher** in the header that scopes the view. A test suite must prove a Teacher token can never read another school's rows.
- `Course` becomes per school: `Class(id, schoolId, curriculum, grade, subject, teacherId)`. A child belongs to a school + curriculum + grade; the map merges every published lesson from every Class matching those three.

## 3. Themes (white label)

Each school has a `theme` JSON edited by Admin on the school page:

```
{ logoUrl, appName, primary, primaryInk, accent, ground, softBorder, mascotColor, worldPalettes: { math: {...}, english: {...} }, fontChoice: "baloo" | "nunito" | "fredoka" }
```

- The dashboard and the mobile app both read the theme through `GET /schools/{id}/theme` (public, cached, ETag) and apply it at runtime: no rebuild per school. In the dashboard, the theme service maps the JSON onto the CSS custom properties from `tokens.json` (`--hq-primary`, `--hq-accent`, …) on the root element, so every component re-themes instantly; in the mobile app the same JSON seeds the `shared/` token set.
- The mobile app applies the school theme after the parent joins: logo on the world map header, primary/accent on tabs and buttons, Pip in `mascotColor`. Child-mode rules (contrast, 64px targets, no red X) are validated server-side when Admin saves a theme: reject a theme whose contrast ratio is below 4.5:1 on any text/background pair and show which pair failed.
- A live preview on the school page: an Angular `hq-phone-preview` component (396×812 Android frame) renders a faithful HTML/SCSS copy of the world map, one practice question and the wrong-answer sheet from `docs/design.md`, driven by the same CSS custom properties, so colour edits animate in the phone with a 300 ms eased transition. This preview is a design replica for theming and for the Review plays screen, not the real app; keep it in `dashboard/src/app/ui/phone-preview/` with one component per stop type so Review plays can show any stop.
- Default theme = the one in `docs/design.md`.

## 4. Feature flags

- Every feature listed in §6 has a flag. `School.featureFlags` is a map `{ flagKey: boolean }`; a global `FeatureFlag(key, description, defaultOn, rolloutStage)` table defines them.
- **Rule for every future feature: it is created with a flag, default off, and the flag is checked in the server (endpoint returns 404 when off), in the dashboard (menu item hidden) and in the app (screen hidden).** Add a `@FeatureFlag("key")` annotation for Spring controllers; in the dashboard a `*hqFeature="key"` structural directive, a `featureGuard('key')` route guard and a `FlagService` backed by signals; in the app a `FeatureGate(key) { … }` composable in `shared/`. A CI check (an ESLint rule for `dashboard/`, a Gradle check for `shared/`, an ArchUnit test for `server/`) fails the build if a new route, screen or controller is added without a flag reference.
- Admin screen **Feature flags**: matrix of schools × flags with toggles, a per-flag description, an "enable for all / disable for all" per column, and an audit log of who flipped what and when. Flags propagate to the app on next sync (the app calls `GET /schools/{id}/flags` on launch and every 6 hours).
- Initial flags: `lessons.pdf`, `lessons.slides`, `lessons.images`, `lessons.manual`, `levels.three`, `retell.recording`, `openAnswer.drawing`, `parentPanel.arabic`, `complaints`, `announcements`, `teacherQuestions`, `stickers.treasureChest`, `progress.weeklyEmail`, `certificates`.

## 5. Roles, accounts and permissions

- One `User(id, schoolId?, email, passwordHash, role: ADMIN|TEACHER|MANAGERIAL, status, lastLoginAt)` table for dashboard users; parents stay in Firebase Auth. Dashboard auth = email + password, JWT with `role` and `schoolId`, refresh tokens, "forgot password" email, forced password change on first login.
- Admin creates schools and invites users by email (`POST /admin/schools/{id}/invites`); the invite email carries a one-time link. Admin can impersonate a Teacher or Managerial user read-only ("View as…") with a banner showing it; every impersonated view is audit-logged.
- `Teacher(userId, displayName, photoUrl, subjects[], curriculum, grades[], bioAr?, bioEn?)`. A teacher can only create lessons whose subject ∈ her subjects, curriculum = her curriculum and grade ∈ her grades; the New lesson chooser only shows those options. Managerial can see her school's lessons but not edit them.
- Permission matrix defined once in `server/src/main/resources/permissions.json`, loaded by the server for `@PreAuthorize` and served at `GET /me/permissions`; the dashboard's `PermissionService` uses it for a `*hqCan="lesson.publish"` directive and route guards. Tests assert every endpoint has an entry and that the dashboard never shows an action the server would refuse.

## 6. Dashboard — screens

Same design system as `docs/design.md` parent mode (Archivo, square corners, 2px rules, red accent), now themed per school and built with the `dashboard/src/app/ui/` components. Left navigation 240px; the items shown depend on role and flags. Route structure: `/admin/**`, `/teacher/**`, `/management/**`, each lazy-loaded and guarded by role; a `/` redirect sends each user to their Home.

### Shared
1. **Sign in** — email/password, forgot password, first-login password change. School logo appears after the email is typed (looked up by domain) with a fade-in.
2. **Home** — role-specific (below). Every Home shows the person's name, the school logo, three big number cards that count up on load, and a "what needs you" list.
3. **Profile** — name, photo, language (EN/AR toggle flips the whole dashboard live), password.

### Admin
4. **Schools** — cards with logo, name, code, counts of children/teachers/lessons, status; **New school** wizard (name → curriculum & grade options → theme → feature flags → first Managerial user), one step per screen with a progress rail and slide transitions.
5. **School page** — tabs: Overview, Users, Theme (live phone preview), Feature flags, Classes, Usage, Billing (cost of AI per month from `tokenUsage`).
6. **Users** — all dashboard users across schools; filter by role/school; invite, disable, reset password, View as.
7. **Feature flags** — the matrix in §4.
8. **All lessons** — every lesson from every school with the school column; the existing lesson pipeline screens (upload, step strip, retry, review plays, parent panel, cache) unchanged.
9. **All complaints** — every complaint across schools, read-only, with SLA stats per school.
10. **Platform usage & cost** — children, plays per day, AI calls, cache hit rate (target > 90 %), cost per school; line charts drawn with the design system.

### Teacher
11. **Home** — "Good morning, Ms Sara": her classes as cards (British · Grade 1 · Math), today's lesson status per class (published / not yet, with a one-tap **Add today's lesson** on the missing ones), how many children played yesterday, the three weakest skills across her classes.
12. **My lessons** — her lessons only, by class and date; the calendar view per class shows gaps.
13. **New lesson** — the chooser pre-filtered to her subjects/curriculum/grades; then the existing pipeline (PDF, slides, images, manual, step strip, retry, three levels, parent panel).
14. **Questions to students** (flag `teacherQuestions`) — write one or more questions of any §5 stop type, choose which classes, choose a date window, and send; they appear on each child's map as a small "From your teacher" island with her photo. Results table per child once answered.
15. **My students** — per class: each child's stars this week, level reached, weak skills; tap a child for the timeline of what they played and their saved retells/drawings (the same parent panel the parent sees).
16. **Announcements** (flag `announcements`) — a short note to all parents of a class ("Tomorrow we start subtraction"), shown in the app's parent mode.

### Managerial
17. **Home** — open complaints count, average first-response time, complaints by category this month, and the newest three.
18. **Complaints inbox** — a list with status (New, In progress, Waiting for parent, Resolved), category (Lesson content, App problem, Teacher, Billing, Other), child/class, and age; filters and search. Selecting one opens a **conversation view**: the parent's message and attachments (screenshots), the child and class context, the teacher's related lesson if the parent linked one, a reply box (replies go to the parent's app and email), internal notes (never shown to the parent), assign to a colleague, change status, and a resolve button that asks for a resolution category. SLA badges turn amber after 24 h and red after 48 h without a reply.
19. **School usage** — children, active families, plays per week, teachers' publishing consistency.
20. **Teachers** — the school's teachers and their classes, read-only.

### Mobile app additions
- **Join school** during Add child: enter the school code → the school's name and logo appear → confirm; the theme applies with a colour transition. Curriculum and grade options come from the school.
- **Parent mode → Help & complaints** (flag `complaints`): category chips, message, optional screenshot, optional "about today's lesson" link; a thread view with the school's replies and push/email notification on reply.
- **From your teacher** island and **Announcements** card in parent mode (flagged).

## 7. Experience: easy, animated, interactive

Animation is for orientation and feedback, never decoration. Build a small motion system in `dashboard/src/app/ui/motion/` (named Angular animation triggers: `pageEnter`, `listStagger`, `rowCollapse`, `countUp`, `shake`, `expandBand`, plus SCSS mixins for micro-interactions) and use only it:

- **Durations:** 150 ms micro (hover, toggle), 250 ms standard (list rows, panels), 400 ms transitions (page, wizard step). Easing: standard `cubic-bezier(0.2, 0, 0, 1)`, emphasised for entrances. Honour `prefers-reduced-motion` by collapsing everything to 0 ms cross-fades.
- **Page changes:** content slides 24 px in the navigation direction with a fade; the left nav's red active rule glides between items.
- **Lists:** rows fade+rise in with a 30 ms stagger on first load only; a row being deleted collapses its height; a new row highlights in `#FBE4DF` for 1.5 s.
- **Numbers:** stat cards count up from 0 over 600 ms on first paint; charts draw their line left-to-right over 500 ms.
- **Pipeline step strip:** the running step pulses with the accent arc; a completed step draws its tick with a stroke animation; an error step shakes once (6 px, 300 ms) and shows the red band expanding from 0 height.
- **Toggles and checkboxes:** the square glyph fills with a 150 ms scale from 0.6; feature-flag toggles show a one-line "Enabled for Al Noor School" confirmation strip that slides in and out.
- **Wizards:** a progress rail on the left; steps slide horizontally; the Next button is disabled until the step is valid and shows why on hover.
- **Drag and drop:** upload zones lift (2px rule → 4px accent) when a file hovers; stops in Review plays reorder with a drag handle and a 250 ms settle animation.
- **Feedback:** every action gets an immediate visible response within 100 ms (optimistic UI where safe: status changes, toggles, assignments) and reconciles with the server; failures roll back with the red band, never a toast.
- **Empty states** show the mascot in `thinking` with one sentence and the primary action; **skeleton loaders** (square grey blocks, 1.2 s shimmer) for anything that takes longer than 300 ms.
- **Keyboard:** every screen navigable with Tab/Enter/Esc; `/` focuses search; `?` shows shortcuts.
- **Onboarding:** first login shows a 4-step guided tour per role (spotlight + text bubble), dismissible, replayable from the Help menu.
- **Arabic:** the whole dashboard mirrors (RTL) with the EN/AR toggle, including animation directions and the nav rule.

Ease of use rules: at most one primary action per screen; the most likely next action is the sticky footer's primary button; every form can be completed without a mouse; every destructive action confirms with a red band, and everything else is undoable for 10 s with an **Undo** strip.

## 8. Complaints — server side

- `Complaint(id, schoolId, parentId, childId, classId?, lessonId?, category, status, priority, assignedTo?, createdAt, firstResponseAt?, resolvedAt?, resolutionCategory?)`, `ComplaintMessage(id, complaintId, authorType: PARENT|STAFF, authorId, body, attachments[], internal, createdAt)`.
- Endpoints: parent `POST /children/{id}/complaints`, `GET /complaints/{id}`, `POST /complaints/{id}/messages`; managerial `GET /school/complaints` (filters), `PATCH /school/complaints/{id}` (status, assignee, priority), `POST /school/complaints/{id}/messages` (with `internal` flag), `GET /school/complaints/stats`.
- Notifications: email + push to the parent on staff reply; email to the assignee on assignment; a daily digest to Managerial of complaints breaching SLA. Emails via an abstraction with a SendGrid/Resend implementation and a log implementation for QA.
- Attachments go to a per-school bucket folder with a signed URL, 10 MB max, images and PDFs only, scanned for type by magic bytes.

## 9. Build order (PRs into `develop`, QA deploy through the pipeline after each phase)

1. **Tenancy and roles** — School, Class, User with roles, Hibernate school filter, permission matrix, JWT, isolation tests, school switcher for Admin, migration of existing data into one default school.
2. **Feature flags and themes** — flag table, `@FeatureFlag`, `FeatureGate`, CI check, Admin matrix screen, theme JSON + validation + live preview, app applies theme and flags, join-school flow in the app.
3. **Angular dashboard shell and motion system** — workspace, tokens pipeline, ui component library, generated API client, auth, navigation, page template, role-based Homes, profile, EN/AR, guided tours, skeletons, undo strip. Port the existing lesson pipeline screens (upload, step strip, retry, review plays, parent panel, cache) from `webAdmin/` to Angular in this phase, at feature parity, then remove `webAdmin/`.
4. **Teacher** — profile, restricted lesson chooser, My lessons, Questions to students (server + app island), My students, Announcements.
5. **Managerial and complaints** — inbox, conversation view, SLA, notifications, app-side Help & complaints, Admin all-complaints view.
6. **Admin usage & cost** and the school wizard polish; update `docs/dev-prompt.md`; screenshot set of every screen for every role at 1366×768, light and dark, EN and AR.

## 10. Acceptance

- The default name **Schools Dashboard** shows everywhere until Admin changes it in Platform settings, and a school's `appName` overrides it inside that school; two schools in QA with different logos and colours; a teacher of school A cannot see or reach anything of school B (proved by tests and by hand with her token).
- Flipping `complaints` off for school A hides the app screen, the dashboard item and returns 404 from the API within one sync; flipping it on restores everything with no rebuild.
- A teacher with subjects [Math], grades [1, 2], British: the New lesson chooser shows only British → Grade 1/2 → Math; she publishes a lesson and it appears on a British Grade 1 child's map in school A only.
- A parent sends a complaint with a screenshot from the QA APK; it appears in Managerial's inbox within 10 s; a reply reaches the parent's app thread and email.
- Every animation in §7 is visible in a screen recording attached to the final PR, the dashboard scores ≥ 90 on Lighthouse accessibility and performance, and the Playwright e2e suite (one login per role, cross-school isolation, a flag flip, a complaint round-trip) is green against QA.
