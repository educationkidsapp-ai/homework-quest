#!/usr/bin/env bash
# Builds the admin panel (Kotlin/Wasm) and stages it in server/panel for the Docker image, which serves it at /panel/.
# The bundle uses relative API URLs, so it works on whatever origin the server runs on.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :webAdmin:wasmJsBrowserDistribution --no-daemon -q "$@"
rm -rf server/panel && mkdir -p server/panel
cp -R webAdmin/build/dist/wasmJs/productionExecutable/. server/panel/
rm -f server/panel/*.map server/panel/*.LICENSE.txt
# content-hash the main bundle (webpack only hashes the .wasm files) so every asset but index.html can be cached forever
hash=$(shasum -a 256 server/panel/admin.js | cut -c1-20)
mv server/panel/admin.js "server/panel/$hash.js"
sed -i.bak "s|src=\"admin.js\"|src=\"$hash.js\"|" server/panel/index.html && rm -f server/panel/index.html.bak
echo "panel: $(du -sh server/panel | cut -f1) in server/panel (bundle $hash.js)"
