import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(17)

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    jvm("desktop")

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            linkerOpts.add("-lsqlite3")   // SQLDelight native driver
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.sharedApi)
            api(projects.sharedUi)

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)

            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.navigation.compose)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.logging)

            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)

            implementation(libs.coil.compose)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core)
            implementation(libs.koin.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.cio)
                implementation(libs.sqldelight.sqlite)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.desktop.uiTestJUnit4)
                implementation(libs.sqldelight.sqlite)
            }
        }
    }
}

android {
    lint { checkReleaseBuilds = false; abortOnError = false }
    namespace = "quest.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
    buildFeatures { buildConfig = true }
    defaultConfig {
        buildConfigField("boolean", "USE_FAKE_API", (findProperty("quest.useFakeApi")?.toString() ?: "true"))
        buildConfigField("String", "API_BASE_URL", "\"${findProperty("quest.apiBaseUrl") ?: "http://10.0.2.2:8080"}\"")
    }
}

// ---------------------------------------------------------------------------
// §4: every new screen sits inside a feature gate
// ---------------------------------------------------------------------------
// The prompt's rule is "a CI check fails the build if a new route, screen or controller is added without a flag
// reference" — an ESLint rule for `dashboard/`, an ArchUnit test for `server/`, and this for `shared/`.
//
// It is deliberately a text scan rather than a compiler plugin or a custom Gradle plugin: the thing being checked is
// that a human remembered a flag, and a missing `FeatureGate(` is exactly the shape of that mistake. A screen that
// genuinely has no flag (sign-in, the PIN pad — you cannot sell a school an app it cannot be signed into) says so on
// one line, so the exemption is in the file that needs it and shows up in that file's review:
//
//     // hq-flag: none (sign-in precedes any school, so there is no flag set to read)
//
// SCREENS_WITHOUT_GATES is every screen that existed when the rule was introduced (P2.2). It never grows: a new entry
// here is a screen shipped without a flag, which is the thing the check exists to prevent.
val screensWithoutGates = setOf(
    "auth/presentation/SignInScreen.kt",
    "parent/presentation/CalendarScreen.kt",
    "parent/presentation/LessonPanelScreen.kt",
    "parent/presentation/ParentHomeScreen.kt",
    "parent/presentation/PinScreen.kt",
    "parent/presentation/ProgressScreen.kt",
)

val checkFeatureGates by tasks.registering {
    group = "verification"
    description = "Fails when a screen under feature/*/presentation has no FeatureGate reference and no `// hq-flag: none (…)` line"
    val featureDir = layout.projectDirectory.dir("src/commonMain/kotlin/quest/feature")
    val allowed = screensWithoutGates
    inputs.dir(featureDir).withPropertyName("featureSources").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.upToDateWhen { true }
    doLast {
        val root = featureDir.asFile
        val screens = root.walkTopDown()
            .filter { it.isFile && it.name.endsWith("Screen.kt") && it.parentFile.name == "presentation" }
            .sortedBy { it.path }
            .toList()
        require(screens.isNotEmpty()) { "checkFeatureGates found no screens under ${root.path} — has the layout moved?" }

        val exempt = Regex("""//\s*hq-flag:\s*none\s*\(.+\)""")
        val ungated = screens.mapNotNull { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            val text = file.readText()
            when {
                text.contains("FeatureGate(") || text.contains("featureEnabled(") -> null
                exempt.containsMatchIn(text) -> null
                relative in allowed -> null
                else -> relative
            }
        }
        // A screen that grew a gate (or a reason) is no longer "pre-existing"; keeping it on the list would let a
        // later edit silently drop the gate again.
        val stale = allowed.filter { name ->
            val file = File(root, name)
            file.exists() && (file.readText().contains("FeatureGate(") || file.readText().contains("featureEnabled("))
        }

        val problems = buildList {
            ungated.forEach { add("$it has no FeatureGate(…) and no `// hq-flag: none (<reason>)` line") }
            stale.forEach { add("$it is on the allow-list but now references a flag — remove it from screensWithoutGates in shared/build.gradle.kts") }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "checkFeatureGates: §4 requires every screen to sit inside a feature gate.\n" +
                    problems.joinToString("\n") { "  - $it" } +
                    "\n\nWrap the screen in FeatureGate(\"<key>\") { … }, or explain the exemption with `// hq-flag: none (<reason>)`.",
            )
        }
        logger.lifecycle("checkFeatureGates: ${screens.size} screens, ${allowed.size} pre-existing exemptions, none missing a flag")
    }
}

// The design-token drift gate lives in :shared-ui (TokensDriftTest, aliased as :shared-ui:checkTokens). CI's App job
// runs `:shared:desktopTest`, so hang both it and the feature-gate check off that task rather than adding new jobs.
tasks.named("desktopTest") { dependsOn(":shared-ui:desktopTest", checkFeatureGates) }

sqldelight {
    databases {
        create("QuestDatabase") {
            packageName.set("quest.core.db")
            generateAsync.set(false)
        }
    }
}

