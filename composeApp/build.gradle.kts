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
            implementation(compose.materialIconsExtended)
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
