plugins {
    id("com.android.test")
}

// Measures the `bench` build of :app on the emulator. Only exists with -Pbench.
android {
    namespace = "com.kivan.jarvis.measure"
    compileSdk = 37
    compileSdkMinor = 2
    buildToolsVersion = "37.0.0"
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    defaultConfig {
        minSdk = 34
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    buildTypes {
        create("bench") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    beforeVariants { it.enable = it.buildType == "bench" }
}

dependencies {
    implementation(libs.androidx.benchmark.macro)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.junit.ext)
}
