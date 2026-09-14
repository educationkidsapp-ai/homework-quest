#!/usr/bin/env bash
# Phase 0 / one-time per environment: state bucket → terraform apply → GitHub environment secrets & variables via gh.
# Runs with YOUR gcloud credentials (application-default); afterwards GitHub Actions deploys through Workload Identity.
#
#   export DEEPSEEK_API_KEY=sk-... ADMIN_PASSWORD=... FIREBASE_CREDENTIALS=/path/to/service-account.json   # never committed
#   infra/bootstrap.sh qa        # project homework-quest-qa   ← develop
#   infra/bootstrap.sh prod      # project homework-quest-prod ← main
set -euo pipefail
ENV=${1:?qa|prod}
cd "$(dirname "$0")/terraform"
PROJECT=$(grep -E '^project_id' "envs/$ENV.tfvars" | sed 's/.*= *"\(.*\)"/\1/')
REGION=$(grep -E '^region' "envs/$ENV.tfvars" | sed 's/.*= *"\(.*\)"/\1/')
REPO=$(grep -E '^github_repo' "envs/$ENV.tfvars" | sed 's/.*= *"\(.*\)"/\1/')
GH_ENV=$([ "$ENV" = prod ] && echo production || echo qa)
STATE="$PROJECT-tfstate"

echo "▸ $ENV → GCP project $PROJECT, GitHub environment $GH_ENV"
gcloud config set project "$PROJECT" >/dev/null
gcloud services enable storage.googleapis.com cloudresourcemanager.googleapis.com >/dev/null
gcloud storage buckets describe "gs://$STATE" >/dev/null 2>&1 || gcloud storage buckets create "gs://$STATE" --location "$REGION" --uniform-bucket-level-access
gcloud storage buckets update "gs://$STATE" --versioning >/dev/null

echo "▸ terraform apply"
terraform init -reconfigure -backend-config="envs/$ENV.backend" >/dev/null
FIREBASE_JSON=""; [ -n "${FIREBASE_CREDENTIALS:-}" ] && [ -f "$FIREBASE_CREDENTIALS" ] && FIREBASE_JSON=$(cat "$FIREBASE_CREDENTIALS")
TF_VAR_deepseek_api_key="${DEEPSEEK_API_KEY:-}" TF_VAR_anthropic_api_key="${ANTHROPIC_API_KEY:-}" TF_VAR_admin_password="${ADMIN_PASSWORD:-}" TF_VAR_firebase_credentials_json="$FIREBASE_JSON" \
  terraform apply -var-file="envs/$ENV.tfvars" -auto-approve

echo "▸ GitHub: variables + secrets for environment $GH_ENV (values go straight from terraform/env to gh, never printed)"
gh variable set GCP_PROJECT_ID     --env "$GH_ENV" --repo "$REPO" --body "$PROJECT"
gh variable set GCP_REGION         --env "$GH_ENV" --repo "$REPO" --body "$REGION"
gh variable set GCP_WIF_PROVIDER   --env "$GH_ENV" --repo "$REPO" --body "$(terraform output -raw wif_provider)"
gh variable set GCP_DEPLOYER_SA    --env "$GH_ENV" --repo "$REPO" --body "$(terraform output -raw deployer_service_account)"
gh variable set API_URL            --env "$GH_ENV" --repo "$REPO" --body "$(terraform output -raw cloud_run_url)"
gh variable set IMAGE              --env "$GH_ENV" --repo "$REPO" --body "$(terraform output -raw image)"
gh variable set HOSTING_URL        --env "$GH_ENV" --repo "$REPO" --body "$(terraform output -raw hosting_url)"
if [ "$ENV" = qa ]; then   # production promotes QA's image: it needs read access to QA's registry
  gh variable set QA_WIF_PROVIDER --env production --repo "$REPO" --body "$(terraform output -raw wif_provider)"
  gh variable set QA_DEPLOYER_SA  --env production --repo "$REPO" --body "$(terraform output -raw deployer_service_account)"
  gh variable set QA_IMAGE        --env production --repo "$REPO" --body "$(terraform output -raw image)"
fi
[ -n "${DEEPSEEK_API_KEY:-}" ] && gh secret set DEEPSEEK_API_KEY --env "$GH_ENV" --repo "$REPO" --body "$DEEPSEEK_API_KEY"
[ -n "${ADMIN_PASSWORD:-}" ]   && gh secret set ADMIN_PASSWORD   --env "$GH_ENV" --repo "$REPO" --body "$ADMIN_PASSWORD"
[ -n "$FIREBASE_JSON" ]        && gh secret set FIREBASE_CREDENTIALS --env "$GH_ENV" --repo "$REPO" --body "$FIREBASE_JSON"
[ -n "${FIREBASE_TOKEN:-}" ]   && gh secret set FIREBASE_TOKEN --repo "$REPO" --body "$FIREBASE_TOKEN"
for s in ANDROID_KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD; do
  [ -n "${!s:-}" ] && gh secret set "$s" --repo "$REPO" --body "${!s}"
done

echo "▸ Firebase Hosting site for the admin panel"
firebase projects:list 2>/dev/null | grep -q "$PROJECT" || echo "  (add Firebase to $PROJECT: firebase projects:addfirebase $PROJECT)"
echo "✓ $ENV ready — API $(terraform output -raw cloud_run_url), admin $(terraform output -raw hosting_url)"
echo "  first image: push to $([ "$ENV" = prod ] && echo main || echo develop) and watch: gh run watch"
