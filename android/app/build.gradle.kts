import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Rust core (crates/ffi) is built by the two tasks below. cargo-ndk cross-compiles the cdylib
// for arm64-v8a, and uniffi-bindgen generates the Kotlin bindings from a host build of the same
// crate (library mode reads uniffi's metadata from the .so, so it must be unstripped; AGP strips
// the Android one when packaging). Both run in the workspace root, one level up, and cargo's own
// incremental build keeps them cheap.
val ndkVersionPinned = "29.0.14206865"

val workspaceDir = layout.projectDirectory.dir("../..")
val rustSources = files(
    workspaceDir.file("Cargo.toml"),
    workspaceDir.file("Cargo.lock"),
    fileTree(workspaceDir.dir("crates/core")) { include("Cargo.toml", "src/**") },
    fileTree(workspaceDir.dir("crates/ffi")) { include("Cargo.toml", "uniffi.toml", "src/**") },
)

val cargoNdk = tasks.register<CargoNdkTask>("cargoNdk") {
    sources.from(rustSources)
    ndkHome.set(providers.environmentVariable("ANDROID_HOME").get() + "/ndk/" + ndkVersionPinned)
    workspace.set(workspaceDir)
    outDir.set(layout.buildDirectory.dir("rust/jniLibs"))
}

val uniffiBindgen = tasks.register<UniffiBindgenTask>("uniffiBindgen") {
    sources.from(rustSources)
    workspace.set(workspaceDir)
    outDir.set(layout.buildDirectory.dir("generated/uniffi"))
}

// Release signing. The keystore and its passwords are kept out of the repository, in
// ~/.config/tether/keystore.properties (storeFile, storePassword, keyAlias, keyPassword; storeFile
// relative to that directory). Keep a copy of both files somewhere safe: an update installs only
// over a build signed with the same key. Without them `assembleRelease` still builds, unsigned
// (`app-release-unsigned.apk`, zipaligned), and scripts/install-phone.sh has it signed by a root step
// the user approves on the phone (the key then lives in /var/lib/dibs-root/keys, dibs task #145).
val keystoreDir = File(System.getProperty("user.home"), ".config/tether")
val keystoreProperties = Properties().apply {
    val f = keystoreDir.resolve("keystore.properties")
    if (f.isFile) f.inputStream().use(::load)
}
// `-Punsigned` builds unsigned even with the key there (to check the root-step path).
val releaseStoreFile = keystoreProperties.getProperty("storeFile")
    ?.let { keystoreDir.resolve(it) }?.takeIf { it.isFile && !providers.gradleProperty("unsigned").isPresent }

// FCM wake (CLAUDE.md "Battery"). The Firebase project's Android app values go into the
// git-ignored local.properties as fcm.apiKey, fcm.appId, fcm.projectId and fcm.senderId. Without
// them the build works and the phone only syncs when it opens, shares, or retries its own queue.
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use(::load)
}
fun fcm(name: String) = localProperties.getProperty("fcm.$name", "").trim()

android {
    namespace = "com.kivan.tether"
    // Only platform android-37.2 is installed; 37 + minor 2 selects it (same as chordhand).
    compileSdk = 37
    compileSdkMinor = 2
    buildToolsVersion = "37.0.0"
    ndkVersion = ndkVersionPinned

    defaultConfig {
        applicationId = "com.kivan.tether"
        minSdk = 34
        targetSdk = 37
        versionCode = 48
        versionName = "0.14.1"
        ndk {
            // The Pixel 8 is arm64-v8a; nothing else is built or shipped.
            abiFilters += "arm64-v8a"
        }
        buildConfigField("String", "FCM_API_KEY", "\"${fcm("apiKey")}\"")
        buildConfigField("String", "FCM_APP_ID", "\"${fcm("appId")}\"")
        buildConfigField("String", "FCM_PROJECT_ID", "\"${fcm("projectId")}\"")
        buildConfigField("String", "FCM_SENDER_ID", "\"${fcm("senderId")}\"")
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoNdk, CargoNdkTask::outDir)
        variant.sources.kotlin?.addGeneratedSourceDirectory(uniffiBindgen, UniffiBindgenTask::outDir)
    }
}

dependencies {
    implementation(project(":dibs"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // uniffi's Kotlin bindings call the Rust library through JNA.
    implementation("${libs.jna.get()}@aar")
    implementation(libs.code.scanner)
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
}
