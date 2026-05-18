# RadioShed

Cross-platform music application — **Android 13+**, **Windows 11+**,
**Linux** (should-work-but-untested), and **iOS 16+** (deferred —
needs Mac access) — built in a single repository using **Kotlin
Multiplatform** + **Compose Multiplatform**. Replaces SiriusXM /
Spotify / YouTube Music with a station-centric experience over the
user's own local music library, with optional online enrichment.

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
both build and run on the dev machine. The `desktopMain` source
set was built to be cross-platform JVM (Compose Desktop +
VLCJ + JDBC SQLite) — Linux is a should-work-but-untested target;
the build script already configures `TargetFormat.Deb` so
`packageDistributionForCurrentOS` from a Linux box would produce
a .deb without code changes.

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
| Audio (Desktop)         | VLCJ 4.x (libvlc bindings — Windows + Linux + macOS)        |
| Audio (iOS, deferred)   | AVAudioEngine                                               |
| Tag I/O                 | jaudiotagger (Android + Desktop; iOS deferred)              |
| In-car (Android)        | Android Auto via `MediaLibraryService` browse + voice search |
| In-car (iOS, deferred)  | CarPlay (audio-template app)                                |
| Min SDK                 | Android 13 (API 33) / Windows 11 / Linux (any modern DE) / iOS 16 |
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

# Build the desktop distributable for the current OS
./gradlew :composeApp:createDistributable
# Windows output: composeApp/build/compose/binaries/main/app/RadioShed/RadioShed.exe
# Linux output:   composeApp/build/compose/binaries/main/app/RadioShed/bin/RadioShed
# macOS output:   composeApp/build/compose/binaries/main/app/RadioShed.app/

# Build a platform-native installer (.msi / .deb / .dmg) from this host
./gradlew :composeApp:packageDistributionForCurrentOS

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
- **`libvlc not found` on desktop** — install VLC. On Windows
  grab the installer from videolan.org; on Linux use the
  distro's package (`apt install vlc` / `dnf install vlc` —
  Fedora needs RPMFusion first). `NativeDiscovery` walks the
  usual install paths and the app falls back to a clear error
  rather than crashing if libvlc isn't present.
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

## App icon pipeline

The app icon (a red shed with a roof-mounted antenna and a single
circular speaker for the door) lives as a single SVG master at
`icon/radioshed.svg`. Every platform-specific PNG / ICO that ships
with the app is rasterized from that source by a PowerShell driver
at `icon/build_icons.ps1` — both files are checked in. Don't edit
the rasterized outputs by hand; re-run the script.

### How the icon was generated

1. **Master SVG** — `icon/radioshed.svg` (512×512 viewBox). Plain
   SVG with `<rect>`, `<polygon>`, `<circle>`, and stroked `<path>`
   primitives. No external fonts or rasters referenced; the icon
   is fully self-contained vector geometry. Z-order: antenna →
   radio waves → roof → shed body → shadows → speaker, so the
   speaker stays on top of the shed and the roof sits above the
   wave arcs.
2. **Rasterization** — `icon/build_icons.ps1` shells out to
   ImageMagick 7 (`magick`). Each output PNG is rendered via a
   two-stage approach: rasterize the SVG at 2× the target pixel
   size from a high DPI (1024), then Lanczos-downsample to the
   final size. This gives noticeably crisper antialiasing at the
   16/24/48 px end of the range than a single-pass rasterize.
3. **Round mipmap variant** — for each Android density, the
   square mipmap is also composited onto a sky-blue circular fill
   (`#CDE4F5`) and written as `ic_launcher_round.png`. The fill
   matches the adaptive-icon background gradient's mid-tone so
   circular-mask launchers and adaptive-shape launchers look
   visually consistent. (minSdk=30 means real devices use the
   vector adaptive icon; the round PNG is a legacy fallback.)
4. **Windows multi-resolution ICO** — `radioshed.ico` is assembled
   by rendering seven separate PNGs (16, 24, 32, 48, 64, 128,
   256) and packing them into a single .ico container. Explorer
   chrome reaches into the right embedded image per surface
   (16 for detail-view, 24 for menu strips, 32 for desktop, 256
   for the large-icon view), so each context gets a hand-tuned
   rasterization rather than a blurry downscale.
5. **Android adaptive icon** — the foreground (`composeApp/src/
   androidMain/res/drawable-v24/ic_launcher_foreground.xml`) and
   background (`drawable/ic_launcher_background.xml`) are
   hand-authored Android VectorDrawables, not bitmaps. Designed
   directly in the 108×108 adaptive-icon viewport with critical
   content kept inside the 72×72 safe zone; decorative bits (the
   radio-wave arcs, the outer pixels of the roof) intentionally
   spill past so circular-mask and teardrop-mask launchers reveal
   more context. Colours and proportions mirror the SVG so
   legacy mipmap PNGs and the vector adaptive icon read as the
   same mark. If you change either, change the other too and
   re-run `build_icons.ps1`.

### Regenerating

```powershell
# From the repo root, with ImageMagick 7 on PATH (or installed at
# the default C:\Program Files\ImageMagick-7.1.2-Q16-HDRI\):
pwsh -File icon/build_icons.ps1
```

For Linux / macOS contributors without PowerShell, the same
rasterization can be done with `magick` directly:

```bash
# Example: Android xxxhdpi (192×192)
magick -background none -density 1024 icon/radioshed.svg \
    -resize 384x384 -filter Lanczos -resize 192x192 \
    composeApp/src/androidMain/res/mipmap-xxxhdpi/ic_launcher.png

# Example: Windows .ico (multi-res)
for s in 16 24 32 48 64 128 256; do
    magick -background none -density 1024 icon/radioshed.svg \
        -resize "$((s*2))x$((s*2))" -filter Lanczos -resize "${s}x${s}" \
        "/tmp/icon-${s}.png"
done
magick /tmp/icon-{16,24,32,48,64,128,256}.png \
    composeApp/src/desktopMain/resources/icon/radioshed.ico
```

Porting the PowerShell driver to a portable shell/bash script
once the project gets a Linux CI lane is tracked in SPEC §20.

### Output locations the script writes

| File                                                                           | Pixel size | Used by                                                |
| ------------------------------------------------------------------------------ | ---------- | ------------------------------------------------------ |
| `composeApp/src/androidMain/res/mipmap-mdpi/ic_launcher.png`                   | 48×48      | Android launchers, pre-26 fallback                     |
| `composeApp/src/androidMain/res/mipmap-mdpi/ic_launcher_round.png`             | 48×48      | Same, round-mask launchers                             |
| `composeApp/src/androidMain/res/mipmap-hdpi/ic_launcher.png`                   | 72×72      | Same                                                   |
| `composeApp/src/androidMain/res/mipmap-hdpi/ic_launcher_round.png`             | 72×72      | Same                                                   |
| `composeApp/src/androidMain/res/mipmap-xhdpi/ic_launcher.png`                  | 96×96      | Same                                                   |
| `composeApp/src/androidMain/res/mipmap-xhdpi/ic_launcher_round.png`            | 96×96      | Same                                                   |
| `composeApp/src/androidMain/res/mipmap-xxhdpi/ic_launcher.png`                 | 144×144    | Same; also the size Android Auto pulls from manifest   |
| `composeApp/src/androidMain/res/mipmap-xxhdpi/ic_launcher_round.png`           | 144×144    | Same                                                   |
| `composeApp/src/androidMain/res/mipmap-xxxhdpi/ic_launcher.png`                | 192×192    | Same                                                   |
| `composeApp/src/androidMain/res/mipmap-xxxhdpi/ic_launcher_round.png`          | 192×192    | Same                                                   |
| `composeApp/src/desktopMain/resources/icon/radioshed.png`                      | 256×256    | Compose Window runtime icon (title bar + taskbar)      |
| `composeApp/src/desktopMain/resources/icon/radioshed-512.png`                  | 512×512    | Linux `.deb` installer icon (Compose nativeDistributions) |
| `composeApp/src/desktopMain/resources/icon/radioshed.ico`                      | multi-res  | Windows `.msi` installer icon (16/24/32/48/64/128/256) |

The Android adaptive-icon VectorDrawables under
`composeApp/src/androidMain/res/drawable*/ic_launcher_{foreground,background}.xml`
are NOT generated by the script — they're hand-authored XML kept
in lock-step with the SVG. The adaptive-icon manifest pointer at
`composeApp/src/androidMain/res/mipmap-anydpi-v26/ic_launcher.xml`
(and `ic_launcher_round.xml`) is unchanged.

### Platforms each output serves

- **Android phone launcher / Pixel / Galaxy launcher** — adaptive
  icon from `drawable-v24/ic_launcher_foreground.xml` +
  `drawable/ic_launcher_background.xml`. PNG mipmaps are the
  pre-26 fallback only.
- **Android Auto** — the MediaLibraryService presents whatever
  `application@android:icon` resolves to via the manifest, which
  is the same `@mipmap/ic_launcher`. AA car-head-unit displays
  pull from the largest available density (xxhdpi at 144 or
  xxxhdpi at 192).
- **Windows 11 title bar + taskbar (runtime)** — `radioshed.png`
  via `Window(icon = ...)` in `Main.kt`.
- **Windows 11 installer (.msi) / Start Menu shortcut /
  Explorer chrome** — `radioshed.ico` via
  `compose.desktop.nativeDistributions { windows { iconFile.set
  (...) } }`.
- **Linux Wayland title bar + taskbar (runtime)** — same
  `radioshed.png` as Windows runtime, via the JVM window-icon
  mechanism. GNOME / KDE strip the title bar entirely on
  Wayland-CSD apps but still display the icon in the dock /
  overview.
- **Linux installer (.deb)** — `radioshed-512.png` via
  `nativeDistributions { linux { iconFile.set(...) } }`. The
  packager installs it under
  `/opt/RadioShed/lib/RadioShed.png` and registers a `.desktop`
  file pointing at it.
- **macOS .dmg** — not yet generated. Needs `radioshed.icns`
  which requires the `iconutil` CLI (macOS-only). The
  `nativeDistributions { macOS { } }` block in
  `composeApp/build.gradle.kts` is wired but commented out
  pending a Mac CI lane.

## Where to go next

- Read **`SPEC.md`** — the product spec, including responsive-design
  requirements (§1.1), platform parity matrix (§3), and the phased
  build plan with checkbox tasks (§20). The latest batch entries
  describe the most recently shipped work.
- For end-user docs, see **`USER_GUIDE.md`** and **`FAQ.md`**.
- For agent / AI-tool conventions, see **`CLAUDE.md`** (project
  context for Claude Code) and **`AGENTS.md`** (coding-style
  conventions).
