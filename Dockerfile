# syntax=docker/dockerfile:1.7
# ---- stage 1: the shared contract (Kotlin/JVM) → ~/.m2 via Gradle publishToMavenLocal (no Android SDK needed) ----
FROM eclipse-temurin:17-jdk AS contract
WORKDIR /src
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.console=plain"
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY shared-api ./shared-api
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :shared-api:publishToMavenLocal -Pquest.serverOnly=true --no-daemon -x jvmTest

# ---- stage 2: Maven builds the Spring Boot server against the published contract ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY --from=contract /root/.m2/repository/quest /contract/quest
COPY shared-api/src/commonMain/resources/schemas ./shared-api/src/commonMain/resources/schemas
COPY server ./server
RUN --mount=type=cache,target=/root/.m2 \
    mkdir -p /root/.m2/repository && cp -r /contract/quest /root/.m2/repository/ && \
    cd server && ./mvnw -q -B package -DskipTests && cp target/server.jar /server.jar

# ---- stage 3: the Angular dashboard (pnpm, no Android SDK) → static bundle served by the API at /dashboard/ ----
# Independent of stages 1–2, so BuildKit runs it in parallel with the JVM build. A headless JRE is needed because
# `postinstall` runs `pnpm gen:api` → openapi-generator-cli, which is a Java program (dashboard/tools/gen-api.mjs).
FROM node:22-alpine AS dashboard
WORKDIR /src/dashboard
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0 CI=1
RUN corepack enable && apk add --no-cache 'openjdk17-jre-headless=~17'
# Everything `pnpm install` needs before the sources: the manifest and lockfile, the scripts `postinstall` runs, and
# the contract it generates the API client from — gen-api.mjs resolves it as dashboard/../server/openapi.json, so it
# has to land at /src/server/openapi.json. The install layer is then reused whenever only dashboard sources change.
COPY dashboard/package.json dashboard/pnpm-lock.yaml ./
COPY dashboard/tools ./tools
COPY server/openapi.json /src/server/openapi.json
# `postinstall` also runs `pnpm schemas` (tools/schemas.mjs), which precompiles shared-api's Play.schema.json into the
# stop editor's validators (N2.4a) and resolves it as dashboard/../shared-api/src/commonMain/resources/schemas.
COPY shared-api/src/commonMain/resources/schemas /src/shared-api/src/commonMain/resources/schemas
RUN --mount=type=cache,target=/pnpm-store \
    corepack pnpm install --frozen-lockfile --store-dir /pnpm-store
# design/tokens.json is only read by `pnpm tokens`; src/styles/_tokens.generated.scss is committed, so the build
# itself needs nothing outside dashboard/ (CI's `pnpm tokens --check` guards the drift). The generated client written
# by postinstall lives in src/app/api/generated, which .dockerignore excludes so this COPY cannot bring a stale one.
COPY dashboard ./
# D11: one bundle serves QA and production — the image always builds the `production` configuration and the
# environment-specific values come from the API at runtime, so promoting the image by digest stays honest.
ARG DASHBOARD_CONFIG=production
RUN corepack pnpm build --configuration="$DASHBOARD_CONFIG"

# ---- stage 4: Temurin 21 JRE + LibreOffice (PPTX → PDF) + Node/anydoc + Tesseract, non-root, listens on $PORT ----
# Layer order is deliberate: apt → Node → anydoc → the app. Only the last layer changes on an ordinary commit, so a
# rebuild never re-downloads the Node tarball or re-resolves the anydoc tree.
FROM eclipse-temurin:21-jre-jammy AS runtime
# LibreOffice still renders PPTX pages to PDF. Tesseract is CR4's free OCR: the server runs it on .jpg/.png and on
# the PDF pages anydoc reports as image-only. `tesseract-ocr` pulls `tesseract-ocr-osd` (orientation/script) itself;
# -eng and -ara are the two languages the product teaches in.
RUN apt-get update && apt-get install -y --no-install-recommends \
      libreoffice-impress libreoffice-writer fonts-dejavu fonts-noto-core fonts-noto-color-emoji fontconfig curl \
      tesseract-ocr tesseract-ocr-eng tesseract-ocr-ara \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r quest && useradd -r -g quest -d /app quest

# Node 22 LTS ("Jod"), the official linux-x64 glibc build, for one job: running the anydoc CLI. Pinned to an exact
# version and to the SHA-256 published at https://nodejs.org/dist/v${NODE_VERSION}/SHASUMS256.txt, which is checked
# before a single byte is unpacked — no NodeSource script, no `latest`. The tarball's share/ (docs, man, systemtap)
# and include/ (C++ headers, only useful for building native addons) are dropped in the same layer.
ENV NODE_VERSION=22.23.2 \
    NODE_SHA256=b294a556e639d64338823920e5866c21c02741742d2e1529ee1a225c1ec9252a \
    PATH=/opt/node/bin:$PATH
RUN set -eux; \
    curl -fsSL -o /tmp/node.tar.gz "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.gz"; \
    printf '%s  /tmp/node.tar.gz\n' "${NODE_SHA256}" > /tmp/node.sha256; \
    sha256sum -c /tmp/node.sha256; \
    rm -f /tmp/node.sha256; \
    mkdir -p /opt/node; \
    tar -xzf /tmp/node.tar.gz -C /opt/node --strip-components=1; \
    rm -f /tmp/node.tar.gz; \
    rm -rf /opt/node/share /opt/node/include /opt/node/CHANGELOG.md /opt/node/README.md; \
    chmod -R a+rX /opt/node; \
    node --version

# @firecrawl/anydoc (MIT) — PDF/PPT(X)/DOC(X)/XLSX/CSV → Markdown on this machine, so the model reads .md instead of
# the original. The tree comes from the committed tools/anydoc/package-lock.json, so every build resolves the same
# versions. The lockfile lists both linux-x64 bindings (npm has no libc discriminator for them), so --libc=glibc
# names the one we want, the assertion proves it landed, and the musl twin (another 8 MB) is dropped.
COPY tools/anydoc/package.json tools/anydoc/package-lock.json /opt/anydoc/
WORKDIR /opt/anydoc
RUN set -eux; \
    npm ci --omit=dev --os=linux --cpu=x64 --libc=glibc --no-audit --no-fund; \
    test -f node_modules/@firecrawl/anydoc-linux-x64-gnu/anydoc.linux-x64-gnu.node; \
    rm -rf node_modules/@firecrawl/anydoc-linux-x64-musl; \
    npm cache clean --force; \
    chmod -R a+rX /opt/anydoc; \
    node -e "require('/opt/anydoc/node_modules/@firecrawl/anydoc')"; \
    node_modules/.bin/anydoc --version

WORKDIR /app
COPY --from=build /server.jar /app/server.jar
# the legacy admin panel (Compose for Web, Wasm) — built by CI / scripts/build-panel.sh into server/panel before
# docker build, served at /panel/. P3.6 retires webAdmin/ and this COPY together with it.
COPY server/panel /app/panel
# the Angular dashboard, served at /dashboard/ (DASHBOARD_DIR below)
COPY --from=dashboard /src/dashboard/dist/browser /app/dashboard
RUN mkdir -p /app/data && chown -R quest:quest /app
USER quest
ARG APP_VERSION=dev
# QUEST_ANYDOC_BIN / QUEST_TESSERACT_BIN are what the server's ProcessBuilder resolves; outside the image they are
# unset and the server falls back to a bare `anydoc` / `tesseract` on PATH (see docs/runbook.md → Local).
ENV APP_VERSION=$APP_VERSION PORT=8080 HOME=/app JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.awt.headless=true -Dfile.encoding=UTF-8" STORAGE_DIR=/app/data/files PANEL_DIR=/app/panel DASHBOARD_DIR=/app/dashboard QUEST_ANYDOC_BIN=/opt/anydoc/node_modules/.bin/anydoc QUEST_TESSERACT_BIN=/usr/bin/tesseract
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD curl -fsS http://127.0.0.1:${PORT}/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
