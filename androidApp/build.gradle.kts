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
        debug {}
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("ci") ?: signingConfigs.getByName("debug")
        }
    }

    // Two environments (§9): `qa` talks to the QA Cloud Run service and installs side by side with production.
    // The API URL is injected from the environment's variables (-Pquest.qa.apiBaseUrl / -Pquest.prod.apiBaseUrl); without one
    // the build uses the in-app fake API (override with -Pquest.useFakeApi=false -Pquest.apiBaseUrl=http://10.0.2.2:8080 for a
    // local server). These fields live on the flavors only: a build-type buildConfigField would override the flavor's value.
    // The Firebase Web API key (a public client identifier) is read from the flavor's google-services.json
    // (`firebase apps:sdkconfig android <app id> > androidApp/src/<flavor>/google-services.json`); sign-in stays fake without it.
    fun firebaseApiKey(flavor: String): String {
        val f = file("src/$flavor/google-services.json")
        if (!f.exists()) return ""
        return Regex("\"current_key\"\\s*:\\s*\"([^\"]+)\"").find(f.readText())?.groupValues?.get(1) ?: ""
    }
    fun com.android.build.api.dsl.ApplicationProductFlavor.apiFields(flavor: String) {
        val envUrl = findProperty("quest.$flavor.apiBaseUrl")?.toString()
        val url = envUrl ?: findProperty("quest.apiBaseUrl")?.toString()
        // an environment URL always means the real server; otherwise gradle.properties' quest.useFakeApi decides
        val fake = if (envUrl != null) false else findProperty("quest.useFakeApi")?.toString()?.toBoolean() ?: (url == null)
        buildConfigField("boolean", "USE_FAKE_API", fake.toString())
        buildConfigField("String", "API_BASE_URL", "\"${url ?: "http://10.0.2.2:8080"}\"")
        // -Pquest.fakeAuth=true keeps FakeAuth (a local server running FAKE_AUTH) even when the flavor has a Firebase key
        buildConfigField("String", "FIREBASE_API_KEY", "\"${if (findProperty("quest.fakeAuth") == "true") "" else firebaseApiKey(flavor)}\"")
    }
    flavorDimensions += "env"
    productFlavors {
        create("qa") {
            dimension = "env"
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            resValue("string", "app_name", "HQ · QA")
            apiFields("qa")
        }
        create("prod") {
            dimension = "env"
            resValue("string", "app_name", "Homework Quest")
            apiFields("prod")
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
