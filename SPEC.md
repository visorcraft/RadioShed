# RadioShed — Product Specification

> Status: draft v2 · Target platforms: **Android 13+ (primary)** and **iOS 16+**
>
> This document is the source of truth for what RadioShed is and how it
> should behave. It is platform-neutral by default; sections that diverge
> per platform are explicitly marked **(Android only)** or **(iOS only)**.
>
> License: **proprietary**. Not for redistribution, resale, public sharing,
> or mirroring. See `LICENSE` for full terms.

---

## 0. North-star principles

- **Offline-first, online-optional.** Every feature works without internet.
  Online features are additive (better metadata, art, lyrics, scrobbling,
  recommendations) and gated by an explicit *Connectivity policy* setting.
- **Stations, not files.** The primary surface is stations. Folder
  management is buried in settings. Album art and station artwork carry
  the experience. Users never see file paths in normal use *unless they
  explicitly opt into file-mode browsing*.
- **One unified Station model.** A station = `Source rules` + `Playback
  rules` + `Identity`. Built-in presets like *Latest Hits* are just
  configurations of that one model.
- **Modular sources.** v1 ships with local files. The `MediaSource`
  interface is built from day one to plug in Jellyfin / Plex / Subsonic /
  DLNA / SMB / WebDAV / cloud providers later without changing station
  logic.
- **Fluid by default.** Every layout scales gracefully across phones,
  tablets, and car center consoles of every aspect ratio. No fixed pixel
  sizes. No assumptions about screen geometry. The car center console is
  a first-class form factor, not an afterthought.
- **No Play Services dependency.** Pure AOSP-compatible APK on Android.
  Standard App Store distribution on iOS. (Source code is proprietary —
  see License in cover block — but runtime has no Google-tier lock-in.)
- **Power knobs everywhere, but sane defaults.** Newcomers never see them;
  power users find them under Settings → Advanced.
- **Drive Mode as a first-class UI**, not an afterthought. On iOS this
  manifests as CarPlay; on Android as the native big-button overlay.
- **No telemetry.** Crash logs are local-only and user-exportable. There
  is no analytics opt-out because there is no analytics.
- **All user data is portable.** Stations, ratings, history, playlists —
  exported as human-readable JSON any time, even though the binary itself
  is closed-source.

---

## 1. Platforms & target devices

| Platform | Min OS    | Primary target                                          |
| -------- | --------- | ------------------------------------------------------- |
| Android  | 8.0 (26)  | AutoLinkR Elite (Android 13 head unit), phones, tablets |
| iOS      | 16.0      | iPhone, iPad, CarPlay-equipped vehicles                 |

The Android build is the lead platform — it drives the in-car experience
the project was started for. iOS is a co-equal release target so the same
user can take their stations between cars/phones.

### 1.1 Form factors & responsive design

RadioShed must look first-class on every screen the user puts it on.

| Form factor               | Typical resolution                              | Orientation      | UI implications                                              |
| ------------------------- | ----------------------------------------------- | ---------------- | ------------------------------------------------------------ |
| Phone portrait            | 1080×2340+                                      | portrait         | Single-column, bottom-anchored controls                      |
| Phone landscape           | 2340×1080+                                      | landscape        | Compact two-column where useful                              |
| Tablet portrait           | 1600×2560                                       | portrait         | Two-pane (master + detail), larger typography                |
| Tablet landscape          | 2560×1600                                       | landscape        | Optional three-pane (stations + queue + now-playing)         |
| **Car center console**    | **1024×600 to 1920×1080**, often non-standard ratios like 1920×720 | **landscape**, fixed | Big touch targets, high contrast, no fine controls   |
| AutoLinkR Elite           | varies                                          | landscape, wide  | Drive Mode default                                           |
| iPad Pro / iPad           | up to 2732×2048                                 | both             | Supports Stage Manager / Split View                          |
| iPhone Pro Max            | 2796×1290                                       | both             |                                                              |
| CarPlay                   | varies (Apple-controlled)                       | landscape        | Apple templates; we control content only                     |
| Watch (Wear OS / Apple)   | small                                           | portrait         | Post-v1 companion app                                        |

**Implementation rules**

- All measurements in `dp` (Android) / `pt` (iOS); never raw pixels
- Compose `WindowSizeClass` (Compact / Medium / Expanded / Large / XLarge)
  drives layout breakpoints; SwiftUI uses `horizontalSizeClass` +
  `verticalSizeClass` if a fallback to native iOS UI is chosen
- Layouts are **fluid, not fixed-pixel**: wider screens show *more*
  content, not *bigger* content, unless the user explicitly bumps font
  size
- Album art renders at any aspect ratio (auto-crop center-square + blurred
  letterbox-fill background)
- Now Playing has 3 layout variants: stacked (compact), side-by-side
  (medium/expanded), three-pane (large+) with queue + lyrics + art
  panels
- Stations Home reflows tile grid by available width — no fixed column
  count
- Drive Mode auto-scales: button count is configurable (4–6), button
  size grows to fill available area, never shrinks below 120 dp tap
  target
- Dynamic typography (XS / S / M / L / XL / XXL) is usable
  independently of system font setting
- **CI snapshot test matrix:** every screen × 5 representative widths
  (compact phone, large phone, tablet, narrow car console 1024×600, wide
  car console 1920×720) × 2 orientations × 2 themes

---

## 2. Tech stack & architecture

### 2.1 Recommended primary stack: **Kotlin Multiplatform + Compose Multiplatform**

| Layer                     | Choice                                                                             | Shared? |
| ------------------------- | ---------------------------------------------------------------------------------- | ------- |
| Shared business logic     | Kotlin Multiplatform (KMP)                                                         | ✅       |
| Shared UI                 | Compose Multiplatform (CMP)                                                        | ✅       |
| Database                  | SQLDelight (KMP)                                                                   | ✅       |
| Networking                | Ktor client (KMP)                                                                  | ✅       |
| DI                        | Koin (KMP-friendly)                                                                | ✅       |
| Async                     | Kotlin coroutines + Flow                                                           | ✅       |
| Audio engine (Android)    | Media3 / ExoPlayer + AudioTrack                                                    | ❌       |
| Audio engine (iOS)        | AVAudioEngine + AVAudioPlayerNode + AVAudioUnit                                    | ❌       |
| Background audio (Android)| `MediaSessionService` foreground service                                           | ❌       |
| Background audio (iOS)    | `AVAudioSession` with `playback` category                                          | ❌       |
| Storage access (Android)  | `MANAGE_EXTERNAL_STORAGE` (sideload-friendly) + SAF fallback                       | ❌       |
| Storage access (iOS)      | `UIDocumentPickerViewController` + security-scoped bookmarks + `NSFileCoordinator` | ❌       |
| TTS (Android)             | `android.speech.tts.TextToSpeech`                                                  | ❌       |
| TTS (iOS)                 | `AVSpeechSynthesizer`                                                              | ❌       |
| In-car UI (Android)       | Native big-button overlay                                                          | ❌       |
| In-car UI (iOS)           | CarPlay framework (audio-template app)                                             | ❌       |
| Casting (Android)         | Cast SDK *(BSD)* + UPnP (Cling) + Sonos SOAP                                       | ❌       |
| Casting (iOS)             | AirPlay 2 (`AVRoutePickerView`) + UPnP + Sonos SOAP                                | ❌       |
| Widgets (Android)         | Glance API (Jetpack)                                                               | ❌       |
| Widgets (iOS)             | WidgetKit                                                                          | ❌       |

Estimated code share: **~65–75%** of total LoC (data layer, station
engine, scanner coordinator, DJ scripting, settings, networking, online
integrations, most of the UI).

### 2.2 Alternative stack (conservative fallback)

If Compose Multiplatform on iOS proves limiting (gesture/animation
parity, performance, or App Store compatibility issues), fall back to:

- **Shared:** KMP business logic + SQLDelight + Ktor (~50% of code)
- **Android UI:** Jetpack Compose
- **iOS UI:** SwiftUI

This is the more conservative path. Pay more in UI duplication, gain
native idioms and lower technical risk. A decision point is built into
the build order (Phase 1 — see §20).

### 2.3 Module layout

The Android Studio KMP wizard generates a **unified `composeApp/`** module
(rather than the historical `shared/` + `androidApp/` two-module split).
Both arrangements are valid; the unified module is the modern KMP
convention when UI is shared via Compose Multiplatform, and is what every
current JetBrains sample uses. Sub-packages inside the single module
provide the same logical separation the SPEC originally envisioned.

```
RadioShed/
├── composeApp/                # Unified KMP module (Android + iOS) with shared UI
│   └── src/
│       ├── commonMain/
│       │   ├── kotlin/com/visorcraft/radioshed/
│       │   │   ├── domain/    # Station, Track, etc. — pure Kotlin
│       │   │   ├── data/      # SQLDelight, repositories
│       │   │   ├── audio/     # AudioCoordinator interface (impls per-platform)
│       │   │   ├── source/    # MediaSource interface + LocalFileSource
│       │   │   ├── scanner/   # Library scanner + tag readers
│       │   │   ├── dj/        # DJ engine (templates, scheduling, ducking)
│       │   │   ├── online/    # MusicBrainz, Last.fm, LRCLib, etc.
│       │   │   ├── cast/      # Cast / UPnP / AirPlay output coordination
│       │   │   └── ui/        # Compose Multiplatform shared screens
│       │   └── composeResources/   # Shared drawables / strings
│       ├── androidMain/
│       │   └── kotlin/com/visorcraft/radioshed/
│       │       ├── audio/     # ExoPlayer-based AudioEngine impl
│       │       ├── service/   # MediaSessionService
│       │       ├── storage/   # MANAGE_EXTERNAL_STORAGE / SAF
│       │       ├── widgets/   # Glance widgets
│       │       └── drive/     # Drive Mode UI
│       ├── iosMain/
│       │   └── kotlin/com/visorcraft/radioshed/
│       │       ├── audio/     # AVAudioEngine-based AudioEngine impl
│       │       ├── storage/   # Document picker + security-scoped bookmarks
│       │       └── (CarPlay/widget code lives in iosApp/)
│       └── commonTest/
├── iosApp/                    # iOS entry point (Swift)
│   ├── iosApp/                # Swift sources (App.swift, ContentView.swift, etc.)
│   ├── iosApp.xcodeproj/      # Xcode project consuming the ComposeApp framework
│   ├── Configuration/         # Build settings
│   ├── CarPlay/               # CarPlay scene + audio templates (when added in Phase 8)
│   └── Widgets/               # WidgetKit extension (when added in Phase 13)
├── docs/decisions/            # Architecture Decision Records
├── .github/workflows/         # GitHub Actions workflows (android.yml, ios.yml)
├── gradle/
│   ├── libs.versions.toml     # Single dependency-version source
│   └── wrapper/
└── version.properties         # Single app-version source
```

### 2.4 Repository & build strategy

**One repository, one Gradle project, two app entry points.** This
monorepo approach is what JetBrains recommends for KMP and is what
production KMP apps use. Two-repo splits (Android repo + iOS repo +
shared library repo) were considered and rejected — KMP's main strength
is single-tree refactoring across common + platform code, and splitting
that across repos forfeits the reason to use KMP in the first place.

#### Build system

| Concern              | Tool                                                       |
| -------------------- | ---------------------------------------------------------- |
| Shared + Android     | Gradle (Kotlin DSL), JDK 17                                |
| iOS framework        | Gradle produces `XCFramework` / SPM-compatible package     |
| iOS app              | Xcode (Swift) consumes the shared framework                |
| Dependency versions  | `gradle/libs.versions.toml` (single source)                |
| iOS dependency mgr   | Swift Package Manager (preferred), CocoaPods fallback      |
| App versioning       | `version.properties` (read by both platforms)              |
| Code style           | ktlint + detekt (Kotlin), swiftformat + swiftlint (Swift)  |
| API docs (internal)  | dokka (Kotlin) → internal/private hosting (not GitHub Pages — repo is private) |

#### Branching, releases, CI

- **Branching:** trunk-based on `master`. Short-lived feature branches.
  Branch protection: required PR reviews + green CI.
- **Tags drive releases:** pushing `v1.0.0` triggers automated artifact
  builds for both platforms.
- **CI:** GitHub Actions matrix
  - Android job (`ubuntu-latest`): ktlint, detekt, unit tests,
    `assembleDebug`, `assembleRelease`, signing
  - iOS job (`macos-latest`): swiftlint, build shared XCFramework, run
    `commonTest` on iOS simulator, `xcodebuild archive`
  - `commonTest` runs on both runners to catch platform-specific
    regressions
- **Release artifacts:**
  - Android: signed APK + AAB → authorized direct-download portal +
    optional Play Store flavor (SAF-only)
  - iOS: IPA → TestFlight (always) + App Store on release tags
- **Pre-commit:** `pre-commit` framework runs ktlint, detekt,
  swiftformat on staged files before they ever reach CI.

#### Private-repo CI cost note

Because the source is proprietary, the repository will be **private**.
GitHub Actions free minutes for private repos are limited (currently
2,000 Linux-equivalent minutes/month on the free tier; macOS counts
**10× Linux minutes**, so a single 30-min macOS build burns 300
minutes). Plan accordingly:

- Use Linux runners for everything that doesn't require macOS
- Self-hosted Mac runner becomes attractive once iOS builds are frequent
- Or upgrade to GitHub Team for more included minutes

#### Building from Windows (this project's primary dev environment)

The Android side builds and tests fully on Windows. iOS builds require
macOS. Realistic paths for a Windows-primary developer:

1. **GitHub Actions macOS runners.** Limited free minutes on private
   repos; budget carefully.
2. **Self-hosted Mac runner.** A Mac mini (M2 ~$600) at home, registered
   as a GitHub Actions runner. No marginal cost per build. Recommended
   once iOS development is frequent.
3. **Cloud Mac.** MacStadium / MacInCloud / AWS EC2 Mac for occasional
   interactive Xcode work without buying a Mac.

iOS Swift code can be authored on Windows (IntelliJ IDEA or VS Code), but
without UIKit/SwiftUI autocomplete and without simulator access. A Mac is
required for App Store submission.

#### Local build commands

```bash
# Android (any host)
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:assembleRelease

# Run unit tests
./gradlew :composeApp:testDebugUnitTest

# Run KMP common tests on iOS simulator (macOS only)
./gradlew :composeApp:iosSimulatorArm64Test

# iOS framework for the iOS app (macOS only)
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64

# iOS app archive (macOS only)
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
    -configuration Release -destination 'generic/platform=iOS' archive
```

---

## 3. Platform parity matrix

| Feature                             | Android                  | iOS                        | Notes                                               |
| ----------------------------------- | ------------------------ | -------------------------- | --------------------------------------------------- |
| Station model & presets             | ✅                        | ✅                          | Identical                                           |
| Playlist creation                   | ✅                        | ✅                          | Identical                                           |
| Local file playback                 | ✅                        | ✅                          | Different decoders                                  |
| MicroSD card                        | ✅                        | ❌ *physically n/a*         | iPad with USB-C external storage is closest analog  |
| Folder-based library                | ✅                        | ⚠️ *via document picker*    | iOS UX feels closer to "import folder"              |
| Cloud providers (Drive/Dropbox/etc.)| Post-v1                  | Post-v1                    | Module per provider                                 |
| FLAC / ALAC / AAC / MP3 / OGG       | ✅                        | ✅                          | Native on both                                      |
| Opus                                | ✅                        | ✅ (iOS 11+)                |                                                     |
| WMA / APE / WV                      | ✅                        | ⚠️ *needs FFmpeg*           | Bundle FFmpeg on iOS                                |
| DSD (DSF/DFF)                       | ✅ (transcode)            | ⚠️ *FFmpeg + transcode*     | Both transcode to PCM                               |
| Cue file support                    | ✅                        | ✅                          | Single-FLAC-album track splitting                   |
| Crossfade / gapless                 | ✅                        | ✅                          | Both                                                |
| Smart Fades (content-aware)         | ✅                        | ✅                          | Detects natural song endings                        |
| Up to 31-band graphic + parametric EQ | ✅                      | ✅                          | iOS uses chained `AVAudioUnitEQ`                    |
| AutoEQ headphone profiles           | ✅                        | ✅                          | Import AutoEQ + APO configs                         |
| Per-output-device EQ profiles       | ✅                        | ✅                          | Auto-switches by output                             |
| ReplayGain                          | ✅                        | ✅                          | Same logic                                          |
| Smart shuffle                       | ✅                        | ✅                          | Identical (shared logic)                            |
| AI DJ (offline TTS)                 | ✅                        | ✅                          | System TTS on both; voices vary                     |
| AI DJ (cloud TTS, BYO key)          | ✅                        | ✅                          | Identical                                           |
| Drive Mode UI                       | ✅ Native overlay         | ❌ — *use CarPlay*          | iOS prohibits custom in-car UIs                     |
| CarPlay                             | n/a                      | ✅                          | Audio-category app                                  |
| Android Auto                        | n/a                      | n/a                        | Out of scope (head unit runs native Android)        |
| Steering-wheel keys                 | ✅                        | ✅ via CarPlay              |                                                     |
| Background playback                 | ✅                        | ✅                          |                                                     |
| Lock-screen / notification controls | ✅                        | ✅                          | MediaSession / NowPlayingInfoCenter                 |
| Bluetooth AVRCP metadata            | ✅                        | ✅                          |                                                     |
| Cast to Chromecast                  | ✅                        | ⚠️ *via AirPlay or 3p lib* | iOS Chromecast support is non-Apple                 |
| AirPlay 2 output                    | n/a                      | ✅                          | Native iOS                                          |
| UPnP/DLNA renderer output           | ✅                        | ✅                          | Cling / libupnp                                     |
| Sonos                               | ✅                        | ✅                          | SOAP API                                            |
| Voice control ("Hey RadioShed")     | ✅                        | ⚠️ *limited*                | iOS prefers Siri Shortcuts                          |
| Last.fm / ListenBrainz scrobbling   | ✅                        | ✅                          |                                                     |
| MusicBrainz / Cover Art Archive     | ✅                        | ✅                          |                                                     |
| LRCLib lyrics                       | ✅                        | ✅                          |                                                     |
| Artist images (fanart.tv)           | ✅                        | ✅                          | Online, opt-in                                      |
| Home-screen widgets                 | ✅ Glance                 | ✅ WidgetKit                | Now-playing + station-launcher widgets              |
| Material You dynamic color          | ✅ (Android 12+)          | n/a                        | Optional theme source                               |
| Backup / restore (zip)              | ✅                        | ✅                          | iOS: Documents folder, visible in Files app         |
| Distribution channel                | Direct download / Play Store flavor | App Store only  | Proprietary; no F-Droid, no AltStore, no sideload   |

⚠️ flags features that work but with reduced UX or extra engineering cost.

---

## 4. The Station model (the heart of the app)

```
Station {
  identity:   { name, color, icon/artwork, sort_position, custom_subtitle }
  source:     SourceRule
  playback:   PlaybackRule
  stats:      { play_count, last_played, total_listen_ms, skip_rate }
}
```

### 4.1 SourceRule — composable

A station's source is built from one or more **selectors**, combined with
**set operators**:

| Selector             | Description                                                                                                   |
| -------------------- | ------------------------------------------------------------------------------------------------------------- |
| `FolderSelector`     | One or more registered folders, recursive flag per folder                                                     |
| `TagFilter`          | Match on any tag: genre, year, decade, BPM, key, rating, mood, label, language, composer, custom user tags    |
| `SmartQuery`         | `Newest N`, `Oldest N`, `Least-played`, `Most-played`, `Highest-rated`, `Longest-unheard`, `Recently-added (last X days)`, `Recently-rated`, `Never-played`, `Adaptive Mix` |
| `PlaylistSelector`   | A manually curated track list (drag-to-order)                                                                 |
| `ArtistSelector`     | One or more artists                                                                                           |
| `AlbumSelector`      | One or more albums                                                                                            |
| `DurationFilter`     | `min/max` seconds (exclude < 30s skits, exclude > 15min ambient)                                              |
| `FileFormatFilter`   | FLAC only, MP3 only, lossless only, etc.                                                                      |
| `PathPattern`        | Glob include/exclude, e.g. `*/Spoken Word/*`                                                                  |

**Operators:** `Union (∪)`, `Intersection (∩)`, `Difference (-)`,
`Top N of` (e.g. "the newest 100 of [Folder Music ∪ Folder Albums]").

Example: *Latest Hits* preset =
`TopN(100, sortBy=ctime, source=AllRegisteredFolders)`. The user can
clone it and tweak (e.g. *Latest Hits — Metal Only* = same thing
intersected with `TagFilter(genre=Metal)`).

### 4.2 PlaybackRule

| Setting                        | Options                                                                                                                                |
| ------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------- |
| Order                          | Shuffle / Sequential / Weighted Random / Smart Shuffle                                                                                 |
| Smart-shuffle: no-repeat depth | Last N tracks (default 25)                                                                                                             |
| Smart-shuffle: artist spacing  | "Don't repeat same artist within X tracks" (default 5)                                                                                 |
| Smart-shuffle: album spacing   | (default 3)                                                                                                                            |
| Smart-shuffle: weighting       | Uniform / Favor under-played / Favor higher-rated / Favor recent / Custom-weighted formula                                             |
| Crossfade                      | Off / 1–12s fixed / Smart Fades (content-aware) / Custom curve                                                                         |
| Gapless                        | On / Off                                                                                                                               |
| Skip-on-dislike                | Off / Same artist for X minutes / Same album / Just this track                                                                         |
| Repeat queue                   | On / Off (radio = always on)                                                                                                           |
| EQ preset override             | Inherit / Specific preset                                                                                                              |
| ReplayGain mode override       | Track / Album / Off / Pre-amp                                                                                                          |
| AI DJ                          | Off / On — frequency: every track / every N / every X minutes / station-change only                                                    |
| Auto-rotate refresh            | For SmartQueries — re-evaluate the "top 100" every X hours/day or every play through                                                   |

---

## 5. Built-in station presets (auto-created at onboarding)

The user can keep, edit, or delete any of these:

1. **Latest Hits** — newest 100 by file ctime, smart shuffle, refresh
   daily
2. **All Music** — every track, smart shuffle, infinite
3. **Discover** — least-played + never-played, smart shuffle
4. **Forgotten Gems** — rated 4–5★ AND not played in 90+ days
5. **Top Rated** — 4–5★ only, smart shuffle
6. **Recently Added** — added in last 30 days
7. **Decade auto-stations** — one per decade represented in your tags
   (50s/60s/70s/…/2020s) — only created for decades with ≥50 tracks
8. **Genre auto-stations** — top 10 genres by track count, one station
   each (toggleable in onboarding)
9. **Workshop** — empty playlist station the user fills with thumbs-up
   tracks during listening (tap "Add to Workshop" anytime)
10. **Smart Mix** — adaptive station that learns from listening habits.
    Uses local play history + ratings + skip patterns to weight track
    selection. Refreshes weekly. Works fully offline. If scrobbling is
    enabled, additionally factors in Last.fm "loved" tracks and similar
    artists.

---

## 6. Onboarding flow

Each step is one screen, swipeable forward/back. None take more than 30
seconds for a non-power-user. The flow is identical on Android and iOS;
only the storage permission step differs.

1. **Welcome** — "RadioShed turns your music into stations. No
   subscriptions, no internet required."
2. **Permissions**
   - **(Android)** Request `MANAGE_EXTERNAL_STORAGE` (with SAF fallback
     button "Or pick folders manually"), `POST_NOTIFICATIONS`,
     `READ_MEDIA_AUDIO`.
   - **(iOS)** Show explainer → user picks folders via document picker
     (iCloud Drive, On My iPhone, external USB, SMB if mounted in Files).
     Notification permission dialog.
3. **Add your first folder** — file/folder picker. Show recursive
   toggle. Allow multiple folders.
4. **Initial scan** — progress bar, count of tracks/artists/albums/
   genres found. Runs in background; user can keep onboarding.
5. **Quick taste setup** *(skippable)* — "What do you want to listen to
   most?" → checkboxes for top genres detected → these auto-create
   starter stations.
6. **Build your first custom station** *(skippable)* — name, pick a
   source (folder/playlist/smart), pick a color, save.
7. **In-car preview** — explains Drive Mode (Android) / CarPlay (iOS) and
   how to enable it.
8. **AI DJ intro** *(skippable)* — sample DJ voice playing. Toggle
   on/off globally. Defer voice selection to settings.
9. **Done** — drop into Stations Home with the auto-created presets
   visible.

---

## 7. UI surfaces

### 7.1 Stations Home (default screen)

- Grid or list (toggle); tile size XS/S/M/L
- Each tile: station artwork (auto-collage of 4 album covers from
  station, or user-uploaded), name, subtitle ("23 tracks", "Smart"), play
  count optional
- **Sort options:** manual (drag), alphabetical, recently played, most
  played, recently created, color, custom (saved sort orders)
- **Group options:** flat / grouped by color / grouped by source-type /
  by folder / custom user tags on stations
- Long-press tile → quick actions (Play, Edit, Duplicate, Delete, Pin to
  top, Add to Drive Mode quick-list, Add to Widget)
- Top bar: search (FTS5 across stations + tracks + artists + albums),
  Drive Mode / CarPlay indicator, Cast button, Settings
- Search results show: matching stations, artists, albums, tracks, with
  "Create station from this artist" / "from this album" CTAs

### 7.2 Now Playing

- Full-screen album art (or station art if user prefers)
- Track / artist / album / station name
- Progress bar with scrubbing (and a "snap to beat" option for power
  users using BPM tags)
- Controls: play/pause, prev, next, shuffle-toggle, EQ shortcut, AI DJ
  toggle, queue button, Cast button
- Gesture: swipe down → minimize, swipe left/right → next/prev, pinch →
  switch to lyrics view, long-press art → "What is this track?" (full
  metadata)
- Tap art → flip to back: full tag dump (read), edit tags, view file
  path *(power user only)*, view ReplayGain values, view audio
  format/bitrate
- Lyrics view: synced LRC karaoke if available, plain text fallback,
  "Fetch lyrics" button if online enabled
- Star rating: 1–5 with 0.5 increments (long-press star), or thumbs
  up/down (toggle in settings)
- "Don't play on this station again" button (tucked behind a dislike
  menu)
- Three-pane layout on Large/XLarge screens: Now Playing (center) +
  Queue (right) + Lyrics (left)

### 7.3 In-car experiences

#### Drive Mode (Android only)

- Activated automatically by: speed sensor if available, BT car connect,
  headlight-on signal, manual lock, or scheduled (e.g. 7–9 AM weekdays)
- Layout auto-scales to screen: 2×2 or 1×3 grid of huge buttons,
  ≥120dp tap targets, high-contrast theme, expands to fill any aspect
  ratio
- Buttons (configurable, choose any 4–6):
  - Play/Pause
  - Next track
  - Previous track
  - Switch station (opens 6-tile big-button station picker, configurable
    contents)
  - Volume up / Volume down
  - Star this track
  - Dislike this track
  - Switch to last station
  - Voice: "Hey RadioShed…"
- Always-visible: track title (huge), artist (large), station name
  (medium), big elapsed/remaining time
- Optional: speedometer-style HUD showing what's coming next

#### CarPlay (iOS only)

iOS prohibits custom in-car UIs; Apple supplies templates. RadioShed
ships as a CarPlay **audio app** using the standard templates:

- `CPListTemplate` — station list (with star/play-count badges)
- `CPNowPlayingTemplate` — Apple's now-playing screen, customized with
  our buttons (rate, dislike, switch station)
- `CPGridTemplate` — quick-access "favourites" grid (the iOS analog of
  Drive Mode quick-list)
- `CPTabBarTemplate` — Stations / Recents / Search
- Voice: rely on Siri Shortcuts (e.g. "Hey Siri, play Latest Hits on
  RadioShed") — exposed as `INPlayMediaIntent`s
- Steering-wheel buttons handled by CarPlay automatically
- Requires the **CarPlay Audio entitlement** from Apple (granted on
  request for music apps)

### 7.4 Library / folder management

One row per registered folder/source: path or display name, recursive
flag, included track count, last scan time, status (mounted/missing).

- **(Android)** Native folder paths, SD-card aware (warns when missing)
- **(iOS)** Display name + provider (iCloud Drive / On My iPhone / SMB /
  external). Bookmarks restored on launch via security-scoped URLs.
- Add folder, remove folder (asks: keep history & ratings for orphaned
  tracks? yes/no)
- "Force re-scan now" / "Pause scanning"
- Per-folder include/exclude rules (path globs, format filters)
- Conflict handling: when SD card / volume is reinserted, tracks
  reconcile silently; warning shown if folder is missing for >7 days
- **Optional file-mode browser** (toggle in Advanced Settings): exposes
  a hierarchical filesystem-style view of registered folders for power
  users who want it. Disabled by default — preserves the
  "stations-not-files" north-star principle for everyone else.

### 7.5 Settings (the power-user love letter)

Top-level groups:

1. **Library** — folders, scan schedule, auto-scan on volume insert,
   format support, global exclude patterns, duplicate detection (by
   hash, fuzzy by tag), missing-art handling, opt-in file-mode browser
2. **Stations** — defaults for new stations, default playback rule,
   default sort/group
3. **Audio** — EQ (graphic up to 31 bands + parametric peaks/notches),
   AutoEQ headphone profile manager + APO config import, **per-output-
   device EQ profiles** (auto-switch when output route changes),
   ReplayGain mode + pre-amp, normalization (LUFS target), crossfade
   default + Smart Fades toggle, gapless toggle, output device picker,
   audio buffer size, sample rate matching, mono summing, channel
   balance, DSP order
4. **Playback** — smart-shuffle params (defaults), skip-on-dislike
   behavior, repeat, sleep timer (X min / end of station / end of
   track) **with optional fade-out**, resume policy on app start (last
   station / silence / specific station)
5. **AI DJ** — global enable, voice (system voices + custom), speech
   rate, pitch, frequency defaults, template editor (with variables
   `{station}`, `{track}`, `{artist}`, `{next_track}`, `{time}`,
   `{weather}` if online), pre-cache size, ducking volume during speech
6. **Online & Sync** — Connectivity policy (Always / WiFi only / Never /
   Manual), per-service toggles (MusicBrainz, Cover Art Archive, LRCLib,
   Last.fm, ListenBrainz, AcoustID, Wikipedia, fanart.tv), API keys,
   sync frequency, fallback behavior
7. **UI / Theme** — light/dark/AMOLED-black/auto, accent color, **Material
   You dynamic color** (Android 12+), station-tile defaults, font size
   (XS–XXL), reduce motion, show/hide power-user UI elements, language
8. **Cast & output** — manage paired Cast/UPnP/AirPlay/Sonos targets,
   transcoding settings per target, gapless-on-cast toggle, auto-cast
   rules (when device detected on network)
9. **In-Car**
   - **(Android)** Drive Mode triggers, layout, button mappings, voice
     command language, wake word (off / "Hey RadioShed" / custom), VAD
     sensitivity, auto-exit conditions, big-button station picker
     contents
   - **(iOS)** CarPlay tab order, favourites grid, Siri Shortcut
     phrases, now-playing customization
10. **Hardware** — steering-wheel button mappings (Android: capture-
    and-bind UI), media-key behavior, Bluetooth auto-connect,
    headphone/jack auto-resume, behavior on call interruption, behavior
    on car-start
11. **Tags & Metadata** — embedded vs sidecar priority, custom tag
    definitions, **multi-value tag separators** (configurable; default
    `;`, `/`, `,`, `\`), composer-tag handling, batch tag editor,
    missing-tag fixer (online lookup if enabled), AcoustID
    fingerprinting on/off, file rename templates *(power user)*,
    cue-file split behavior
12. **Widgets** *(post-onboarding hint screen)* — configure home-screen
    widgets: now-playing, station-launcher, library stats
13. **Privacy** — history retention (forever / N days / off), scrobble
    consent per service. No analytics opt-out exists because there is
    no analytics — all data stays local.
14. **Backup & Restore** — auto-backup interval (off/daily/weekly),
    backup location (any registered folder), include/exclude (settings,
    stations, ratings, history, custom artwork), encryption with
    passphrase, restore wizard with conflict resolution
15. **Advanced** — log level, log export, force full re-scan, clear
    caches (art / lyrics / DJ TTS / DB query cache), DB vacuum,
    experiment flags, "Developer" panel (DB browser, raw SQL console
    for the truly brave)
16. **About** — version, build, third-party licenses, "Stations
    summary" (count of stations, total tracks across them, total
    listening time)

### 7.6 Home-screen widgets

- **(Android)** Built with Glance API (Jetpack):
  - **Now Playing widget** — adaptive, resizable, round + standard +
    Material You-compliant variants. Shows art + title + transport
    controls.
  - **Station launcher widget** — pin 1, 4, 6, or 12 stations to the
    home screen for one-tap launch.
  - **Library stats widget** — track count, listening time this week,
    most-played station.
- **(iOS)** Built with WidgetKit:
  - **Now Playing widget** (small / medium / large / extra large /
    Lock-screen)
  - **Station launcher widget** (small grid of 1, medium 4, large 8)
  - **Library stats widget**
  - **Live Activity** for Now Playing on Lock Screen + Dynamic Island

---

## 8. Audio pipeline

```
File → Decoder
     → ReplayGain pre-gain (track or album mode)
     → Crossfade / Smart-Fade mixer (when transitioning)
     → DJ duck mixer (lowers music when TTS playing)
     → EQ chain: graphic (up to 31 bands) + parametric peaks/notches
     → Per-output EQ profile (loaded based on current route)
     → Normalization limiter (optional, soft-knee, LUFS target)
     → Output → AudioTrack (Android) / AVAudioOutputNode (iOS)
                or → Cast/UPnP/AirPlay/Sonos renderer
```

- **Android:** ExoPlayer's `ConcatenatingMediaSource` provides true
  sample-accurate gapless via decoder pre-roll; `DefaultRenderersFactory`
  for software fallback; FFmpeg extension bundled for WMA/APE/DSD/cue.
- **iOS:** `AVAudioEngine` graph with `AVAudioPlayerNode` (×2 for
  crossfade), chained `AVAudioUnitEQ` instances (multiple to exceed the
  per-instance band limit), `AVAudioMixerNode`. Gapless via scheduling
  the next buffer pre-EOF on the second player node. FFmpeg via
  `mobile-ffmpeg` for non-native codecs (WMA, APE, WV, DSD, cue).
- **Crossfade curves:** equal-power (default), linear, S-curve, custom.
- **Smart Fades:** content-aware crossfade. Analyzes the last N seconds
  of the outgoing track and the first N seconds of the incoming track to
  detect natural endings (fade-outs, hard stops, silence) and align the
  fade boundaries accordingly. When a song fades out naturally, no
  crossfade is applied; when both tracks have hard endings/starts, a
  full crossfade is used.
- **ReplayGain:** read from tags (MP3 ID3v2 `TXXX:REPLAYGAIN_*` / FLAC
  Vorbis comments / Opus header gain). Background per-track LUFS scan
  for tag-less files (toggleable).
- **EQ:**
  - Graphic EQ: 10 / 15 / 31-band selectable
  - Parametric EQ: arbitrary peaks/notches (Q, gain, frequency)
  - **AutoEQ import:** drop in AutoEQ project profiles for popular
    headphones (1000+ models) — automatically configures parametric EQ
    to compensate for the headphone's frequency response
  - **Equalizer APO config import:** parse `.txt` config files from the
    Windows EAPO ecosystem
  - **Per-output-device EQ profile:** the app remembers the last EQ
    profile used per output (car speakers, headphones X, headphones Y,
    Bluetooth Z) and auto-switches when the route changes. User can
    bind explicitly via Settings → Audio → Per-output profiles.
- **AcoustID fingerprinting** (online) for files with no usable tags.

---

## 9. AI DJ subsystem

- **Engine:**
  - **(Android)** `android.speech.tts.TextToSpeech` (offline,
    zero-internet works); pluggable cloud (ElevenLabs / Coqui /
    Piper-local-model) for power users.
  - **(iOS)** `AVSpeechSynthesizer` for offline; same pluggable cloud
    options.
- **Templates** (editable; user can write their own with variables):
  - Station ID: *"You're tuned to RadioShed — {station}."*
  - Up-next: *"Coming up: {next_track} by {next_artist}."*
  - Recap: *"That was {track} by {artist}. Coming up next…"*
  - Time/weather (if online): *"It's 7:42 in the morning. Here's
    {next_track}."*
  - Custom: any string with `{var}` substitutions, plus a Markov-style
    randomizer of phrasings to keep it from sounding identical every
    time.
- **Caching:** TTS audio for upcoming tracks is generated 1–2 tracks
  ahead during idle CPU; cached on disk keyed by `(template_hash,
  voice_id, variables)` to avoid runtime hitches.
- **Ducking:** music dips by configurable dB during DJ speech; speech
  is timed to land in the intro silence of the next track when possible.
- **Frequency control per station:** off / every track / every N tracks
  / every X minutes / station-change only / quiet hours (e.g. silent
  10pm–6am).
- **Power-user mode:** swap in custom voice models, write template hooks
  (sandboxed), chain multiple templates ("ID + recap + up-next").

---

## 10. Online integrations (all opt-in, all gated by Connectivity policy)

| Feature                | Service                                                | Fallback if offline                              |
| ---------------------- | ------------------------------------------------------ | ------------------------------------------------ |
| Album/artist art       | Cover Art Archive (MusicBrainz)                        | Embedded art → folder.jpg → auto-generated tile  |
| Artist images (HQ)     | fanart.tv                                              | None                                             |
| Metadata fix           | MusicBrainz                                            | Embedded tags only                               |
| Fingerprint ID         | AcoustID + Chromaprint                                 | "Unknown track" with file name                   |
| Lyrics (synced)        | LRCLib                                                 | Embedded `.lrc` sidecar                          |
| Lyrics (plain)         | LRCLib / Genius (if user adds key)                     | Embedded                                         |
| Scrobbling             | Last.fm + ListenBrainz                                 | Local play history only                          |
| Recommendations        | Last.fm similar artists (informs Discover/Smart Mix)   | Pure under-played weighting                      |
| Artist bio             | Wikipedia/Wikidata                                     | Hidden                                           |
| TTS voices (premium)   | ElevenLabs etc. (BYO API key)                          | System TTS                                       |
| Time/weather DJ        | Open-Meteo + system clock                              | Time only                                        |

All requests batched, cached aggressively, respect rate limits, fail
silently. A single "sync now" button forces a refresh while connected.

---

## 11. Hardware integration & output

### 11.1 Android hardware

- **Steering-wheel keys** routed via `MediaSession` from standard
  `KEYCODE_MEDIA_*` events. A "Capture binding" UI lets the user press
  unmapped buttons and assign actions.
- **Bluetooth AVRCP** metadata (title/artist/album/art) so the car's
  center console shows it natively.
- **Headlight / speed signals:** AutoLinkR Elite may expose these via
  Android sensors / vehicle data API; detect at runtime and surface in
  Drive Mode triggers if available, gracefully hide if not.
- **Auto-resume on car-start:** detect `BOOT_COMPLETED` +
  `BluetoothDevice.ACTION_ACL_CONNECTED` for a registered car BT MAC and
  auto-launch with last station.

### 11.2 iOS hardware

- **CarPlay** handles steering-wheel keys, knobs, and screen rendering
  automatically once the entitlement is granted.
- **Bluetooth AVRCP** metadata via `MPNowPlayingInfoCenter`.
- **Lock screen / control center** via `MPRemoteCommandCenter`.
- **Auto-resume** by registering as a Bluetooth audio source; iOS
  handles the wake-and-resume.

### 11.3 Cast & multi-room output

RadioShed can route audio to networked devices instead of (or in
addition to) the local speakers/headphones. All cast targets appear in
a unified picker (Now Playing → Cast button).

| Target type        | Android          | iOS                                                      | Notes                                                          |
| ------------------ | ---------------- | -------------------------------------------------------- | -------------------------------------------------------------- |
| Chromecast         | Google Cast SDK  | ⚠️ via 3rd-party lib (`gcast-ios` or AirPlay alternative) | Apple discourages Cast on iOS; AirPlay is the native answer    |
| AirPlay 2          | n/a              | `AVRoutePickerView` + native                              | iOS's preferred multi-room                                     |
| UPnP / DLNA renderer | Cling           | libupnp bridge                                            | Works for any DLNA-compatible speaker / amp                    |
| Sonos              | SOAP API         | SOAP API                                                  | Supports group/ungroup + per-speaker volume                    |
| Plex / PlexAmp clients | Plex API     | Plex API                                                  | Stream to any Plex client on the network                       |
| Kodi               | Kodi JSON-RPC    | Kodi JSON-RPC                                             |                                                                |

**Capabilities:**

- **Transcoding** when the renderer doesn't support the source format
  (e.g., DSD → FLAC for Chromecast)
- **Adjustable bitrate** for cast streams (preserves bandwidth on slow
  networks)
- **Gapless on cast** when the renderer supports it (UPnP gapless,
  Chromecast multi-channel)
- **Synchronized multi-room:** group multiple Sonos / AirPlay targets
  for whole-home audio
- **Volume control** per target, independent of device output
- **Auto-cast rules:** "When my Sonos kitchen speaker is on the network,
  always play there" — toggleable per target

---

## 12. Library scanning & file management

- **Initial scan:** background job (WorkManager on Android, BGTask on
  iOS); reports progress to UI; non-blocking.
- **Incremental:**
  - **(Android)** `FileObserver` on registered folders + mtime polling
    fallback for SD-card SAF paths.
  - **(iOS)** `NSFilePresenter` / `NSFileCoordinator` notifications for
    Documents, plus periodic mtime polling for iCloud Drive folders.
- **Volume eject/insert:**
  - **(Android)** `ACTION_MEDIA_MOUNTED/EJECTED` → mark tracks
    "offline" rather than deleting; reconcile on remount via folder
    UUID + path hash.
  - **(iOS)** Bookmark resolution failure on launch → mark tracks
    offline, auto-reconcile when bookmark resolves again.
- **Hash-based dedup** (optional): MD5 of audio stream (not full file)
  to detect dupes across folders; user can pick which to keep.
- **Format coverage:** see §3.
- **Cue file support:** when a `.cue` sidecar is present beside a
  single-file album rip (typical FLAC + cue), parse cue, expose virtual
  per-track entries in the library, set proper start/end offsets in
  the audio engine.
- **Tag readers:** jaudiotagger (Android) / `AVAsset` metadata + custom
  ID3/FLAC parsers (iOS) — both wrapped behind a shared `TagReader`
  interface in KMP. Read embedded `LYRICS` / `USLT` / `SYLT`,
  ReplayGain, BPM, ISRC, custom `TXXX` fields, **composer**, **album
  artist**, **conductor**, **performer**.
- **Multi-value tag handling:** many fields (artist, genre, composer)
  can hold multiple values. The parser supports configurable
  separators (default `;`, `/`, `,`, `\` plus the V2.4 multi-value
  null-byte form). User can override the separator set per library.
- **Custom tag definitions:** user can register additional `TXXX`
  field names as first-class searchable tags (e.g. `MOOD`, `OCCASION`,
  `DJ_RATING`).

---

## 13. Permissions

### Android 13

| Permission                                                              | Purpose                              | Required?                             |
| ----------------------------------------------------------------------- | ------------------------------------ | ------------------------------------- |
| `MANAGE_EXTERNAL_STORAGE`                                               | Fast scan of huge libraries          | Preferred (sideloaded direct download)|
| `READ_MEDIA_AUDIO`                                                      | Fallback when MEMS not granted       | Yes                                   |
| `POST_NOTIFICATIONS`                                                    | Playback notification, scan progress | Yes                                   |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`              | Background playback                  | Yes                                   |
| `WAKE_LOCK`                                                             | Uninterrupted playback               | Yes                                   |
| `INTERNET` + `ACCESS_NETWORK_STATE`                                     | Online integrations + casting        | Yes                                   |
| `ACCESS_WIFI_STATE` + `CHANGE_WIFI_MULTICAST_STATE`                     | UPnP / Sonos / Cast discovery        | Yes                                   |
| `BLUETOOTH_CONNECT`                                                     | Steering-wheel key routing           | Yes                                   |
| `RECORD_AUDIO`                                                          | Voice control wake-word              | Optional                              |

### iOS 16+

Capabilities & entitlements:

- `Background Modes: Audio, AirPlay, and Picture in Picture`
- `CarPlay (Audio app)` — request from Apple
- `Network: Outgoing connections (client)` + Bonjour / multicast for
  UPnP / Sonos / AirPlay discovery
- `App Sandbox: User-Selected File (Read/Write)` for document picker
  bookmarks
- `Microphone Usage` — only if voice control implemented
- `Speech Recognition Usage` — same
- `Documents folder visible in Files app` —
  `LSSupportsOpeningDocumentsInPlace` + `UIFileSharingEnabled` (so users
  can drop music into the app's sandbox via Files)
- `WidgetKit` extension entitlement
- `App Group` (for sharing data with the WidgetKit extension)

---

## 14. Backup / restore / portability

- One-tap export to `radioshed-backup-YYYYMMDD.zip` containing JSON
  dumps + custom artwork + DJ template files + EQ presets + per-output
  EQ bindings.
- Optional passphrase encryption (AES-256-GCM).
- Restore wizard: pick zip, see what's inside, conflict resolution
  (merge / overwrite / keep mine / per-station selection).
- Auto-backup to a registered folder on schedule.
- All user data is human-readable JSON — even though the binary is
  proprietary, the user's data is never trapped.
- **Cross-platform portable**: a backup made on Android restores cleanly
  on iOS and vice-versa; only platform-specific paths (folder bookmarks)
  require re-pointing on restore.

---

## 15. Search

- Backed by SQLite FTS5 (via SQLDelight) with rank tuning.
- Searches: stations, tracks, artists, albums, genres, custom tags,
  lyrics (if cached).
- Filters via DSL in the search bar:
  `artist:Hendrix year:>1968 genre:rock`. Power-user feature, hidden
  behind a "?" hint.
- Voice search:
  - **(Android)** in Drive Mode via wake word + on-device STT.
  - **(iOS)** Siri Shortcuts mapped to common queries.

---

## 16. Modular sources roadmap (post-v1)

The `MediaSource` interface in v1:

```kotlin
interface MediaSource {
    suspend fun listTracks(filter: SourceFilter): Flow<Track>
    suspend fun fetchStream(track: Track): MediaItem
    suspend fun fetchArtwork(track: Track): ByteArray?
    val capabilities: SourceCapabilities  // search? streaming? playlists?
}
```

`LocalFileSource` ships in v1 (with platform-specific impls). Subsequent
plug-ins (each its own toggleable module):

**Self-hosted servers**
- `JellyfinSource` (well-documented REST API, no auth fuss)
- `SubsonicSource` (covers Navidrome, Airsonic, OpenSubsonic)
- `PlexSource` (PlexAPI)
- `EmbySource`
- `KodiSource` (JSON-RPC)
- `AudiobookshelfSource`

**Network filesystems**
- `DLNASource` (Cling on Android, libupnp/bridge on iOS)
- `SMBSource` (SMBJ on Android, SMBClient on iOS)
- `WebDAVSource`

**Cloud storage providers**
- `GoogleDriveSource`
- `OneDriveSource`
- `DropboxSource`
- `BoxSource`
- `pCloudSource`

**Streaming**
- `IcecastSource` (internet radio for hybrid stations — "my Folk
  station + intermittent BBC 6 Music")

**Streaming-source common features:**
- Manual + automatic media caching (local copy)
- Playback cache (forward read-ahead) to survive connection drops
- Adjustable bitrate per target

Stations don't care which source(s) the tracks come from — a station
can pull from local + Jellyfin + Subsonic + Google Drive simultaneously.

---

## 17. Data model (SQLDelight, shared)

Tables: `folders`, `tracks`, `artists`, `albums`, `track_artists`,
`track_tags`, `track_personnel` (composer, conductor, performer),
`stations`, `station_history`, `ratings`, `skip_log`, `play_count`,
`playlists`, `playlist_tracks`, `artwork_cache`, `lyrics_cache`,
`dj_cache`, `eq_presets`, `eq_output_bindings`, `dj_voices`, `cast_targets`,
`widget_configs`, `backups`. FTS5 virtual tables on tracks/artists/
albums/stations/lyrics. WAL mode + checkpoint on quit.

Schema lives in `composeApp/src/commonMain/sqldelight/` and is
identical on both platforms. Migrations are versioned.

---

## 18. Cross-platform engineering plan

### 18.1 What's shared (commonMain)

- All domain models (`Track`, `Station`, `SourceRule`, `PlaybackRule`)
- Station evaluation engine (smart shuffle, no-repeat, weighting)
- Database layer (SQLDelight)
- Online integration clients (Ktor HTTP)
- Cast/UPnP/Sonos coordination logic (sans the platform discovery APIs)
- Settings store
- DJ template engine (string + variables; TTS dispatch is
  platform-specific)
- Scanner coordinator (file iteration is platform-specific via
  `expect/actual`)
- Tag reading abstraction (impls are platform-specific)
- Cue file parser
- Smart Fades content analysis (DSP shared; rendering platform-specific)
- Most of the UI (Compose Multiplatform): Stations Home, Now Playing,
  Settings, Library, Onboarding

### 18.2 What's platform-specific

- Audio engine (ExoPlayer vs AVAudioEngine)
- File enumeration & permissions
- Foreground audio service / `AVAudioSession` setup
- Lock-screen / now-playing metadata
- TTS engine binding
- Drive Mode (Android) vs CarPlay scenes (iOS)
- Bluetooth / hardware-key event sources
- Cast/AirPlay native APIs
- Widget hosts (Glance vs WidgetKit)
- App entry point + lifecycle plumbing

### 18.3 Testing strategy

- Pure-Kotlin unit tests in `commonTest` for all business logic
- Per-platform integration tests for audio engines (golden-file output
  comparison for crossfade, gapless, EQ correctness)
- Snapshot tests for shared Compose UI on both platforms across the
  responsive-design CI matrix (§1.1)
- Library stress test fixture: synthetic 50k-track corpus generated at
  test time

### 18.4 Distribution

- **Android:** signed APK + AAB. Distribution channels:
  - **Authorized direct-download portal** (gated, license-key
    protected) — primary channel for proprietary distribution
  - Optional **Play Store** flavor (separate compiled flavor that swaps
    `MANAGE_EXTERNAL_STORAGE` for SAF-only)
  - **No F-Droid** (license-incompatible — F-Droid requires FOSS)
  - **No AltStore / sideload mirrors**
- **iOS:** App Store only.
  - TestFlight for beta
  - **No AltStore / community channels**
- All third-party / open-source dependencies retain their own licenses;
  RadioShed complies with their terms (FFmpeg LGPL → dynamic linking
  where required, attribution in About → Licenses, etc.)

---

## 19. Out of scope for v1 (but designed-for / post-v1)

- Multi-user profiles (single user assumed; profiles can be a
  station-group filter later)
- Wear OS / Apple Watch companion app (post-v1)
- Android TV / tvOS app (post-v1; can reuse shared module)
- Podcasts (different metadata model — best as a separate sibling app
  reusing the audio pipeline)
- Audiobooks (resume-points, pitch, skip-silence — all deferred)
- Video
- Cloud sync between devices ("RadioShed Sync" optional service —
  post-v1)
- Full-text lyrics search across the whole library (FTS index is built
  but query UI deferred)
- Apple Music / Spotify import (separate import-only utility, not core)
- Tasker integration (Android automation power-user — post-v1)
- Visualizer (post-v1)

---

## 20. Phased build plan

Each phase produces a working, demoable artifact. Tasks are checkboxes
(`- [ ]` open, `- [x]` done) so progress is trackable in this file
directly. Phase durations assume a single full-time developer; adjust
for reality.

### Phase 0 — Foundation & decisions (~1 week)

Goal: repo exists, decisions documented, CI skeleton green.

- [x] Create **private** GitHub repository with branch protection on `master`
- [x] Draft proprietary `LICENSE` file: no redistribution, no resale, no public sharing, no mirroring; reserve all rights; private use of authorized binaries only (lawyer review still pending before public release)
- [x] Add `.editorconfig`, `.gitignore`, `.gitattributes`
- [ ] Add `CONTRIBUTING.md` (private team contribution guide)
- [x] Create `docs/decisions/` with ADR template (`0000-template.md`)
- [ ] Document iOS build host strategy in `docs/decisions/0001-ios-build-host.md` (cloud / self-hosted Mac / buy Mac mini)
- [x] Create `gradle/libs.versions.toml` with curated dependency versions (KMP wizard generated; customize as needed)
- [x] Create `version.properties` (single app-version source)
- [ ] Add ktlint, detekt, swiftformat, swiftlint configs (deferred until Kotlin/Swift code exists)
- [ ] Set up `pre-commit` hooks (deferred until lint configs exist)
- [x] GitHub Actions: Android Linux job skeleton (lint + smoke build)
- [x] GitHub Actions: iOS macOS job skeleton (placeholder build, mindful of macOS minute cost on private repo)
- [x] Verify Android 13 SDK on the dev machine (see `README.md`)
- [x] Move `android13-smoketest/` aside or delete; toolchain check served its purpose

### Phase 1 — KMP bootstrap & "hello world" on both platforms (~1–2 weeks)

Goal: a single-screen app from this repo builds and runs on Android emulator and iOS simulator.

- [x] Create unified `composeApp/` Kotlin Multiplatform module *(wizard generated; replaces planned `shared/` + `androidApp/` split — see §2.3 note)*
- [x] Configure source sets: `commonMain`, `androidMain`, `iosMain`, `commonTest`
- [x] Configure iOS targets: `iosArm64`, `iosSimulatorArm64` *(wizard skipped `iosX64`; Apple has retired x86 simulators)*
- [x] Add Compose Multiplatform plugin + Material 3 dependency
- [x] Configure `composeApp/` Android settings: `compileSdk=36`, `targetSdk=36`, `minSdk=30`, `jvmTarget=11` *(wizard's choices; SPEC versions previously read 34/33/26/17 and have been updated to match)*
- [x] Create `iosApp/` Xcode project (SwiftUI shell hosting CMP `UIViewController`)
- [ ] Configure shared framework export: SPM (preferred) or CocoaPods (fallback) *(wizard chose direct framework; bump to SPM later)*
- [x] Wire up Koin DI scaffolding in `commonMain` *(Modules.kt + per-platform PlatformModule; RadioShedApp.kt on Android, doInitKoin from iOSApp.swift)*
- [ ] Implement responsive `WindowSizeClass`-aware shared layout scaffolding *(LandingScreen uses `BoxWithConstraints`; full WindowSizeClass scaffolding pending Stations Home in Phase 6)*
- [x] Implement one shared Compose screen rendering on both platforms *(branded RadioShed shell — `App.kt` → `RadioShedTheme` → `LandingScreen`)*
- [x] CI: Android `assembleDebug` succeeds on Linux runner
- [ ] CI: iOS framework builds + `xcodebuild` builds `iosApp` on macOS runner *(verifying after destination fix)*
- [ ] CI: snapshot-test matrix across §1.1 form factors
- [ ] **Decision gate:** is CMP-on-iOS performance and UX acceptable? Record outcome in `docs/decisions/0002-ui-stack.md`
- [ ] If fallback chosen: scaffold SwiftUI app shell in `iosApp/`, document UI duplication strategy

### Phase 2 — Database & domain models (~1 week)

Goal: schema set up, domain models compile, repositories pass basic CRUD tests.

- [x] Add SQLDelight to shared module (Android + Native drivers; sqlite-3.38 dialect for `RETURNING` + UPSERT)
- [x] Schema: `tracks`, `folders`, `artists`, `albums`, `track_artists`, `track_tags`, `track_personnel`
- [x] Schema: `stations`, `playlists`, `playlist_tracks`, `station_history`
- [x] Schema: `ratings`, `skip_log`, `play_count`, `station_dislike`
- [x] Schema: `artwork_cache`, `lyrics_cache`, `dj_cache`
- [x] Schema: `eq_presets`, `eq_output_bindings`, `dj_voices`, `cast_targets`, `widget_configs`, `backups`
- [ ] FTS5 virtual tables: `tracks_fts`, `artists_fts`, `albums_fts`, `stations_fts`, `lyrics_fts` *(deferred to Phase 3 — search UI; needs FTS5 dialect verification on iOS Native)*
- [ ] Migration directory + migration test harness *(deferred until we have an actual migration to verify; baseline schema only for now)*
- [x] Domain models: `Track`, `Station`, `SourceRule`, `PlaybackRule`, `Folder`, `Artist`, `Album` (+ `Playlist`, `Rating`, `Tag`, `Personnel`, `EqPreset`, `EqBand`, `EqOutputBinding`, `CastTarget`, `DjVoice`, `ConnectivityPolicy`, `SleepTimer`)
- [x] Repository implementations: `FolderRepository`, `LibraryRepository`, `StationRepository`, `PlaylistRepository`, `RatingRepository`, `ArtworkCacheRepository`, `LyricsCacheRepository`, `DjCacheRepository`, `AudioSettingsRepository`, `CastTargetRepository`, `WidgetConfigRepository`, `BackupRepository`
- [x] Settings store via `multiplatform-settings` (`SettingsStore` with theme mode, connectivity policy, onboarding state, last station, file-mode browser opt-in)
- [x] Unit tests for all domain models in `commonTest` (`SourceRuleSerializationTest`, `PlaybackRuleSerializationTest`, `RatingTest`)
- [ ] Integration tests for repositories on Android + iOS *(deferred — requires DB-backed test harness; pure-domain tests cover model/serialization correctness for now)*

### Phase 3 — Library & scanner (~2 weeks)

Goal: user can pick folders, app scans and indexes them, search works.

- [x] `MediaSource` interface in `commonMain` (+ `DiscoveredTrack`, `SourceFilter`, `SourceCapabilities`)
- [x] `LocalFileSource` (commonMain class wrapping platform-specific `FileEnumerator`)
- [x] **(Android)** File enumeration via `MANAGE_EXTERNAL_STORAGE` (`AndroidFileEnumerator` + glob matcher)
- [ ] **(Android)** SAF tree-URI fallback when MEMS not granted *(deferred to Phase 3 part 2)*
- [ ] **(iOS)** Document picker integration (`UIDocumentPickerViewController`) *(stub for now; needs Mac access)*
- [ ] **(iOS)** Security-scoped bookmark persistence + restoration on launch *(deferred)*
- [ ] **(iOS)** iCloud Drive on-demand download via `NSFileCoordinator` *(deferred)*
- [x] `TagReader` interface in `commonMain` (+ `ParsedTags` with composer/personnel/multi-value support)
- [x] **(Android)** jaudiotagger-android impl (composer, conductor, producer, arranger, lyricist, remixer, multi-value artist/genre/mood/language/label, MusicBrainz IDs)
- [ ] **(iOS)** AVAsset metadata + custom ID3/FLAC/Vorbis parsers *(stub; needs Mac access)*
- [x] Cue-file parser in `commonMain` (+ 9 unit tests covering quoted/unquoted strings, multi-FILE cues, INDEX 00 pregap, malformed lines, frame-to-ms conversion)
- [x] Scanner coordinator: queue, batching, progress reporting via `ScanProgress` flow (Started/Progress/Warning/Completed/Failed)
- [x] Folder management UI (add/rename/recursive-toggle/delete) — `ui/library/LibrarySettingsScreen.kt`. Excludes/include globs UI deferred to a later pass.
- [ ] Optional file-mode browser (power-user toggle, off by default) *(still deferred)*
- [x] Onboarding steps 1–5 — `ui/onboarding/OnboardingScreen.kt`: Welcome → Permissions → Folder → Scan → Done
- [x] Search UI — `ui/search/SearchScreen.kt`. *Backing is `LIKE` not FTS5: SQLDelight 2.1's parser crashes on FTS5 `MATCH` queries (and on virtual-table compound `name { ... }` blocks). Acceptable for libraries up to ~10k tracks. Revisit when SQLDelight 3.x lands or we evaluate alternatives.*
- [ ] Search DSL parser (`artist:Hendrix year:>1968 genre:rock`) *(deferred — plain full-text first)*
- [ ] Stress test: index 50k synthetic tracks within 5 min on mid-tier hardware *(deferred to Phase 4+ verification)*

Also added in Phase 3 part 2:

- [x] Navigation: lightweight sealed-class state machine (`nav/AppScreen.kt`) — Onboarding / Home / Library / Search. Avoids a full nav library while the surface is small.
- [x] Post-onboarding `HomeScreen` replacing `LandingScreen`. Wordmark + 3 nav cards (Stations placeholder, Library, Search) + version footer.
- [x] `SearchRepository` with case-insensitive substring matching across tracks/artists/albums; LIKE patterns properly escape `%`, `_`, `\`.

### Phase 4 — Audio engine (~2 weeks)

Goal: tracks play, pause, skip; lock-screen controls work; Bluetooth metadata appears.

- [ ] `AudioEngine` interface in `commonMain`
- [ ] **(Android)** ExoPlayer-based AudioEngine impl
- [ ] **(iOS)** AVAudioEngine-based AudioEngine impl
- [ ] **(Android)** `MediaSessionService` + foreground notification
- [ ] **(iOS)** `AVAudioSession` configured for `.playback`, `mixWithOthers = false`
- [ ] **(iOS)** `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter` wiring
- [ ] Lock-screen / notification controls (play, pause, prev, next, scrub)
- [ ] Bluetooth AVRCP metadata propagation
- [ ] Audio focus / interruption handling (call, alarm, route change)
- [ ] Cue-aware playback (start/end offsets within a single audio file)
- [ ] Format coverage smoke tests: MP3, AAC, FLAC, OGG, Opus, WAV, ALAC
- [ ] Bundle FFmpeg for non-native formats: WMA, APE, WV, DSD
- [ ] Verify contiguous decoding (gapless polish lands in Phase 7)

### Phase 5 — Stations engine (~2 weeks)

Goal: user can create stations; engine plays tracks per source + playback rules.

- [ ] `SourceRule` evaluator: folder, tag filter, smart query, playlist, artist, album, duration, format, path-glob
- [ ] Set operators: `Union`, `Intersection`, `Difference`, `TopN`
- [ ] `PlaybackRule` engine: shuffle, sequential, weighted random, smart shuffle
- [ ] No-repeat memory + artist/album spacing
- [ ] Weighting policies: uniform, under-played, higher-rated, recent, custom
- [ ] Adaptive Mix smart query (learns from listening history)
- [ ] Auto-rotate refresh job for SmartQueries (e.g. Latest Hits re-eval daily)
- [ ] Built-in presets registry (10 presets per §5, including Smart Mix)
- [ ] Station editor UI: identity, source builder, playback rules
- [ ] Drag-to-reorder station list
- [ ] Station duplicate / delete / rename
- [ ] Auto-collage station artwork (4-album-cover grid)
- [ ] Custom artwork upload per station
- [ ] Onboarding steps 5–6 (taste setup + first custom station)

### Phase 6 — Now Playing & core UI (~2 weeks)

Goal: app feels like a music app, not a tech demo. Looks great on every form factor.

- [ ] Stations Home: grid + list views, sort/group menus, tile size XS–L, fluid reflow
- [ ] Long-press tile → quick actions (play, edit, duplicate, delete, pin, add to widget)
- [ ] Now Playing screen: full-screen art, scrubber, controls
- [ ] Now Playing: 3 layout variants for compact / medium / expanded / large+
- [ ] Album-art aspect-ratio handling (square crop + blurred letterbox fill)
- [ ] Gesture handling: swipe, pinch, long-press
- [ ] Lyrics view: synced LRC karaoke + plain text fallback
- [ ] Star rating UI (1–5 with 0.5 increments) + thumbs up/down toggle
- [ ] Skip tracking instrumentation
- [ ] "Don't play on this station again" dislike menu
- [ ] Onboarding steps 7–9 (in-car preview, AI DJ intro, done)
- [ ] Theme system: light, dark, AMOLED-black, accent color
- [ ] Material You dynamic color (Android 12+) opt-in
- [ ] Font scaling (XS–XXL)
- [ ] Reduce-motion mode
- [ ] Accessibility audit: TalkBack on Android, VoiceOver on iOS
- [ ] CI snapshot-test pass across §1.1 form factor matrix

### Phase 7 — Audio pipeline polish (~1–2 weeks)

Goal: power-user audio features work and sound right on both platforms.

- [ ] Crossfade impl on both engines (equal-power, linear, S-curve, custom)
- [ ] **Smart Fades** content-aware crossfade (silence/fade-out detection)
- [ ] Gapless playback verified sample-accurate
- [ ] ReplayGain reading from tags (MP3 ID3v2 `TXXX`, FLAC Vorbis, Opus header)
- [ ] ReplayGain pre-gain application + pre-amp setting
- [ ] Background LUFS scan job for tag-less files
- [ ] **Graphic EQ: 10 / 15 / 31-band selectable** on both engines
- [ ] **Parametric EQ** peaks/notches on both engines
- [ ] **AutoEQ profile import** (1000+ headphone presets bundled or user-importable)
- [ ] **Equalizer APO `.txt` config import**
- [ ] **Per-output-device EQ profiles** (auto-switch on route change)
- [ ] EQ preset manager UI + JSON import/export
- [ ] Output normalization limiter (LUFS target, soft-knee)
- [ ] Channel balance + mono summing
- [ ] Audio buffer + sample-rate matching settings
- [ ] DSP order configuration
- [ ] Sleep timer with optional fade-out
- [ ] Cross-platform parity test: golden-file outputs match within tolerance

### Phase 8 — In-car experiences (~2 weeks, parallel work streams)

Goal: RadioShed is usable while driving on both platforms.

#### Android — Drive Mode

- [ ] Drive Mode trigger registry (BT MAC, speed, schedule, headlight, manual)
- [ ] Big-button overlay UI, fluid scaling across console aspect ratios (1024×600, 1280×720, 1920×720, 1920×1080)
- [ ] Configurable button selection (4–6 from available actions)
- [ ] Big-button station picker (6-tile grid, configurable contents)
- [ ] Voice command engine (offline STT) + wake word ("Hey RadioShed" / custom / off)
- [ ] Steering-wheel key capture-and-bind UI
- [ ] Auto-exit conditions (BT disconnect, speed threshold, manual)
- [ ] Integration test on AutoLinkR Elite hardware

#### iOS — CarPlay

- [ ] Submit CarPlay Audio entitlement request to Apple (do early — can take weeks)
- [ ] CarPlay scene delegate setup
- [ ] `CPListTemplate` — stations list with badges
- [ ] `CPNowPlayingTemplate` customization (rate, dislike, switch station)
- [ ] `CPGridTemplate` — favorites grid (Drive Mode quick-list analog)
- [ ] `CPTabBarTemplate` — Stations / Recents / Search
- [ ] Siri Shortcuts (`INPlayMediaIntent` per station)
- [ ] CarPlay simulator end-to-end test
- [ ] Real-vehicle test (CarPlay-equipped car)

### Phase 9 — AI DJ (~1–2 weeks)

Goal: optional radio-style voice between tracks, configurable per station.

- [ ] TTS abstraction in `commonMain`
- [ ] **(Android)** `TextToSpeech` impl
- [ ] **(iOS)** `AVSpeechSynthesizer` impl
- [ ] Template engine: `{station}`, `{track}`, `{artist}`, `{next_track}`, `{time}`, `{weather}`
- [ ] Markov-style phrasing randomizer to avoid identical-sounding repeats
- [ ] Default templates library (station ID, up-next, recap, time)
- [ ] Template editor UI
- [ ] TTS pre-cache (1–2 tracks ahead, disk-keyed by hash)
- [ ] Music ducking implementation in both audio engines
- [ ] Per-station DJ frequency config (off / every / every N / minutes / station-change)
- [ ] Quiet-hours window
- [ ] Cloud TTS plugin point + ElevenLabs reference impl (BYO API key)

### Phase 10 — Online integrations (~1–2 weeks)

Goal: power users with connectivity get richer art, lyrics, scrobbling, recs.

- [ ] Connectivity policy enforcement (Always / WiFi-only / Never / Manual)
- [ ] Ktor HTTP client with persistent disk cache + rate-limit handling
- [ ] Cover Art Archive client (album art fetch on missing embedded art)
- [ ] **fanart.tv client** (HQ artist images)
- [ ] MusicBrainz client (track ID + metadata fix)
- [ ] LRCLib client (synced + plain lyrics)
- [ ] Last.fm OAuth flow + scrobbling client
- [ ] ListenBrainz token-based scrobbling client
- [ ] AcoustID + Chromaprint fingerprinting
- [ ] Open-Meteo client (weather variable for DJ templates)
- [ ] Wikipedia/Wikidata client (artist bio)
- [ ] Per-service toggle UI in Settings → Online & Sync

### Phase 11 — Cast & multi-room output (~1–2 weeks)

Goal: send audio to networked devices instead of local speakers.

- [ ] `CastTarget` abstraction in `commonMain`
- [ ] Discovery layer per platform (mDNS / SSDP / Bonjour wrappers)
- [ ] **(Android)** Google Cast SDK integration (Chromecast)
- [ ] **(iOS)** AirPlay 2 picker integration (`AVRoutePickerView`)
- [ ] UPnP/DLNA renderer support (both platforms)
- [ ] Sonos SOAP client (both platforms)
- [ ] Plex / Kodi client casting
- [ ] Transcoding pipeline for unsupported formats per target
- [ ] Adjustable bitrate per target
- [ ] Gapless on cast (where supported)
- [ ] Multi-room sync (group AirPlay / Sonos)
- [ ] Auto-cast rules
- [ ] Cast picker UI in Now Playing + Stations Home top bar
- [ ] Settings → Cast & output management screen

### Phase 12 — Backup & restore (~1 week)

Goal: user can move their setup between devices and platforms.

- [ ] kotlinx.serialization JSON serializers for all user data
- [ ] Custom artwork bundling
- [ ] DJ template + EQ preset + per-output binding bundling
- [ ] Zip archive writer/reader (cross-platform)
- [ ] AES-256-GCM passphrase encryption
- [ ] Auto-backup scheduler (off / daily / weekly)
- [ ] Restore wizard UI (preview contents, conflict resolution)
- [ ] Cross-platform restore: Android backup → iOS restore (and vice versa)

### Phase 13 — Widgets (~1 week)

Goal: home-screen presence on both platforms.

- [ ] **(Android)** Glance widget infrastructure
- [ ] **(Android)** Now-Playing widget (small / medium / large variants)
- [ ] **(Android)** Station-launcher widget (1 / 4 / 6 / 12 tiles)
- [ ] **(Android)** Library-stats widget
- [ ] **(iOS)** WidgetKit extension target
- [ ] **(iOS)** Now-Playing widget (small / medium / large / extra-large / Lock-screen)
- [ ] **(iOS)** Station-launcher widget (small / medium / large)
- [ ] **(iOS)** Library-stats widget
- [ ] **(iOS)** Live Activity (Lock Screen + Dynamic Island Now Playing)
- [ ] Widget config UI in Settings → Widgets
- [ ] App Group set up for widget data sharing (iOS)

### Phase 14 — Power tools & advanced settings (~1–2 weeks)

Goal: every "Advanced" setting from §7.5 is implemented and works.

- [ ] All Settings groups fully implemented (16 sections per §7.5)
- [ ] Tag editor (single-track + batch)
- [ ] Multi-value tag editor with separator preview
- [ ] File rename templates
- [ ] DB browser UI (read-only)
- [ ] Raw SQL console (developer panel)
- [ ] Log export
- [ ] Force re-scan, clear caches, DB vacuum
- [ ] DJ template scripting hooks (sandboxed)
- [ ] Custom voice model upload
- [ ] Path-glob exclude tester ("show me what this rule would skip")
- [ ] Duplicate detection UI (by hash + by tag fuzz)
- [ ] AutoEQ profile bundled library + import wizard

### Phase 15 — Hardening, beta, launch (~2–3 weeks)

Goal: shipped on both platforms.

- [ ] 50k-track stress test on AutoLinkR Elite + on iPhone
- [ ] Battery profiling (Android Battery Historian, iOS Energy Log)
- [ ] Memory profiling (heap dump analysis, leak detection)
- [ ] Local crash handler (crash → log file → user-export menu)
- [ ] User documentation: `USER_GUIDE.md`
- [ ] FAQ
- [ ] App Store assets: screenshots (matrix of form factors), video preview, descriptions, keywords
- [ ] App Store Privacy Nutrition Label (will be empty — no data collection)
- [ ] Play Store assets (if pursuing Play Store flavor)
- [ ] TestFlight beta (iOS)
- [ ] Authorized direct-download landing page (Android beta + release)
- [ ] License-key gating for direct-download portal (if applicable)
- [ ] App Store submission (allow 1–2 weeks for review)
- [ ] Play Store submission (optional)
- [ ] CI release automation: tag → builds + uploads APK + IPA to private artifact bucket
- [ ] Launch announcement (private channels)

### Phase 16 — Post-v1: streaming & cloud sources (deferred, optional)

Each is a self-contained module, tackled as user demand surfaces.

- [ ] `JellyfinSource` module
- [ ] `SubsonicSource` module (covers Navidrome, Airsonic, OpenSubsonic)
- [ ] `PlexSource` module
- [ ] `EmbySource` module
- [ ] `KodiSource` module
- [ ] `AudiobookshelfSource` module
- [ ] `DLNASource` module
- [ ] `SMBSource` module
- [ ] `WebDAVSource` module
- [ ] `GoogleDriveSource`, `OneDriveSource`, `DropboxSource`, `BoxSource`, `pCloudSource`
- [ ] `IcecastSource` (internet radio for hybrid stations)
- [ ] Hybrid station support (mix local + streaming sources in one station)
- [ ] Source-level connectivity policy (LAN-only / always / WiFi-only)
- [ ] Streaming cache controls (manual + automatic + forward-read playback cache + adjustable bitrate)

### Phase 17 — Post-v1: wearables & secondary surfaces (deferred)

- [ ] Wear OS companion app (Glance-driven)
- [ ] Apple Watch companion app (WatchKit + WidgetKit)
- [ ] Watch ↔ phone playlist/library sync
- [ ] Tasker plugin (Android automation)
- [ ] Visualizer (audio-reactive Now Playing background)
- [ ] Android TV app
- [ ] tvOS app

---

## Appendix A — Compose Multiplatform vs native UI: when to fall back

Use Compose Multiplatform if Phase 1 shows:

- Smooth scrolling at 60fps with a list of 1k+ items on iOS
- No visible lag on station tile grid animations
- CarPlay integration works alongside CMP without conflicts (CarPlay UI
  is always native, never CMP — the question is whether the rest of the
  iOS app can be CMP)
- The team is comfortable debugging Kotlin/Native + Compose runtime
  issues

Fall back to native (SwiftUI on iOS) if:

- Performance is consistently below acceptable
- iOS users report the app "feels off" compared to native iOS apps
- App Store review flags non-native-feeling UI as a guideline issue
- Compose for iOS hits a bug that blocks a critical path

The shared business logic (KMP without CMP) is valuable regardless — it
saves ~50% of code even if UI is duplicated.

---

## Appendix B — iOS-specific constraints & risks

These are spelled out so they don't surprise us mid-build.

- **No microSD.** iPhones lack the slot entirely. iPads can use external
  USB-C drives via Files, but the UX is awkward. The realistic iOS user
  story is: "drop your music into iCloud Drive or the app's Documents
  folder, point RadioShed at it." Communicate this clearly in onboarding.
- **App Store guidelines.** Music players that play user-owned files
  (Doppler, VLC, Evermusic) have been approved consistently. We don't
  duplicate Apple Music's catalog access, so we're in safe territory.
- **CarPlay entitlement.** Required for in-car use. Apple grants it for
  legitimate music apps but the request can take weeks. Submit early.
- **Background audio reliability.** iOS may aggressively suspend the app
  if `AVAudioSession` is misconfigured. Use `playback` category, set
  `AVAudioSessionCategoryOptionMixWithOthers = false`, declare the audio
  background mode.
- **No `INSTALL_PACKAGES` equivalent.** The "sideload as APK" Android
  story has no iOS analog. App Store is the only mainstream channel.
- **File access is bookmark-based, not path-based.** Every registered
  folder is a security-scoped URL bookmark. Bookmarks survive across
  launches but can become stale (renamed parent, iCloud eviction). Plan
  a "re-grant access" flow.
- **iCloud Drive eviction.** Files marked "Optimize iPhone Storage" may
  not be on-device when we want to play them. Trigger
  `NSFileCoordinator.coordinateReading` with the appropriate options to
  pull them down, and surface "downloading" state to the user.
- **WMA / APE / DSD** require bundled FFmpeg. Increases binary size by
  ~10–15 MB. Acceptable.
- **No analog to Android `BOOT_COMPLETED` + auto-launch on BT connect.**
  iOS handles auto-launch via CarPlay activation — the moment the user
  plugs in or connects, iOS routes to the registered CarPlay app.
- **Chromecast on iOS** is not native; using the Cast SDK on iOS is
  technically possible but Apple discourages it. AirPlay is the native
  multi-room story; we may simply not ship Chromecast support on iOS
  and let users with Chromecast targets use AirPlay-bridged
  alternatives.
- **TestFlight beta** has a 90-day expiration per build. Plan release
  cadence accordingly.

---

## Appendix C — License posture

RadioShed source code and compiled binaries are **proprietary**. Key
implications baked into the spec:

- **Repository** is private; never publish source publicly or to
  mirrors.
- **No F-Droid, no AltStore, no community sideload channels** —
  distribution is exclusively via authorized direct download (Android)
  and App Store / Play Store (controlled flavors).
- **Open-source dependencies are still allowed**, but their licenses
  must be respected in the compiled product (FFmpeg LGPL → dynamic
  linking where required; attribution in About → Licenses screen).
- **User data remains portable** even though the binary is closed —
  backup/restore round-trips through human-readable JSON.
- **No analytics, no telemetry** — the proprietary status is about
  distribution control, not user surveillance.
- **CI cost** — private repo means GitHub Actions free minutes are
  limited; macOS minutes cost 10× Linux minutes. Plan budget or move
  to a self-hosted Mac runner once iOS builds are frequent.

A draft proprietary `LICENSE` is in Phase 0; recommend a lawyer review
the final text before public launch.

---

*End of spec. Open questions and changes are tracked in commit history;
amendments should bump the doc version at the top.*
