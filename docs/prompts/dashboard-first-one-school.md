# Dashboard-first, one school — prompt

Paste everything below this line into Claude Code. It replaces the build order of `docs/teacher-v1-prompt.md` and `docs/dashboard-prompt.md` with a single goal: **finish the whole dashboard system for one school, starting with the teacher, with zero work on the mobile app.** The planner/worker setup (§B of the dashboard prompt), the dynamic product name (default **Schools Dashboard**), the feature-flag rule, the Angular stack, the Spring backend, the lesson pipeline, gradebook and exams from the earlier prompts all still apply; this prompt says what to build, in what order, and what "done" means. Update `docs/plan.md` first.

---

## 1. Goal and boundaries

- **Build for one school now, many later.** Every table that belongs to a school carries `schoolId`, the Hibernate school filter is active, and the school is resolved from the user's JWT. There is exactly one `School` row, seeded (`name`, `code`, curricula, grades, school week Sun–Thu, timezone Asia/Riyadh), and no UI to create a second one. Adding a school later must require only: a new row, users invited to it, and unhiding the Admin school screens. No code path may assume "the school" by id, name or constant; an ArchUnit test fails on any `schoolId = 1` style literal outside seeds and tests.
- **Dashboard only.** No changes to `shared/`, `androidApp/` or `iosApp/` in this work. Where the app would normally supply data (children's answers), the dashboard gets what it needs from a **web player** (§5) and from seed data, so every screen can be finished and verified without a phone.
- **Teacher first, then the rest.** Order: teacher role complete → Admin minimal (only what a teacher needs to exist) → Admin full for this school → Managerial and complaints (dashboard side only; the parent side arrives with the app later) → polish, docs, production.

## 2. Roles in this build

| Role | In this build |
|---|---|
| **Teacher** | complete, as specified in `docs/teacher-v1-prompt.md` §4, §7, §8 plus §4 below |
| **Admin** | classes, teachers and assignments, children rosters, all lessons, feature flags, platform settings (name, logo, school week), usage & cost, cache; school screens hidden behind `multiSchool` flag (off) |
| **Managerial** | complaints inbox and school usage, working on complaints created by Admin/Teacher on a parent's behalf ("logged by staff") and by the web player's test parent, since the app is not here yet |

## 3. Data that must exist without the app

- **Children rosters.** Admin (or a teacher for her own classes, flag `teacher.rosterEdit`) adds children to a class: full name, optional parent email, optional photo, active. Import from CSV/XLSX with a preview and duplicate detection. `Child.classId` is set here; when the app arrives, the parent's join code links the parent account to the existing child row instead of creating one (match by class + name, confirmed by the parent). Keep `Child.parentId` nullable.
- **Results.** All scoring, gradebook, levels and exam results are computed by the server from `Attempt` rows, exactly as they will be when the app sends them. In this build, `Attempt` rows come from the web player (§5) and from the QA seed (`seed/attempts.csv`: 600 children × 20 lessons of realistic play so gradebooks, levels, exam distributions and usage charts are populated).

## 4. Teacher — full screen list (all in this build)

1. Sign in, forced password change, forgot password (email through the notification abstraction; log implementation on QA).
2. **This week** — cross-class weekly calendar, drag to move, drag to copy, gap summary, exams closing, marks waiting.
3. **My classes** — cards per assignment with today's status and children count.
4. **Class page** — tabs: Calendar (month), Children (roster with stars, level, weak skills; add/edit if flag on), Gradebook, Exams.
5. **Lesson editor** — source (PDF, slides, images, manual), date, notes; step strip with retry and continue; Review skills; Review plays (three levels, inline edit, add stop, attach image, reorder, phone preview per stop type); Parent panel (EN/AR); publish sheet with sibling-class selection; unpublish/re-publish with versioning; delete draft/error lessons; "Analyzed before · 0 tokens" cache badge.
6. **Results** per lesson — who played, stars per stop, level reached, weakest stops, saved open-stop work with marking, release toggle, CSV export.
7. **Gradebook** — children × lessons grid, bands, averages, teacher overrides, comments, "needs marking" filter, CSV/XLSX export.
8. **Child page** — level band and trend per subject, score line chart, skills, comments, open-stop work.
9. **Exams** — create (settings: window, level/mixed, attempts 1, hints off, release mode), build with the same editor, publish, results page (table, distribution, per-question difficulty, absent + re-open per child, release), CSV/XLSX, per-child PDF result sheet.
10. **Preview as child** — opens the web player on any lesson or exam of hers, so she can play it before publishing.
11. **Profile** — name, photo, password, EN/AR.

Teacher navigation shows only these; every route and API call is assignment-scoped (403 + redirect otherwise).

## 5. Web player (dashboard-side stand-in for the app)

A route `/play/:lessonId?as=:childId` inside the Angular dashboard that renders the child experience faithfully enough to test everything end to end:

- Uses the phone-preview component set (396×812 frame) extended to be **playable**: every stop type from the spec (readPage with tapTask, storyPieces, wordCards, move, explain, choice, trueFalse, sequence, count, compare, sound, word, readTap, multiSelect, selectAll, match, order, trace, retell, openAnswer, writeSentence), the pot and ingredients, stars, the wrong-answer sheet, level selector and unlocking, exam mode (window, single attempt, no hints), the certificate. Read-aloud via the browser's `speechSynthesis`; retell recording via `MediaRecorder`; drawing on a canvas.
- It sends real `POST /children/{id}/attempts` and media uploads through the same endpoints the app will use, authenticated as a **test parent** account that Admin can create per class ("Test parent for 1A", flag `webPlayer`). Teachers can play as any child of their classes in a *preview* mode that records attempts under a hidden `previewOf=teacherId` marker so previews never pollute the gradebook; Admin can toggle "record as real" on QA for seeding.
- Child-mode rules enforced here too: no red X, no numbers, no timer, 64 px targets, read-aloud everywhere.
- This component set becomes the reference when the mobile app is built later; keep it under `dashboard/src/app/player/` with one component per stop type and a Storybook-style gallery route `/player/gallery` that renders every stop type in every state for design review.

## 6. Admin — full screen list for this school

Classes (create, join code, printable code card PDF, deactivate) · Teachers (create with temporary password, subjects, curriculum, photo; assignments with the one-teacher-per-subject-per-class rule) · Children rosters (per class, CSV/XLSX import) · All lessons and exams (read-only pipeline views, cache page) · Feature flags (this school; the matrix collapses to one column while `multiSchool` is off) · Platform settings (product name, logo, school week, timezone, contrast-validated default theme with live phone preview) · Users (all roles, reset password, disable, View as with banner and audit log) · Usage & cost (children, plays per day, AI calls, cache hit rate, token cost per month) · Audit log.

## 7. Managerial — dashboard side

Home (open complaints, first-response time, categories) · Complaints inbox with conversation view, internal notes, assignment, status, SLA badges, resolve categories · **Log a complaint** on behalf of a parent (phone call / in person) with the child and class · School usage · Teachers (read-only). The parent-facing thread and notifications are already implemented server-side so the app only has to render them later.

## 8. Non-functional (same bar throughout)

Angular 20 standalone/signals, generated API client, `*hqFeature` and `*hqCan`, Transloco EN/AR with RTL, the motion system, skeletons, undo strip, keyboard navigation, Lighthouse ≥ 90 accessibility and performance, bundle ≤ 350 kB gzipped per route, p95 < 300 ms on list endpoints against the QA seed, Playwright e2e per role, Vitest units, `quality-performance` veto on every PR, docs updated per package.

## 9. Build order (planner packages; PRs into `develop`; QA deploy after each; stop and report per phase)

**Phase 1 — Foundations for one school.** Migrations: `School` (one seeded row), `schoolId` everywhere, Hibernate filter, `Class`, `Teacher`, `TeachingAssignment`, `Child` with nullable `parentId`, `Lesson.classId/teacherId/type`. Auth (email/password, JWT, refresh, forced change, forgot password). `permissions.json`, flags table with the initial keys. Admin: Classes, Teachers + assignments, Children rosters, Platform settings (name/logo/week). Seed: one school, 30 classes, 40 teachers, 600 children. Acceptance: a seeded teacher can sign in and see her assignments; the isolation and one-teacher-per-subject tests pass.

**Phase 2 — Teacher: lessons.** This week, My classes, Class page (Calendar, Children tabs), Lesson editor with the full pipeline, publish/unpublish/delete, Preview as child stub (opens gallery). Acceptance: Sara uploads a PDF, reviews three levels, publishes to 1A and 1B, sees both in This week; a second upload of the same PDF costs 0 tokens.

**Phase 3 — Web player.** All stop types playable, attempts and media posted through the real endpoints, test-parent accounts, preview mode, gallery route. Acceptance: playing the seeded "Hot Soup for Mummy" lesson end to end produces attempts that appear in Results within 5 s.

**Phase 4 — Teacher: gradebook, levels, exams.** Results, Gradebook, Child page, marking, release, exports; Exams end to end including the single-attempt and window rules exercised through the web player; PDF result sheet. Acceptance: with the attempts seed, the 1A gradebook loads under 1 s and matches a hand-computed sample; an exam sat through the player yields the expected result and cannot be sat twice.

**Phase 5 — Admin full and Managerial.** Feature flags screen, Users with View as and audit, Usage & cost, cache page; Managerial home, inbox, log-a-complaint, school usage. Acceptance: Admin flips `exams` off and the teacher's Exams tab and API disappear within one refresh; a logged complaint moves New → In progress → Resolved with SLA badges behaving over a simulated clock.

**Phase 6 — Polish, docs, production.** Motion pass, EN/AR pass, Lighthouse and performance budgets, Playwright suites per role, `docs/*.md` updated, `deploy-prod.yml` with my approval, first `v1.0.0` release of the dashboard.

## 10. Definition of done for the whole build

- Every screen in §4, §6 and §7 exists, is reachable only by its role, and is demonstrated in a recorded walkthrough per role on QA.
- The full teacher loop runs on QA with no mobile app: create teacher → sign in → This week → upload → review → publish → play as child in the web player → results → gradebook → exam → release → export.
- Adding a second school is proven on QA by inserting one row and one user through the seed script and showing the two teachers cannot see each other's data; no code change needed beyond enabling `multiSchool`.
- The player component set has a gallery of every stop type in every state, so the mobile build later has a working reference.
- `quality-performance` approved every PR; `docs/plan.md` shows all packages done.
