Feature specs live in docs/features/. Follow the Conventions section of docs/features/00-week-plan.md for every feature.

# MySchool (مدرستي): what a new session needs to know

MySchool is a platform for **one school**. Staff (Admin, Manager, Coordinator, Teacher) use the web dashboard.
Parents and children use the mobile app. The repo is `educationkidsapp-ai/homework-quest`; "Homework Quest" is the
old name and still appears in package names. The QA pilot school is *Learning International School*.

## Read first
- [docs/features/00-week-plan.md](docs/features/00-week-plan.md): the current week's specs, conventions and definition of done.
- `docs/milestones/` (on the owner's machine, not yet committed): what is already built (milestones 01–12), in business terms.
- [docs/runbook.md](docs/runbook.md): the reference for tenancy, roles, flags, themes, chat, notifications, push, exams and QA.
- [docs/plan.md](docs/plan.md): the decision log (D1…, DR…) and the status table of every package and PR.
- [.claude/AGENT_RULES.md](.claude/AGENT_RULES.md): git, pipeline and token rules for worker agents.
- Role flows: [teacher](docs/teacher-flow.md), [coordinator](docs/coordinator-flow.md), [management](docs/management-flow.md), [admin](docs/admin-flow.md).
- Brand: [docs/brand/palette.md](docs/brand/palette.md). Use only the palette tokens and no literal colours.

## Architecture
| Area | Where | Stack |
|---|---|---|
| Server | `server/` | Spring Boot 3.5, Java 21, Maven, PostgreSQL + Flyway (`src/main/resources/db/migration`), H2 for local runs and tests |
| Contract | `shared-api/` | KMP DTOs (e.g. `quest.api.dto.NotificationKind`) and JSON schemas, shared by server and app. `server/openapi.json` is checked by `OpenApiContractTest` |
| Dashboard | `dashboard/` | Angular 22, zoneless, own UI kit in `src/app/ui`, generated API client (`pnpm gen:api`), i18n in `src/assets/i18n/{en,ar}.json`, served by the server at `<api>/dashboard/` |
| Mobile app | `shared/` (logic and UI), `androidApp/`, `iosApp/` | Kotlin Multiplatform + Compose. Features in `shared/src/commonMain/kotlin/quest/feature/*` (data/domain/presentation), routes in `quest/core/navigation/Routes.kt` |
| Design tokens | `design/tokens.json`, `shared-ui/` | `pnpm tokens --check` asserts the dashboard has not drifted from the tokens |
| Infra | `infra/terraform`, `.github/workflows`, `deploy/` | GCP Cloud Run + Cloud SQL + GCS. See [deploy/README.md](deploy/README.md) |
| E2E and seed | `e2e/` | Shell scripts against a running server; `e2e/local/` for local-only flows |

`webAdmin/` and `desktopApp/` are legacy and replaced by the dashboard. Do not extend them.

Shared interfaces change first, in their own PR: Flyway migrations, `server/openapi.json`,
`server/src/main/resources/permissions.json`, `design/tokens.json` and `shared-api/`. Flyway is at **V35**; take
the next free number and never edit an applied migration.

## Patterns to reuse (do not invent new ones)
- **Roles and permissions.**
  - Server: `permissions.json` is the matrix. `tenancy/TeacherScope`, `CoordinatorScope` and `ManagerScope` narrow
    every query, and the `*ScopeArchitectureTest` ArchUnit rules enforce it.
  - Dashboard: `core/permissions` (`can.directive`, `can.guard`, generated from the same JSON).
  - Runbook: "Roles and permissions".
- **School isolation.**
  - Every table has `school_id`, resolved from the JWT by `tenancy/TenantContext`.
  - `TenantArchitectureTest` forbids school-id literals.
  - New tables need a case in `tenancy/IsolationTest`.
- **Feature switches.**
  - Server: `flags/` (`FeatureFlags`, `FeatureFlagInterceptor`).
  - Dashboard: `core/flags` (`feature.guard`, `feature.directive`, `flags.defaults`).
  - App: `feature/school/FeatureGate.kt`, `Flags.kt`.
  - New switches are off by default. Runbook: "Feature flags".
- **Background jobs.**
  - Lesson generation: `analysis/LessonPipeline`, step deadlines in `PipelineDeadlines`, and `PipelineWatchdog`,
    which moves a stalled step to error/timeout and offers Retry.
  - The dashboard polls `/status` (`features/lessons/lesson-creation.service`), and a notification is written when
    the lesson is ready.
  - Inject `java.time.Clock` (`config/TimeConfig`, `TestClock` in tests). Never call `now()` directly. School-day
    logic lives in `platform/SchoolCalendar`.
- **Messaging and real time.**
  - `chat/`: REST plus the `/ws/chat` socket.
  - `ChatBus` uses Postgres LISTEN/NOTIFY across instances (`InMemoryChatBus` in tests).
  - `ChatPresence` tracks online status, plus a typing indicator.
  - Dashboard: `core/chat`.
  - App: `feature/chat`.
  - Runbook: "Chat and notifications".
- **Notifications.**
  - `notifications/NotificationService.notify(...)` with a `NotificationKind` writes the bell row.
  - The link it carries is resolved on the dashboard by `core/notifications/notification-target.ts`.
- **Push and deep links.**
  - `push/`: `FcmPushSender`, `ParentPush`, `DeviceService` (`POST /me/devices`, `/me/devices/unregister`), and
    `RecordingPushSender` in tests.
  - Android uses data-only FCM messages.
  - App: `feature/push/domain/NotificationRouter` maps a push to a route, and `PushNavigator` opens it only after
    the parent gate.
  - Runbook: "Push notifications (B4)".
- **Exports.**
  - CSV and Excel: `grading/GradingExports`.
  - Exam PDF: `exams/ExamController`.
  - Dashboard downloads go through `core/download`.
- **Attachments.**
  - `files/`: `AttachmentService`, `MediaController`, `FileStore` (GCS on QA, local on H2), and `ImageMetadata`,
    which strips EXIF and GPS.
  - Chat files: `chat/ChatAttachments`, with a 10.5 MB limit and dedupe by `clientId`.
  - Complaints: `chat/ComplaintService`, kept separate from messages.
  - Runbook: "Attachments and typing (B5)" and "Complaints (B6)".
- **App lock.**
  - `feature/lock` (`AppLock`, `LockScreen`) and `core/platform/Biometrics.*`.
  - Offered after the first sign-in, on cold start and in the parent area. Accepts biometric or the screen lock.
- **Exam integrity.**
  - Grading happens on the server only (`grading/AnswerKey`, `exams/SealedPaper`).
  - Results stay hidden until released, and the first answer wins.
- **UI rules.** Arabic and English for every string, right to left, dark mode, palette tokens only, and every daily
  action within two taps.

## Run and test locally
Facts about this Mac:
- The default JDK is 17, which Gradle uses.
- The server needs JDK 21: `export JAVA_HOME=/Users/kareemshehab/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.11+10/Contents/Home`.
- There is no Docker, so the Postgres tests run only in CI.
- Use `corepack pnpm`.

| What | Command |
|---|---|
| Server on H2 | `server/run-local.sh` (`SPRING_PROFILES_ACTIVE=h2`) |
| Contract, before server tests | `./gradlew :shared-api:jvmTest :shared-api:publishToMavenLocal -Pquest.serverOnly=true` |
| Server tests | `cd server && ./mvnw test -Dtest.excludedGroups=postgres` |
| Dashboard | `corepack pnpm --dir dashboard install`, then `lint`, `test`, `build --configuration=qa`, `tokens --check` |
| App, shared logic | `./gradlew :shared:desktopTest` |
| Android QA APK | `./gradlew :androidApp:assembleQaDebug` (worktrees need `local.properties` with `sdk.dir`) |
| iOS | `.github/workflows/ios.yml` runs only on push; dispatch it by hand for mobile branches |

CI (`.github/workflows/ci.yml`) runs only the jobs whose paths changed: contract, server, app, dashboard, scripts,
infra and docs (doc links are checked).

## Git, QA and Firebase
- **Branches.** Branch from `origin/develop` and open one PR per package into `develop`. Use conventional commits
  passed with `git commit -F`. Never push to `develop` or `main`.
- **QA deploy.** A merge to `develop` triggers `.github/workflows/deploy-qa.yml`, which deploys to Cloud Run in GCP
  project `homework-quest-qa` (me-central1). The QA API is
  `https://homework-quest-api-625882725080.me-central1.run.app` and the dashboard is at `/dashboard/`.
- **Production.** `main` deploys to `homework-quest-prod` through `deploy-production.yml`, with a canary.
  `rollback.yml` rolls back.
- **Android testers.** `scripts/distribute-qa-apk.sh "notes" "tester@…"` builds `assembleQaRelease` and uploads it
  with `firebase appdistribution:distribute`.
  - App `1:625882725080:android:818833947f3232e8df35ad`.
  - Firebase account `educationkidsapp@gmail.com`.
- **iPhone.** Push, the widget and the Live Activity need an Apple Developer account, which the school does not
  have yet.
- **Never use QA for test data or screenshots.** QA holds the school's real data and seeding is off
  (`seed_school=false`). Never set `SEED_RESET` there. Use a local H2 seed instead.
- **Terraform.** The CI deployer cannot set project IAM, so never add project IAM bindings in Terraform. IAM grants
  are the owner's decision.
- **Secrets.** Never print or commit a secret. Refer to it by its environment-variable name. Admin credentials live
  only in the git-ignored `.env`.
