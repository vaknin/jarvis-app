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

rootProject.name = "jarvis-link"
include(":app")
// Only with -Pbench: startup and frame measurements, and the baseline profile, on the emulator.
if (providers.gradleProperty("bench").isPresent) include(":bench")
