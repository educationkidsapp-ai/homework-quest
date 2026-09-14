# Environments, CI/CD and deployment (§9)

Two isolated environments, each with its own GCP project, Cloud SQL, bucket, Secret Manager, Firebase project,
Spring profile, Android flavor and API key — a QA mistake cannot touch production data.

| | QA | Production |
|---|---|---|
| Git branch | `develop` (default; PRs target it) | `main` |
| GCP project | `homework-quest-qa` | `homework-quest-prod` |
| GitHub environment | `qa` | `production` |
| Spring profile | `qa` (seeded sample lessons, debug logs) | `prod` |
| Android flavor | `qa` → `app.homeworkquest.qa`, "HQ · QA" | `prod` → `app.homeworkquest` |
| Admin panel | `https://homework-quest-qa.web.app` | `https://homework-quest-prod.web.app` |
| Deploy trigger | merge to `develop` | `Deploy production` workflow (manual, from `main`, type `deploy`) |

Everything inside the projects is Terraform (`infra/terraform`): APIs, Artifact Registry, Cloud SQL 16 (+ backups, PITR in prod),
the files bucket (24 h lifecycle on `uploads/`), Secret Manager, the runtime service account, the Cloud Run service, and
Workload Identity Federation so GitHub Actions deploys **without any service-account keys**.

## Phase 0 — bootstrap (once per environment)

Prerequisites on your side: `gh auth login` (done), `gcloud auth login && gcloud auth application-default login`, the two
GCP projects created with billing attached, Firebase added to each (`firebase projects:addfirebase <project>`).

```bash
export DEEPSEEK_API_KEY=sk-...            # QA key
export ADMIN_PASSWORD=...                 # admin panel sign-in
export FIREBASE_CREDENTIALS=~/keys/homework-quest-qa-firebase.json   # service account for verifying parents' tokens
export FIREBASE_TOKEN=$(firebase login:ci)                            # once, shared by both environments
# optional: ANDROID_KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD
infra/bootstrap.sh qa
# then again with the production values
infra/bootstrap.sh prod
```

`bootstrap.sh` creates the Terraform state bucket, applies Terraform with your credentials, and writes the resulting
variables (`GCP_PROJECT_ID`, `GCP_REGION`, `GCP_WIF_PROVIDER`, `GCP_DEPLOYER_SA`, `API_URL`, `IMAGE`, `HOSTING_URL`) and
secrets (`DEEPSEEK_API_KEY`, `ADMIN_PASSWORD`, `FIREBASE_CREDENTIALS`, `FIREBASE_TOKEN`, `ANDROID_*`) into the GitHub
environment with `gh variable set` / `gh secret set`. Values never appear in chat, commits or logs. After that, CI
deploys through Workload Identity and re-applies Terraform on every deploy.

## The six workflows

| Workflow | Trigger | What it does |
|---|---|---|
| `ci.yml` | every PR / push to `develop`, `main` | contract tests → server tests (H2 + Postgres via Testcontainers) → app tests, screenshots, Android QA APK, Wasm admin. iOS full-cycle UI test on `main` or PRs labelled `ios`. Status check `ci`. |
| `deploy-qa.yml` | merge to `develop` | build image once (tag = SHA) → `terraform apply` (QA) → `gcloud run deploy` → smoke test → admin panel to Firebase Hosting → signed QA APK → comment with API / admin / APK links on the merged PR |
| `deploy-production.yml` | manual (`gh workflow run deploy-production.yml -f confirm=deploy`) from `main` | promotes the **same QA image by digest** (no rebuild) → `terraform apply` (prod) → Cloud Run revision with **no traffic** (`canary` tag) → smoke tests on the canary URL → 100 % traffic → admin panel → production APK attached to a GitHub release `vYYYY.MM.DD-<sha>` |
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
