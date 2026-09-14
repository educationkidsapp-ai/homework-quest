#!/usr/bin/env bash
# Builds the app + UI tests and opens an interactive XCUITest session driven by scripts/ios-driver.py.
#   scripts/ios-drive.sh <simulator-udid>       then:  python3 scripts/ios-driver.py tree | tap "Sign in" | ...
set -euo pipefail
cd "$(dirname "$0")/../iosApp"
D=${1:?simulator udid}
xcodebuild build-for-testing -project iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -destination "platform=iOS Simulator,id=$D" -configuration Debug CODE_SIGNING_ALLOWED=NO -derivedDataPath build/DerivedData 2>&1 | grep -E "error:|BUILD" | head -5
pkill -f "ios-driver.py serve" || true; pkill -f "xcodebuild test-without-building" || true
xcrun simctl terminate "$D" app.homeworkquest 2>/dev/null || true
(nohup python3 ../scripts/ios-driver.py serve > /tmp/driver.log 2>&1 &)
(TEST_RUNNER_QUEST_DRIVER=http://127.0.0.1:8099 nohup xcodebuild test-without-building -project iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -destination "platform=iOS Simulator,id=$D" -derivedDataPath build/DerivedData -only-testing:iosAppUITests/RemoteDriver > /tmp/xctest.log 2>&1 &)
echo "driver session starting (log: /tmp/xctest.log)"
