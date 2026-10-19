plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.protobuf")
}

android {
    namespace = "com.example.ytclient"
    // Bumped to 36 because io.github.dokar3:quickjs-kt:1.0.5 declares
    // android-36 as its minimum compileSdk in its AAR metadata.
    compileSdk = 36

    defaultConfig {
        // The app's public identity on devices and stores. Reverse-DNS of the GitHub account that
        // publishes it (github.com/vidiapp-dev), so the namespace is one only that account
        // controls; the account's hyphen becomes an underscore because app IDs can't contain one.
        //
        // Deliberately decoupled from `namespace` above, which stays com.example.ytclient: that only
        // names the generated R class and is where the Kotlin sources live, so changing it would
        // mean moving every file for no user-visible gain. Changing THIS one, on the other hand, is
        // a new app as far as Android is concerned — an install of the old com.example.ytclient
        // build is left untouched alongside it, with its local data, rather than upgraded. Which is
        // also why this must not change again once released: every user would be stranded the same
        // way.
        applicationId = "io.github.vidiapp_dev.vidi"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }

        // Day-to-day / performance-testing build: release behaviour, debug signing.
        //
        // Exists because a DEBUGGABLE build cannot be compiled ahead of time on Android 14+ — and
        // not as a matter of tuning: ArtService rewrites the compiler filter for a debuggable
        // package before dex2oat even runs. Measured 2026-10-14 on a physical Pixel (GrapheneOS):
        // `cmd package compile -m speed -f` on the debug build produced
        // `dex2oat64 … --compiler-filter=verify --compilation-reason=cmdline` and
        // `ArtService: … actualCompilerFilter=verify, status=PERFORMED`. So every debug install
        // runs its own code — kotlinx.serialization's JSON parser, Compose — uncompiled, and the
        // home feed's cold-start warm (~13 MB of Innertube JSON) cost 117+ CPU-seconds and 34 s of
        // wall time with the network, YouTube and the ranker all ruled out as the cause. See
        // RUNTIME_PERF_PLAN.md for the full investigation.
        //
        // initWith(release): non-debuggable, so ART treats it like any store app (JIT while it
        // runs, profile-guided background compilation when idle). Signed with the DEBUG key so it
        // installs over an existing debug build without losing local data (history, subs,
        // playlists live in app storage, which Android only preserves across an update signed
        // with the same key). isMinifyEnabled stays false, inherited from release: R8 would also
        // help, but this app leans on reflection-heavy libraries (kotlinx.serialization, protobuf
        // lite, QuickJS bindings, Media3), and turning shrinking on is its own project with its own
        // failure modes — this build type is meant to change exactly one variable.
        //
        // Trade-off: not debuggable means no `run-as`, so the per-thread CPU profiling used in that
        // investigation won't work against it. Logcat — including every timing line — is
        // unaffected. Build with `gradlew installProfile`, or pick the `profile` variant in
        // Android Studio's Build Variants panel.
        create("profile") {
            initWith(getByName("release"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Compose BOM — pins all compose versions together
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Compose Material (M2) — only for the pull-to-refresh primitives (pullRefresh +
    // PullRefreshIndicator); material3's PullToRefreshBox isn't in the pinned BOM's material3 1.2.x.
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material:material-icons-core")
    // Extended icon set (Subscriptions, History, PlaylistPlay/Add, …) used by the library tabs.
    // R8 strips the unused icons in release builds.
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    // ExoPlayer (Media3). 1.10.1 is required by the native SABR chunk-framework
    // player (SabrMediaSource/SabrChunkSource use the 1.10 ChunkSampleStream
    // ctor + LoadingInfo continueLoading + SubtitleParser.Factory APIs).
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
    // OkHttp-backed data source — googlevideo accepts OkHttp's requests (206)
    // where the built-in DefaultHttpDataSource gets 403. Routes ExoPlayer's
    // segment fetches through the same OkHttp stack we proved works.
    implementation("androidx.media3:media3-datasource-okhttp:1.10.1")
    // MediaSession: lockscreen / notification / PiP media controls. Wraps the
    // Composable-owned ExoPlayer in an in-process MediaSession hosted by a thin
    // MediaSessionService (the system shows the MediaStyle notification).
    implementation("androidx.media3:media3-session:1.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // HTTP client — bridged from JS fetch() in the runtime
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.6.0")

    // DataStore — Preferences-backed key-value persistence for the YT runtime
    // (visitorData, poToken, expiry).  Used by core/storage/SessionStore.kt.
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // androidx.webkit — needed for WebSettingsCompat.setSafeBrowsingEnabled
    // and WebViewFeature feature detection in core/webpo/WebPoTokenProvider.kt.
    implementation("androidx.webkit:webkit:1.11.0")

    // QuickJS — embedded JavaScript runtime for the YouTube protocol layer.
    // dokar3/quickjs-kt is a single Kotlin-Multiplatform artifact that supports
    // Android, JVM and Native targets through the same coordinate.
    // See: https://github.com/dokar3/quickjs-kt
    implementation("io.github.dokar3:quickjs-kt:1.0.5")

    // Protobuf (lite) — runtime for the generated SABR/UMP message classes
    // (protos vendored from LuanRT/googlevideo under src/main/proto). Used by the
    // native Kotlin SABR streaming client.
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")

    // SABR port (ported from PipePipe, GPLv3) dependencies:
    //  - jsr305 supplies javax.annotation.Nonnull/Nullable used throughout the port.
    //  - nanojson is the JSON parser the ported YoutubeSabrFormat uses.
    //  - brotli dec supplies org.brotli.dec.BrotliInputStream used by
    //    SabrMediaSegmentCollector to decompress brotli-encoded media segments.
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("com.grack:nanojson:1.9")
    implementation("org.brotli:dec:0.1.2")

    // JVM unit tests (src/test) — fixture-driven parser tests for the native Innertube client
    // (Option A, RUNTIME_PERF_PLAN.md). Run with `gradlew testDebugUnitTest`.
    testImplementation("junit:junit:4.13.2")
}

// ─────────────────────────────────────────────────────────────────────────────
// Protobuf codegen — compiles src/main/proto/**/*.proto (YouTube SABR/UMP wire
// format) into Java-lite message classes consumed by the SABR client.
// ─────────────────────────────────────────────────────────────────────────────
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// JS runtime build pipeline
//
// `npm run build` in ../js-runtime produces app/src/main/assets/youtube-runtime.js.
// We register the bundle as a task output and run the bundler before assets are
// packaged, so hitting Run in Android Studio always ships the latest bundle.
// ─────────────────────────────────────────────────────────────────────────────
val jsRuntimeDir = file("${rootDir}/js-runtime")
val jsRuntimeBundle = file("${projectDir}/src/main/assets/youtube-runtime.js")
val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val npmExecutable = if (isWindows) "npm.cmd" else "npm"

val npmInstall by tasks.registering(Exec::class) {
    group = "js-runtime"
    description = "Install JS runtime dependencies (runs only when package.json changes)."
    workingDir = jsRuntimeDir
    commandLine(npmExecutable, "install", "--no-audit", "--no-fund")
    // Up-to-date tracking: skip if the declared dependencies haven't changed since node_modules was
    // populated. package-lock.json matters as much as package.json — it is the file that actually
    // changes when a dependency is bumped within its existing semver range.
    inputs.file(File(jsRuntimeDir, "package.json"))
    inputs.file(File(jsRuntimeDir, "package-lock.json"))
    outputs.dir(File(jsRuntimeDir, "node_modules"))
}

val buildJsRuntime by tasks.registering(Exec::class) {
    group = "js-runtime"
    description = "Bundle js-runtime/src into app/src/main/assets/youtube-runtime.js via esbuild."
    dependsOn(npmInstall)
    workingDir = jsRuntimeDir
    commandLine(npmExecutable, "run", "build")
    // Re-runs when sources, the bundler config, or the resolved dependency set change.
    inputs.dir(File(jsRuntimeDir, "src"))
    inputs.file(File(jsRuntimeDir, "esbuild.config.mjs"))
    inputs.file(File(jsRuntimeDir, "package.json"))
    // package-lock.json is NOT redundant with package.json here, and leaving it out was a real
    // staleness bug: package.json pins ranges ("youtubei.js": "^17.0.1"), so an `npm install` that
    // resolves a NEW youtubei.js leaves package.json byte-identical. Without this line Gradle then
    // considers the bundle UP-TO-DATE and ships one built against the OLD library — a very
    // unpleasant thing to debug given how much behaviour lives inside youtubei.js (player
    // extraction, the UA pool, the session-cache default that quietly disables forceFresh).
    inputs.file(File(jsRuntimeDir, "package-lock.json"))
    outputs.file(jsRuntimeBundle)
    // Make sure the destination directory exists before esbuild writes to it.
    doFirst { jsRuntimeBundle.parentFile.mkdirs() }
}

// Hook into every Android variant so debug/release Run/Build both rebuild the bundle
// before the asset merge step.
androidComponents.onVariants { variant ->
    val variantName = variant.name.replaceFirstChar { it.uppercase() }
    afterEvaluate {
        tasks.findByName("merge${variantName}Assets")?.dependsOn(buildJsRuntime)
        tasks.findByName("package${variantName}Resources")?.dependsOn(buildJsRuntime)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Licence notices shipped inside the APK
//
// LICENSE (GPL-3.0) and THIRD_PARTY_NOTICES.md live at the repo root, where GitHub and readers look
// for them — but the MIT/ISC/BSD/Apache licences of the bundled components require their notices to
// accompany the distributed BINARY too, not just the source. This copies both files into a
// generated assets directory at build time (so there's one copy to maintain, not two that drift);
// they land at assets/LICENSE and assets/THIRD_PARTY_NOTICES.md in every APK.
//
// Registered through the variant API's addGeneratedSourceDirectory rather than a plain
// sourceSets srcDir: that makes AGP wire the task dependency into EVERY task that reads assets
// (merge, lint, ...), where a bare srcDir would leave Gradle's implicit-dependency validation to
// fail the first build that runs lint.
// ─────────────────────────────────────────────────────────────────────────────
abstract class CopyLicenseNotices : DefaultTask() {
    @get:InputFiles
    abstract val notices: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        notices.files.forEach { it.copyTo(out.resolve(it.name), overwrite = true) }
    }
}

val copyLicenseNotices = tasks.register<CopyLicenseNotices>("copyLicenseNotices") {
    group = "build"
    description = "Copy LICENSE and THIRD_PARTY_NOTICES.md into the APK's assets."
    notices.from(rootProject.file("LICENSE"), rootProject.file("THIRD_PARTY_NOTICES.md"))
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(copyLicenseNotices, CopyLicenseNotices::outputDir)
}
