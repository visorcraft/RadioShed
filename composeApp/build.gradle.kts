import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

// Load version.properties from the repo root. Single source of truth
// for the app version — drives AGP versionCode/versionName, Compose
// Desktop installer packageVersion, AND the generated BuildInfo
// Kotlin object below. Missing keys throw at configure-time so a
// half-edited properties file can't silently ship a stale version.
val versionProps: Properties = Properties().apply {
    val propsFile = rootProject.file("version.properties")
    require(propsFile.exists()) {
        "version.properties not found at ${propsFile.absolutePath}"
    }
    propsFile.inputStream().use { load(it) }
}
fun requiredVersionProp(key: String): String =
    versionProps.getProperty(key)
        ?: error("$key missing from version.properties")
val appVersionMajor = requiredVersionProp("versionMajor").toInt()
val appVersionMinor = requiredVersionProp("versionMinor").toInt()
val appVersionPatch = requiredVersionProp("versionPatch").toInt()
val appVersionName = "$appVersionMajor.$appVersionMinor.$appVersionPatch"
val appVersionCode = requiredVersionProp("versionCode").toInt()
val appBuildDate = requiredVersionProp("buildDate")

kotlin {
    // Silence the "expect/actual classes in Beta" warning — this is an intentional
    // pattern used throughout for cross-platform driver factories, time helpers, etc.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    // Desktop / JVM target. Source set is `desktopMain` (the JVM target
    // is named "desktop" so the corresponding source set follows that
    // convention). Bytecode targets JVM 17 to match the Gradle/AGP
    // toolchain — desktop has no constrained class-file requirement
    // like Android's VERSION_11. The `compose.desktop.application`
    // block at the bottom of this file wires the entry point and
    // distribution formats.
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            // SQLDelight's iOS driver (co.touchlab:sqliter) references
            // sqlite3_* symbols via cinterop. Static frameworks need the
            // dependency declared here so Xcode auto-links libsqlite3 when
            // linking the consumer iOS app — without this, the iosApp
            // build fails at link time with "Undefined symbols: sqlite3_*".
            linkerOpts.add("-lsqlite3")
        }
    }

    sourceSets {
        // Generated BuildInfo lives in a separate srcDir wired into
        // commonMain so the constants come from version.properties at
        // configure-time, not from a hand-edited .kt file (which
        // historically drifted from AGP versionName because the two
        // needed manual sync). The generator task -> source-dir
        // dependency wiring lives at the bottom of this file.
        commonMain {
            kotlin.srcDir(
                layout.buildDirectory.dir("generated/buildinfo/kotlin"),
            )
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            // Vector icons (PlayArrow, Pause, SkipNext, SkipPrevious, …).
            // We swapped from Unicode media glyphs (⏮ ⏸ ⏭) because their
            // vertical metrics differ across system fonts — Samsung's Noto
            // renders U+23F8 with extra descenders that get clipped at
            // typical button heights, while Roboto on Pixels renders it
            // fine. Vector icons render identically everywhere.
            //
            // Pinned via `libs.compose.materialIconsExtended` (1.7.3 in
            // gradle/libs.versions.toml). The `compose.*` accessor for
            // this artifact is deprecated upstream because the artifact
            // itself is frozen at 1.7.3 and no longer receives updates
            // from Compose Multiplatform; the explicit-coordinate route
            // gets us the working build without the deprecation noise.
            implementation(libs.compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            // Persistence
            implementation(libs.sqldelight.coroutinesExtensions)
            implementation(libs.sqldelight.primitiveAdapters)

            // DI
            implementation(libs.koin.core)
            implementation(libs.koin.compose)

            // Material 3 dynamic colour from a user-picked seed.
            // Drives Settings → Theme color so the user gets the
            // accessibility-correct Material You algorithm without
            // having to use their wallpaper as the seed source.
            implementation(libs.material.kolor)

            // Settings
            implementation(libs.multiplatformSettings.noArg)
            implementation(libs.multiplatformSettings.coroutines)

            // Serialization + coroutines
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)

            // Image loading (Coil 3 KMP) — renders album art from file://
            // URIs persisted by AndroidArtworkPersister.
            implementation(libs.coil.compose)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            // JDBC sqlite driver — gives the JVM-side unit tests a real
            // in-memory RadioShedDatabase so integration tests can
            // round-trip stations (save → reload → evaluate → enqueue)
            // against the actual SQLDelight schema rather than mocking
            // every repository. The same dep ships on the desktop
            // target; pulling it into commonTest reuses it for free
            // on the Android JVM test classpath.
            implementation(libs.sqldelight.sqliteDriver)
        }

        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)

            implementation(libs.sqldelight.androidDriver)
            // Bundled modern SQLite — see DatabaseDriverFactory.android for why.
            implementation(libs.requery.sqliteAndroid)
            implementation(libs.koin.android)

            // Audio metadata reader (Phase 3 scanner)
            implementation(libs.jaudiotagger)

            // Audio engine (Phase 4): ExoPlayer + MediaSession for
            // background playback, lock-screen, BT AVRCP metadata.
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.session)
            implementation(libs.androidx.media3.common)
        }

        iosMain.dependencies {
            implementation(libs.sqldelight.nativeDriver)
        }

        val desktopMain by getting {
            dependencies {
                // Compose desktop runtime — provides Window, application,
                // Swing-based MenuBar, file dialogs, etc.
                implementation(compose.desktop.currentOs)
                // ui-tooling: Compose dev-mode introspection (the
                // composable inspector helpers used by `@Preview` etc.).
                // Reach for it via the version-catalog entry rather than
                // the `compose.uiTooling` accessor — the accessor is
                // deprecated in Compose Multiplatform 1.10 in favour of
                // the explicit `org.jetbrains.compose.ui:ui-tooling`
                // coordinate.
                implementation(libs.compose.uiTooling)

                // SQLDelight JDBC driver for the desktop DB. Stores at
                // jdbc:sqlite:%APPDATA%/RadioShed/radioshed.db (per the
                // path computed in DatabaseDriverFactory.desktop.kt).
                implementation(libs.sqldelight.sqliteDriver)

                // Tag reader — pure-JVM library, identical to what the
                // Android scanner uses. Lifts ID3, Vorbis, M4A, FLAC,
                // APE — same coverage on desktop as on Android.
                implementation(libs.jaudiotagger)

                // VLCJ: Java bindings for libvlc. Plays virtually every
                // format (MP3/FLAC/Opus/ALAC/WMA/APE/DSD) without
                // pulling decoders into the project ourselves. Requires
                // a libvlc install on the host — `nativediscovery`
                // searches typical Windows / macOS / Linux paths and
                // surfaces a clear error when it can't find one.
                implementation(libs.vlcj)

                // Coroutines Swing dispatcher — used by AudioEngine to
                // route VLCJ media-event callbacks (which fire on
                // VLCJ's own threads) onto the Compose UI thread when
                // updating StateFlows that drive the player UI.
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }
}

android {
    namespace = "com.visorcraft.radioshed"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.visorcraft.radioshed"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Both pulled from version.properties at the top of this
        // file so AndroidManifest, the desktop installer metadata,
        // and the in-app BuildInfo object never drift apart.
        versionCode = appVersionCode
        versionName = appVersionName
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
}

sqldelight {
    databases {
        create("RadioShedDatabase") {
            packageName.set("com.visorcraft.radioshed.db")
            dialect(libs.sqldelight.dialectSqlite338)
            // verifyMigrations stays off: the existing 1.sqm /
            // 2.sqm / 6.sqm files use ALTER TABLE / CREATE TABLE
            // against tables defined in the .sq baseline, which
            // the verifier doesn't see in an isolated parse —
            // every FK reference fails with "No column found with
            // name id" because `station(id)` and `track(id)` aren't
            // visible in the migration's own view. Turning this on
            // requires re-stating the prior schema at the top of
            // each .sqm — meaningful refactor tracked in SPEC
            // Phase 18.
        }
    }
}

// Compose desktop entry point + distribution. Run with
// `./gradlew :composeApp:run` for live development; package an
// installer with `./gradlew :composeApp:packageDistributionForCurrentOS`.
compose.desktop {
    application {
        mainClass = "com.visorcraft.radioshed.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "RadioShed"
            // Compose Desktop's per-OS validators reject MAJOR=0
            // (DMG specifically requires MAJOR > 0), so when
            // versionMajor=0 we bump the installer's MAJOR to 1 to
            // keep packaging buildable during pre-release. Once
            // versionMajor>=1 (it's 1.0.0 now), pass it through
            // unchanged so the OS install metadata matches what the
            // user sees in About.
            packageVersion = if (appVersionMajor >= 1) appVersionName
                             else "1.$appVersionMinor.$appVersionPatch"
            // Description is baked into the Windows PE FileDescription
            // resource on the generated RadioShed.exe, which is what
            // Task Manager's "Name" column and the taskbar hover
            // tooltip read. We want both to say "RadioShed", not a
            // marketing tagline, so the user can find / identify the
            // process at a glance. Longer descriptive blurbs live in
            // README.md / app-store listings, not here.
            description = "RadioShed"
            copyright = "© Visorcraft. Proprietary."
            vendor = "Visorcraft"
            // jlink-bundled JRE modules. The default runtime image is
            // minimal and excludes java.sql, but our desktop SQLDelight
            // driver (JdbcSqliteDriver) calls java.sql.DriverManager
            // to register the sqlite-jdbc driver — without this, the
            // distributable launcher dies on first DB access with
            // "java/sql/DriverManager NoClassDefFoundError". java.naming
            // is a transitive requirement of java.sql.
            modules("java.sql", "java.naming")

            // Installer icons. Each packager needs its native format:
            // - Windows .msi: multi-resolution .ico (16/24/32/48/64/
            //   128/256 embedded in one file) so Explorer chrome
            //   reaches into the right slot per surface.
            // - macOS .dmg:  .icns (not yet generated — DMG packaging
            //   only runs on macOS hosts which we don't have today;
            //   wire this when a Mac CI lane lands).
            // - Linux .deb:  the largest PNG we ship (512×512); dpkg
            //   installs it under hicolor/<size>/apps and the DE
            //   downscales for the taskbar / window list.
            //
            // All three files are produced by `icon/build_icons.ps1`
            // (or the equivalent bash variant on Linux). Re-run that
            // script after editing icon/radioshed.svg to refresh.
            windows {
                iconFile.set(
                    project.file(
                        "src/desktopMain/resources/icon/radioshed.ico",
                    ),
                )
            }
            linux {
                iconFile.set(
                    project.file(
                        "src/desktopMain/resources/icon/radioshed-512.png",
                    ),
                )
            }
            // macOS .icns generation requires the `iconutil` CLI which
            // ships only on macOS, so it isn't part of build_icons.ps1.
            // Keep the platform-block here so a future macOS lane can
            // fill in .icns without re-shaping the nativeDistributions
            // section.
            // macOS { iconFile.set(project.file("src/desktopMain/resources/icon/radioshed.icns")) }
        }
    }
}

// ---- Generated BuildInfo.kt ----
//
// Emits com.visorcraft.radioshed.BuildInfo into a generated source
// directory that's wired into commonMain (see sourceSets block
// above). Inputs are the parsed version.properties values; output
// is a tiny Kotlin file with `const val` properties.
//
// Inputs declared via @Input properties so Gradle caches the task
// and only re-runs when the version values change — editing
// version.properties is the only thing that should invalidate it.
// Run automatically as a dependency of every commonMain compile
// task; also exposed for manual invocation via
// `./gradlew :composeApp:generateBuildInfo`.
abstract class GenerateBuildInfoTask : DefaultTask() {
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val versionCode: Property<Int>
    @get:Input abstract val buildDate: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val pkgDir = outputDir.get().asFile
            .resolve("com/visorcraft/radioshed")
        pkgDir.mkdirs()
        val file = pkgDir.resolve("BuildInfo.kt")
        file.writeText(
            """
            // GENERATED by :composeApp:generateBuildInfo — do NOT edit.
            // Edit version.properties at the repo root and re-run the
            // build (or just `./gradlew :composeApp:generateBuildInfo`).
            package com.visorcraft.radioshed

            /**
             * Build / version constants. All three fields come from
             * version.properties via the generateBuildInfo Gradle task,
             * so AndroidManifest, Compose Desktop installer metadata,
             * and the in-app About screen never drift apart.
             */
            object BuildInfo {
                const val VERSION_NAME: String = "${versionName.get()}"
                const val VERSION_CODE: Int = ${versionCode.get()}
                const val BUILD_DATE: String = "${buildDate.get()}"
            }
            """.trimIndent() + "\n",
        )
    }
}

val generateBuildInfo = tasks.register<GenerateBuildInfoTask>("generateBuildInfo") {
    group = "build"
    description = "Generate BuildInfo.kt from version.properties."
    versionName.set(appVersionName)
    versionCode.set(appVersionCode)
    buildDate.set(appBuildDate)
    outputDir.set(layout.buildDirectory.dir("generated/buildinfo/kotlin"))
}

// Wire the generator as an upstream dependency of every Kotlin
// compile task. matching{} catches commonMain + every target's
// compile task (compileKotlinDesktop, compileDebugKotlinAndroid,
// etc.) without having to enumerate them.
tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }
    .configureEach { dependsOn(generateBuildInfo) }
// Same for the SQLDelight schema generator's downstream tasks and
// the KSP-style metadata tasks Compose Multiplatform creates —
// they all expect commonMain sources to be present when they run.
tasks.matching { it.name == "kspCommonMainKotlinMetadata" }
    .configureEach { dependsOn(generateBuildInfo) }
