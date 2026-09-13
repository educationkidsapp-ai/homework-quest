#!/usr/bin/env bash
# One-time Google Cloud setup + deploy for the Homework Quest API.
# Idempotent: re-running skips resources that already exist.
#
#   set -a; source .env; source deploy/variables.env; set +a
#   ./deploy/gcloud.sh setup     # APIs, Artifact Registry, Cloud SQL, bucket (24 h lifecycle), secrets, service account
#   ./deploy/gcloud.sh build     # Cloud Build → Artifact Registry image
#   ./deploy/gcloud.sh deploy    # Cloud Run service wired to Cloud SQL, the bucket and Secret Manager
#   ./deploy/gcloud.sh all
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source deploy/variables.env; set +a
: "${PROJECT_ID:?set PROJECT_ID}"

exists() { "$@" >/dev/null 2>&1; }

setup() {
  gcloud config set project "$PROJECT_ID" >/dev/null
  echo "▸ enabling APIs"
  gcloud services enable run.googleapis.com sqladmin.googleapis.com storage.googleapis.com secretmanager.googleapis.com \
    artifactregistry.googleapis.com cloudbuild.googleapis.com iam.googleapis.com

  echo "▸ artifact registry $AR_REPO"
  exists gcloud artifacts repositories describe "$AR_REPO" --location "$REGION" || \
    gcloud artifacts repositories create "$AR_REPO" --repository-format docker --location "$REGION"

  echo "▸ cloud sql $SQL_INSTANCE (PostgreSQL 16)"
  if ! exists gcloud sql instances describe "$SQL_INSTANCE"; then
    gcloud sql instances create "$SQL_INSTANCE" --database-version POSTGRES_16 --tier "$SQL_TIER" --region "$REGION" \
      --storage-auto-increase --availability-type zonal --no-assign-ip --network default 2>/dev/null || \
    gcloud sql instances create "$SQL_INSTANCE" --database-version POSTGRES_16 --tier "$SQL_TIER" --region "$REGION" --storage-auto-increase
  fi
  exists gcloud sql databases describe "$DB_NAME" --instance "$SQL_INSTANCE" || gcloud sql databases create "$DB_NAME" --instance "$SQL_INSTANCE"
  DB_PASSWORD="${DB_PASSWORD:-$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)}"
  if exists gcloud sql users describe "$DB_USER" --instance "$SQL_INSTANCE"; then
    gcloud sql users set-password "$DB_USER" --instance "$SQL_INSTANCE" --password "$DB_PASSWORD"
  else
    gcloud sql users create "$DB_USER" --instance "$SQL_INSTANCE" --password "$DB_PASSWORD"
  fi

  echo "▸ bucket gs://$BUCKET with 24-hour delete rule"
  exists gcloud storage buckets describe "gs://$BUCKET" || gcloud storage buckets create "gs://$BUCKET" --location "$REGION" --uniform-bucket-level-access
  gcloud storage buckets update "gs://$BUCKET" --lifecycle-file deploy/lifecycle.json

  echo "▸ secrets"
  # The active provider's key is required; the other may stay empty.
  case "$LLM_PROVIDER" in
    deepseek) : "${DEEPSEEK_API_KEY:?set DEEPSEEK_API_KEY in .env}" ;;
    anthropic) : "${ANTHROPIC_API_KEY:?set ANTHROPIC_API_KEY in .env}" ;;
  esac
  for s in DEEPSEEK_API_KEY ANTHROPIC_API_KEY DB_PASSWORD; do
    exists gcloud secrets describe "$s" || gcloud secrets create "$s" --replication-policy automatic
    printf '%s' "${!s:-unset}" | gcloud secrets versions add "$s" --data-file=-
  done

  echo "▸ service account $SERVICE_ACCOUNT"
  SA_EMAIL="$SERVICE_ACCOUNT@$PROJECT_ID.iam.gserviceaccount.com"
  exists gcloud iam service-accounts describe "$SA_EMAIL" || gcloud iam service-accounts create "$SERVICE_ACCOUNT" --display-name "Homework Quest API"
  gcloud projects add-iam-policy-binding "$PROJECT_ID" --member "serviceAccount:$SA_EMAIL" --role roles/cloudsql.client --condition=None >/dev/null
  gcloud storage buckets add-iam-policy-binding "gs://$BUCKET" --member "serviceAccount:$SA_EMAIL" --role roles/storage.objectAdmin >/dev/null
  for s in DEEPSEEK_API_KEY ANTHROPIC_API_KEY DB_PASSWORD; do
    gcloud secrets add-iam-policy-binding "$s" --member "serviceAccount:$SA_EMAIL" --role roles/secretmanager.secretAccessor >/dev/null
  done
  echo "✓ setup done"
}

build() {
  echo "▸ cloud build → $IMAGE:${GIT_SHA:-latest}"
  gcloud builds submit --tag "$IMAGE:${GIT_SHA:-latest}" --project "$PROJECT_ID" .
}

deploy() {
  SA_EMAIL="$SERVICE_ACCOUNT@$PROJECT_ID.iam.gserviceaccount.com"
  CONN="$PROJECT_ID:$REGION:$SQL_INSTANCE"
  echo "▸ cloud run $SERVICE"
  gcloud run deploy "$SERVICE" \
    --image "$IMAGE:${GIT_SHA:-latest}" --region "$REGION" --platform managed \
    --service-account "$SA_EMAIL" \
    --add-cloudsql-instances "$CONN" \
    --set-env-vars "DATABASE_URL=jdbc:postgresql:///$DB_NAME?cloudSqlInstance=$CONN&socketFactory=com.google.cloud.sql.postgres.SocketFactory,DB_USER=$DB_USER,STORAGE=gcs,GCS_BUCKET=$BUCKET,LLM_PROVIDER=$LLM_PROVIDER,DEEPSEEK_MODEL=$DEEPSEEK_MODEL,ANTHROPIC_MODEL=$ANTHROPIC_MODEL" \
    --set-secrets "DEEPSEEK_API_KEY=DEEPSEEK_API_KEY:latest,ANTHROPIC_API_KEY=ANTHROPIC_API_KEY:latest,DB_PASSWORD=DB_PASSWORD:latest" \
    --memory 1Gi --cpu 1 --min-instances 0 --max-instances 3 --concurrency 20 --timeout 300 \
    --allow-unauthenticated
  URL=$(gcloud run services describe "$SERVICE" --region "$REGION" --format 'value(status.url)')
  echo "✓ deployed: $URL"
  echo "  point the release app at it: ./gradlew :androidApp:assembleRelease -Pquest.release.apiBaseUrl=$URL"
}

case "${1:-all}" in
  setup) setup ;;
  build) build ;;
  deploy) deploy ;;
  all) setup; build; deploy ;;
  *) echo "usage: $0 setup|build|deploy|all"; exit 1 ;;
esac
