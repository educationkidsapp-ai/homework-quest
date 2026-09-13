plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

android {
    namespace = "quest.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.homeworkquest"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        debug {
            buildConfigField("boolean", "USE_FAKE_API", (findProperty("quest.useFakeApi")?.toString() ?: "true"))
            buildConfigField("String", "API_BASE_URL", "\"${findProperty("quest.apiBaseUrl") ?: "http://10.0.2.2:8080"}\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "USE_FAKE_API", (findProperty("quest.release.useFakeApi")?.toString() ?: "false"))
            buildConfigField("String", "API_BASE_URL", "\"${findProperty("quest.release.apiBaseUrl") ?: "https://REPLACE-WITH-CLOUD-RUN-URL"}\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}

dependencies {
    implementation(projects.shared)
    implementation(libs.androidx.activity.compose)
    implementation(libs.koin.android)
    implementation(compose.runtime)
    implementation(compose.foundation)
}
