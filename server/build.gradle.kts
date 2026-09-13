plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "quest"
version = "0.1.0"
description = "Homework Quest API — Spring Boot"

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

// The Spring Boot BOM pins older Kotlin libraries; shared-api is compiled against these versions.
extra["kotlin.version"] = libs.versions.kotlin.get()
extra["kotlin-serialization.version"] = libs.versions.serialization.get()
extra["kotlin-coroutines.version"] = libs.versions.coroutines.get()

dependencies {
    // Shared contract: JSON schemas, sample model outputs and the illustration list (Kotlin JVM jar).
    implementation(projects.sharedApi)

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    // Cloud SQL connector (socket factory) for Cloud Run → Cloud SQL without a public IP.
    runtimeOnly("com.google.cloud.sql:postgres-socket-factory:1.21.0") {
        exclude(group = "io.grpc")
        exclude(group = "io.opentelemetry")
    }

    implementation(libs.anthropic.java)
    implementation(libs.poi.ooxml)
    implementation(libs.pdfbox)
    // Cloud Storage over the HTTP transport only: the gRPC/xDS transport and its telemetry roughly double the image.
    implementation(libs.gcs) {
        exclude(group = "io.grpc", module = "grpc-xds")
        exclude(group = "io.grpc", module = "grpc-netty-shaded")
        exclude(group = "io.grpc", module = "grpc-alts")
        exclude(group = "io.grpc", module = "grpc-googleapis")
        exclude(group = "org.conscrypt")
        exclude(group = "com.google.cloud", module = "google-cloud-monitoring")
        exclude(group = "com.google.api.grpc", module = "proto-google-cloud-monitoring-v3")
        exclude(group = "io.opentelemetry")
        exclude(group = "io.opentelemetry.contrib")
        exclude(group = "io.opentelemetry.semconv")
        exclude(group = "com.google.cloud.opentelemetry")
    }

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    runtimeOnly("com.h2database:h2")   // `h2` profile: local runs without PostgreSQL
}

tasks.withType<Test> { useJUnitPlatform() }

springBoot { mainClass.set("quest.server.ServerApplication") }
tasks.bootJar { archiveFileName.set("server.jar") }
