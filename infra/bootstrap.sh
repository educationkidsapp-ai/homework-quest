#!/usr/bin/env bash
# Phase 0 / one-time per environment: state bucket → terraform apply → GitHub environment secrets & variables via gh.
# Runs with YOUR gcloud credentials (application-default); afterwards GitHub Actions deploys through Workload Identity.
#
#   export DEEPSEEK_API_KEY=sk-... ADMIN_PASSWORD=...          # never committed; FIREBASE_CREDENTIALS / ANTHROPIC_API_KEY / RESEND_API_KEY optional
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

echo "▸ terraform: secret containers first, then your values, then everything else"
terraform init -reconfigure -backend-config="envs/$ENV.backend" >/dev/null
terraform apply -var-file="envs/$ENV.tfvars" -auto-approve -target=google_secret_manager_secret.s >/dev/null
OPTIONAL=$(../secrets.sh "$PROJECT" | paste -sd, -)
[ -n "${DEEPSEEK_API_KEY:-}" ] || gcloud secrets versions access latest --secret DEEPSEEK_API_KEY --project "$PROJECT" >/dev/null 2>&1 || { echo "export DEEPSEEK_API_KEY first (Cloud Run needs it)"; exit 1; }
[ -n "${ADMIN_PASSWORD:-}" ]   || gcloud secrets versions access latest --secret ADMIN_PASSWORD   --project "$PROJECT" >/dev/null 2>&1 || { echo "export ADMIN_PASSWORD first (admin sign-in needs it)"; exit 1; }
terraform apply -var-file="envs/$ENV.tfvars" -auto-approve -var="optional_secrets=[$(echo "$OPTIONAL" | sed 's/[^,]*/"&"/g' | sed 's/""//')]"

echo "▸ GitHub: variables + secrets for environment $GH_ENV (values go straight from terraform/env to gh, never printed)"
gh variable set GCP_PROJECT_ID     --env "$GH_ENV" --repo "$REPO" --body "$PROJECT"
gh variable set GCP_REGION         --env "$GH_ENV" --repo "$REPO" --body "$REGION"
gh variable set GCP_WIF_PROVIDER   --env "$GH_ENV" --repo "$REPO" --body "$(terraform output -raw wif_provider)"
gh variable set GCP_DEPLOYER_SA    --env "$GH_ENV" --repo "$REPO" --body "$(terraform output -raw deployer_service_account)"
gh variable set API_URL            --env "$GH_ENV" --repo "$REPO" --body "$(terraform output -raw cloud_run_url)"
gh variable set IMAGE              --env "$GH_ENV" --repo "$REPO" --body "$(terraform output -raw image)"
gh variable set ADMIN_URL          --env "$GH_ENV" --repo "$REPO" --body "$(terraform output -raw admin_url)"
if [ "$ENV" = qa ]; then   # production promotes QA's image: it needs read access to QA's registry
  gh variable set QA_WIF_PROVIDER --env production --repo "$REPO" --body "$(terraform output -raw wif_provider)"
  gh variable set QA_DEPLOYER_SA  --env production --repo "$REPO" --body "$(terraform output -raw deployer_service_account)"
  gh variable set QA_IMAGE        --env production --repo "$REPO" --body "$(terraform output -raw image)"
fi
[ -n "${DEEPSEEK_API_KEY:-}" ] && gh secret set DEEPSEEK_API_KEY --env "$GH_ENV" --repo "$REPO" --body "$DEEPSEEK_API_KEY"
[ -n "${ADMIN_PASSWORD:-}" ]   && gh secret set ADMIN_PASSWORD   --env "$GH_ENV" --repo "$REPO" --body "$ADMIN_PASSWORD"
[ -n "${RESEND_API_KEY:-}" ]  && gh secret set RESEND_API_KEY  --env "$GH_ENV" --repo "$REPO" --body "$RESEND_API_KEY"   # auth mail (optional)
if [ -n "${FIREBASE_CREDENTIALS:-}" ]; then FJ="$FIREBASE_CREDENTIALS"; [ -f "$FJ" ] && FJ=$(cat "$FJ"); gh secret set FIREBASE_CREDENTIALS --env "$GH_ENV" --repo "$REPO" --body "$FJ"; fi
for s in ANDROID_KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD; do
  [ -n "${!s:-}" ] && gh secret set "$s" --repo "$REPO" --body "${!s}"
done

echo "▸ Firebase Authentication (parents' sign-in) — the only Firebase feature in use"
firebase projects:list 2>/dev/null | grep -q "$PROJECT" || echo "  (add Firebase to $PROJECT once: firebase projects:addfirebase $PROJECT, then infra/firebase-auth.sh $PROJECT)"
echo "✓ $ENV ready — API $(terraform output -raw cloud_run_url), admin panel $(terraform output -raw admin_url)"
echo "  first image: push to $([ "$ENV" = prod ] && echo main || echo develop) and watch: gh run watch"
