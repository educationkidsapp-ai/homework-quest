#!/usr/bin/env bash
# One-time per environment: Firebase Authentication for parents (the only Firebase feature Homework Quest uses).
# Enables the Email/Password provider through the Identity Toolkit API, registers the Android app and writes its
# google-services.json (the app reads the Firebase Web API key from it). Needs: gcloud + firebase CLIs logged in as a
# project owner, and Firebase already added to the project (`firebase projects:addfirebase <project>`).
#
#   infra/firebase-auth.sh homework-quest-qa    # → androidApp/src/qa/google-services.json
#   infra/firebase-auth.sh homework-quest-prod  # → androidApp/src/prod/google-services.json
set -euo pipefail
PROJECT=${1:?gcp project}
FLAVOR=$([ "$PROJECT" = homework-quest-prod ] && echo prod || echo qa)
PACKAGE=$([ "$FLAVOR" = prod ] && echo app.homeworkquest || echo app.homeworkquest.qa)
cd "$(dirname "$0")/.."
TOKEN=$(gcloud auth print-access-token)
H=(-H "Authorization: Bearer $TOKEN" -H "x-goog-user-project: $PROJECT" -H "Content-Type: application/json")

echo "▸ Firebase Auth: initialise + Email/Password provider"
curl -sf -X POST "${H[@]}" "https://identitytoolkit.googleapis.com/v2/projects/$PROJECT/identityPlatform:initializeAuth" -d '{}' >/dev/null || true
curl -sf -X PATCH "${H[@]}" "https://identitytoolkit.googleapis.com/admin/v2/projects/$PROJECT/config?updateMask=signIn.email" \
  -d '{"signIn":{"email":{"enabled":true,"passwordRequired":true}}}' >/dev/null && echo "  email/password enabled"

echo "▸ Android app $PACKAGE"
APP_ID=$(firebase apps:list ANDROID --project "$PROJECT" 2>/dev/null | grep -F "$PACKAGE" | awk -F'│' '{print $3}' | tr -d ' ' || true)
if [ -z "$APP_ID" ]; then
  APP_ID=$(firebase apps:create android "Homework Quest ${FLAVOR^^}" --package-name "$PACKAGE" --project "$PROJECT" 2>/dev/null | grep -o '1:[0-9]*:android:[0-9a-f]*')
fi
echo "  app id $APP_ID"
mkdir -p "androidApp/src/$FLAVOR"
firebase apps:sdkconfig android "$APP_ID" --project "$PROJECT" 2>/dev/null | sed -n '/^{/,$p' > "androidApp/src/$FLAVOR/google-services.json"
echo "  wrote androidApp/src/$FLAVOR/google-services.json (commit it: the Web API key is a public client id)"
echo "✓ parents can register / sign in with email + password in the $FLAVOR app"
