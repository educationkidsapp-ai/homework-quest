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
# optional: ANTHROPIC_API_KEY, RESEND_API_KEY (auth email — see below), ANDROID_KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD
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

## Auth email (invites and password resets)

The dashboard's invites and password resets go through the server's `Mailer` (P1.3). Terraform sets these on Cloud Run:

| Env var | Terraform | QA today | Meaning |
|---|---|---|---|
| `MAIL_PROVIDER` | `mail_provider` | `log` | `log` = the invite/reset link is only written to the Cloud Run log; `resend` = really sent |
| `RESEND_API_KEY` | optional secret | absent | Resend key; wired into Cloud Run only once it has a version in Secret Manager |
| `MAIL_FROM` | `mail_from` | `no-reply@homework-quest.invalid` | **placeholder** (RFC 2606 `.invalid`); replace with an address on a Resend-verified domain before switching |
| `DASHBOARD_URL` | fixed to the API URL | `<API>` | origin the links are built on; the server appends `/panel/…`. Falls back to `PUBLIC_URL` when empty |

The product name in the mail is **not** an env var: since P2.1 it lives in the `platform_settings` table and is edited in
the dashboard under **Platform settings** (`PUT /admin/platform-settings`, ADMIN only), so changing it needs no deploy.

With the default `log` provider **no link reaches anyone** — it stays in the log, so an invited person cannot complete
sign-up from an email. The QA path that does work is the `e2e` seed: it creates staff through the ADMIN-only create-user
endpoint (`POST /admin/schools/{id}/users`, see `e2e/seed/seed.mjs`) and hands the report real passwords, so QA needs no
mailbox at all.

Switching QA to Resend (owner, once a key exists — the key never goes into git, tfvars or the Terraform state):

```bash
export RESEND_API_KEY=re_...                     # from resend.com, for a verified sending domain
infra/secrets.sh homework-quest-qa               # adds a Secret Manager version (prints only the secret NAMES)
gh secret set RESEND_API_KEY --env qa --body "$RESEND_API_KEY"   # so the deploy workflow can re-push it
# then in infra/terraform/envs/qa.tfvars:
#   mail_provider = "resend"
#   mail_from     = "no-reply@<your-verified-domain>"
# commit that on a branch, open a PR, merge → deploy-qa re-applies Terraform and the next invite is really sent
```

`infra/secrets.sh` prints the optional secrets that now have a version and the deploy workflow feeds that list to
Terraform's `optional_secrets`, so `RESEND_API_KEY` reaches the container only after the value exists — exactly like
`ANTHROPIC_API_KEY`. Production works the same way with `envs/prod.tfvars` and `--env production`.

`DASHBOARD_URL` currently equals the API URL because the dashboard is served by the API container at `<API>/panel/`
(decision D2). If the dashboard ever moves to its own hosting, this is the one value to repoint — the links then use the
dashboard origin and everything else stays as it is.

## Sleeping an environment

Cloud Run scales to zero by itself; the database is the only part that bills while idle. Stop it when QA is not in use
and start it again when needed — the deploy workflows wake it themselves before deploying:

```bash
infra/env.sh qa sleep     # stops Cloud SQL (data kept, storage-only cost); the API answers 503 meanwhile
infra/env.sh qa wake      # starts it again (~1–2 min); the API is back as soon as the database is up
infra/env.sh qa status
```

## The seven workflows

| Workflow | Trigger | What it does |
|---|---|---|
| `ci.yml` | every PR / push to `develop`, `main` | a ten-second `changes` job decides what the diff needs, then contract tests → server tests (H2 first, Testcontainers second) → app tests, screenshots, Android QA **debug** APK, Wasm admin → dashboard → shell scripts → Terraform/actionlint. Status check `ci`, where a filtered-out job counts as a pass. No macOS. |
| `ios.yml` | push to `develop` / `main` / a `v*` tag that touches the app, or `gh workflow run ios.yml` | the only macOS runner: Kotlin/Native compile for the simulator target + the full-cycle UI test |
| `deploy-qa.yml` | merge to `develop` | build image once (server + admin panel at `/panel/`, tag = SHA) → `terraform apply` (QA) → `gcloud run deploy` → smoke test → Playwright against the deployed QA → signed QA APK (workflow artifact) → comment with API / admin / APK links on the merged PR |
| `deploy-production.yml` | manual (`gh workflow run deploy-production.yml -f confirm=deploy`) from `main` | promotes the **same QA image by digest** (no rebuild) → `terraform apply` (prod) → Cloud Run revision with **no traffic** (`canary` tag) → smoke tests on the canary URL → 100 % traffic → production APK attached to a GitHub release `vYYYY.MM.DD-<sha>` |
| `rollback.yml` | manual (`gh workflow run rollback.yml -f environment=production`) | shifts traffic back to the previous (or a named) revision, no build |
| `migration-check.yml` | PRs touching `server/src/main/resources/db/migration/**` | applied migrations unchanged, new ones additive (no DROP/RENAME), apply on Postgres 16 on top of `develop`'s schema, JPA `validate` boots |
| `actions-cost.yml` | 06:00 UTC Monday, or on demand | minutes per workflow for the last seven days as a job summary; opens an issue labelled `infra` when the projected month is over 1,500 of the free plan's 2,000 minutes |
| `dependabot.yml` | weekly | Terraform updates, labelled `dependencies` (Renovate owns the rest — see `renovate.json`) |

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
