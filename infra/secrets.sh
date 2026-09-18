#!/usr/bin/env bash
# Push user-supplied secret values from the environment into Secret Manager — a new version only when the value
# changed. Values are read from env vars and piped straight to gcloud; nothing is echoed.
#
#   infra/secrets.sh <gcp-project>        # reads DEEPSEEK_API_KEY ADMIN_PASSWORD ANTHROPIC_API_KEY FIREBASE_CREDENTIALS
#                                        #       RESEND_API_KEY SEED_STAFF_PASSWORD
#
# FIREBASE_CREDENTIALS may be a path to the service-account JSON or the JSON itself. Empty variables are skipped
# (the existing version stays). Prints the names of optional secrets that now have a version, one per line, so
# callers can pass them to Terraform as optional_secrets.
set -euo pipefail
PROJECT=${1:?gcp project}
sync() {
  local name=$1 value=$2
  [ -z "$value" ] && return 0
  local current
  current=$(gcloud secrets versions access latest --secret "$name" --project "$PROJECT" 2>/dev/null || true)
  if [ "$current" != "$value" ]; then
    printf '%s' "$value" | gcloud secrets versions add "$name" --project "$PROJECT" --data-file=- >/dev/null
    echo "  $name: new version" >&2
  else
    echo "  $name: unchanged" >&2
  fi
}
sync DEEPSEEK_API_KEY "${DEEPSEEK_API_KEY:-}"
sync ADMIN_PASSWORD "${ADMIN_PASSWORD:-}"
sync ANTHROPIC_API_KEY "${ANTHROPIC_API_KEY:-}"
sync RESEND_API_KEY "${RESEND_API_KEY:-}"   # auth mail; only used when mail_provider = "resend"
sync SEED_STAFF_PASSWORD "${SEED_STAFF_PASSWORD:-}"   # shared password of the seeded teachers; only used when seed_school = true
FIREBASE_JSON="${FIREBASE_CREDENTIALS:-}"
[ -n "$FIREBASE_JSON" ] && [ -f "$FIREBASE_JSON" ] && FIREBASE_JSON=$(cat "$FIREBASE_JSON")
sync FIREBASE_CREDENTIALS "$FIREBASE_JSON"
for s in ANTHROPIC_API_KEY FIREBASE_CREDENTIALS RESEND_API_KEY SEED_STAFF_PASSWORD; do
  gcloud secrets versions list "$s" --project "$PROJECT" --filter="state=ENABLED" --format="value(name)" --limit 1 2>/dev/null | grep -q . && echo "$s"
done
exit 0
