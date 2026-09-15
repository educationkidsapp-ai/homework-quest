# Homework Quest

A phone app for children in grades 1–3 (with a parent mode), a web admin panel where the school's slides become
lessons, and a backend that analyses each slide deck **once** and publishes it to every child on that course.

* **App** — Kotlin Multiplatform + Compose Multiplatform (Android first, iOS from the same UI). Downloads published
  lessons, plays them, reports answers. Never uploads slides, never calls AI.
* **Admin panel** — Compose Multiplatform for Web (Kotlin/Wasm, JS fallback) in `webAdmin/`. Uploads slides, reviews
  and edits what the model wrote, publishes. Being replaced by `dashboard/`, an Angular 22 workspace that grows it into
  a multi-school panel for three roles; both are in the tree until phase 3 retires `webAdmin/`.
* **Backend** — Spring Boot 3 / Java 21 / PostgreSQL in `server/` (Maven). DeepSeek by default (Anthropic optional),
  permanent AI cache, Firebase auth for parents, JWT for admins. Docker → Google Cloud Run.

> `docs/design.md` is a **stand-in** written during development; `docs/example-play.html` was never received, so the seeded
> Hot Soup lesson is a placeholder to be transcribed stop-for-stop when the file arrives.

## The loop

1. **Admin** creates a lesson (curriculum, grade, subject, date), uploads the PDF / PPTX / images.
2. **Server** reads the slides (Prompt A → `SourceAnalysis`): pages, vocabulary, story pieces, events, facts, skills — with
   "unsure" questions for the admin. Cached forever by *file SHA-256 + course + prompt version*.
3. **Admin** confirms the skills. The server writes **three levels** (Prompt B → `Play` ×3: *Same as the book / Think /
   Challenge*), the Level-1 **Again** variant, and the bilingual **parent panel** (Prompt C). All cached; the same slides
   for the same course never cost a second model call.
4. **Admin** reviews on a phone preview (the app's own stop composables), edits or regenerates a stop / a level, publishes.
5. Every child on that (curriculum, grade) sees an **island** on the day's map. 22 stop types cook a pot of ingredients;
   an exit ticket ends each level; certificate + sticker; Level 2 unlocks after Level 1 with ≥ 2 stars on most stops.
6. **Parents** (PIN) see the calendar, progress bands in words (never percentages), weak skills (review islands appear),
   recordings and drawings, and the parent panel in English + Arabic.

## Repository

```
shared-api/   KMP contract for app, server and admin: ContentApi / AdminApi, DTOs (Stop sealed type, Play, SourceAnalysis,
              ParentPanel, MapResponse), the four JSON schemas + SchemaValidator, MapAssembler (§7), CacheKeys (§4), seeds.
              Targets: jvm (published to ~/.m2 for the server), android, ios, wasmJs, js
shared-ui/    Design system + every stop composable, pot/journey widgets, tracing. No DB, no platform services → reused by the web admin
shared/       The app: MVI features (auth, children, content, map, journey, rewards, parent), SQLDelight, Koin, Ktor
androidApp/   desktopApp/   iosApp/       entry points (Android · desktop runner · SwiftUI host)
dashboard/    Angular 22 workspace (pnpm, standalone + signals, strict TS): the ui component library, motion system,
              EN/AR, Vitest + Playwright. Replaces webAdmin/ in phase 3; the two run side by side until then
webAdmin/     Compose for Web admin panel (wasmJs + js), RemoteAdminApi, MVI features (auth, lessons, editor, reports)
server/       Spring Boot 3 (Java 21, Maven): auth · tenancy · schools · users · children · content · analysis · admin ·
              files; Flyway; H2 profile; permissions.json is the one permission matrix
design/       tokens.json — the single token source the Angular dashboard and shared-ui both generate from
e2e/          Cross-cutting scripts: the two-school QA seed and the cross-school isolation assertions
docs/         plan.md (work packages) · runbook.md (operating the platform) · prompts/ · design.md · screenshots/
infra/        Terraform (per-environment GCP project), bootstrap.sh, secrets.sh, firebase-auth.sh, env.sh — see deploy/README.md
Dockerfile · docker-compose.yml · .github/workflows/{ci,deploy-qa,deploy-production,rollback,migration-check}.yml
```

### Architecture

* **MVI everywhere** — `MviViewModel<State, Intent, Effect>`: one `StateFlow`, an effects channel, a sequential intent
  queue. Every feature is `feature/<name>/{data,domain,presentation}`; `ArchitectureTest` enforces the layering.
* **One contract** — the server encodes responses with the same kotlinx codec and validates every model answer against the
  same schemas the app ships. `OpenApiContractTest` fails CI if a route the shared API calls disappears.
* **Map rule (§7)** and **cache keys (§4)** are pure Kotlin in `shared-api`, used verbatim by `FakeContentApi`, the server
  and the admin panel.
* **Offline** — lessons are cached forever per version in SQLDelight; answers go to an outbox flushed on the map screen
  and on lesson completion; without the server the map is assembled locally from cached lessons.

## Run it

### App (fake API, no server)

```bash
./gradlew :androidApp:installDebug            # seeded lessons: Hot Soup, Counting by 2s, the sh sound
./gradlew :desktopApp:run                     # same UI in a phone-sized window
```

### Server without PostgreSQL (in-memory H2)

```bash
cp .env.example .env                          # DEEPSEEK_API_KEY=sk-…  (LLM_PROVIDER=fake needs no key)
./server/run-local.sh                         # http://localhost:8080 — admin@quest.local / admin1234, FAKE_AUTH (Bearer fake-token-<uid>)
```

App against it from the emulator: `./gradlew :androidApp:installQaDebug -Pquest.apiBaseUrl=http://10.0.2.2:8080`

### Server + PostgreSQL (Docker)

```bash
docker compose up --build                     # Postgres 16 + API on http://localhost:8080 (profile local: fake Firebase auth)
```

### Admin panel

```bash
cd dashboard && corepack pnpm install && pnpm start   # Angular, http://localhost:4200/panel/ (styleguide at /panel/styleguide)

./gradlew :webAdmin:wasmJsBrowserDevelopmentRun -Pquest.admin.apiBaseUrl=http://localhost:8080   # legacy, Kotlin/Wasm
./gradlew :webAdmin:jsBrowserDevelopmentRun     -Pquest.admin.apiBaseUrl=http://localhost:8080   # legacy, JS fallback
```

### Tests

```bash
./gradlew :shared-api:jvmTest :shared:desktopTest      # schemas, scoring, map rule; architecture; 45 screenshot tests
./gradlew :shared-ui:checkTokens                      # Tokens.kt / Theme.kt against design/tokens.json
cd server && ./mvnw test                              # 107 tests: parent flow, admin pipeline + cache, tenancy isolation,
                                                      # auth + permissions, OpenAPI contract (Testcontainers needs Docker)
cd dashboard && pnpm lint && pnpm test                # ESLint (incl. the local hq rules) + Vitest
```

## Prompts

`server/src/main/java/quest/server/analysis/Prompts.java` — Prompt A (slides → analysis, vision model), Prompt B (analysis +
confirmed skills → one play per level), Prompt C (plays → parent panel). Versions live in `CacheKeys` (`a1`, `b2`, `c2`);
bump one to invalidate its cache. Rejected model answers are kept under `data/llm-failures/` for tuning. Small repairs
(explicit nulls, enum case, over-long strings, missing hints, unknown illustration keys) are applied before validation;
anything else is retried once with the validator's errors.

## Environments and CI/CD

`develop` → **QA** (`homework-quest-qa`), `main` → **production** (`homework-quest-prod`): separate GCP projects (Firebase Auth only),
Cloud SQL, buckets, keys, Spring profiles (`qa` / `prod`) and Android flavors (`qa` / `prod`). Terraform in `infra/terraform`,
six GitHub Actions workflows (CI, QA deploy with APK link on the PR, production promotion with a no-traffic canary, rollback,
migration check, Dependabot). Everything is driven with `gh` — see [deploy/README.md](deploy/README.md).

Each environment serves **many schools**. A school is a tenant with its own join code, curricula, grades and users;
every tenant row carries `school_id` and is filtered in the server from the caller's JWT. Dashboard users are `ADMIN`
(the platform owner, across every school, with an `X-School-Id` switcher), `TEACHER` and `MANAGERIAL` (one school
each); parents stay in Firebase Auth. Existing data lives in the default school `HQ0001`. Operating it — sleep/wake,
accounts and invites, the permission matrix, seeding two schools, rollback — is [docs/runbook.md](docs/runbook.md).

## Still needed from the school / project owner

* `docs/example-play.html` (the reference Hot Soup journey) and the real `docs/design.md`
* iOS: `FIREBASE_API_KEY` in `iosApp/Configuration/Config.xcconfig` for the production Firebase project (the Android
  `google-services.json` per flavor is written by `infra/firebase-auth.sh`; no server-side Firebase key is needed)
