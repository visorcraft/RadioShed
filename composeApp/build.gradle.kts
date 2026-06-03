import org.gradle.api.file.FileSystemOperations
import org.gradle.process.ExecOperations
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.util.Properties
import javax.inject.Inject

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
val appImageToolUrl =
    "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage"
val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()
val isLinuxX64Host = osName.contains("linux") && osArch in setOf("amd64", "x86_64")

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
            targetFormats(
                TargetFormat.AppImage,
                TargetFormat.Msi,
                TargetFormat.Dmg,
                TargetFormat.Deb,
            )
            // Side-car resources copied beside the desktop binary by
            // jpackage (into the app-image / .deb / .AppImage). The
            // generateThirdPartyLicenses task writes the aggregate
            // THIRD_PARTY_LICENSES under `common/` here so each desktop
            // release carries the license file next to the launcher
            // (LICENSE §5.1). NOTE: Compose only picks up files placed in
            // a platform subdir of this root (`common/` = all OSes,
            // `linux/`, `linux-x64/`, …); files at the root are ignored
            // (prepareAppResources reports NO-SOURCE).
            appResourcesRootDir.set(project.layout.projectDirectory.dir("desktopAppResources"))
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
            // - Linux .deb:  the shared Compose PNG; dpkg installs it
            //   under hicolor/<size>/apps and the DE downscales for
            //   the taskbar / window list.
            //
            // Keep the Linux package icon pointed at the common
            // Compose resource so `:composeApp:run` and the `.deb`
            // launcher use the same checked-in bitmap.
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
                        "src/commonMain/composeResources/drawable/radioshed.png",
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

val appImageToolFile = layout.buildDirectory.file("compose/tools/appimagetool-x86_64.AppImage")

abstract class DownloadAppImageToolTask : DefaultTask() {
    @get:Input abstract val downloadUrl: Property<String>
    @get:Input abstract val appImageToolEnv: Property<String>
    @get:Input abstract val pathEnv: Property<String>
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun download() {
        if (findAppImageTool(appImageToolEnv.get(), pathEnv.get()) != null) return

        val toolFile = outputFile.get().asFile
        if (toolFile.canExecute()) return

        toolFile.parentFile.mkdirs()
        URI(downloadUrl.get()).toURL().openStream().use { input ->
            toolFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        toolFile.setExecutable(true)
    }

    private fun findAppImageTool(configuredPath: String, path: String): File? {
        configuredPath
            .takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.canExecute() }
            ?.let { return it }

        return path
            .split(File.pathSeparator)
            .asSequence()
            .map { File(it, "appimagetool") }
            .firstOrNull { it.canExecute() }
    }
}

abstract class PackageSingleFileAppImageTask @Inject constructor(
    private val fileSystemOperations: FileSystemOperations,
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val downloadUrl: Property<String>
    @get:Input abstract val hostOsName: Property<String>
    @get:Input abstract val hostOsArch: Property<String>
    @get:Input abstract val appImageToolEnv: Property<String>
    @get:Input abstract val pathEnv: Property<String>
    @get:Internal
    abstract val downloadedAppImageTool: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty
    @get:Internal abstract val appDir: DirectoryProperty
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun packageAppImage() {
        require(isLinuxX64Host()) {
            "packageSingleFileAppImage currently supports Linux x86_64 hosts only."
        }

        val source = sourceDir.get().asFile
        val target = appDir.get().asFile
        val output = outputFile.get().asFile
        val tool = findAppImageTool(appImageToolEnv.get(), pathEnv.get())
            ?: downloadedAppImageTool.get().asFile.takeIf { it.canExecute() }
            ?: error(
                "appimagetool is unavailable; set APPIMAGETOOL, put appimagetool on PATH, " +
                    "or allow Gradle to download ${downloadUrl.get()}",
            )

        fileSystemOperations.delete {
            delete(target)
        }
        fileSystemOperations.copy {
            from(source)
            into(target)
        }

        target.resolve("AppRun").apply {
            writeText(
                """
                #!/bin/sh
                HERE="$(dirname "$(readlink -f "${'$'}0")")"
                exec "${'$'}HERE/bin/RadioShed" "${'$'}@"
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
        target.resolve("radioshed.desktop").writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=RadioShed
            Exec=RadioShed
            Icon=radioshed
            Categories=AudioVideo;Audio;Player;
            Terminal=false
            """.trimIndent() + "\n",
        )
        fileSystemOperations.copy {
            from(target.resolve("lib/RadioShed.png"))
            into(target)
            rename { "radioshed.png" }
        }

        output.parentFile.mkdirs()
        output.delete()
        execOperations.exec {
            executable = tool.absolutePath
            args("-n", target.absolutePath, output.absolutePath)
            environment("ARCH", "x86_64")
            environment("VERSION", versionName.get())
            environment("APPIMAGE_EXTRACT_AND_RUN", "1")
        }.assertNormalExitValue()
    }

    private fun isLinuxX64Host(): Boolean =
        hostOsName.get().contains("linux") && hostOsArch.get() in setOf("amd64", "x86_64")

    private fun findAppImageTool(configuredPath: String, path: String): File? {
        configuredPath
            .takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.canExecute() }
            ?.let { return it }

        return path
            .split(File.pathSeparator)
            .asSequence()
            .map { File(it, "appimagetool") }
            .firstOrNull { it.canExecute() }
    }
}

val downloadAppImageTool = tasks.register<DownloadAppImageToolTask>("downloadAppImageTool") {
    group = "distribution"
    description = "Download appimagetool for building a single-file Linux AppImage."
    downloadUrl.set(appImageToolUrl)
    appImageToolEnv.set(providers.environmentVariable("APPIMAGETOOL").orElse(""))
    pathEnv.set(providers.environmentVariable("PATH").orElse(""))
    outputFile.set(appImageToolFile)
}

val packageSingleFileAppImage = tasks.register<PackageSingleFileAppImageTask>("packageSingleFileAppImage") {
    group = "distribution"
    description = "Package the desktop app-image directory as a single-file Linux .AppImage."
    dependsOn("packageAppImage", downloadAppImageTool)

    versionName.set(appVersionName)
    downloadUrl.set(appImageToolUrl)
    hostOsName.set(osName)
    hostOsArch.set(osArch)
    appImageToolEnv.set(providers.environmentVariable("APPIMAGETOOL").orElse(""))
    pathEnv.set(providers.environmentVariable("PATH").orElse(""))
    downloadedAppImageTool.set(appImageToolFile)
    sourceDir.set(layout.buildDirectory.dir("compose/binaries/main/app/RadioShed"))
    appDir.set(layout.buildDirectory.dir("compose/tmp/singleFileAppImage/RadioShed.AppDir"))
    outputFile.set(
        layout.buildDirectory.file("compose/binaries/main/appimage/RadioShed-$appVersionName-x86_64.AppImage"),
    )
}

if (isLinuxX64Host) {
    tasks.matching { it.name == "packageDistributionForCurrentOS" }.configureEach {
        dependsOn(packageSingleFileAppImage)
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

// ---- Generated THIRD_PARTY_LICENSES ----
//
// Renders the aggregate THIRD_PARTY_LICENSES from the SAME
// LicenseCatalog.kt the in-app Licenses screen reads, so the file and
// the screen can never drift. The renderer is a desktop-target JVM
// `main` (LicenseFileRenderer.kt) run via JavaExec; it reuses the
// compiled commonMain catalog directly off the desktop runtime
// classpath — no second copy of the data.
//
// Outputs:
//   - repo-root THIRD_PARTY_LICENSES (release file accompanying each build),
//   - composeResources/files/THIRD_PARTY_LICENSES.txt (ships inside every
//     APK / jar / iOS framework via the Compose resources mechanism),
//   - desktopAppResources/THIRD_PARTY_LICENSES (copied beside the desktop
//     binary by jpackage via appResourcesRootDir).
//
// Deliberately NOT wired upstream of compile* (unlike generateBuildInfo):
// it depends on the desktop compile, so wiring it before compile would
// cycle. The bundled .txt is checked in; package tasks regenerate it.
val licensesResDir = layout.projectDirectory.dir(
    "src/commonMain/composeResources/files/licenses",
)
val thirdPartyLicensesRoot = rootProject.layout.projectDirectory.file("THIRD_PARTY_LICENSES")
val thirdPartyLicensesBundled = layout.projectDirectory.file(
    "src/commonMain/composeResources/files/THIRD_PARTY_LICENSES.txt",
)
// Under `common/` so Compose's appResourcesRootDir actually picks it up
// (root-level files are ignored — see the nativeDistributions note above).
val thirdPartyLicensesDesktop = layout.projectDirectory.file(
    "desktopAppResources/common/THIRD_PARTY_LICENSES",
)

val generateThirdPartyLicenses = tasks.register<JavaExec>("generateThirdPartyLicenses") {
    group = "build"
    description = "Render THIRD_PARTY_LICENSES from LicenseCatalog.kt (single source of truth)."
    val desktopMainCompilation = kotlin.targets.getByName("desktop")
        .compilations.getByName("main")
    dependsOn(desktopMainCompilation.compileTaskProvider)
    classpath = files(
        desktopMainCompilation.output.allOutputs,
        configurations.getByName("desktopRuntimeClasspath"),
    )
    mainClass.set("com.visorcraft.radioshed.licenses.LicenseFileRendererKt")
    // The renderer writes the same body to every output path (arg[1..]).
    // Passing the desktop side-car as a 4th arg keeps the copy inside the
    // JVM main, which is configuration-cache-safe (no `project`/`copy`
    // reference at execution time).
    args(
        licensesResDir.asFile.absolutePath,
        thirdPartyLicensesRoot.asFile.absolutePath,
        thirdPartyLicensesBundled.asFile.absolutePath,
        thirdPartyLicensesDesktop.asFile.absolutePath,
    )
    inputs.dir(licensesResDir)
    inputs.file("src/commonMain/kotlin/com/visorcraft/radioshed/licenses/LicenseCatalog.kt")
    outputs.file(thirdPartyLicensesRoot)
    outputs.file(thirdPartyLicensesBundled)
    outputs.file(thirdPartyLicensesDesktop)
}

// Make packaging depend on the generator so the side-car can't go stale
// at release time.
//
// The real reader of appResourcesRootDir (= desktopAppResources/) is the
// Compose plugin's `prepareAppResources` Sync task: it copies that directory
// into the build dir, and jpackage (createDistributable / package* / their
// release + run variants) reads the *copied* result via an @Internal
// appResourcesDir property. So the ordering edge that actually matters is
// generateThirdPartyLicenses -> prepareAppResources; without it Gradle is free
// to run the Sync before the generator overwrites the side-car on disk,
// copying a stale file into the app-image. Wiring the generator onto
// prepareAppResources guarantees regeneration before the copy; every
// createDistributable / package* / packageSingleFileAppImage task picks it up
// transitively (they all dependOn prepareAppResources).
tasks.matching { it.name == "prepareAppResources" }
    .configureEach { dependsOn(generateThirdPartyLicenses) }

// Keep the explicit package*-task edges too. They are redundant once
// prepareAppResources is covered, but harmless, and they keep the generator
// wired even if a future Compose version renames the Sync task.
tasks.matching {
    it.name in setOf(
        "packageDistributionForCurrentOS",
        "packageReleaseDistributionForCurrentOS",
        "packageDeb", "packageReleaseDeb",
        "packageAppImage", "packageReleaseAppImage",
        "packageMsi", "packageReleaseMsi",
        "packageDmg", "packageReleaseDmg",
    )
}.configureEach { dependsOn(generateThirdPartyLicenses) }
