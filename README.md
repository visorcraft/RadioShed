# RadioShed

Cross-platform music application — **Android 13+** and **iOS 16+** — built
in a single repository using **Kotlin Multiplatform** + **Compose
Multiplatform**. Replaces SiriusXM / Spotify / YouTube Music with a
station-centric experience over the user's own local music library, with
optional online enrichment.

> **License: proprietary.** Source code, compiled binaries, and
> documentation in this repository are not for redistribution, resale,
> public sharing, or mirroring. See `LICENSE` for full terms.
>
> **Specification:** see [`SPEC.md`](./SPEC.md) for the full product
> spec, feature catalog, and phased build plan with checkbox tasks.

## Repository status

This workspace currently holds:

- `SPEC.md` — the product specification (source of truth).
- `README.md` — this file (toolchain + repo overview).
- `android13-smoketest/` — a throwaway "hello world" Android app used to
  verify the Android toolchain end-to-end. **Slated for removal in
  Phase 0** once the KMP project is scaffolded.

The real KMP project structure (`shared/`, `androidApp/`, `iosApp/`) is
created in Phase 1 of the build plan — see SPEC.md §20.

## Tech stack

| Layer                       | Choice                                                |
| --------------------------- | ----------------------------------------------------- |
| Shared business logic       | Kotlin Multiplatform (KMP)                            |
| Shared UI                   | Compose Multiplatform                                 |
| Database                    | SQLDelight                                            |
| HTTP                        | Ktor client                                           |
| DI                          | Koin                                                  |
| Audio (Android)             | Media3 / ExoPlayer                                    |
| Audio (iOS)                 | AVAudioEngine                                         |
| In-car (Android)            | Native big-button "Drive Mode" overlay                |
| In-car (iOS)                | CarPlay (audio-template app)                          |
| Min SDK                     | Android 8.0 (API 26) / iOS 16                         |
| Target SDK                  | Android 14 (API 34) / iOS latest                      |
| Build system                | Gradle (Kotlin DSL), JDK 17                           |
| iOS dependency mgr          | Swift Package Manager (preferred), CocoaPods fallback |

## Toolchain on the dev machine (Windows)

The Android side and all shared Kotlin code build fully on Windows. iOS
builds require macOS (see "iOS builds" below).

### Android (already installed and verified)

| Component             | Version / Location                                            |
| --------------------- | ------------------------------------------------------------- |
| OS                    | Windows 11                                                    |
| JDK                   | Microsoft OpenJDK 17 — `C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot` |
| Android SDK           | `C:\Program Files (x86)\Android\android-sdk`                  |
| Android platforms     | `android-33`, `android-35`, `android-36`                      |
| Build-tools           | `33.0.2`, `35.0.1`, `36.0.0`                                  |
| Platform-tools        | `36.0.0` (`adb`)                                              |
| Emulator              | `36.2.12`                                                     |
| System images         | `android-33;google_apis;x86_64`, `android-35;google_apis;x86_64` |
| Gradle (system)       | `9.4.1` (Chocolatey, used to bootstrap project wrappers)      |

Persistent user-level environment variables set:

```
ANDROID_HOME      = C:\Program Files (x86)\Android\android-sdk
ANDROID_SDK_ROOT  = C:\Program Files (x86)\Android\android-sdk
JAVA_HOME         = C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot
PATH              += %ANDROID_HOME%\platform-tools
                     %ANDROID_HOME%\cmdline-tools\latest\bin
                     %ANDROID_HOME%\emulator
```

Variables set via `setx` / the user registry take effect only in **new**
shells. If a current shell does not see them, open a fresh terminal.

### iOS builds (require macOS)

iOS code can be authored from Windows, but compilation, simulator runs,
and App Store submission all require macOS. Three workable paths from a
Windows-primary environment:

1. **GitHub Actions macOS runners** — limited free minutes on a private
   repo (this repo will be private; see License); macOS minutes count
   10× Linux minutes against the included quota. Suitable for early CI
   only.
2. **Self-hosted Mac runner** — a Mac mini (M2 ~$600) registered as a
   GitHub Actions self-hosted runner. No marginal per-build cost.
   Recommended once iOS development is frequent.
3. **Cloud Mac** — MacStadium / MacInCloud / AWS EC2 Mac for occasional
   interactive Xcode work.

Decision is documented in `docs/decisions/0001-ios-build-host.md` once
made (Phase 0 task).

## Repository layout (target — built incrementally per `SPEC.md` §20)

```
RadioShed/
├── README.md
├── SPEC.md
├── LICENSE                      # Proprietary, drafted in Phase 0
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── version.properties           # Single app-version source
├── gradle/
│   └── libs.versions.toml       # Dependency versions
├── shared/                      # Kotlin Multiplatform module
│   └── src/{commonMain,androidMain,iosMain,commonTest}/...
├── androidApp/                  # Android entry point (Compose)
├── iosApp/                      # Xcode project (SwiftUI shell + KMP framework)
├── docs/
│   └── decisions/               # Architecture Decision Records
├── .github/workflows/           # GitHub Actions workflows (android.yml, ios.yml)
└── android13-smoketest/         # Temporary; removed in Phase 0
```

Today only the smoketest exists. Everything else is created during the
phased build (see `SPEC.md` §20).

## Common build commands (after Phase 1)

```bash
# Android (any host)
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:assembleRelease

# Run common Kotlin tests on JVM
./gradlew :shared:jvmTest

# Run common tests on Android
./gradlew :shared:testDebugUnitTest

# iOS framework (macOS only)
./gradlew :shared:assembleXCFramework

# iOS app archive (macOS only)
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
    -configuration Release -destination 'generic/platform=iOS' archive
```

## Verifying the Android toolchain (current — pre-KMP)

```bash
cd android13-smoketest
./gradlew assembleDebug
```

Expected:

```
BUILD SUCCESSFUL in <n>s
app/build/outputs/apk/debug/app-debug.apk
```

If that succeeds, the machine can build any Android 13 application —
which is the prerequisite for the KMP `androidApp/` module that lands in
Phase 1.

### Running on the Android emulator

```bash
avdmanager create avd -n android13 -k "system-images;android-33;google_apis;x86_64"
emulator -avd android13
adb install -r android13-smoketest/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.smoketest/.MainActivity
```

## Reinstalling the Android SDK pieces

```bash
sdkmanager "platforms;android-33" "build-tools;33.0.2" \
           "sources;android-33" \
           "system-images;android-33;google_apis;x86_64"
sdkmanager --licenses
```

## Troubleshooting

- **`SDK location not found`** — ensure `ANDROID_HOME` is set in the
  current shell, or that the project's `local.properties` pins
  `sdk.dir`.
- **`Unsupported class file major version`** — confirm `JAVA_HOME`
  points at JDK 17. AGP 8.2+ requires JDK 17.
- **Gradle / AGP version mismatch** — AGP 8.2.x requires Gradle
  8.2 – 8.x; do not bump a wrapper to Gradle 9 without upgrading AGP
  first.
- **Wrapper download fails behind a proxy** — add
  `systemProp.https.proxyHost` and `systemProp.https.proxyPort` to
  `gradle.properties`.
- **iOS framework build fails on macOS runner** — confirm Xcode CLI
  tools are installed and the Kotlin/Native iOS targets in
  `shared/build.gradle.kts` match the Xcode SDK version.

## Where to go next

- Read **`SPEC.md`** — the product spec, including responsive-design
  requirements (§1.1), platform parity matrix (§3), and the phased
  build plan with checkbox tasks (§20).
- Phase 0 is the immediate next milestone: stand up the private repo,
  draft the proprietary `LICENSE`, set up CI skeleton, decide the iOS
  build host strategy.
