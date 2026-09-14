plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library) apply false
    `maven-publish`
}

// The server (Maven) depends on the JVM artifact: `./gradlew :shared-api:publishToMavenLocal -Pquest.serverOnly=true`
group = "quest"
version = "0.1.0"
publishing { repositories { mavenLocal() } }

val serverOnly = (findProperty("quest.serverOnly") ?: System.getenv("QUEST_SERVER_ONLY"))?.toString() == "true"
if (!serverOnly) apply(plugin = "com.android.library")

kotlin {
    jvmToolchain(17)
    jvm()
    if (!serverOnly) {
        androidTarget()
        iosArm64()
        iosSimulatorArm64()
        // The web admin panel (Kotlin/Wasm first, JS fallback) shares the same contract and validator.
        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
        wasmJs { browser() }
        js(IR) { browser() }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.json.schema.validator)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}

if (!serverOnly) {
    extensions.configure<com.android.build.gradle.LibraryExtension> {
        lint { checkReleaseBuilds = false; abortOnError = false }
        namespace = "quest.api"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
    }
}

// Embed the JSON schema files as Kotlin constants so common code (app + server) validates with the same text.
val generateSchemaConstants by tasks.registering {
    val schemaDir = layout.projectDirectory.dir("src/commonMain/resources/schemas")
    val outDir = layout.buildDirectory.dir("generated/schemas/kotlin")
    inputs.dir(schemaDir)
    outputs.dir(outDir)
    doLast {
        val out = outDir.get().asFile.resolve("quest/api/validation/Schemas.kt")
        out.parentFile.mkdirs()
        val sb = StringBuilder("package quest.api.validation\n\n// GENERATED from src/commonMain/resources/schemas — do not edit.\nobject Schemas {\n")
        schemaDir.asFile.listFiles { f -> f.name.endsWith(".schema.json") }!!.sortedBy { it.name }.forEach { f ->
            val name = f.name.removeSuffix(".schema.json")
            val body = f.readText().replace("$", "\${'$'}")
            sb.append("    val $name: String = \"\"\"$body\"\"\"\n")
        }
        sb.append("}\n")
        out.writeText(sb.toString())
    }
}
kotlin.sourceSets.commonMain { kotlin.srcDir(generateSchemaConstants) }
