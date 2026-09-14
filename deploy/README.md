# Environments, CI/CD and deployment (§9)

Two isolated environments, each with its own GCP project, Cloud SQL, bucket, Secret Manager, Firebase Authentication,
Spring profile, Android flavor and API key — a QA mistake cannot touch production data.

| | QA | Production |
|---|---|---|
| Git branch | `develop` (default; PRs target it) | `main` |
| GCP project | `homework-quest-qa` | `homework-quest-prod` |
| GitHub environment | `qa` | `production` |
| Spring profile | `qa` (seeded sample lessons, debug logs) | `prod` |
| Android flavor | `qa` → `app.homeworkquest.qa`, "HQ · QA" | `prod` → `app.homeworkquest` |
| API | `https://homework-quest-api-<project-number>.me-central1.run.app` | same shape, prod project |
| Admin panel | `<API>/panel/` (served by the API, same origin) | `<API>/panel/` |
| Deploy trigger | merge to `develop` | `Deploy production` workflow (manual, from `main`, type `deploy`) |

Everything inside the projects is Terraform (`infra/terraform`): APIs, Artifact Registry, Cloud SQL 16 (+ backups, PITR in prod),
the files bucket (24 h lifecycle on `uploads/`), Secret Manager, the runtime service account, the Cloud Run service, and
Workload Identity Federation so GitHub Actions deploys **without any service-account keys**. Firebase is used for one
thing only — parents' sign-in (Firebase Authentication, free tier); the admin panel is served by the API and the QA APK is
a workflow artifact.

## Phase 0 — bootstrap (once per environment)

Prerequisites on your side: `gh auth login`, `gcloud auth login --update-adc`, `firebase login`, a billing account.
Everything else is CLI:

```bash
gcloud projects create homework-quest-qa && gcloud billing projects link homework-quest-qa --billing-account <ID>
firebase projects:addfirebase homework-quest-qa    # needs the Firebase terms accepted once in the console for this account
export DEEPSEEK_API_KEY=sk-...            # LLM key for this environment
export ADMIN_PASSWORD=...                 # admin panel sign-in (admin@quest.local)
# optional: ANTHROPIC_API_KEY, ANDROID_KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD
infra/bootstrap.sh qa                     # state bucket → terraform → your secret values → GitHub variables/secrets
infra/firebase-auth.sh homework-quest-qa  # Email/Password provider + Android app → androidApp/src/qa/google-services.json (commit)
# production: the same three with homework-quest-prod / prod
```

`bootstrap.sh` creates the Terraform state bucket, applies Terraform with your credentials (secret containers first, then
your values via `gcloud secrets versions add` — they never enter the Terraform state), and writes the resulting variables
(`GCP_PROJECT_ID`, `GCP_REGION`, `GCP_WIF_PROVIDER`, `GCP_DEPLOYER_SA`, `API_URL`, `ADMIN_URL`, `IMAGE`) and secrets
(`DEEPSEEK_API_KEY`, `ADMIN_PASSWORD`, `ANDROID_*`) into the GitHub environment with `gh variable set` / `gh secret set`.
Values never appear in chat, commits or logs. After that, CI deploys through Workload Identity and re-applies Terraform on
every deploy. Parents' ID tokens are verified with the Cloud Run service account — no Firebase service-account key anywhere.

## The six workflows

| Workflow | Trigger | What it does |
|---|---|---|
| `ci.yml` | every PR / push to `develop`, `main` | contract tests → server tests (H2 + Postgres via Testcontainers) → app tests, screenshots, Android QA APK, Wasm admin. iOS full-cycle UI test on `main` or PRs labelled `ios`. Status check `ci`. |
| `deploy-qa.yml` | merge to `develop` | build image once (server + admin panel at `/panel/`, tag = SHA) → `terraform apply` (QA) → `gcloud run deploy` → smoke test → signed QA APK (workflow artifact) → comment with API / admin / APK links on the merged PR |
| `deploy-production.yml` | manual (`gh workflow run deploy-production.yml -f confirm=deploy`) from `main` | promotes the **same QA image by digest** (no rebuild) → `terraform apply` (prod) → Cloud Run revision with **no traffic** (`canary` tag) → smoke tests on the canary URL → 100 % traffic → production APK attached to a GitHub release `vYYYY.MM.DD-<sha>` |
| `rollback.yml` | manual (`gh workflow run rollback.yml -f environment=production`) | shifts traffic back to the previous (or a named) revision, no build |
| `migration-check.yml` | PRs touching `server/src/main/resources/db/migration/**` | applied migrations unchanged, new ones additive (no DROP/RENAME), apply on Postgres 16 on top of `develop`'s schema, JPA `validate` boots |
| `dependabot.yml` | weekly | Gradle, Maven, Actions, Terraform updates, labelled `dependencies` |

Watching from the terminal:

```bash
gh pr create --base develop --fill && gh pr checks --watch
gh run watch                                    # the deploy that started on merge
gh workflow run deploy-production.yml -f confirm=deploy && gh run watch
gh release list
```

### Production gate

The account is on the GitHub Free plan with a private repository, where GitHub does not enforce environment reviewers or
branch protection. The gate is therefore in the workflow itself: production deploys only via manual dispatch, only from
`main`, only with `confirm=deploy`, and only an image that already ran through QA. Upgrading to GitHub Pro (or making
the repo public) lets you add the `production` required reviewer with one command:
`gh api --method PUT repos/educationkidsapp-ai/homework-quest/environments/production --input reviewers.json`.

## Local

```bash
docker compose up --build                       # Postgres + API, profile local
./server/run-local.sh                           # in-memory H2, no Docker
./gradlew :androidApp:installQaDebug -Pquest.qa.apiBaseUrl=https://<qa-cloud-run-url>
```
