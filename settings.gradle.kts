rootProject.name = "HomeworkQuest"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

// `-Pquest.serverOnly=true` (used by the Dockerfile and CI) builds only the JVM server and its contract,
// so no Android SDK is needed in the container.
val serverOnly = (extra.properties["quest.serverOnly"] ?: System.getenv("QUEST_SERVER_ONLY"))?.toString() == "true"
include(":shared-api")
if (!serverOnly) {
    include(":shared-ui")
    include(":shared")
    include(":androidApp")
    include(":desktopApp")
}
