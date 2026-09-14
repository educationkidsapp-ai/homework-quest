# Deploying to Google Cloud

Everything runs in one region (`REGION` in `variables.env`, default `me-central1`).

| Piece | Service | Notes |
|---|---|---|
| API container | **Cloud Run** | `homework-quest-api`, scales to zero, 2 GiB / 2 vCPU (PDF rendering + LibreOffice) |
| Database | **Cloud SQL for PostgreSQL 16** | Cloud SQL Java connector (socket factory), no public IP; Flyway migrates on start |
| Uploaded slides, page images, children's media | **Cloud Storage** bucket | lifecycle rule deletes `uploads/` objects older than 24 h; page images and media stay |
| Secrets | **Secret Manager** | `DEEPSEEK_API_KEY`, `ANTHROPIC_API_KEY`, `DB_PASSWORD`, `ADMIN_PASSWORD`, `ADMIN_JWT_SECRET`, `FIREBASE_CREDENTIALS` |
| Images | **Artifact Registry** + **Cloud Build** | `deploy/gcloud.sh build` (multi-stage Dockerfile: Gradle contract → Maven server → Temurin 21 JRE + LibreOffice) |
| Admin panel | **Firebase Hosting** | Compose for Web (Wasm) static files, `deploy/gcloud.sh admin` |
| Parent sign-in | **Firebase Authentication** | the server verifies ID tokens with the Firebase Admin SDK |

## First deploy (from your laptop)

```bash
cp .env.example .env                      # DEEPSEEK_API_KEY, DB_PASSWORD, ADMIN_PASSWORD, PROJECT_ID, FIREBASE_CREDENTIALS=<path to service-account.json>
set -a; source .env; set +a
gcloud auth login && gcloud auth application-default login
./deploy/gcloud.sh all                    # setup → build → deploy → admin
```

`setup` is idempotent — re-run it after changing a secret. `deploy` prints the service URL and sets `PUBLIC_URL` on the service.
Build the release app against it:

```bash
./gradlew :androidApp:assembleRelease -Pquest.release.apiBaseUrl=https://homework-quest-api-xxxx-run.app
```

The admin panel's origin (`https://<project>.web.app`) must be in `CORS_ORIGINS` (set in `variables.env`, applied by `deploy`).

## Continuous deployment

`.github/workflows/deploy.yml` runs the shared-contract tests, the server tests (H2 + Testcontainers) and the OpenAPI ↔ contract check,
then on push to `main` builds the image with Cloud Build, deploys to Cloud Run and (when `FIREBASE_TOKEN` is set) the admin panel to Firebase Hosting.
Repository secrets: `GCP_PROJECT_ID`, `GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_DEPLOY_SERVICE_ACCOUNT` (a deployer SA with
`roles/run.admin`, `roles/cloudbuild.builds.editor`, `roles/iam.serviceAccountUser`, `roles/artifactregistry.writer`, `roles/storage.admin` on the Cloud Build bucket),
optional `FIREBASE_TOKEN` (`firebase login:ci`). Optional variable: `GCP_REGION`.

## Checks

```bash
curl https://<service-url>/health                                   # {"status":"ok","version":"<git sha>"}
open https://<service-url>/swagger-ui.html                          # OpenAPI
gcloud run services logs read homework-quest-api --region $REGION   # request lines carry ids and status only, never slide content
```
