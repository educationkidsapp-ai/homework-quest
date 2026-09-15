#!/usr/bin/env bash
# Put an environment to sleep or wake it up. Cloud Run already scales to zero; the database is the only part that
# bills while idle, so "sleep" stops the Cloud SQL instance (data kept, storage-only cost) and "wake" starts it again
# (~1–2 minutes; the API answers again as soon as the database is up).
#
#   infra/env.sh qa sleep     infra/env.sh qa wake     infra/env.sh qa status
set -euo pipefail
ENV=${1:?qa|prod}; ACTION=${2:?sleep|wake|status}
cd "$(dirname "$0")/terraform"
PROJECT=$(grep -E '^project_id' "envs/$ENV.tfvars" | sed 's/.*= *"\(.*\)"/\1/')
INSTANCE="homework-quest-$ENV-db"
case "$ACTION" in
  sleep)
    echo "▸ stopping $INSTANCE in $PROJECT"
    gcloud sql instances patch "$INSTANCE" --project "$PROJECT" --activation-policy NEVER --quiet >/dev/null
    echo "✓ $ENV is asleep — wake it with: infra/env.sh $ENV wake" ;;
  wake)
    state=$(gcloud sql instances describe "$INSTANCE" --project "$PROJECT" --format 'value(settings.activationPolicy)')
    if [ "$state" = "ALWAYS" ]; then echo "✓ $ENV is already awake"; exit 0; fi
    echo "▸ starting $INSTANCE in $PROJECT"
    gcloud sql instances patch "$INSTANCE" --project "$PROJECT" --activation-policy ALWAYS --quiet >/dev/null
    for _ in $(seq 1 40); do
      [ "$(gcloud sql instances describe "$INSTANCE" --project "$PROJECT" --format 'value(state)')" = "RUNNABLE" ] && break; sleep 5
    done
    echo "✓ $ENV is awake" ;;
  status)
    gcloud sql instances describe "$INSTANCE" --project "$PROJECT" --format 'value(state,settings.activationPolicy)' | sed "s/^/$INSTANCE: /" ;;
  *) echo "usage: infra/env.sh qa|prod sleep|wake|status"; exit 1 ;;
esac
