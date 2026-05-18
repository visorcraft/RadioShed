import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

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
        versionCode = 1
        versionName = "1.0"
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
            // (DMG specifically requires MAJOR > 0). The user-facing
            // BuildInfo.VERSION_NAME is still "0.1.0"; this is just
            // the installer/distribution version which the OS bumps
            // around per-update.
            packageVersion = "1.0.0"
            description = "Local-first cross-platform music app."
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
        }
    }
}
