# RadioShed

RadioShed is a private, station-centric music app for a local music library.
It targets **Android 13+**, **Windows 11+**, **Linux**, and eventually
**iOS 16+**. It is built with Kotlin Multiplatform and Compose Multiplatform.

RadioShed is meant to replace shuffle-heavy streaming apps with something
closer to personal radio: scan your music, build stations, tune playback rules,
save playlists, fetch optional metadata, and keep your library under your own
control.

> **License: proprietary.** Source code, compiled binaries, and documentation
> in this repository are not for redistribution, resale, public sharing, or
> mirroring. See `LICENSE` for full terms.

## Status

| Platform | Status |
| --- | --- |
| Android 13+ | Primary mobile target. Debug/release APK builds are supported. |
| Linux x86_64 | Desktop run, app-image directory, single-file `.AppImage`, and `.deb` are verified. |
| Windows 11 | Compose Desktop target is supported; MSI packaging should be validated on a Windows host. |
| macOS | Compose Desktop `.dmg` target is configured, but icon packaging needs a Mac lane. |
| iOS 16+ | Source set exists, but real iOS implementation is deferred until Mac access. |

For the product roadmap, feature list, and phased implementation plan, read
[`SPEC.md`](./SPEC.md). For user workflows, read [`USER_GUIDE.md`](./USER_GUIDE.md)
and [`FAQ.md`](./FAQ.md).

## Repository Layout

There is one Gradle module: `:composeApp`.

```text
RadioShed/
├── README.md
├── SPEC.md
├── USER_GUIDE.md
├── FAQ.md
├── AGENTS.md                     # Authoritative AI-agent/project guide
├── CLAUDE.md                     # Stub redirecting Claude agents to AGENTS.md
├── CONTRIBUTING.md
├── LICENSE
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   └── libs.versions.toml
└── composeApp/
    ├── build.gradle.kts
    └── src/
        ├── commonMain/           # Shared domain, data, UI, source, station logic
        ├── androidMain/          # Android app, Media3, Android Auto, widgets
        ├── desktopMain/          # Windows/Linux desktop, VLCJ, AWT dialogs
        ├── iosMain/              # iOS stubs pending Mac access
        └── commonTest/           # Shared JVM-runnable tests
```

## Prerequisites

### All Development Hosts

- JDK 17. Gradle/AGP requires JDK 17 even though some bytecode targets are
  lower.
- Network access for the first Gradle sync and dependency download.
- Git and a normal shell environment.

### Android

- Android SDK with API 35 compile/target packages.
- Android 13+ device or emulator.
- `adb`; on the Windows dev machine it lives at
  `C:\android-sdk\platform-tools\adb.exe`.

Recommended Windows environment:

```text
ANDROID_HOME      = C:\android-sdk
ANDROID_SDK_ROOT  = C:\android-sdk
JAVA_HOME         = C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot
PATH              += %ANDROID_HOME%\platform-tools
                     %ANDROID_HOME%\cmdline-tools\latest\bin
                     %ANDROID_HOME%\emulator
```

Open a fresh terminal after changing persistent environment variables.

### Desktop Playback

Desktop playback uses VLCJ and system `libvlc` 3.x.

- Windows: install VLC from VideoLAN.
- Linux: install the distro VLC package, for example `vlc` on Debian/Ubuntu,
  Fedora/RPMFusion, Arch, or CachyOS.
- macOS: install VLC.app.

Without `libvlc`, the desktop app can launch but playback fails when audio is
opened.

### Linux Packaging

Linux x86_64 packaging can produce three useful outputs:

- app-image directory from Compose/JPackage,
- true single-file `.AppImage`,
- `.deb` installer.

Additional tools:

- `.deb` packaging needs `dpkg-deb` on PATH.
- The single-file `.AppImage` task uses AppImageKit `appimagetool`.
  If `APPIMAGETOOL=/path/to/appimagetool` is set or `appimagetool` is on PATH,
  Gradle uses that. Otherwise Gradle downloads the x86_64 AppImageKit tool to
  `composeApp/build/compose/tools/`.
- Running AppImages on some Linux installs may require FUSE. If FUSE is not
  available, run with `--appimage-extract-and-run`.

## Build From Source

Run commands from the repository root.

### Android

```bash
# Build debug APK
./gradlew :composeApp:assembleDebug

# Build release APK
./gradlew :composeApp:assembleRelease

# Run shared/unit tests through the Android JVM test variant
./gradlew :composeApp:testDebugUnitTest

# Run one test class
./gradlew :composeApp:testDebugUnitTest --tests "com.visorcraft.radioshed.data.SearchDslTest"
```

Debug APK output:

```text
composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

### Desktop Development Run

```bash
./gradlew :composeApp:run
```

This compiles the desktop target and launches the Compose Desktop window.
It is the fastest loop for UI and shared logic work.

### Desktop App-Image Directory

```bash
./gradlew :composeApp:createDistributable
```

Outputs:

- Windows: `composeApp/build/compose/binaries/main/app/RadioShed/RadioShed.exe`
- Linux: `composeApp/build/compose/binaries/main/app/RadioShed/bin/RadioShed`
- macOS: `composeApp/build/compose/binaries/main/app/RadioShed.app/`

This is a runnable directory tree, not a single-file Linux AppImage.

### Linux Single-File AppImage

```bash
./gradlew :composeApp:packageSingleFileAppImage
```

Output:

```text
composeApp/build/compose/binaries/main/appimage/RadioShed-<version>-x86_64.AppImage
```

Example:

```bash
chmod +x composeApp/build/compose/binaries/main/appimage/RadioShed-1.0.0-x86_64.AppImage
composeApp/build/compose/binaries/main/appimage/RadioShed-1.0.0-x86_64.AppImage
```

### Current-OS Packages

```bash
./gradlew :composeApp:packageDistributionForCurrentOS
```

On Linux x86_64, this builds:

- app-image directory,
- single-file `.AppImage`,
- `.deb`.

Linux outputs:

```text
composeApp/build/compose/binaries/main/app/RadioShed/
composeApp/build/compose/binaries/main/appimage/RadioShed-<version>-x86_64.AppImage
composeApp/build/compose/binaries/main/deb/radioshed_<version>_amd64.deb
```

On Windows, this builds the configured Windows package. On macOS, this builds
the configured macOS package when run from a Mac with the required icon tooling.

### SQLDelight Regeneration

After editing `.sq` or `.sqm` files:

```bash
./gradlew :composeApp:generateCommonMainRadioShedDatabaseInterface --rerun-tasks
```

### iOS Framework

Requires macOS:

```bash
./gradlew :composeApp:assembleXCFramework
```

## Installation And Running

### Android Debug Install

```bash
"C:\android-sdk\platform-tools\adb.exe" install -r \
    composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

On Linux/macOS with `adb` on PATH:

```bash
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

### Linux AppImage

The AppImage is the easiest Linux artifact to test or share privately.

```bash
chmod +x composeApp/build/compose/binaries/main/appimage/RadioShed-1.0.0-x86_64.AppImage
composeApp/build/compose/binaries/main/appimage/RadioShed-1.0.0-x86_64.AppImage
```

If FUSE is not available:

```bash
composeApp/build/compose/binaries/main/appimage/RadioShed-1.0.0-x86_64.AppImage \
    --appimage-extract-and-run
```

### Linux Deb

On Debian-based systems:

```bash
sudo apt install ./composeApp/build/compose/binaries/main/deb/radioshed_1.0.0_amd64.deb
```

Or:

```bash
sudo dpkg -i composeApp/build/compose/binaries/main/deb/radioshed_1.0.0_amd64.deb
sudo apt -f install
```

The `.deb` installs the application under `/opt/radioshed/` and registers a
desktop launcher.

### Desktop Directory Build

Use this when you want the unpacked application rather than an installer:

```bash
composeApp/build/compose/binaries/main/app/RadioShed/bin/RadioShed
```

On Windows, run:

```bat
composeApp\build\compose\binaries\main\app\RadioShed\RadioShed.exe
```

## First-Run Setup

1. Install VLC/libvlc before desktop playback testing.
2. Launch RadioShed.
3. Add one or more music folders from the onboarding/library settings flow.
4. Run a scan and wait for metadata import to finish.
5. Review detected artists, albums, folders, and genres.
6. Create or edit stations to match how you listen.
7. Set network policy before enabling online enrichment if you want to limit
   background lookups.
8. Create a backup once your folders, settings, stations, and playlists look
   correct.

Keep music paths stable after scanning. If you move a library, update folders
inside RadioShed and rescan so playlists, station rules, artwork, and history
continue to resolve cleanly.

## Best-Practice Usage

- Prefer a stable top-level music folder per device or synced library. Avoid
  scanning temporary download directories.
- Keep filenames and tags reasonably clean before large imports. RadioShed can
  help organize metadata, but clean source tags reduce duplicate artists and
  albums.
- Use stations for repeat listening patterns and playlists for hand-picked
  track lists.
- Set online policy to **Wi-Fi only** or **Never** before travel or metered
  connections.
- Back up after major setup work: first scan, station edits, playlist imports,
  and settings changes.
- On desktop, leave VLC installed and updated. RadioShed uses system libvlc
  rather than bundling audio decoders.
- If playback behavior seems wrong after a large library change, rescan folders
  before editing station rules.
- For portable Linux testing, prefer the single-file `.AppImage`; for a
  machine you control long-term, prefer `.deb` on Debian-based systems.

## Development Best Practices

- Read [`AGENTS.md`](./AGENTS.md) before making code changes. It is the
  authoritative engineering and agent guide.
- Keep `CLAUDE.md` as a redirect stub only.
- Run focused tests for touched logic, then at least
  `./gradlew :composeApp:compileKotlinDesktop` for shared UI/desktop work.
- After SQL changes, run the SQLDelight regeneration task with `--rerun-tasks`.
- On Linux packaging changes, run:

```bash
./gradlew :composeApp:packageSingleFileAppImage --rerun-tasks
./gradlew :composeApp:packageDistributionForCurrentOS --rerun-tasks
```

## Troubleshooting

- **`SDK location not found`**: set `ANDROID_HOME` / `ANDROID_SDK_ROOT`, or add
  a local `local.properties` with `sdk.dir`.
- **`java` not found**: install JDK 17 and set `JAVA_HOME`.
- **`Unsupported class file major version`**: confirm Gradle is running on
  JDK 17, not an older JRE.
- **`libvlc not found` on desktop**: install VLC/libvlc 3.x for the host OS.
- **Playback opens but no audio starts**: confirm the file path still exists,
  VLC can play the same file, and the folder was rescanned after moves.
- **`packageDeb` fails with `Invalid or unsupported type: [deb]`**: install
  the package that provides `dpkg-deb`.
- **Single-file `.AppImage` packaging fails before appimagetool runs**: set
  `APPIMAGETOOL`, put `appimagetool` on PATH, or allow Gradle to download it.
- **`TargetFormat.AppImage` confusion**: Compose/JPackage's AppImage target
  creates a directory. The single downloadable file comes from
  `:composeApp:packageSingleFileAppImage`.
- **Packaged desktop app reports `java/sql/DriverManager` missing**: rebuild
  with `--rerun-tasks`; `java.sql` and `java.naming` are configured in
  `composeApp/build.gradle.kts`.
- **Android Auto appears stale**: use `adb logcat -s RadioShedMS:V` and
  `adb logcat -s RadioShedArt:V` to inspect media session and artwork output.

## App Icon Assets

The checked-in cross-platform bitmap is:

```text
composeApp/src/commonMain/composeResources/drawable/radioshed.png
```

It is 256×256 and feeds the shared welcome screen, the desktop runtime icon,
the Linux `.deb`, and the single-file `.AppImage`.

Android launcher icons live under `composeApp/src/androidMain/res/`, including
adaptive icon XML and legacy mipmap PNGs. If icon artwork changes, keep the
Android adaptive icon, Android mipmaps, and shared Compose PNG visually in sync.

## Documentation Map

- [`USER_GUIDE.md`](./USER_GUIDE.md): user workflows and feature walkthroughs.
- [`FAQ.md`](./FAQ.md): common questions and confusion points.
- [`SPEC.md`](./SPEC.md): product specification and phased roadmap.
- [`AGENTS.md`](./AGENTS.md): authoritative engineering/agent instructions.
- [`CLAUDE.md`](./CLAUDE.md): redirect stub for Claude-family agents.
