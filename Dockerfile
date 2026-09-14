# syntax=docker/dockerfile:1.7
# ---- stage 1: the shared contract (Kotlin/JVM) → ~/.m2 via Gradle publishToMavenLocal (no Android SDK needed) ----
FROM eclipse-temurin:21-jdk AS contract
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

# ---- stage 3: Temurin 21 JRE + LibreOffice (PPTX → PDF) + fonts, non-root, listens on $PORT ----
FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends \
      libreoffice-impress libreoffice-writer fonts-dejavu fonts-noto-core fonts-noto-color-emoji fontconfig curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r quest && useradd -r -g quest -d /app quest
WORKDIR /app
COPY --from=build /server.jar /app/server.jar
RUN mkdir -p /app/data && chown -R quest:quest /app
USER quest
ENV PORT=8080 HOME=/app JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.awt.headless=true -Dfile.encoding=UTF-8" STORAGE_DIR=/app/data/files
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD curl -fsS http://127.0.0.1:${PORT}/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
