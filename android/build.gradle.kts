// AGP 9 has Kotlin built in (the `kotlin-android` plugin must NOT be applied); the Kotlin Gradle
// plugin on the buildscript classpath selects the Kotlin version.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
