#!/usr/bin/env bash
# Builds the QA Android APK pointing to the Cloud Run QA server and uploads it to Firebase App Distribution via CLI.
# Usage:
#   ./scripts/distribute-qa-apk.sh ["Release notes"] ["tester1@example.com,tester2@example.com"]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"

NOTES="${1:-"QA Release $(date '+%Y-%m-%d %H:%M')"}"
TESTERS="${2:-""}"
APP_ID="1:625882725080:android:818833947f3232e8df35ad"
PROJECT_ID="homework-quest-qa"
FIREBASE_ACCOUNT="educationkidsapp@gmail.com"
QA_API="https://homework-quest-api-3tslmnbusq-ww.a.run.app"

# Java 17 for Gradle
if [ -d "/opt/homebrew/Cellar/openjdk@17/17.0.15/libexec/openjdk.jdk/Contents/Home" ]; then
  export JAVA_HOME="/opt/homebrew/Cellar/openjdk@17/17.0.15/libexec/openjdk.jdk/Contents/Home"
fi

echo "==> Building QA Android APK..."
cd "$REPO"
./gradlew :androidApp:assembleQaRelease -Pquest.qa.apiBaseUrl="$QA_API" --no-daemon

APK="$REPO/androidApp/build/outputs/apk/qa/release/androidApp-qa-release.apk"
if [ ! -f "$APK" ]; then
  echo "Error: APK not found at $APK" >&2
  exit 1
fi

echo "==> Distributing APK to Firebase App Distribution ($PROJECT_ID)..."
CMD=(firebase appdistribution:distribute "$APK" --app "$APP_ID" --project "$PROJECT_ID" --account "$FIREBASE_ACCOUNT" --release-notes "$NOTES")

if [ -n "$TESTERS" ]; then
  CMD+=(--testers "$TESTERS")
fi

"${CMD[@]}"
echo "==> Distribution complete!"
