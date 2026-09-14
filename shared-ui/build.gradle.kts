import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.library)
}

/**
 * Design system + every child-mode composable (stops, pot, journey widgets) + parent/admin components.
 * No database, no platform services, so the admin panel can reuse it on the web (wasmJs target added in Phase 5).
 */
kotlin {
    jvmToolchain(17)
    androidTarget { @OptIn(ExperimentalKotlinGradlePluginApi::class) compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    jvm("desktop")
    iosArm64(); iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(projects.sharedApi)
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.materialIconsExtended)
            api(compose.ui)
            api(compose.components.resources)
            implementation(libs.kotlinx.datetime)
        }
        val desktopMain by getting { dependencies { implementation(compose.desktop.currentOs) } }
    }
}

android {
    namespace = "quest.ui"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "quest.ui.resources"
    generateResClass = always
}
