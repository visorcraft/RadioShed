# RadioShed

Cross-platform music application — **Android 13+**, **Windows 11+**, and
**iOS 16+** (deferred — needs Mac access) — built in a single repository
using **Kotlin Multiplatform** + **Compose Multiplatform**. Replaces
SiriusXM / Spotify / YouTube Music with a station-centric experience
over the user's own local music library, with optional online
enrichment.

> **License: proprietary.** Source code, compiled binaries, and
> documentation in this repository are not for redistribution, resale,
> public sharing, or mirroring. See `LICENSE` for full terms.
>
> **Specification:** see [`SPEC.md`](./SPEC.md) for the full product
> spec, feature catalog, and phased build plan with checkbox tasks.
>
> **User-facing docs:** [`USER_GUIDE.md`](./USER_GUIDE.md) for
> end-user feature walkthroughs, [`FAQ.md`](./FAQ.md) for common
> confusion points.

## Repository layout

There is **one Gradle module** — `:composeApp` — with three platform
source sets. iOS source lives in-tree but is currently stubbed
(needs Mac access for build + test); Android and Windows desktop
both build and run on the dev machine.

```
RadioShed/
├── README.md
├── SPEC.md                       # Product spec + phased build plan (canonical)
├── USER_GUIDE.md                 # End-user feature walkthroughs
├── FAQ.md                        # Common questions / confusion points
├── CLAUDE.md                     # AI/agent project conventions
├── AGENTS.md                     # Coding-style conventions for AI tools
├── CONTRIBUTING.md               # Branch / commit / PR conventions
├── LICENSE                       # Proprietary
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   └── libs.versions.toml        # Dependency versions
└── composeApp/
    ├── build.gradle.kts
    └── src/
        ├── commonMain/           # Pure-Kotlin domain, data, UI, audio orchestration
        ├── androidMain/          # Android 13+ — Media3, jaudiotagger, AA, widgets
        ├── desktopMain/          # Windows 11 — VLCJ audio, Compose Desktop, AWT dialogs
        ├── iosMain/              # iOS 16+ stub — AVAudioEngine actuals (deferred)
        └── commonTest/           # JVM-runnable Kotlin tests (executed via Android variant)
```

## Tech stack

| Layer                   | Choice                                                      |
| ----------------------- | ----------------------------------------------------------- |
| Shared business logic   | Kotlin Multiplatform (KMP)                                  |
| Shared UI               | Compose Multiplatform 1.10.x                                |
| Database                | SQLDelight 2.1 (Android: requery sqlite-android; Desktop: JdbcSqliteDriver) |
| HTTP                    | JDK `HttpURLConnection` + `kotlinx.serialization`           |
| DI                      | Koin (`platformModule` expect val + `sharedModule`)         |
| Audio (Android)         | Media3 / ExoPlayer + `MediaLibraryService`                  |
| Audio (Windows)         | VLCJ 4.x (libvlc bindings)                                  |
| Audio (iOS, deferred)   | AVAudioEngine                                               |
| Tag I/O                 | jaudiotagger (Android + Desktop; iOS deferred)              |
| In-car (Android)        | Android Auto via `MediaLibraryService` browse + voice search |
| In-car (iOS, deferred)  | CarPlay (audio-template app)                                |
| Min SDK                 | Android 13 (API 33) / Windows 11 / iOS 16                   |
| Target SDK              | Android 14 (API 35)                                         |
| Build system            | Gradle (Kotlin DSL), JDK 17                                 |

## Toolchain on the dev machine (Windows 11)

The Android side, the Windows desktop side, and all shared Kotlin
code build fully on Windows. iOS builds require macOS.

| Component             | Version / Location                                                       |
| --------------------- | ------------------------------------------------------------------------ |
| OS                    | Windows 11                                                               |
| JDK                   | Microsoft OpenJDK 17 — `C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot` |
| Android SDK           | `C:\android-sdk`                                                         |
| `adb`                 | `C:\android-sdk\platform-tools\adb.exe` (NOT on PATH)                    |
| VLC (for desktop run) | Standard install from videolan.org — libvlc auto-discovered              |

Persistent user-level environment variables:

```
ANDROID_HOME      = C:\android-sdk
ANDROID_SDK_ROOT  = C:\android-sdk
JAVA_HOME         = C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot
PATH              += %ANDROID_HOME%\platform-tools
                     %ANDROID_HOME%\cmdline-tools\latest\bin
                     %ANDROID_HOME%\emulator
```

Variables set via `setx` / the user registry take effect only in **new**
shells. If a current shell does not see them, open a fresh terminal.

### iOS builds (require macOS)

iOS code can be authored from Windows, but compilation, simulator
runs, and App Store submission all require macOS. The iOS source
set under `composeApp/src/iosMain/` is currently stubbed — most
platform peers return `Null*` impls. Real iOS implementation is
deferred to a Mac-equipped session.

## Common build commands

```bash
# Build the Android debug APK
./gradlew :composeApp:assembleDebug
# Output: composeApp/build/outputs/apk/debug/composeApp-debug.apk

# Install latest debug build on the connected device
"C:\android-sdk\platform-tools\adb.exe" install -r \
    composeApp/build/outputs/apk/debug/composeApp-debug.apk

# Run JVM-side unit tests (commonTest sources execute via the Android variant)
./gradlew :composeApp:testDebugUnitTest

# Single test class
./gradlew :composeApp:testDebugUnitTest --tests "com.visorcraft.radioshed.data.SearchDslTest"

# Build the Windows desktop distributable
./gradlew :composeApp:createDistributable
# Output: composeApp/build/compose/binaries/main/app/RadioShed/RadioShed.exe

# Run the desktop app from Gradle (live reload during development)
./gradlew :composeApp:run

# Force SQLDelight regen after editing .sq / .sqm files
./gradlew :composeApp:generateCommonMainRadioShedDatabaseInterface --rerun-tasks

# iOS framework (macOS only)
./gradlew :composeApp:assembleXCFramework
```

## Quick smoke-test path

After cloning + initial Gradle sync:

```bash
# Android
./gradlew :composeApp:testDebugUnitTest :composeApp:assembleDebug
"C:\android-sdk\platform-tools\adb.exe" install -r \
    composeApp/build/outputs/apk/debug/composeApp-debug.apk

# Desktop
./gradlew :composeApp:createDistributable
start "" "composeApp\build\compose\binaries\main\app\RadioShed\RadioShed.exe"
```

If both succeed the toolchain is healthy. If `RadioShed.exe`
launches with a `java/sql/DriverManager` error, the bundled JRE is
missing the `java.sql` module — see `composeApp/build.gradle.kts`
`nativeDistributions { modules(...) }` (already configured; the
error indicates a stale build cache, run `--rerun-tasks`).

## Troubleshooting

- **`SDK location not found`** — ensure `ANDROID_HOME` is set in the
  current shell, or that the project's `local.properties` pins
  `sdk.dir`.
- **`Unsupported class file major version`** — confirm `JAVA_HOME`
  points at JDK 17. AGP requires JDK 17 (the bytecode target is
  VERSION_11; the toolchain itself is 17).
- **`libvlc not found` on desktop** — install the standard VLC
  media player from videolan.org. `NativeDiscovery` walks the
  usual install paths; the app falls back to a clear error rather
  than crashing if libvlc isn't present.
- **`java/sql/DriverManager` `NoClassDefFoundError` on the
  packaged desktop EXE** — bundled JRE is missing `java.sql`.
  Should be fixed by `modules("java.sql", "java.naming")` in
  `nativeDistributions` already; rebuild with `--rerun-tasks` if
  it surfaces.
- **AA "doesn't seem to be working"** — `adb logcat -s
  RadioShedMS:V` will show the session-callback handshake. Most
  past issues were `setCustomLayout` thrown exceptions or
  missing `DEFAULT_SESSION_AND_LIBRARY_COMMANDS` (now both
  guarded against).
- **AA cover art shows blank** — `adb logcat -s RadioShedArt:V`
  shows whether the FileProvider URI was generated and the bytes
  embed succeeded per track switch.

## Where to go next

- Read **`SPEC.md`** — the product spec, including responsive-design
  requirements (§1.1), platform parity matrix (§3), and the phased
  build plan with checkbox tasks (§20). The latest batch entries
  describe the most recently shipped work.
- For end-user docs, see **`USER_GUIDE.md`** and **`FAQ.md`**.
- For agent / AI-tool conventions, see **`CLAUDE.md`** (project
  context for Claude Code) and **`AGENTS.md`** (coding-style
  conventions).
