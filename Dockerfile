# syntax=docker/dockerfile:1.7
# ---- build stage: Gradle builds only the server + shared contract (no Android SDK needed) ----
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /src
ENV QUEST_SERVER_ONLY=true GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.console=plain"
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY shared-api ./shared-api
COPY server ./server
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :server:bootJar -Pquest.serverOnly=true --no-daemon -x test && \
    cp server/build/libs/server.jar /server.jar

# A trimmed JRE (jlink) keeps the final image well under 300 MB.
RUN "$JAVA_HOME/bin/jlink" --add-modules \
      java.base,java.sql,java.desktop,java.naming,java.management,java.instrument,java.net.http,java.security.jgss,java.security.sasl,\
java.xml,java.xml.crypto,java.logging,java.scripting,java.rmi,java.compiler,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.zipfs,\
jdk.httpserver,jdk.management,jdk.charsets \
      --strip-debug --no-man-pages --no-header-files --compress=2 --output /jre

# ---- runtime stage: alpine + trimmed JRE, non-root, listens on $PORT ----
FROM alpine:3.20 AS runtime
RUN apk add --no-cache fontconfig ttf-dejavu tzdata wget && addgroup -S quest && adduser -S quest -G quest
ENV JAVA_HOME=/opt/jre PATH="/opt/jre/bin:$PATH"
COPY --from=build /jre /opt/jre
WORKDIR /app
COPY --from=build /server.jar /app/server.jar
USER quest
ENV PORT=8080 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.awt.headless=true -Dfile.encoding=UTF-8"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s CMD wget -qO- http://127.0.0.1:${PORT}/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
