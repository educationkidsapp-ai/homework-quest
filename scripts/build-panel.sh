#!/usr/bin/env bash
# Builds the admin panel (Kotlin/Wasm) and stages it in server/panel for the Docker image, which serves it at /panel/.
# The bundle uses relative API URLs, so it works on whatever origin the server runs on.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :webAdmin:wasmJsBrowserDistribution --no-daemon -q "$@"
rm -rf server/panel && mkdir -p server/panel
cp -R webAdmin/build/dist/wasmJs/productionExecutable/. server/panel/
rm -f server/panel/*.map
echo "panel: $(du -sh server/panel | cut -f1) in server/panel"
