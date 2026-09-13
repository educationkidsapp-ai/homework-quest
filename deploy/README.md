# Deploying to Google Cloud

Everything runs in one region (`REGION` in `variables.env`, default `me-central1`).

| Piece | Service | Notes |
|---|---|---|
| API container | **Cloud Run** | `homework-quest-api`, scales to zero, 1 GiB |
| Database | **Cloud SQL for PostgreSQL 16** | connected through the Cloud SQL Java connector (socket factory), no public IP needed |
| Uploaded slides | **Cloud Storage** bucket | lifecycle rule deletes any object older than 24 h; the API deletes them earlier once generation succeeds |
| Secrets | **Secret Manager** | `ANTHROPIC_API_KEY`, `DB_PASSWORD` mounted as env vars |
| Images | **Artifact Registry** + **Cloud Build** | `deploy/gcloud.sh build` |

## First deploy (from your laptop)

```bash
cp .env.example .env                      # fill ANTHROPIC_API_KEY, DB_PASSWORD, GCP_PROJECT
set -a; source .env; export PROJECT_ID=$GCP_PROJECT REGION=$GCP_REGION; set +a
gcloud auth login && gcloud auth application-default login
./deploy/gcloud.sh all
```

`setup` is idempotent — re-run it after changing a secret. `deploy` prints the service URL; build the release app against it:

```bash
./gradlew :androidApp:assembleRelease -Pquest.release.apiBaseUrl=https://homework-quest-api-xxxx-run.app
```

## Continuous deployment

`.github/workflows/deploy.yml` runs the server tests and, on push to `main`, builds the image with Cloud Build and deploys it.
Repository secrets: `GCP_PROJECT_ID`, `GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_DEPLOY_SERVICE_ACCOUNT` (a deployer SA with
`roles/run.admin`, `roles/cloudbuild.builds.editor`, `roles/iam.serviceAccountUser`, `roles/artifactregistry.writer`, `roles/storage.admin` on the Cloud Build bucket).
Optional variable: `GCP_REGION`.

## Checks

```bash
curl https://<service-url>/health          # {"status":"ok","version":"0.1.0"}
gcloud run services logs read homework-quest-api --region $REGION   # request lines carry ids and status only, never slide content
```
