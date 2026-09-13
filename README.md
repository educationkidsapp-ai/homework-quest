# Homework Quest

A phone app for a Grade 1 child (with a parent mode) that turns the teacher's daily class slides into a question game.
Kotlin Multiplatform + Compose Multiplatform (Android first, iOS from the same UI) with a Spring Boot + PostgreSQL backend on Google Cloud Run.

> `docs/design.md` in this repo is a **stand-in** written during development; the original design document was not available.
> Every visual token is in one file (`shared/src/commonMain/kotlin/quest/core/design/Tokens.kt`) so the real design drops in without touching screens.

## The loop

1. Parent uploads PDF / PowerPoint / photos of the day's slides (or types the task) and tags it Math or English.
2. The server reads the slides with Claude (Prompt A) and lists the **skills the teacher taught**, flagging anything unsure.
3. Parent confirms the skills (untick, resolve unsure items, add one manually).
4. For each skill the server generates (Prompt B) a one-sentence explanation, 2–3 worked examples in the teacher's method, and 7 game questions with hints.
5. The child plays: wrong → hint + second try (never a red X); right → ink overlay, stars, sticker.
6. **Again** / **Harder** fetch a fresh set that never repeats a shown question. Weak skills come back automatically tomorrow.

## Repository

```
shared-api/     Kotlin Multiplatform contract shared by app and server: LessonApi, DTOs, the two JSON schemas, validator, sample outputs
shared/         The app (Compose Multiplatform). MVI, one package per feature with data / domain / presentation
androidApp/     Android entry point            desktopApp/   desktop runner for fast iteration       iosApp/   SwiftUI host
server/         Spring Boot 3 (Java 17) API + Flyway + JPA        deploy/   gcloud scripts        Dockerfile / docker-compose.yml
docs/design.md  design tokens, screens and rules
```

### Architecture (app)

* **MVI** — `core/mvi/MviViewModel`: one `StateFlow<State>`, an `effects` channel for one-shot events (speech, navigation), a sequential intent queue. Every feature has a `Contract` (`State` / `Intent` / `Effect`).
* **Feature packages** — `feature/{practice,lesson,map,rewards,parent}/{data,domain,presentation}`. `domain` holds pure Kotlin (use cases, `PracticeSession`, `ProgressBands`, `TraceScorer`); `data` holds repositories, `FakeLessonApi`, `KtorLessonApi`, SQLDelight access; `presentation` holds contracts, view models and composables. Features depend on other features only through `domain` interfaces.
* **Navigation** — Compose Navigation (androidx multiplatform), type-safe `@Serializable` routes in `core/navigation/Routes.kt`. Chosen over Decompose because the graph is small, `lifecycle-viewmodel` is multiplatform now, and it is one less mental model; Decompose would earn its place only for iOS-native navigation stacks, which the design rules out.
* **DI** — Koin. `ApiConfig.Fake` vs `ApiConfig.Server(url)` swaps the `LessonApi` implementation; no screen knows which is behind it.
* **Storage** — SQLDelight (`shared/src/commonMain/sqldelight/quest/core/db/Quest.sq`), mirrored by the server's Flyway migration. Question sets are cached locally so play works offline once downloaded.
* **Platform** — `expect`/`actual` for text-to-speech (Android `TextToSpeech`, iOS `AVSpeechSynthesizer`, desktop logs), file/camera pickers, SQL driver.

## Run the Android app on fake data

```bash
./gradlew :androidApp:installDebug          # default: quest.useFakeApi=true
```

On a fresh install the fake API seeds today's Math and English lessons so the child has islands to play immediately; parent mode
(PIN → Add lesson) walks through `uploading → reading → confirm → generating → ready` with delays. A typed task or file name
containing `error` shows the error state.

* Desktop runner (same UI, 412×915 window): `./gradlew :desktopApp:run`  (`QUEST_API_URL=http://localhost:8080` to use the server)
* Against a local server from the emulator: `./gradlew :androidApp:installDebug -Pquest.useFakeApi=false -Pquest.apiBaseUrl=http://10.0.2.2:8080`
* Release against Cloud Run: `./gradlew :androidApp:assembleRelease -Pquest.release.apiBaseUrl=https://<cloud-run-url>`

## Run the server and PostgreSQL locally

```bash
cp .env.example .env            # ANTHROPIC_API_KEY, DB_PASSWORD…  (FAKE_LLM=true runs without a key)
docker compose up --build       # Postgres 16 + API on http://localhost:8080
curl localhost:8080/health
```

Without Docker: `SPRING_PROFILES_ACTIVE=h2 FAKE_LLM=true ./gradlew :server:bootRun` (in-memory database, sample answers).

### `.env` variables

| Variable | Meaning |
|---|---|
| `ANTHROPIC_API_KEY` | server-side only; never shipped in the app |
| `ANTHROPIC_MODEL` | default `claude-opus-5` (vision-capable) |
| `FAKE_LLM` | `true` → answer from `shared-api` sample outputs |
| `DB_NAME` / `DB_USER` / `DB_PASSWORD` | PostgreSQL |
| `DATABASE_URL` | JDBC URL (compose and Cloud Run set it) |
| `STORAGE` / `UPLOAD_DIR` / `GCS_BUCKET` | `local` dir or `gcs` bucket for uploaded slides |
| `PORT` | listen port (Cloud Run sets it) |
| `MAX_UPLOAD_MB` | per-file limit, default 25 |

### API

| Endpoint | Body | Returns |
|---|---|---|
| `POST /lessons` | multipart: `request` (JSON `CreateLessonRequest`) + `files[]` | `LessonJob` in `uploading` (202) |
| `GET /lessons/{id}` | — | `LessonJob`: status `uploading` · `reading` · `needs_confirmation` · `generating` · `ready` · `error`, skills (with `unsure`), question sets when ready |
| `POST /lessons/{id}/confirm` | `ConfirmSkillsRequest` | job in `generating` |
| `POST /skills/{id}/generate` | `{mode: again\|harder\|easier, excludeQuestionIds, length}` | a new `QuestionSet` (cached by skill + mode + request) |
| `DELETE /lessons/{id}/files` | — | `{deleted: n}` — the app calls it automatically once ready |
| `GET /health` | — | `{status, version}` |

Two additions to the original spec: the `generating` status (between confirm and ready) and `questionSets` on `GET /lessons/{id}` once ready.
Errors are `{code, message}` with codes `unreadable_file`, `no_teaching_content`, `model_failed`, `too_large`, `not_found`, `bad_request`.

### AI pipeline

`server/src/main/java/quest/server/ai/Prompts.java` holds Prompt A (skill extraction) and Prompt B (question generation). Both return JSON only.
Output is validated with the **same** validator the app uses (`shared-api` `SchemaValidator`: JSON Schema 2020-12 + answer-correctness rules), and
retried once with the validation errors. PDFs are sent as document blocks, PPTX is rendered to one PNG per slide (Apache POI), photos as image blocks.
Uploaded files are deleted as soon as the app confirms `ready` and by the bucket's 24-hour lifecycle rule regardless. Logs carry ids, counts and status only.

## Deploy to Google Cloud

See [`deploy/README.md`](deploy/README.md): `./deploy/gcloud.sh all` creates Cloud SQL (PostgreSQL 16), the Cloud Storage bucket with the 24-hour delete rule,
Secret Manager secrets, an Artifact Registry repo, builds with Cloud Build and deploys to Cloud Run. `.github/workflows/deploy.yml` does the same on push to `main`.

## Tests

```bash
./gradlew :shared-api:jvmTest :shared:desktopTest :server:test
```

* `shared-api` — schema validation (valid samples, unknown fields, wrong answers, >12-word explanations, repeated ids, illustration list ↔ schema sync)
* `shared` — progress bands, trace scoring (49 samples / 30 px / 0.35–0.6–0.8), practice session, no-repeat generation, rewards, the full fake loop through the DB, and a **screenshot per screen** (`shared/build/screenshots/*.png`, rendered in the 412×915 frame)
* `server` — every endpoint with the Anthropic call mocked, the retry-once-then-error path, PPTX → PNG

## Child-screen checklist

Every child screen: no red X, no percentage, no timer, a working read-aloud button, targets ≥ 64 dp, tiles 176×100 with 12 dp gaps, palette and type from `docs/design.md`.
Progress is 7 stars; a wrong answer dims the tile and opens the hint sheet (with the number line for numeric types); locked islands say "This island is still asleep."

## Privacy

No accounts, one device, one child, local data. No analytics or ads SDKs. The model is instructed never to reproduce children's names or faces; the server keeps only skills and questions.
The parent PIN is stored as a salted, iterated SHA-256 hash.

## Illustration set

Keys live in `shared-api/src/commonMain/kotlin/quest/api/Illustrations.kt` and the schema enum (a test keeps them in sync); glyphs in `shared/.../core/design/Illustration.kt`.
Currently: ship, sheep, shop, shell, shoe, fish, chair, cheese, chick, chips, thumb, three, bath, moth, sun, sock, cat, dog, hat, bed, cup, pen, pig, bus, fox, apple, ball, tree, bee, moon, star, car.
