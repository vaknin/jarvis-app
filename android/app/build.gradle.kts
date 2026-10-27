import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Rust core (crates/ffi) is built by the two tasks below (buildSrc/RustTasks.kt): cargo-ndk
// cross-compiles the cdylib (arm64 for the Pixel, x86_64 for the emulator), and uniffi-bindgen
// generates the Kotlin bindings from a host build of the same crate.
val ndkVersionPinned = "29.0.14206865"

val workspaceDir = layout.projectDirectory.dir("../..")
val rustSources = files(
    workspaceDir.file("Cargo.toml"),
    workspaceDir.file("Cargo.lock"),
    fileTree(workspaceDir.dir("crates/core")) { include("Cargo.toml", "src/**") },
    fileTree(workspaceDir.dir("crates/voice")) { include("Cargo.toml", "src/**") },
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

val home = System.getProperty("user.home")

// Release signing: Jarvis's key, kept on the server outside git. An update installs only over a
// build signed with the same key, so keep ~/.config/jarvis/tether-keystore* safe.
val keys = Properties().apply {
    val f = File(home, ".config/jarvis/tether-keystore.properties")
    if (f.isFile) f.inputStream().use(::load)
}

// The phone's own key for the forum's gate on dibs (it lets in only ids it knows). It stays on the
// server, outside git, and goes only into the APK.
val forumSecret = File(home, ".config/jarvis/forum-phone-iroh-secret").takeIf { it.isFile }?.readText()?.trim()
    ?: error("no ~/.config/jarvis/forum-phone-iroh-secret (make one with the jarvis repo's `forum-gate keygen`)")

// FCM, only to wake the phone (Push.kt): the client values of the Firebase project, kept outside git
// in ~/.config/jarvis/tether-fcm.properties as apiKey, appId, projectId and senderId. Without them
// the app builds and works, just without wakes.
val fcmKeys = Properties().apply {
    val f = File(home, ".config/jarvis/tether-fcm.properties")
    if (f.isFile) f.inputStream().use(::load)
}
fun fcm(name: String) = fcmKeys.getProperty(name, "").trim()

android {
    namespace = "com.kivan.tether"
    // Platform 37.2 (37 + minor 2), as in the dibs app: current Compose needs 37.
    compileSdk = 37
    compileSdkMinor = 2
    buildToolsVersion = "37.0.0"
    ndkVersion = ndkVersionPinned

    defaultConfig {
        applicationId = "com.kivan.tether"
        minSdk = 34
        targetSdk = 36
        versionCode = 200
        versionName = "1.0.0"
        // A call goes to jarvis-voice through the forum tunnel's voice port (Voice.PORT).
        buildConfigField("String", "VOICE_URL", "\"ws://127.0.0.1:8791/call\"")
        buildConfigField("String", "FORUM_SECRET", "\"$forumSecret\"")
        buildConfigField("String", "FCM_API_KEY", "\"${fcm("apiKey")}\"")
        buildConfigField("String", "FCM_APP_ID", "\"${fcm("appId")}\"")
        buildConfigField("String", "FCM_PROJECT_ID", "\"${fcm("projectId")}\"")
        buildConfigField("String", "FCM_SENDER_ID", "\"${fcm("senderId")}\"")
    }

    signingConfigs {
        if (keys.isNotEmpty()) {
            create("release") {
                storeFile = File(keys.getProperty("storeFile"))
                storePassword = keys.getProperty("storePassword")
                keyAlias = keys.getProperty("keyAlias")
                keyPassword = keys.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Debug builds call a voice service on the emulator's host directly (no tunnel), for tests.
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "VOICE_URL", "\"ws://10.0.2.2:8790/call\"")
        }
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
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoNdk, CargoNdkTask::outDir)
        variant.sources.kotlin?.addGeneratedSourceDirectory(uniffiBindgen, UniffiBindgenTask::outDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    // uniffi's Kotlin bindings call the Rust library through JNA.
    implementation("${libs.jna.get()}@aar")
    implementation(libs.code.scanner)
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.core.telecom)
}
