import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
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
    fileTree(workspaceDir.dir("crates/tabs-wire")) { include("Cargo.toml", "src/**") },
    fileTree(workspaceDir.dir("crates/ffi")) { include("Cargo.toml", "uniffi.toml", "src/**") },
)

val cargoNdk = tasks.register<CargoNdkTask>("cargoNdk") {
    sources.from(rustSources)
    ndkHome.set(providers.environmentVariable("ANDROID_HOME").get() + "/ndk/" + ndkVersionPinned)
    workspace.set(workspaceDir)
    outDir.set(layout.buildDirectory.dir("rust/jniLibs"))
    targets.set(if (project.hasProperty("emuRelease") || project.hasProperty("bench")) listOf("x86_64") else listOf("arm64-v8a", "x86_64"))
}

val uniffiBindgen = tasks.register<UniffiBindgenTask>("uniffiBindgen") {
    sources.from(rustSources)
    workspace.set(workspaceDir)
    outDir.set(layout.buildDirectory.dir("generated/uniffi"))
}

val home = System.getProperty("user.home")

// Tests on the emulator: `-PemuRelease` makes the release build for x86_64 with the emulator's key
// (never the phone's), and `-PforumTicket=...` points it at a private gate, so a test can publish and
// fail without the phone's store or the live voice service seeing it.
val emuRelease = project.hasProperty("emuRelease")
// `-Pbench` adds a build type for the emulator only: a release build with fake tabs and the emulator's
// key, which the :bench module measures. `-Pbench=plain` leaves it unshrunk, which is what the baseline
// profile is taken from. Normal builds never see it.
val bench = project.hasProperty("bench")
val benchPlain = project.findProperty("bench") == "plain"
val forumTicket = (project.findProperty("forumTicket") as String?).orEmpty()

// Every commit is a higher versionCode, so the phone can tell a newer build from Jarvis's server
// (Updater.kt) from its own. The first lean builds were 200; the count passed that at commit 225.
val commits = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
    .standardOutput.asText.get().trim().toInt()

// Release signing: Jarvis's key, kept on the server outside git. An update installs only over a
// build signed with the same key, so keep ~/.config/jarvis/jarvis-keystore* safe.
val keys = Properties().apply {
    val f = File(home, ".config/jarvis/jarvis-keystore.properties")
    if (f.isFile) f.inputStream().use(::load)
}

// The phone's own key for the forum's gate on dibs (it lets in only ids it knows). It stays on the
// server, outside git, and goes only into the APK.
val forumSecret = File(home, ".config/jarvis/forum-phone-iroh-secret").takeIf { it.isFile }?.readText()?.trim()
    ?: error("no ~/.config/jarvis/forum-phone-iroh-secret (make one with the jarvis repo's `forum-gate keygen`)")
// Debug builds (the emulator) get their own key: an endpoint with the phone's id anywhere else takes
// the phone's relay traffic (the relay sends to the newest client with an id), which dropped Aviv's
// calls on 2026-10-28 while an emulator ran a debug build. Its id is in the gate's allow file too.
val forumEmuSecret = File(home, ".config/jarvis/forum-emu-iroh-secret").takeIf { it.isFile }?.readText()?.trim()
    ?: error("no ~/.config/jarvis/forum-emu-iroh-secret (debug builds must never carry the phone's key)")

// FCM, only to wake the phone (Push.kt): the client values of the Firebase project, kept outside git
// in ~/.config/jarvis/jarvis-fcm.properties as apiKey, appId, projectId and senderId. Without them
// the app builds and works, just without wakes.
val fcmKeys = Properties().apply {
    val f = File(home, ".config/jarvis/jarvis-fcm.properties")
    if (f.isFile) f.inputStream().use(::load)
}
fun fcm(name: String) = fcmKeys.getProperty(name, "").trim()

android {
    namespace = "com.kivan.jarvis"
    // Platform 37.2 (37 + minor 2), as in the dibs app: current Compose needs 37.
    compileSdk = 37
    compileSdkMinor = 2
    buildToolsVersion = "37.0.0"
    ndkVersion = ndkVersionPinned

    defaultConfig {
        applicationId = "com.kivan.jarvis"
        minSdk = 34
        targetSdk = 36
        versionCode = commits
        versionName = "1.0.$commits"
        // A call goes to jarvis-voice through the forum tunnel's voice port (Voice.PORT).
        buildConfigField("String", "VOICE_URL", "\"ws://127.0.0.1:8791/call\"")
        buildConfigField("String", "FORUM_SECRET", "\"$forumSecret\"")
        buildConfigField("String", "TEST_TICKET", "\"$forumTicket\"")
        // The app this one replaced (Move): debug builds move from its debug build, signed alike.
        buildConfigField("String", "OLD_APP", "\"com.kivan.tether\"")
        manifestPlaceholders["oldApp"] = "com.kivan.tether"
        // Test hooks in Move, only in builds for the emulator's private gate.
        buildConfigField("boolean", "EMU_TEST", emuRelease.toString())
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
        // Debug builds call a voice service on the emulator's host directly (no tunnel), for tests:
        // the live one, or `-PvoiceUrl=ws://10.0.2.2:8811/call` for a `serve --dry-run` on another port.
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "OLD_APP", "\"com.kivan.tether.debug\"")
            manifestPlaceholders["oldApp"] = "com.kivan.tether.debug"
            val voiceUrl = (project.findProperty("voiceUrl") as String?) ?: "ws://10.0.2.2:8790/call"
            buildConfigField("String", "VOICE_URL", "\"$voiceUrl\"")
            buildConfigField("String", "FORUM_SECRET", "\"$forumEmuSecret\"")
        }
        release {
            // The phone only: half the APK, which updates download over mobile data (Updater). Debug
            // builds keep x86_64 for the emulator.
            ndk { abiFilters += if (emuRelease) "x86_64" else "arm64-v8a" }
            if (emuRelease) buildConfigField("String", "FORUM_SECRET", "\"$forumEmuSecret\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        if (bench) {
            create("bench") {
                initWith(getByName("release"))
                applicationIdSuffix = ".bench"
                matchingFallbacks += "release"
                ndk { abiFilters.clear(); abiFilters += "x86_64" }
                buildConfigField("String", "FORUM_SECRET", "\"$forumEmuSecret\"")
                isMinifyEnabled = !benchPlain
                isShrinkResources = !benchPlain
            }
        }
    }

    if (bench) sourceSets.getByName("bench").kotlin.directories += "src/debug/java/com/kivan/jarvis/tabs"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Screens drawn on the JVM (Robolectric + Roborazzi): no emulator, so light on memory.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "2g"
                // Robolectric on API 36 reaches into FileDescriptor.
                it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
            }
        }
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
    implementation(libs.androidx.profileinstaller)
    testImplementation("junit:junit:4.13.2")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
