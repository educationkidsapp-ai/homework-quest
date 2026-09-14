#!/usr/bin/env bash
# One-time Google Cloud setup + deploy for the Homework Quest API.
# Idempotent: re-running skips resources that already exist.
#
#   set -a; source .env; source deploy/variables.env; set +a
#   ./deploy/gcloud.sh setup     # APIs, Artifact Registry, Cloud SQL, bucket (24 h lifecycle), secrets, service account
#   ./deploy/gcloud.sh build     # Cloud Build → Artifact Registry image
#   ./deploy/gcloud.sh deploy    # Cloud Run service wired to Cloud SQL, the bucket and Secret Manager
#   ./deploy/gcloud.sh admin     # build the Wasm admin panel against the Cloud Run URL, deploy to Firebase Hosting
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
  ADMIN_JWT_SECRET="${ADMIN_JWT_SECRET:-$(openssl rand -base64 48 | tr -d '/+=' | cut -c1-48)}"
  : "${ADMIN_PASSWORD:?set ADMIN_PASSWORD in .env}"
  for s in DEEPSEEK_API_KEY ANTHROPIC_API_KEY DB_PASSWORD ADMIN_PASSWORD ADMIN_JWT_SECRET; do
    exists gcloud secrets describe "$s" || gcloud secrets create "$s" --replication-policy automatic
    printf '%s' "${!s:-unset}" | gcloud secrets versions add "$s" --data-file=-
  done
  # Firebase service account for verifying parents' ID tokens (a file path in .env)
  exists gcloud secrets describe FIREBASE_CREDENTIALS || gcloud secrets create FIREBASE_CREDENTIALS --replication-policy automatic
  if [ -n "${FIREBASE_CREDENTIALS:-}" ] && [ -f "$FIREBASE_CREDENTIALS" ]; then gcloud secrets versions add FIREBASE_CREDENTIALS --data-file="$FIREBASE_CREDENTIALS"; else printf '' | gcloud secrets versions add FIREBASE_CREDENTIALS --data-file=- ; fi

  echo "▸ service account $SERVICE_ACCOUNT"
  SA_EMAIL="$SERVICE_ACCOUNT@$PROJECT_ID.iam.gserviceaccount.com"
  exists gcloud iam service-accounts describe "$SA_EMAIL" || gcloud iam service-accounts create "$SERVICE_ACCOUNT" --display-name "Homework Quest API"
  gcloud projects add-iam-policy-binding "$PROJECT_ID" --member "serviceAccount:$SA_EMAIL" --role roles/cloudsql.client --condition=None >/dev/null
  gcloud storage buckets add-iam-policy-binding "gs://$BUCKET" --member "serviceAccount:$SA_EMAIL" --role roles/storage.objectAdmin >/dev/null
  for s in DEEPSEEK_API_KEY ANTHROPIC_API_KEY DB_PASSWORD ADMIN_PASSWORD ADMIN_JWT_SECRET FIREBASE_CREDENTIALS; do
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
    --set-env-vars "^@^SPRING_PROFILES_ACTIVE=prod@CLOUD_SQL_INSTANCE=$CONN@DB_NAME=$DB_NAME@DB_USER=$DB_USER@STORAGE_KIND=gcs@GCS_BUCKET=$BUCKET@LLM_PROVIDER=$LLM_PROVIDER@DEEPSEEK_MODEL=$DEEPSEEK_MODEL@DEEPSEEK_VISION_MODEL=$DEEPSEEK_VISION_MODEL@ANTHROPIC_MODEL=$ANTHROPIC_MODEL@CORS_ORIGINS=$CORS_ORIGINS@ADMIN_EMAIL=$ADMIN_EMAIL@APP_VERSION=${GIT_SHA:-latest}" \
    --set-secrets "DEEPSEEK_API_KEY=DEEPSEEK_API_KEY:latest,ANTHROPIC_API_KEY=ANTHROPIC_API_KEY:latest,DB_PASSWORD=DB_PASSWORD:latest,ADMIN_PASSWORD=ADMIN_PASSWORD:latest,ADMIN_JWT_SECRET=ADMIN_JWT_SECRET:latest,FIREBASE_CREDENTIALS=FIREBASE_CREDENTIALS:latest" \
    --memory 2Gi --cpu 2 --min-instances 0 --max-instances 3 --concurrency 20 --timeout 600 \
    --allow-unauthenticated
  URL=$(gcloud run services describe "$SERVICE" --region "$REGION" --format 'value(status.url)')
  gcloud run services update "$SERVICE" --region "$REGION" --update-env-vars "PUBLIC_URL=$URL" >/dev/null
  echo "✓ deployed: $URL"
  echo "  point the release app at it: ./gradlew :androidApp:assembleRelease -Pquest.release.apiBaseUrl=$URL"
  echo "  build the admin panel against it: ./gradlew :webAdmin:wasmJsBrowserDistribution -Pquest.admin.apiBaseUrl=$URL"
}

# The admin panel: Compose for Web (Wasm) built against the Cloud Run URL, served by Firebase Hosting.
admin() {
  URL=${API_URL:-$(gcloud run services describe "$SERVICE" --region "$REGION" --format 'value(status.url)')}
  echo "▸ admin panel → $URL"
  ./gradlew :webAdmin:wasmJsBrowserDistribution -Pquest.admin.apiBaseUrl="$URL" --no-daemon
  command -v firebase >/dev/null || { echo "install the Firebase CLI: npm i -g firebase-tools"; exit 1; }
  firebase deploy --only hosting --project "$PROJECT_ID"
  echo "✓ admin panel at https://$PROJECT_ID.web.app — make sure CORS_ORIGINS on Cloud Run includes it"
}

case "${1:-all}" in
  setup) setup ;;
  build) build ;;
  deploy) deploy ;;
  admin) admin ;;
  all) setup; build; deploy; admin ;;
  *) echo "usage: $0 setup|build|deploy|admin|all"; exit 1 ;;
esac
