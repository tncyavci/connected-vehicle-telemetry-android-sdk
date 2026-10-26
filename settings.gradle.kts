pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "connected-vehicle-telemetry-android-sdk"

include(
    ":app",
    ":core:domain",
    ":core:crypto",
    ":core:database",
    ":core:network",
    ":feature:cockpit",
)
