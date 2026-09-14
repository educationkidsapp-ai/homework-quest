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

    // Release signing comes from the CI secrets (ANDROID_KEYSTORE_BASE64 decoded to a file) or a local keystore.properties;
    // without either, release builds fall back to the debug key so `assembleRelease` still works on a laptop.
    val keystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystoreFile != null) create("ci") {
            storeFile = keystoreFile
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }
    buildTypes {
        debug {
            buildConfigField("boolean", "USE_FAKE_API", (findProperty("quest.useFakeApi")?.toString() ?: "true"))
            buildConfigField("String", "API_BASE_URL", "\"${findProperty("quest.apiBaseUrl") ?: "http://10.0.2.2:8080"}\"")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("ci") ?: signingConfigs.getByName("debug")
            buildConfigField("boolean", "USE_FAKE_API", (findProperty("quest.release.useFakeApi")?.toString() ?: "false"))
            buildConfigField("String", "API_BASE_URL", "\"${findProperty("quest.release.apiBaseUrl") ?: "https://REPLACE-WITH-CLOUD-RUN-URL"}\"")
        }
    }

    // Two environments (§9): `qa` talks to the QA Cloud Run service and installs side by side with production.
    // The API URL is injected by CI from the environment's variables (-Pquest.qa.apiBaseUrl / -Pquest.prod.apiBaseUrl).
    flavorDimensions += "env"
    productFlavors {
        create("qa") {
            dimension = "env"
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            resValue("string", "app_name", "HQ · QA")
            findProperty("quest.qa.apiBaseUrl")?.let { buildConfigField("String", "API_BASE_URL", "\"$it\""); buildConfigField("boolean", "USE_FAKE_API", "false") }
        }
        create("prod") {
            dimension = "env"
            resValue("string", "app_name", "Homework Quest")
            findProperty("quest.prod.apiBaseUrl")?.let { buildConfigField("String", "API_BASE_URL", "\"$it\""); buildConfigField("boolean", "USE_FAKE_API", "false") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    // AGP 8.7 lint crashes on Kotlin 2.1 analysis (KaCallableMemberCall); release builds must not depend on it
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    implementation(projects.shared)
    implementation(libs.androidx.activity.compose)
    implementation(libs.koin.android)
    implementation(compose.runtime)
    implementation(compose.foundation)
}
