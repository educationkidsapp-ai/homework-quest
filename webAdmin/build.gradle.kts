import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsTargetDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

/**
 * The admin panel: Compose Multiplatform for Web. Kotlin/Wasm is the primary target (`wasmJsBrowserDistribution`),
 * Kotlin/JS the fallback for browsers without Wasm GC (`jsBrowserDistribution`). Same design system and stop
 * composables as the app (shared-ui), same contract and validator (shared-api). Uploads and AI live behind the server.
 *
 *   ./gradlew :webAdmin:wasmJsBrowserDevelopmentRun -Pquest.admin.apiBaseUrl=http://localhost:8080
 *   ./gradlew :webAdmin:wasmJsBrowserDistribution            # same-origin bundle, served by the server at /panel/
 */
// Empty (the default for distributions) = same origin: the server serves the bundle under /panel/. Dev runs point at a local server.
val apiBaseUrl = (findProperty("quest.admin.apiBaseUrl") ?: "").toString()

kotlin {
    fun KotlinJsTargetDsl.web() {
        browser {
            commonWebpackConfig { outputFileName = "admin.js" }
        }
        binaries.executable()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { web() }
    js(IR) { web() }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.sharedUi)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.js)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.navigation.compose)
            implementation(libs.filekit.core)
            implementation(libs.filekit.compose)
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "quest.admin.resources"
    generateResClass = always
}

// Bake the API base URL into a Kotlin constant at build time.
val generateConfig by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/config/kotlin")
    inputs.property("apiBaseUrl", apiBaseUrl)
    outputs.dir(outDir)
    doLast {
        val f = outDir.get().asFile.resolve("quest/admin/BuildConfig.kt")
        f.parentFile.mkdirs()
        f.writeText("package quest.admin\n\n// GENERATED — set with -Pquest.admin.apiBaseUrl=...\nobject BuildConfig { const val API_BASE_URL = \"$apiBaseUrl\" }\n")
    }
}
kotlin.sourceSets.commonMain { kotlin.srcDir(generateConfig) }
