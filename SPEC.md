# RadioShed — Product Specification

> Status: draft v3 · Target platforms: **Android 13+ (primary)** and **iOS 16+**
>
> This document is the source of truth for what RadioShed is and how it
> should behave. It is platform-neutral by default; sections that diverge
> per platform are explicitly marked **(Android only)** or **(iOS only)**.
>
> The phased build plan in §20 was last reconciled against the live
> Android codebase in a full audit (see §20 Phase 19 for the full
> "shipped post-Phase-18" log). The accuracy of every checkbox below
> reflects that audit.
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
- [x] Add `CONTRIBUTING.md` (private team contribution guide) — covers working environment, build/install loop, branching/commits, code style, testing, SPEC update conventions, and license posture.
- [x] Create `docs/decisions/` with ADR template (`0000-template.md`)
- [x] Document iOS build host strategy in `docs/decisions/0001-ios-build-host.md` — accepts a phased approach: Phase 1 (now) uses GitHub Actions `macos-latest` for release-tagged + weekly verification only (private-repo macOS minutes are 10× cost); Phase 2 trigger is "iOS commits multiple times a week" → switch to a self-hosted Mac mini runner.
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
- [~] Implement responsive `WindowSizeClass`-aware shared layout scaffolding — `BoxWithConstraints` drives the breakpoint logic across Stations Home (compact vs medium+ tile grid), Now Playing (single-pane vs two-pane, plus the `<360dp` overflow-as-bottom-sheet fallback), Onboarding's TwoPaneStep, and the Library Browser top bar. A formal `WindowSizeClass` accessor is still preferable for future XLarge-screen work but isn't blocking v1.
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
- [x] Folder management UI (add/rename/recursive-toggle/delete) — `ui/library/LibrarySettingsScreen.kt`. **Per-folder include/exclude path globs** ship via the Edit folder dialog: comma-separated `**/Spoken Word/**` / `*.flac` patterns get parsed, filtered for empties, and persisted via `FolderRepository.setGlobs(id, includeGlobs, excludeGlobs)`. The `folder` table already had `include_globs` / `exclude_globs` columns + `setGlobs` query — only the UI was missing.
- [x] Optional file-mode browser (power-user toggle, off by default) *(shipped as a full in-app file manager — `ui/library/FolderBrowserScreen.kt`. Reached from Track Info → "Open folder". Greatly expanded post-v1: breadcrumb, sort, in-page filter, multi-select, custom user bookmarks, audio-duration overlay — see Phase 19 for the full feature set.)*
- [x] Onboarding steps 1–5 — `ui/onboarding/OnboardingScreen.kt`: Welcome → Permissions → Folder → Scan → Done
- [x] Search UI — `ui/search/SearchScreen.kt`. *Backing is `LIKE` not FTS5: SQLDelight 2.1's parser crashes on FTS5 `MATCH` queries (and on virtual-table compound `name { ... }` blocks). Acceptable for libraries up to ~10k tracks. Revisit when SQLDelight 3.x lands or we evaluate alternatives.*
- [x] Search DSL parser (`artist:Hendrix year:>1968 genre:rock`) — `commonMain/data/SearchDsl.kt` ships the full grammar (tokenizer + field dispatch + year-range with `>`, `>=`, `<`, `<=`, `a-b`, plus quote handling and unknown-field fallthrough to free text).
- [ ] Stress test: index 50k synthetic tracks within 5 min on mid-tier hardware *(deferred to Phase 4+ verification)*

Also added in Phase 3 part 2:

- [x] Navigation: lightweight sealed-class state machine (`nav/AppScreen.kt`) — Onboarding / Home / Library / Search. Avoids a full nav library while the surface is small.
- [x] Post-onboarding `HomeScreen` replacing `LandingScreen`. Wordmark + 3 nav cards (Stations placeholder, Library, Search) + version footer.
- [x] `SearchRepository` with case-insensitive substring matching across tracks/artists/albums; LIKE patterns properly escape `%`, `_`, `\`.

### Phase 4 — Audio engine (~2 weeks)

Goal: tracks play, pause, skip; lock-screen controls work; Bluetooth metadata appears.

- [x] `AudioEngine` interface in `commonMain` (+ `Playable`, `PlayerState`, `Position`, `Track.toPlayable()`)
- [x] **(Android)** ExoPlayer-based AudioEngine impl — `AudioEngineAndroid` connects to a `MediaSessionService` via `MediaController`, exposing `StateFlow<PlayerState>` / `nowPlaying` / `position`
- [ ] **(iOS)** AVAudioEngine-based AudioEngine impl *(stub `AudioEngineIos`; needs Mac access)*
- [x] **(Android)** `MediaSessionService` + foreground notification — `RadioShedMediaSessionService` (Media3 auto-promotes to foreground and posts the notification with transport controls)
- [ ] **(iOS)** `AVAudioSession` configured for `.playback`, `mixWithOthers = false` *(deferred — needs Mac)*
- [ ] **(iOS)** `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter` wiring *(deferred — needs Mac)*
- [x] Lock-screen / notification controls (play, pause, prev, next, scrub) — automatic via Media3 `MediaSession` + standard transport
- [x] Bluetooth AVRCP metadata propagation — automatic via `MediaItem.mediaMetadata` (title, artist, album, artworkUri)
- [x] Audio focus / interruption handling (call, alarm, route change) — `AudioAttributes(handleAudioFocus = true)` + `setHandleAudioBecomingNoisy(true)` on the ExoPlayer
- [x] Cue-aware playback (start/end offsets within a single audio file) — `MediaItem.ClippingConfiguration` set when `Playable.cueOffsetStartMs` / `cueOffsetEndMs` are present
- [ ] Format coverage smoke tests: MP3, AAC, FLAC, OGG, Opus, WAV, ALAC *(needs physical device + sample files; deferred to verification pass)*
- [ ] Bundle FFmpeg for non-native formats: WMA, APE, WV, DSD *(deferred — adds ~10–15 MB and needs careful licensing/integration)*
- [ ] Verify contiguous decoding (gapless polish lands in Phase 7) *(deferred to Phase 7)*

Phase 4 part 1 also added:

- [x] `MiniPlayer` composable — sticky bottom transport bar with art placeholder, title/artist/album, prev/play-pause/next, progress bar; only renders when something is loaded
- [x] Tap-to-play wiring in `SearchScreen` — clicking a track row calls `audioEngine.play(track.toPlayable())`
- [x] `App.kt` rework: post-onboarding screens now stack vertically with the `MiniPlayer` below; Onboarding still owns the full screen

### Phase 5 — Stations engine (~2 weeks)

Goal: user can create stations; engine plays tracks per source + playback rules.

- [x] `SourceRule` evaluator: folder, tag filter, smart query, playlist, artist, album (DurationFilter / FileFormatFilter / PathPattern require composition with another source via Intersection — standalone they evaluate to empty, by design)
- [x] Set operators: `Union`, `Intersection`, `Difference`, `TopN`
- [x] `PlaybackRule` engine: shuffle, sequential, weighted random, smart shuffle
- [x] No-repeat memory (artist/album spacing requires per-track artist pre-load — Phase 5 part 2)
- [x] Weighting policies: `Uniform`, `FavorRecent` *(others fall back to Uniform in v1; need richer per-track stats — Phase 5 part 2)*
- [ ] Adaptive Mix smart query (learns from listening history) *(falls back to LeastPlayed for v1)*
- [ ] Auto-rotate refresh job for SmartQueries (e.g. Latest Hits re-eval daily) *(deferred)*
- [x] Built-in presets registry — `PresetSeeder` seeds Latest Hits / All Music / Discover / Top Rated / Recently Added / Forgotten Gems / Liked Songs at first-scan completion. **Genre auto-stations** ship as a one-tap "Generate genre stations" card in Settings → Library: picks top-N genre tags by track count (≥20 tracks), creates `Genre: X` stations seeded with `TagFilter("GENRE", [name])` + Smart Shuffle, idempotent. **Decade auto-stations** ship as a sibling "Generate decade stations" card: buckets albums by `Album.year` into Pre-50s / 50s / 60s / … / 2020s, creates a `Decade: X` station seeded with `AlbumSelector` over the in-decade album IDs (≥3 albums per bucket), idempotent. Backed by a new `selectAlbumIdsByYearRange` SQL query and `LibraryRepository.albumIdsByYearRange(min, max)`.
- [x] Station editor UI: identity, source builder, playback rules — `ui/stations/StationEditorScreen.kt` covers identity, color, every `SourceRule` selector + set-operator combiner, and every `PlaybackRule` knob.
- [x] Drag-to-reorder station list — `ui/stations/ReorderStationsScreen.kt` via the shared `ui/util/DragReorder.kt` utility. The Stations Home long-press sheet also offers per-row Move up / Move down for one-step nudges without entering reorder mode.
- [x] Station duplicate / delete / rename — `StationActionSheet` ships Open / Edit / **Duplicate** / Pin / Move up / Move down / Delete. Duplicate clones source + playback + cosmetics into a new user-row with a `(copy)` name suffix, then drops the user straight into the editor on the copy (the canonical "tweak this rule chain" workflow).
- [x] Auto-collage station artwork (4-album-cover grid) — generated from the station's first 4 distinct album covers when no custom artwork is set.
- [x] Custom artwork upload per station — `ui/util/ImagePicker` (with `supportsImagePicker()` + `rememberImagePicker { bytes -> … }`) round-trips an image picked from the system file picker into the station's artwork bytes column.
- [~] Onboarding steps 5–6 (taste setup + first custom station) — superseded by the linear 5-step flow shipped in current `OnboardingScreen.kt`. The "Done" step shows a 3-card mini-tour (Stations / Drive Mode / AI DJ teaser) instead of dedicated taste-setup screens. Re-evaluate post-v1 if user testing flags the simpler flow as missing context.

Phase 5 part 1 also added:

- [x] `StationPlayback` orchestrator — singleton in Koin; auto-advances on `PlayerState.Ended`; tracks `currentStation` + `candidateCount` as `StateFlow`
- [x] `StationsHomeScreen` replacing the Phase 4 placeholder card; tap → play; current/playing station highlighted
- [x] `PresetSeeder` runs automatically when onboarding's first scan completes; idempotent
- [x] Per-station dislike filtering wired through `RatingRepository.dislikedTrackIdsForStation`
- [x] 7 unit tests on `PlaybackQueue` (sequential wrap, no-repeat depth, depth-clamping when pool < depth, dislike exclusion, FavorRecent bias, empty-queue handling)

### Phase 6 — Now Playing & core UI (~2 weeks)

Goal: app feels like a music app, not a tech demo. Looks great on every form factor.

**Phase 6 part 1 (done):** responsive Stations Home grid + full-screen Now Playing with scrubber / transport / star rating / long-press dislike menu.

- [x] Stations Home: responsive grid (1 col compact, adaptive ≥240dp tiles for medium+) *(Phase 6 part 1 — sort/group/list-toggle deferred)*
- [~] Stations Home: list/grid view toggle, sort menus, tile size XS–L preference — **tile-size selector ships** (Small / Medium / Large / Extra-large mapped to 200 / 240 / 280 / 360 dp Adaptive minSize, persisted via `SettingsStore.getStationsTileSizeDp`, exposed as a top-bar dropdown). Compact widths still force single-column. The list/grid toggle and the sort menus are still deferred — a future surface where users want them, but the tile grid + drag-reorder + long-press sort already cover most reorganizing use cases.
- [~] Long-press tile → quick actions — `StationActionSheet` (in `StationsHomeScreen.kt`) ships Open station / Edit / Duplicate / Pin to top / Move up / Move down / Reset stats / Delete. **"Add to Drive Mode quick-list" and "Add to Widget" are still deferred** — they pair with the Drive Mode overlay (Phase 8) and the widget-config UI (Phase 13).
- [x] Now Playing screen: full-screen art, scrubber, controls — Coil-driven `AsyncImage` artwork, full transport, blurred backdrop (`BlurredBackdrop`), tap-to-expand fullscreen lightbox.
- [x] Now Playing: layout variants for compact (stacked) and medium+ (two-pane) *(Phase 6 part 1 — three-pane queue/lyrics deferred to large-tablet pass)*
- [x] Album-art aspect-ratio handling (square crop + blurred letterbox fill) — `AlbumArtPlaceholder` renders a two-layer composition: a `Modifier.blur(28.dp)`-cropped backdrop of the same image plus a `ContentScale.Fit` foreground. Square art reads as a single image (the layers are pixel-identical inside the frame); non-square art shows the full image with its own blurred halo filling the letterbox instead of edge-clipping.
- [x] Gesture handling: swipe, pinch, long-press — long-press skip-menu, MiniPlayer swipe-up to expand, MiniPlayer horizontal swipe-to-skip (left = next, right = previous), and **Now Playing artwork pinch-to-lyrics** (zoom factor > 1.15× jumps to the lyrics screen, mirroring the gestural pattern of "zoom in to see more"). Implemented in `AlbumArtPlaceholder` via `detectTransformGestures(panZoomLock = true)`.
- [x] Lyrics view: synced LRC karaoke + plain text fallback — LRClib + sidecar `.lrc` + embedded USLT/SYLT, tap-to-seek, copy to clipboard, A−/A+ font scale (persisted), source attribution badge, manual artist/title override on miss, cooperative auto-scroll (drag suppresses for ~5s) + "Jump to current line" FAB when active line scrolls off-screen.
- [x] Star rating UI (1–5) + tap-same-star-to-clear — half-star precision shipped (`SettingsStore.useHalfStarRatings`).
- [x] Skip tracking instrumentation — feeds the Stats screen's most-skipped tracks card and per-station skip rate.
- [x] "Don't play on this station again" dislike menu *(Phase 6 part 1 — long-press skip-next)*; global "Don't play on any station" override also available.
- [x] Onboarding steps 7–9 (in-car preview, AI DJ intro, done) — superseded by the linear 5-step flow with a 3-card "Done" mini-tour. Live-file ticker + filename basename rendered during the scan step. See Phase 19 for ticker polish.
- [x] Theme system: light, dark, AMOLED-black, accent color — `ThemeMode { System, Light, Dark, Amoled }` ships in `ui/theme/Theme.kt` with all four options exposed in Settings → Theme. AMOLED forces pure-black background/surface even when Material You dynamic color is active (the user's true-black preference wins over the wallpaper palette).
- [x] Material You dynamic color (Android 12+) opt-in — `SettingsStore.getMaterialYouEnabled()`, applied via `RadioShedTheme`.
- [x] Font scaling (XS–XXL) — Settings → Accessibility offers six discrete stops (XS / S / M / L / XL / XXL → 0.85× / 0.92× / 1.0× / 1.10× / 1.20× / 1.30×) that scale every Material text style across the app via `RadioShedTheme(fontScale = …)`. Persisted via `SettingsStore.getFontScale()`. The Lyrics A−/A+ adjuster still operates on top of this for that one screen.
- [x] Reduce-motion mode — `SettingsStore.getReduceMotion()` honored across animated transitions and the blurred backdrop.
- [ ] Accessibility audit: TalkBack on Android, VoiceOver on iOS
- [ ] CI snapshot-test pass across §1.1 form factor matrix

### Phase 7 — Audio pipeline polish (~1–2 weeks)

Goal: power-user audio features work and sound right on both platforms.

- [ ] Crossfade impl on both engines (equal-power, linear, S-curve, custom) *(deferred — needs dual-ExoPlayer pipeline. End-of-track fade-out shipped as the lighter single-player alternative.)*
- [ ] **Smart Fades** content-aware crossfade (silence/fade-out detection) *(deferred with crossfade)*
- [ ] Gapless playback verified sample-accurate *(deferred with crossfade)*
- [x] ReplayGain reading from tags — Scanner reads MP3 ID3v2 `TXXX`, FLAC Vorbis comments, Opus header gain into `Track.replayGainTrack` / `replayGainAlbum` / `replayGainTrackPeak`.
- [x] ReplayGain pre-gain application + pre-amp setting — applied on each track load via `AudioEngineAndroid.applyReplayGain()`; user-tunable pre-amp slider in Settings → Audio.
- [ ] Background LUFS scan job for tag-less files *(deferred)*
- [ ] **Graphic EQ: 10 / 15 / 31-band selectable** on both engines *(deferred — needs custom DSP via biquad cascade. 5-band shipped today via Android system Equalizer.)*
- [ ] **Parametric EQ** peaks/notches on both engines *(deferred with band-count work)*
- [ ] **AutoEQ profile import** (1000+ headphone presets bundled or user-importable) *(deferred with band-count work)*
- [ ] **Equalizer APO `.txt` config import** *(deferred with band-count work)*
- [ ] **Per-output-device EQ profiles** (auto-switch on route change) *(deferred — needs route-change observer + per-route preset binding)*
- [x] EQ preset manager UI + JSON import/export — `ui/settings/EqualizerScreen.kt` with built-in presets (Flat / Bass Boost / Vocal / Treble), user "Save current…" custom presets, long-press chip to delete, and JSON import / export round-trip via `EqPresetIO`. Bypass switch + persisted band gains.
- [ ] Output normalization limiter (LUFS target, soft-knee) *(deferred)*
- [ ] Channel balance + mono summing *(deferred)*
- [ ] Audio buffer + sample-rate matching settings *(deferred)*
- [ ] DSP order configuration *(deferred — depends on per-output EQ profiles + limiter)*
- [x] Sleep timer with optional fade-out — `audio/SleepTimer.kt` with idle / running / end-of-track states, +15-min extend, optional fade-out on the final ramp. MiniPlayer surfaces a live countdown badge. End-of-track mode races safely with auto-advance (mutex-coordinated).
- [ ] Cross-platform parity test: golden-file outputs match within tolerance *(deferred — pairs with iOS engine impl)*

### Phase 8 — In-car experiences (~2 weeks, parallel work streams)

Goal: RadioShed is usable while driving on both platforms.

#### Android — Drive Mode

- [ ] Drive Mode trigger registry (BT MAC, speed, schedule, headlight, manual) *(not started — requires `BluetoothManager` + `LocationManager` plumbing)*
- [~] Big-button overlay UI — a manual Drive Mode toggle (`SettingsStore.getDriveMode()`) re-skins Now Playing's transport row with oversized hit targets and hides Replay-10/Forward-10 to keep the surface scannable. The full overlay scaling for non-standard console aspect ratios (1024×600, 1280×720, 1920×720, 1920×1080) is still deferred.
- [ ] Configurable button selection (4–6 from available actions) *(deferred with overlay)*
- [ ] Big-button station picker (6-tile grid, configurable contents) *(deferred with overlay)*
- [~] Voice command engine — RecognizerIntent voice search (`ui/util/VoiceInput.kt`) ships in the Search field. The full offline-STT engine + wake word ("Hey RadioShed" / custom / off) is still deferred.
- [ ] Steering-wheel key capture-and-bind UI *(deferred — needs `KeyEvent` capture flow)*
- [ ] Auto-exit conditions (BT disconnect, speed threshold, manual) *(deferred with trigger registry)*
- [ ] Integration test on AutoLinkR Elite hardware *(deferred — pairs with overlay)*

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

- [x] Connectivity policy enforcement (Always / WiFi-only / Never / Manual) — `ConnectivityPolicy` enum in `domain/Settings.kt`, persisted via `SettingsStore.getConnectivityPolicy()`, gated through `NetworkPolicyGate` (commonMain) backed by an expect/actual `ConnectivityChecker` (Android: `ConnectivityManager.NetworkCapabilities`; iOS: stub). LyricsService and MissingArtworkSync both consult the gate before reaching the fetcher; Manual permits user-initiated retries but blocks automatic ones, WiFi-only checks `NET_CAPABILITY_NOT_METERED || TRANSPORT_WIFI || TRANSPORT_ETHERNET`, Never blocks both.
- [~] Ktor HTTP client with persistent disk cache + rate-limit handling — Cover Art Archive (`AndroidCoverArtFetcher`) and LRClib (`AndroidLyricsFetcher`) use Ktor with on-disk caches keyed by trackId / album MBID; transient HTTP errors no longer fall through as "not found". Rate-limit backoff + a unified Ktor `HttpClient` factory still deferred.
- [x] Cover Art Archive client — direct MBID fetch + release-group fallback, missing-artwork sync sweeps the library on demand (`MissingArtworkSync`).
- [ ] **fanart.tv client** (HQ artist images) *(deferred)*
- [ ] MusicBrainz client (track ID + metadata fix) *(deferred — Cover Art Archive uses cached MBIDs only)*
- [x] LRCLib client (synced + plain lyrics) — `AndroidLyricsFetcher` resolves via title/artist with multiple query candidates (`LyricsQueryCandidates`), persists to `lyrics_cache` with TTL, falls back to local `.lrc` sidecar (`LocalLyricsLoader`) and embedded USLT/SYLT (`Scanner` extracts at scan), and accepts manual artist/title overrides for stubborn matches.
- [ ] Last.fm OAuth flow + scrobbling client *(deferred)*
- [ ] ListenBrainz token-based scrobbling client *(deferred)*
- [ ] AcoustID + Chromaprint fingerprinting *(deferred)*
- [ ] Open-Meteo client (weather variable for DJ templates) *(deferred — paired with AI DJ)*
- [ ] Wikipedia/Wikidata client (artist bio) *(deferred)*
- [~] Per-service toggle UI in Settings → Online & Sync — Settings → Online now exposes the four-way `ConnectivityPolicy` picker (Always / Wi-Fi only / Ask each time / Never) with a one-line subtitle that explains what's currently gated. Per-service toggles (Cover Art Archive vs LRClib individually) are still deferred but the global gate is no longer hidden.

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

- [x] kotlinx.serialization JSON serializers for all user data — `source/BackupBundle.kt` covers stations, playlists, ratings, liked songs, listening history, custom EQ presets, and a settings round-trip.
- [ ] Custom artwork bundling *(deferred — pairs with zip writer)*
- [~] DJ template + EQ preset + per-output binding bundling — custom EQ presets ship in the bundle (`BackupBundle` v3). DJ templates and per-output bindings are not yet built upstream.
- [ ] Zip archive writer/reader (cross-platform) *(deferred — current bundle is plain JSON, which keeps things human-readable but rules out artwork blobs)*
- [ ] AES-256-GCM passphrase encryption *(deferred with zip)*
- [x] Auto-backup scheduler — `BackupService` runs weekly via `WorkManager` when enabled; `SettingsStore.getAutoBackupEnabled()` toggles it. Auto-prune to last N keeps the directory tidy.
- [~] Restore wizard UI — Settings → Backup & Restore lists every bundle with relative timestamps, supports import via the system file picker (#206), runs a per-key type-validating restore (`SettingsStore.restoreFromBackup`), and matches moved tracks via path-suffix fallback (#212). The Restore? confirmation dialog now opens with a **pre-restore preview** powered by `BackupService.preview(uri)` — parses the bundle without applying anything and shows a single-line summary ("3 stations · 5 playlists · 142 ratings · 12 liked · 7 settings · 2 EQ presets") so the user knows what they're about to merge before committing. True conflict-resolution UI (per-station merge vs replace) is still deferred.
- [ ] Cross-platform restore: Android backup → iOS restore (and vice versa) *(deferred — pairs with iOS implementation pass)*

### Phase 13 — Widgets (~1 week)

Goal: home-screen presence on both platforms.

- [~] **(Android)** Glance widget infrastructure — shipped via classic `RemoteViews` + `AppWidgetProvider` (`androidMain/widget/NowPlayingWidget.kt`) rather than Jetpack Glance. Functionally equivalent for the Now-Playing widget; revisit Glance when more widget variants land.
- [x] **(Android)** Now-Playing widget — small + medium + large `RemoteViews` layouts with art thumb (decoded at widget-target size, recycled on re-render to avoid the OOM bug we hit pre-#290), title / artist, and `PendingIntent` transport buttons routed through `RadioShedMediaSessionService`.
- [ ] **(Android)** Station-launcher widget (1 / 4 / 6 / 12 tiles) *(deferred — pairs with widget config UI)*
- [ ] **(Android)** Library-stats widget *(deferred)*
- [ ] **(iOS)** WidgetKit extension target *(deferred — needs Mac)*
- [ ] **(iOS)** Now-Playing widget (small / medium / large / extra-large / Lock-screen) *(deferred — needs Mac)*
- [ ] **(iOS)** Station-launcher widget (small / medium / large) *(deferred — needs Mac)*
- [ ] **(iOS)** Library-stats widget *(deferred — needs Mac)*
- [ ] **(iOS)** Live Activity (Lock Screen + Dynamic Island Now Playing) *(deferred — needs Mac)*
- [ ] Widget config UI in Settings → Widgets *(deferred — only the Now-Playing widget exists today)*
- [ ] App Group set up for widget data sharing (iOS) *(deferred — needs Mac)*

### Phase 14 — Power tools & advanced settings (~1–2 weeks)

Goal: every "Advanced" setting from §7.5 is implemented and works.

- [~] All Settings groups fully implemented (16 sections per §7.5) — Library, Stations defaults via station editor, Audio (EQ + ReplayGain + sleep timer), Playback rules per station, Online (single connectivity policy gate), UI/Theme (light/dark/Material You + reduce-motion), Backup & Restore, Privacy (history retention via clear-history), and About all ship. AI DJ, Cast & output, In-Car (beyond toggle), Hardware, Tags & Metadata, Widgets, and the Advanced developer panel are still deferred / partial.
- [ ] Tag editor (single-track + batch) *(deferred — only the lyrics-search artist/title override exists today)*
- [ ] Multi-value tag editor with separator preview *(deferred)*
- [ ] File rename templates *(deferred)*
- [ ] DB browser UI (read-only) *(deferred)*
- [ ] Raw SQL console (developer panel) *(deferred)*
- [~] Log export — Settings → Crash Logs (`CrashLogScreen`) lists per-process exit reasons with expandable stack traces and a Share button per entry. A general "export full log" action across non-crash logs is not yet built.
- [~] Force re-scan, clear caches, DB vacuum — "Rescan all folders" (#156), "Clear lyrics cache" (#257), and force-rescan-on-folder-change (#217) all ship. DB vacuum and a unified "clear all caches" sweep are still deferred.
- [ ] DJ template scripting hooks (sandboxed) *(deferred — pairs with AI DJ)*
- [ ] Custom voice model upload *(deferred — pairs with AI DJ)*
- [ ] Path-glob exclude tester ("show me what this rule would skip") *(deferred)*
- [ ] Duplicate detection UI (by hash + by tag fuzz) *(deferred)*
- [ ] AutoEQ profile bundled library + import wizard *(deferred — pairs with band-count work in Phase 7)*

### Phase 15 — Hardening, beta, launch (~2–3 weeks)

Goal: shipped on both platforms.

- [ ] 50k-track stress test on AutoLinkR Elite + on iPhone
- [ ] Battery profiling (Android Battery Historian, iOS Energy Log)
- [ ] Memory profiling (heap dump analysis, leak detection) *(NowPlayingWidget bitmap recycle bug #290 was caught and fixed without a formal pass)*
- [x] Local crash handler — `crash/CrashLogReader` reads `getHistoricalProcessExitReasons()` on Android; the Settings → Crash Logs screen surfaces them with per-entry Share so users can export a stack trace without ADB. iOS is a Null stub (TestFlight handles iOS crashes natively).
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

### Phase 18 — Remaining v1 work (catalogued post-audit)

Items surfaced by the post-build punch-list audit. None are user-blocking
for the current Android build, but each is tracked here so nothing slips
between phases. Some duplicate placeholders that already exist earlier in
the plan — kept here as a single discoverable list with cross-references.

Status legend: `[x]` = shipped on Android; `[~]` = partial / Android only,
iOS deferred; `[ ]` = still deferred.

#### Big features deferred (multi-day each)

- [ ] **Crossfade + gapless verification (#112)** — needs dual-ExoPlayer
  pipeline or a custom `AudioProcessor`. Cross-references Phase 4
  ("Verify contiguous decoding") and Phase 7 ("Crossfade impl on both
  engines", "Gapless playback verified sample-accurate"). End-of-track
  fade-out (single-player) ships today as the lighter alternative.
- [ ] **iOS implementation pass** — every `AudioEngineIos`,
  `FileEnumeratorIos`, `TagReaderIos`, `AVAudioSession`,
  `MPNowPlayingInfoCenter`, document-picker, and security-scoped-bookmark
  stub from Phases 3–4. Needs Mac access; requires a dedicated build-out
  cycle.
- [ ] **Cast / multi-room output** — entire Phase 11 (Chromecast, AirPlay,
  UPnP/DLNA, Sonos, Plex/Kodi, transcoding, multi-room sync, auto-cast
  rules). No code yet beyond the `CastTarget` schema row.
- [~] **Home-screen widget** — Android `AppWidgetProvider` ships
  (`androidMain/widget/NowPlayingWidget.kt`) with `RemoteViews` UI,
  Koin-injected AudioEngine reads, and `PendingIntent` transport buttons
  routed through `RadioShedMediaSessionService`. Implementation uses
  classic `RemoteViews` rather than Glance — functionally equivalent.
  iOS `WidgetKit` extension still deferred.
- [x] **Search DSL parser** — `commonMain/data/SearchDsl.kt` implements
  the full `artist:Hendrix year:>1968 genre:rock` query language with
  tokenizer, field dispatch, year-range parser (exact, `>`, `>=`, `<`,
  `<=`, `a-b` range), quote handling, and unknown-field fallthrough to
  free text.
- [~] **Drag-to-reorder** — stations
  (`ui/stations/ReorderStationsScreen.kt`) and playlist tracks
  (`ui/playlists/PlaylistDetailScreen.kt`) ship via the shared
  `ui/util/DragReorder.kt` utility. Queue reordering still deferred —
  the Up Next sheet has interleaved priority + rule rows that complicate
  drag handling. Remove + re-add ships as the workaround.

#### Quality / polish deferred from the audit

- [~] **Background-blur perf on Now Playing** — `BlurredBackdrop` is
  isolated as its own composable with KDoc explaining the
  recomposition-isolation strategy. Coil's memory cache backs the
  bitmap reuse. Explicit `remember(artworkUri)`-keyed pre-render not
  added; would help further on devices below Android 12 where
  `Modifier.blur` is a no-op and the work falls to software.
- [~] **SQLDelight `verifyMigrations` enabled** — flag stays **off**
  in `composeApp/build.gradle.kts` because the `.sqm` files use
  ALTER TABLE / CREATE TABLE against `station(id)` / `track(id)`
  references the verifier can't resolve in an isolated parse
  (every FK fails with "No column found with name id"). Turning
  it on cleanly needs each migration's prior schema re-stated at
  the top — a meaningful refactor still pending. Migration `6.sqm`
  is in place (backfills `station_pinned_track` for users with
  pre-existing DBs that crashed on `playStation`); we just don't
  have the harness validating it yet.
- [ ] **Media3 1.7+ bump** — clears the `CommandButton.Builder()`
  deprecation warnings. Holds until 1.7 is stable and the Media3 release
  notes are reviewed for breaking changes on `MediaSession`. Pinned at
  1.6.1 in `gradle/libs.versions.toml`.
- [x] **Scanner: first-seen-wins artwork** — `Scanner.kt` allocates an
  `albumsWithArtwork` set per scan and short-circuits on subsequent
  hits; the DB-level `setAlbumArtworkIfMissing` (Library.sq) uses
  `INSERT OR IGNORE` as a second guard.

#### Stuff never built that some users might miss

- [x] **Pinned tracks per station** — `station_pinned_track` table
  (Stations.sq), full repo + UI + playback merge. `StationDetailScreen`
  shows them with an Unpin button.
- [x] **Per-station detail screen** — `ui/stations/StationDetailScreen.kt`
  with header card, source-rule summary, candidate count, pinned tracks,
  top played, recently played sections.
- [x] **Crash log surface** — `ui/settings/CrashLogScreen.kt` lists
  `CrashLogEntry` rows with expandable stack traces and Share buttons.
  `AndroidCrashLogReader` uses `getHistoricalProcessExitReasons()`. iOS
  is a Null stub (TestFlight handles iOS crashes anyway).
- [~] **Onboarding polish** — current flow is 5 steps (Welcome →
  Permissions → Folder → Scan → Done). The "Done" step shows a 3-card
  mini-tour (Stations / Drive Mode / AI DJ teaser). The originally
  spec'd interactive taste-setup / first-custom-station / in-car-preview
  / AI-DJ-intro steps are not built — the linear 5-step flow ships
  instead.
- [x] **Bluetooth multi-click handling** —
  `RadioShedMediaSessionService.kt`'s `RadioShedLibraryCallback`
  intercepts `KEYCODE_HEADSETHOOK` / `KEYCODE_MEDIA_PLAY_PAUSE` with a
  350ms click-count window: 1→play/pause, 2→skipNext, 3+→skipPrevious.
- [x] **Now Playing overflow on narrow screens** — `NowPlayingScreen.kt`
  switches to `OverflowSheet` (modal bottom sheet) at `<360dp` width so
  submenu items don't clip. Wide screens keep the `DropdownMenu`.
- [x] **Forgotten Gems sort tweak** — `SmartQueryKind.ForgottenGemsByRating`
  is a distinct enum value with its own evaluator branch. Selectable in
  the StationEditor as "Forgotten gems — highest rated".

#### Polish shipped post-audit (not in original Phase 18)

These features were built after the audit was written and aren't in the
deferred list above. They're worth noting because they round out the v1
experience:

- Now Playing overflow `Go to album` / `Go to artist` / `Go to station`
- Now Playing artwork tap-to-expand fullscreen lightbox
- Now Playing tappable artist/album subtitle spans (jump from text)
- Now Playing queue button shows live numeric badge of queued tracks
- Replay-10s / Forward-10s transport buttons (hidden in Drive Mode)
- MiniPlayer long-press → Track context sheet
- Sleep-timer end-of-track mode + +15min extend
- Track context sheet: Play next / Add to queue / Add to playlist / Go
  to album / Go to artist / View lyrics / Set as ringtone / Share track
- Album / Artist long-press in Library → context sheet (Play / Shuffle /
  Play next / Add to queue / Add to playlist / Go to detail)
- Album / Artist / Genre detail screens: Play next / Add to queue
  overflow actions, total runtime + counts in the header
- Library Songs sort by Most played / Top rated, album-name subtitle row
- Library Browser: Random album button, Shuffle all
- Search: Liked-only filter chip, Play all / Shuffle all on tracks
- Stats: Most-skipped tracks card alongside Top played
- History screen: filter chips by station
- Lyrics: copy to clipboard, A− / A+ font size adjuster (persisted),
  forgiving USLT/SYLT parser, manual artist/title override on miss,
  source attribution badge
- Liked Songs: dedicated screen, "Save as playlist…" action, preset
  station, full backup/restore round-trip
- Voice search via RecognizerIntent — strips natural-language verb prefixes ("play X", "find X", "search for X", "play me X", "play song X", "play track X", "play album X", "play artist X") via `stripVoicePrefix()` so the actual search runs against the meaningful tail rather than echoing the spoken verb.
- Playlist export to .m3u via share sheet
- Volume slider on Now Playing (separate from system volume)
- Backup bundle v3: settings round-trip + custom EQ presets +
  liked-track timestamps; auto-prune to last N; share intent; import
  from system file picker; path-suffix fallback for moved files
- Equalizer: 5-band + presets + custom presets + bypass switch +
  preset export/import as JSON
- Repeat mode (off / one / all) with notification action
- Shuffle session toggle on Now Playing
- Dynamic-color (Material You) toggle, reduce-motion mode, drive mode
- Auto-collage station artwork, custom per-station artwork upload
- Skip tracking instrumentation feeding the Stats screen
- Track Info dialog: copy file path, tappable artist/album rows,
  enriched stats (plays, skips, ReplayGain, BPM, etc.)
- Settings: Clear lyrics cache button (preserves manual overrides)

### Phase 19 — Post-Phase-18 polish (shipped after the audit)

A continuous polish stream that lands after the Phase 18 audit. None of
these were originally scoped in the phased plan; they ship because the
v1 surface keeps revealing rough edges as the build matures. Grouped by
surface so future maintainers can scan them quickly.

#### File Manager v2 (replaces the original "Open folder" stub)

The Track Info → "Open folder" button used to launch a system intent
that several Samsung file managers silently dropped (#298). RadioShed
now ships its own in-app browser at `ui/library/FolderBrowserScreen.kt`
with `MANAGE_EXTERNAL_STORAGE` driving filesystem access, and the
following capabilities — every one of them was a separate ship:

- [x] Breadcrumb top bar — each segment tappable for one-tap-up nav
- [x] Folder header with stats (folder count / audio count / total
  bytes) + Play All / Shuffle All
- [x] Folder long-press context — Open / Play all music here
  (recursive) / Shuffle all music here / Add as music folder
  (idempotent — re-scans if already registered) / Pin to bookmarks /
  Copy path
- [x] File long-press context — Play / Play next / Add to queue / Add
  to playlist / Copy path / Show full path / Share file
- [x] Sort options (Name A→Z, Name Z→A, Recently modified, Largest
  first), persisted via `SettingsStore.getFileBrowserSort()`
- [x] In-page search filter (toggleable; resets on directory change)
- [x] Quick-jump bookmarks bar — preset chips (Storage / Internal /
  Music / Downloads, only rendered when the path actually exists) +
  user-pinned custom folders (long-press a Star chip to remove)
- [x] Audio-duration overlay — for files whose Track row exists in the
  library, the row subtitle shows duration alongside file size
- [x] Multi-select mode — long-press an audio file to enter; the top
  bar swaps to "X selected" + Close + Select-all-audio; the bottom
  action bar offers Play / Shuffle / Play next / Add to queue.
  Selection persists across folders so users can build a queue from
  multiple directories.

#### Listening Stats charts

`ui/settings/StatsScreen.kt` already had top-N rankings; this round
adds three time-aware visualizations powered by `station_history`
aggregations on `StationRepository`:

- [x] 30-day daily activity bar chart — one bar per day with a
  square-root-ish height curve so a single play stays visible against
  a peak of 50; "today" sits on the right
- [x] Hour-of-day histogram (24 bars) — peak-hour label ("Peak around
  7pm") and gridline labels at 12am / 6am / noon / 6pm / 12am
- [x] Completion-rate tiles — "Completion rate" % and "Finished" raw
  ratio, hidden when `total = 0` to avoid divide-by-zero ugliness

Backed by new SQL — `selectPlaysPerDayWindow`,
`selectPlaysPerHourWindow`, `selectCompletionStats` — that share a
common `nowMs` / `sinceMs` envelope so all three views stay in sync.

#### MiniPlayer enhancements

- [x] Horizontal swipe-to-skip — left = next, right = previous; lives
  in its own `pointerInput` so Compose's gesture-resolution arbitrates
  with the existing vertical swipe-up-to-expand. Drag-end commit (no
  accidental skips from a small horizontal twitch while tapping).
- [x] Sleep-timer countdown badge — when `SleepTimer.state` is
  `Running`, a compact bedtime-icon chip with `42s` / `23m` /
  `1:04h` / `EOT` countdown sits between the title column and the
  transport buttons. Hidden when idle.

#### Lyrics polish

- [x] Cooperative auto-scroll — synced lyrics no longer yank the user
  back to the active line while they read ahead. Drags are detected
  via `LazyListState.interactionSource.collectIsDraggedAsState()`;
  auto-scroll is suppressed while dragging plus a 5-second idle grace
  window. Active line off-screen surfaces a "Jump to current line"
  FAB that returns the user and re-arms auto-scroll.

#### Library / Album / Artist / Playlist / Genre / History detail polish

- [x] Album detail — per-track `× N` play-count badge (subtle trailing
  text, only rendered when count > 0). Also surfaces never-played
  tracks as "what haven't I tried on this album?".
- [x] Artist detail — same `× N` play-count badges on track rows for
  visual consistency with Album detail.
- [x] Playlist detail — header subtitle now reads `"12 tracks · 47
  min"` instead of the raw count; runtime formatter shared with
  Album / Artist detail.
- [x] Library Artists tab — sort dropdown (Name A→Z / Z→A / Most
  played) with persistence via `SettingsStore.getLibraryArtistSort()`.
  "Most played" lazy-loads `topPlayedArtists(Long.MAX_VALUE)` only
  when the user picks that sort.
- [x] Genre detail — empty state when zero tracks (catches the
  stale-tag case where a Genre row exists but its tracks were
  retagged or removed)
- [x] History screen — date-bucket section headers ("Today",
  "Yesterday", "Earlier this week", "Earlier this month", "Older")
  computed once per visible-list change
- [x] Album / Artist context sheet — "Create station from this album"
  / "Create station from this artist" actions. Inserts a new user
  station seeded with `AlbumSelector` / `ArtistSelector` source +
  sensible default name (album title / artist name) + Sequential
  play order for albums, then drops the user straight into the
  StationEditor on the new row. The canonical SPEC §7.1
  "Create station from this artist / from this album" CTA, finally
  wired through.
- [x] Library Browser Songs tab — quick text filter above the
  list. Substring match (case-insensitive) against title or
  filename, composes with the existing sort + artist/album
  filters. Saves a trip to the global Search screen for the
  common "find that song in this view" task.
- [x] Library Browser tab labels — inline count badges
  ("Songs · 1234", "Albums · 187", …) so users can see the size
  of each pile at a glance. Hidden when the count is zero so
  fresh installs read as plain "Songs / Artists / Albums /
  Genres".
- [x] Library Browser sort dropdowns for Albums + Genres tabs.
  Albums sort: Title A→Z / Z→A, Year — oldest first / newest first,
  Most played. Genres sort: Name A→Z / Z→A. Persisted via
  `SettingsStore.getLibraryAlbumSort()` / `getLibraryGenreSort()`.
  Most-played album sort lazy-loads `topPlayedAlbums(Long.MAX_VALUE)`
  only when that sort is active.
- [x] MiniPlayer haptic feedback on swipe-skip. Uses
  `LocalHapticFeedback.current` to fire `HapticFeedbackType.LongPress`
  when the horizontal swipe threshold is crossed and the skip
  command actually goes out — the user feels the gesture confirm
  without having to look at the screen.
- [x] Stations Home empty-state CTA. The "No stations yet." card
  now ends with a "Go to Library" button so users can register a
  folder without having to backtrack to Home first.

#### Search polish

- [x] IME `ImeAction.Search` on the query field — magnifier key on
  the IME, dismisses the keyboard on press so results are immediately
  visible. Debounced search keeps running as before.

#### Onboarding polish

- [x] Live-file scan ticker — replaces the single-line "127 tracks ·
  /storage/emulated/0/Music/Some Album/Long Track Name.flac" (which
  wrapped/clipped on narrow screens) with a clean two-line layout:
  count headline ("127 tracks scanned") above a smaller subline
  showing just the basename. Both lines ellipsize. Empty / started /
  done / failed states handled separately.

#### Up Next sheet polish

- [x] Album-art thumbnails on every queued / recently-played row (32dp,
  falls back to the music-note icon when art is missing). The
  artwork map is built lazily when the sheet opens and dropped when
  it closes, so we don't pay the album lookup cost outside the
  transient sheet view.

#### IME action audit across all text-input dialogs

Pressing Enter / Done / Search on the IME now actually submits across
every single-field confirmation dialog in the app — previously the IME
defaulted to "Done" with no action wired. Touched: Liked Songs → Save
as playlist, Playlists → New playlist, CollectionContextSheets → New
playlist, TrackContextSheet → New playlist, AddToPlaylistSheet → New
playlist, EqualizerScreen → Save preset, LyricsScreen → Edit search
(Artist→Next, Title→Search), LibrarySettingsScreen → Add folder
(path→Next, name→Done) and Rename folder, FolderBrowserScreen →
Filter folder. Each touched dialog also factors a single
`confirmXxx: () -> Unit` lambda so the IME path and the button path
share submit behavior.

#### Crash safety pass (post-audit fix sweep)

Concurrency fixes in the playback pipeline that were caught by code
review rather than user reports — none of them triggered a visible
bug, but each had a defensible "this could race" footprint:

- [x] `PlaybackQueue.removePriorityAt` race with `advanceToNext`
- [x] `StationPlayback.skipPrevious` history race
- [x] `QueueBottomSheet` rapid X-tap remove (off-by-one when stale
  pre-removal indices were addressed twice)
- [x] `republishPriorityCount` called outside mutex — race with
  advance
- [x] `skipPrevious` `userSkipPending` flag set before mutex acquired
- [x] `SleepTimer.extend` reading stale `remainingMs` from the
  StateFlow; now driven off the monotonic `startedMark`
- [x] `SleepTimer` end-of-track racing the auto-advance path
- [x] `Onboarding` folder insert UNIQUE-constraint crash on
  back-then-forward navigation
- [x] `AndroidPlaylistExporter` same-millisecond filename collision +
  cache-dir cleanup
- [x] `BackupService` prune list including the just-written file
- [x] `Scanner` doesn't mark removed files unavailable on rescan
- [x] `AndroidArtworkPersister` leaves `.tmp` files on disk-full
- [x] `AppSettings` showing stale toggle state on re-entry
- [x] `Stations Home` double-tap race on station load
- [x] `SearchDsl` `year:>` with empty operand fell through to free
  text instead of erroring
- [x] `PresetSeeder` concurrent `seedIfEmpty` could double-seed
- [x] `AndroidLyricsFetcher` swallowing transient HTTP errors as
  "not found" (cached as a miss; broke retry)
- [x] Now Playing title wrapping per-character on narrow screens —
  fixed via `softWrap = false, maxLines = 1, overflow = Ellipsis`
- [x] `VolumeRow`, `PreampSlider`, `EndFadeSlider`,
  `FolderColorRow` all writing prefs every drag frame; debounced or
  conditional now
- [x] `restoreFromBackup` per-key type validation (only writes a key
  if the bundle's type matches what the app currently stores there)

#### Phase 19 audit follow-up (peer code review fix sweep)

A second pass after the parallel-agent peer review uncovered a batch
of correctness bugs in the polish stream itself. None of these were
user-reported, but each was wrong on inspection and shipped only
because the original review had focused on the wider codebase rather
than the v2 polish:

- [x] **Connectivity policy was decorative.** The Settings →
  Connectivity picker (Always / WiFi-only / Never / Manual) was read
  for display but never consulted by `LyricsService` or
  `MissingArtworkSync`, so a user on Wi-Fi-only still hit the
  network on cellular. New `NetworkPolicyGate` (commonMain) +
  `ConnectivityChecker` expect/actual (Android consults
  `ConnectivityManager.NetworkCapabilities`; iOS stub returns
  permissive defaults). LyricsService gates per-fetch on
  `allowAutomaticFetch` (passive resolves) or
  `allowUserInitiatedFetch` (the manual force-refresh button —
  Manual policy permits user-driven calls). MissingArtworkSync
  emits a new `Progress.PolicyBlocked` state when the sweep is
  refused, surfaced as a distinct red message in the
  `ArtworkSyncCard`.
- [x] **`StationRepository.duplicate()` was not atomic.** Two
  concurrent Duplicate taps could read the same `MAX(sort_position)`
  and insert two stations with identical sort positions. Now wrapped
  in a SQLDelight transaction with a new `selectMaxStationSort`
  query inside the transaction. While here, also added
  `bumpCopySuffix()` so duplicating "Foo" → "Foo (copy)" → "Foo
  (copy 2)" instead of "Foo (copy) (copy)".
- [x] **Station up/down swap could leave duplicate sort_position
  rows.** Two `reorder()` calls outside any transaction could be
  observed mid-pair by `selectAllStations` Flow consumers, or leave
  the DB with a duplicate position if the process was killed
  between writes. New `StationRepository.swapSortPositions()` runs
  both UPDATEs inside a single transaction.
- [x] **Genre auto-station generator was N+1.** Per-genre
  `tracksByTag(GENRE, value).size` issued a full row fetch just to
  read `.size`, stalling cold-start on libraries with dozens of
  distinct genres. New `selectTagValueTrackCounts` SQL does a single
  COUNT(*) GROUP BY pass; `LibraryRepository.tagValueTrackCounts()`
  returns the (value → count) map directly.
- [x] **Genre & decade auto-stations weren't truly idempotent.** If
  the user renamed an auto-generated "Genre: Rock" → "Rock Mix", a
  re-run created a duplicate "Genre: Rock". Now match by source rule
  rather than name: genre matches `TagFilter("GENRE", [name])`
  (case-insensitive); decade matches existing `AlbumSelector` whose
  ID set has ≥80% overlap with the candidate decade's albums.
- [x] **Decade auto-stations had a hardcoded `nowYear = 2030`.**
  Replaced with a runtime `currentYear()` expect/actual (Android
  uses `java.util.Calendar`, iOS uses `NSCalendar`) plus a derived
  `currentDecadeEnd` so the bucket list extends past 2030 when the
  current calendar year is in a later decade.
- [x] **`dailyActivity()` lost boundary-day plays.** A history row
  whose `played_at` landed exactly on `now − windowDays * DAY_MS`
  was bucketed as `days_ago == windowDays`, which the
  `(windowDays-1 downTo 0)` renderer skipped. Adjusted `sinceMs`
  by +1 ms so all included rows fit the 0..windowDays-1 range.
- [x] **`selectCompletionStats` returned nullable `completed`.** The
  `SUM(CASE …)` over zero rows was nullable, so callers had to
  `?: 0L`. Wrapped in `IFNULL(..., 0)` so the column is now non-null
  and the contract leak is fixed.
- [x] **`LibraryBrowserScreen` Songs tab quick filter reset on tab
  switch.** State was declared inside the `LibraryTab.Songs ->`
  branch; switching tabs and back re-`remember`-ed an empty filter.
  Hoisted to function scope alongside `selectedTab`.
- [x] **`albumPlayCounts` / `artistPlayCounts` were frozen at first
  load.** `LaunchedEffect(sort)` had a `.isEmpty()` guard that
  prevented re-fetching when the user switched away from MostPlayed
  and back. Removed the guard so subsequent activations refresh the
  snapshot.
- [x] **MiniPlayer's three sibling `pointerInput` blocks couldn't
  arbitrate gestures.** detectVerticalDragGestures and
  detectHorizontalDragGestures each got the raw event stream
  independently, so a diagonal swipe could fire both `onExpand()`
  and `skipNext()`. Drag handling is now a single `awaitEachGesture`
  with manual axis lock at the first slop crossing — the first
  axis-dominant move locks the gesture, and only the matching
  channel (vertical → expand, horizontal → skip) accumulates after
  that. Tap stays in its own pointerInput (taps don't cross slop so
  they don't conflict). Skip-haptic switched from
  `HapticFeedbackType.LongPress` (heavy buzz) to `TextHandleMove`
  (light tick) for everyday skip use.
- [x] **MiniPlayer sleep-timer countdown formatter was wrong for
  >1h durations.** Rendered as `"1:30h"` (number of hours, then
  minutes, then suffix `h`) which read as "1:30 hours". Now
  `"1h 30m"`.
- [x] **Lyrics auto-scroll grace window armed unreliably across KMP
  targets.** `LazyListState.interactionSource.collectIsDraggedAsState()`
  is a documented API but its `DragInteraction` emission isn't
  guaranteed on every Compose-multiplatform target. Replaced with
  `listState.isScrollInProgress` gated by a
  `animatingProgrammatically` flag we set around our own
  `animateScrollToItem` calls — when scrolling-and-not-our-animate,
  it's the user. Works the same on Android and is reliable
  cross-platform.
- [x] **Pinch-to-lyrics fired the navigation per-event, not per-
  gesture.** `detectTransformGestures`'s callback runs on every
  pointer-move while two fingers are down, so a fast pinch could
  push the Lyrics screen multiple times. Replaced with a manual
  `awaitEachGesture` + cumulative-zoom accumulator + `fired` flag
  so `onPinchOpen()` runs at most once per gesture; threshold
  bumped from 1.15× to 1.20× cumulative zoom.
- [x] **FolderBrowser duration loop was O(n²).** Each per-file DB
  hit triggered `durationsMs = acc.toMap()` — a fresh map copy on
  every iteration, plus a recomposition of the entire LazyColumn.
  Now batches the publish every 25 hits with a final flush so the
  UI still updates incrementally without the quadratic copying.
- [x] **`BackupRestoreScreen` Restore button enabled while preview
  was loading or had failed to parse.** The "Reading backup
  contents…" placeholder also rendered an enabled "Restore" button.
  Now `enabled = previewLoaded` so the action is gated on a
  successful (or failed) parse.
- [x] **`BackupRestoreScreen` preview state could persist across
  dialog dismiss/reopen.** `var preview by remember(file.uri)` keyed
  the state on URI; opening the same backup twice in a row could
  return cached `previewLoaded = false` from the first try if the
  initial load was cancelled. Removed the key so the let block's
  enter/exit naturally drops state, and the LaunchedEffect re-runs.
- [x] **`SettingsStore.pinFileBrowserFolder` corrupted on tab/
  newline in name.** Folders displayed via the file system can
  occasionally contain `\t` or `\n`, which corrupts the
  `name\tpath\nname2\tpath2` blob. `name` and `path` are now both
  sanitized at write time.
- [x] **`LibrarySummaryCard` showed stale counts.** Loaded once via
  `LaunchedEffect(Unit)`; if the user added a folder and scanned
  while AppSettings was on the back stack, the card never
  refreshed. Now subscribes to `observeTrackCount()` Flow as a
  "library changed" trigger and re-fetches the others on every
  emission.
- [x] **`EditFolderDialog` glob editor split on `,` corrupted brace
  patterns.** Comma split would break `**/{Rock,Pop}/**` into two
  invalid patterns. Switched to one-pattern-per-line input
  (placeholder shows the new format).
- [x] **Backup omitted user-portable preferences.** `dumpForBackup()`
  serialised the bool / volume / preamp / theme set but silently
  dropped `KEY_FONT_SCALE`, `KEY_LYRICS_SCALE`,
  `KEY_STATIONS_TILE_SIZE`, the three Library Browser sort keys,
  and `KEY_FILE_BROWSER_SORT` — so a restore on a new device
  reverted those preferences to defaults without warning. Added to
  the dump and to `expectedTypeForKey()` so the typed-restore path
  accepts them too.
- [x] **`BundlePreview.exportedAtMs` was captured but never
  rendered.** The restore-confirmation dialog now shows
  "Created 3 days ago" beneath the contents row so users with
  multiple backups can pick the right vintage at a glance.
- [x] **Deprecated `LocalClipboardManager` consolidated.** Four
  call sites (FolderBrowser file/folder context sheets, Lyrics
  Copy button, Now Playing track-info path row) now go through a
  single `rememberCopyToClipboard()` helper in `ui/util/Clipboard.kt`,
  which carries the file-level `@Suppress("DEPRECATION")` for the
  old API. The suspend-aware `LocalClipboard` migration is parked
  until Compose ships a commonMain `ClipEntry` constructor (the
  current actual on Android requires `android.content.ClipData`,
  which can't be referenced from commonMain without new
  expect/actual scaffolding).
- [x] **`BackupService.RestoreResult.Done.errors` count.** Three
  `runCatching` blocks (stations, ratings, liked) previously
  swallowed failures silently — a SQLite constraint or unrecognised
  source-rule JSON would just decrement the visible-applied number
  with no signal that the bundle was partially broken. Each
  per-row catch now bumps a per-bucket failure counter, the
  totals roll up into `RestoreResult.Done.errors`, and the
  restore status string surfaces "— N rows failed during import"
  when non-zero.

#### Phase 19 polish (continued, batch 3)

- [x] **Sleep timer extend menu — three stops.** The "+15 minutes"
  shortcut on the sleep timer dropdown now sits between sibling
  "+5 minutes" and "+30 minutes" options so users can nudge the
  deadline by one song or by a full half-hour without dialing in
  a fresh duration.
- [x] **History screen — granular trim.** The overflow menu was
  one button, "Clear history". Now it has three: Clear last hour,
  Clear last 24 hours, Clear all. Backed by a new
  `trimHistoryNewerThan(threshold)` SQL query — the inverse of the
  existing `trimHistoryOlderThan` — so the user can wipe a recent
  casual-listening session without losing their long-term log.
- [x] **Stats — listening streak.** Two new tiles ("Current
  streak" / "Longest streak (90d)") show the consecutive run of
  days with ≥1 play, computed from a new SQL query
  `selectActivePlayDays` that walks distinct local-time calendar
  dates and bucketing in Kotlin. Hidden when no plays exist yet.
- [x] **Search — clear-text affordance.** When the query field is
  non-empty, the trailing-icon slot now shows a Clear (X) icon
  that wipes the field in one tap. Voice mic remains shown only
  on an empty field, where it's still the most useful affordance.
- [x] **Up Next sheet — Save as playlist.** A "Save" button next
  to "Clear" in the Up Next header opens a `SaveQueueAsPlaylistDialog`
  that prompts for a name (defaults to "Queue snapshot"), then
  persists every visible upcoming track into a new playlist via
  `PlaylistRepository.insert` + `addTrack`. Both the priority
  queue and the rule-driven peek rows are captured — handy for
  freezing a smart-shuffle session you liked before it scrolls
  past.

#### Phase 19 polish (continued, batch 5)

- [x] **Stations Home — inline filter.** When the user has 9 or
  more stations, an `OutlinedTextField` (with leading search /
  trailing clear icons) appears between the page header and the
  tile grid. Substring match against station name and
  customSubtitle, case-insensitive. Hidden on smaller libraries
  to keep fresh-install UX uncluttered.
- [x] **Stations Home — "Hide built-ins" toggle.** Persisted
  `KEY_STATIONS_HIDE_BUILTINS` boolean, exposed in the same
  GridView dropdown that hosts the tile-size stops. Off by
  default; flipping it filters preset stations (`isBuiltIn = 1`)
  out of the visible grid for power users with a dozen custom
  stations.
- [x] **Library Browser — scroll-to-top FAB.** The Songs tab
  LazyColumn carries a `rememberLazyListState`; once
  `firstVisibleItemIndex > 8`, a `SmallFloatingActionButton` with
  Up arrow renders bottom-right and `animateScrollToItem(0)`s on
  tap. A `derivedStateOf` keeps the visibility check cheap.
- [x] **Stats — weekday histogram.** New
  `selectPlaysPerWeekdayWindow` SQL query buckets plays by
  local weekday (`strftime('%w')`) over a 30-day window, rotated
  in Kotlin to Monday-first. Renders as a 7-bar `WeekdayHistogram`
  with a "Busiest day" header below the existing hour-of-day
  histogram.
- [x] **Lyrics — long-press FAB to scroll to top.** The existing
  jump-to-current FAB now responds to long-press by scrolling all
  the way to the first line and arming the user-override window
  so the auto-scroll doesn't immediately yank back. Surface
  swapped to a `combinedClickable` Box so both gestures fit on
  one widget.
- [x] **Replay/Forward 10s — long-press for 30s.** The
  ±10-second seek buttons in the Now Playing transport row gained
  a long-press affordance: long-press jumps 30s instead of 10s.
  Same combinedClickable+Surface pattern as the SkipNext
  long-press menu, threaded through `onReplay(deltaMs)` /
  `onForward(deltaMs)` callbacks so the parent computes the
  bounds (preserving the existing `(durationMs - 250)` cap on
  forward seeks).

#### Phase 19 polish (continued, batch 6)

- [x] **Library Browser scroll-to-top FAB on every tab.** The
  Songs tab's back-to-top FAB is now mirrored on the Albums,
  Artists, and Genres grid tabs via a shared
  `GridWithBackToTopFab` helper (wraps a LazyVerticalGrid with a
  Box + SmallFloatingActionButton). Threshold for grids is
  `firstVisibleItemIndex > 16` since multi-column grids fit more
  rows on screen.
- [x] **Search recent — per-entry remove.** Each recent-query
  AssistChip now carries a trailing close icon (clickable) that
  drops just that one entry from the persisted list. The "Clear"
  button next to the section header still nukes everything.
  Backed by a new `SettingsStore.removeRecentSearch(query)`.
- [x] **Stats — concrete date range header.** "Last 30 days" was
  a hardcoded section title; the timeline now shows
  `"Apr 4 – May 3"` computed from `formatShortDate(nowMs)` and
  `formatShortDate(nowMs - 29d)`. New expect/actual
  `formatShortDate(epochMs: Long)` (Android `SimpleDateFormat`,
  iOS `NSDateFormatter`). Falls back to the prior generic label
  on any formatter exception.

#### Phase 19 polish (continued, batch 7)

- [x] **Theme picker color swatches.** Each option in the
  Settings → Theme picker now shows three small color dots
  (background / surface tint / primary accent) pulled from the
  baked palette via `previewSwatchesFor(mode)` in
  `ui/theme/Color.kt`. System mode interleaves a Light + Dark
  background dot since the actual scheme is OS-decided at
  runtime.
- [x] **Detail-screen track sort menus.** Album, Artist, and
  Genre detail screens each gained a sort-icon dropdown alongside
  the overflow menu. AlbumTrackSort defaults to
  `TrackNumber`; ArtistTrackSort defaults to `AlbumOrder`;
  GenreTrackSort defaults to `TitleAsc`. Each enum is local to
  its screen — sort state isn't persisted, since the user's
  intent is usually session-local.
- [x] **Playlist detail one-click sort.** Three new menu items
  in the playlist overflow — "Sort A → Z", "Sort by duration",
  "Shuffle once" — each commit a fresh persisted track order
  via `PlaylistRepository.setTrackOrder` and reload. Drag-to-
  reorder still works for fine-tuning after a one-click sort.
- [x] **MiniPlayer "Next: X" when paused.** The artist/album
  subtitle is replaced with `Next: <track>` when the player is
  paused and the queue has at least one upcoming entry —
  `stationPlayback.upcoming(1).firstOrNull()`. Falls back to the
  base subtitle (and then to `stateLabel`) when queue is empty.
- [x] **Now Playing queue-position chip.** New
  `StationPlayback.finiteQueueProgress: StateFlow<Pair<Int, Int>?>`
  publishes `(currentIdx1Based, totalSize)` while the active
  surface is a finite `playTracks` queue, and resets to null on
  station entry / stop. NowPlayingScreen surfaces it as a small
  "Track 4 of 12" label below the station name; hidden in
  station mode (the candidate pool is unbounded so the label
  would be misleading).
- [x] **EQ A/B compare.** Long-press a built-in preset chip in
  EqualizerScreen to arm a comparison: the current band gains
  are snapshotted, the preset is applied, and a tertiary-color
  banner appears above the chip row. Tapping the banner toggles
  between the preset ("B") and the original ("A"); the "Done"
  text-button releases comparison. Picking another preset or
  custom preset releases automatically. Custom-preset long-
  press still deletes (long-standing affordance, kept).
- [x] **History "Continue session from here".** Tapping a
  history row used to play the visible-list newest-first, which
  produced backwards-flowing playback. The tap now selects all
  visible entries with `playedAt ≤ entry.playedAt`, reverses
  them to chronological order, and seeds the queue starting
  from the tapped entry — re-creating the original listening
  session forward in time.
- [x] **Crossfade + gapless verification.** Audited
  `AudioEngineAndroid` for both. **Gapless:** the `playTracks`
  finite-list path goes through ExoPlayer's `setMediaItems`
  (line 163) which is gapless-capable when the underlying
  decoder reports gapless metadata; the single-track
  `setMediaItem` path used for station-driven playback is not
  gapless by definition because each track loads independently.
  **Crossfade:** not implemented — would need a dual-engine
  setup with overlap/fade buses. Both items remain deferred to a
  Phase 20+ audio refactor; documented here so the gap is
  explicit instead of implicit.

#### Phase 19 audit follow-up (peer review fix sweep, batch 7+)

A second peer-review pass on the batch-4-through-7 polish stream
found a dozen issues. All fixed in this round:

- [x] **EQ A/B compare lost the B side on toggle.** `abShowingB`
  toggled the boolean without re-applying the preset gains, so
  the second tap of the banner left the bands on A while the
  label said "Comparing B". Now captures `abPresetGains` at
  `startAB` time and the toggle applies whichever side was just
  selected.
- [x] **EQ band slider didn't clear A/B comparison.** Moving a
  band slider mid-comparison silently overwrote the comparison
  state without releasing the banner — a subsequent banner tap
  could overwrite the user's manual tweak. `BandSliders.onChange`
  now routes through a wrapper that calls `clearAB()` first.
- [x] **EQ chip onClick was dead code under combinedClickable.**
  The `AssistChip(onClick = ...)` body was unreachable because
  the outer `combinedClickable` consumed the gesture. Empty
  lambdas now (`onClick = {}`) so the intent is explicit.
- [x] **MiniPlayer "Next:" stale on queue mutation while paused.**
  The `remember(state, playable.trackId)` block didn't observe
  queue mutations, so adding to the queue while paused didn't
  refresh the peek. Adds `priorityCount` (from
  `priorityCountFlow`) as a third remember key.
- [x] **`finiteQueueProgress` stuck on stale position when track
  not in snapshot.** When a priority-queued track or a history-
  popped track played, `indexOfFirst` returned -1 but the flow
  wasn't cleared, so "Track 4 of 12" persisted across an
  unrelated track. Now sets the flow to null in the no-match
  branch.
- [x] **`finiteQueueProgress` wrong on duplicate track ids.**
  `indexOfFirst { it.id == … }` returned the FIRST occurrence,
  so a list containing the same track twice (legitimate for
  playlists or explicit double-add) reported the wrong slot when
  the second occurrence played. Replaced with a position cursor
  + a new `resolveFiniteCursor()` helper that does sequential
  advance, replay-current detection, and outward-search for
  skipPrevious.
- [x] **History "Continue from here" wrong index + filter
  contamination.** Tapping a history row re-played the visible
  (filtered) list starting at index 0, so search-filtered users
  got a holes-in-the-session queue starting at the oldest
  visible entry instead of the tapped one. Now uses the full
  unfiltered `entries` list, computes the start index from the
  tapped HistoryEntry id, and seeds playback at that position.
- [x] **Search recent X icon double-fired the chip's onClick.**
  An `Icon` with `.clickable` placed in `AssistChip.trailingIcon`
  fires both the icon's clickable and the chip's outer onClick,
  re-running the just-deleted query. Restructured: chip + remove
  IconButton are now sibling cells in a Row, so each has its own
  isolated touch target.
- [x] **Search scope chip row clipped on narrow phones.** Five
  `FilterChip`s in a non-scrolling Row exceeded the viewport
  on a 360dp width. Added `.horizontalScroll(rememberScrollState())`
  to the row.
- [x] **`KEY_STATIONS_HIDE_BUILTINS` missing from backup.** The
  hide-built-ins toggle persisted locally but didn't round-trip
  through backup/restore. Added to the bool dump list and
  `expectedTypeForKey()`.
- [x] **`AndroidConnectivityChecker.isUnmetered` too permissive.**
  The OR-of-transports (NOT_METERED || WIFI || ETHERNET) returned
  true for Wi-Fi tethered to a metered cellular hotspot, defeating
  the WiFi-only connectivity policy. Now consults
  `NET_CAPABILITY_NOT_METERED` exclusively — the carrier-side
  signal is the only reliable indicator.
- [x] **Stations Home page header counts ignored Hide-built-ins
  toggle.** Subtitle read "10 stations · 6 built-in" while the
  grid showed only 4 customs — confusing. Now reads
  "4 custom · 6 built-in hidden" when the toggle is on.
- [x] **Stations Home grid blank when Hide-built-ins hides
  everything.** When all stations were built-in and the toggle
  was on, the grid silently rendered empty under a non-empty
  page header. Added an inline empty hint that points the user
  at the recovery action ("Add a custom station" or "untoggle
  Hide built-ins"). The substring filter has the same hint when
  it filters to nothing.
- [x] **LibraryBrowser songsFilter inert under artistFilter.**
  Typing in the Songs quick filter while an artist was filtered
  did nothing — `artistTracks` bypassed the filter pipeline
  entirely. Now applies the same substring match to
  `artistTracks` so the filter feels consistent.
- [x] **History granular trim had no confirmation.** "Clear last
  hour" and "Clear last 24 hours" deleted immediately on tap; the
  more-destructive "Clear all" had a confirm dialog. Both
  granular actions now route through a parameterised
  `trimConfirm` AlertDialog with the cutoff snapshotted at
  menu-tap time so the confirm-button delete window doesn't
  drift if the user takes 30 seconds to confirm.
- [x] **Stats date-range header `nowMs` never re-evaluated.**
  Captured in `remember { nowMillis() }` with no key, so a
  long-open StatsScreen could show a stale "Apr 4 – May 3" past
  midnight. Re-keyed on `dailyActivity` so the label moves in
  lock-step with the underlying data fetch.

#### Phase 19 polish (continued, batch 8)

- [x] **Detail-screen sort prefs persist.** Album / Artist /
  Genre detail screens persist their selected track sort via
  three new `SettingsStore` keys (`KEY_ALBUM_TRACK_SORT`,
  `KEY_ARTIST_TRACK_SORT`, `KEY_GENRE_TRACK_SORT`). Each screen
  reads on entry, writes on every selection change. Defaults
  fall through cleanly when the persisted enum value can't be
  parsed (a renamed/removed sort key is silently ignored).
- [x] **PlaylistDetail scroll-to-top FAB.** Mirrors the Library
  Songs and Album detail FAB pattern; appears bottom-right when
  `firstVisibleItemIndex > 8`, animates the LazyColumn back to
  position 0. Coexists cleanly with the drag-to-reorder
  affordance — the FAB is on the parent Box, not the LazyColumn,
  so a drag never accidentally fires it.
- [x] **Lyrics sync offset slider.** A new "Sync" text button on
  the Lyrics screen topbar (only shown when synced lyrics are
  loaded) opens an AlertDialog with a ±5 second slider in 100 ms
  steps. Three quick-stops: "BT" pre-sets −250 ms (typical
  Bluetooth latency), "Reset" zeros, and "+250 ms" mirrors. The
  offset is global — most drift comes from system-wide audio
  latency (BT, speakers), not per-track lyric error — and
  persisted via new `KEY_LYRICS_SYNC_OFFSET`. The synced-lyrics
  matching logic now subtracts the offset from `positionMs`
  before lookup so positive values delay the highlight, negative
  values pull it earlier.
- [x] **Stats time-window selector.** A row of three FilterChips
  (7 / 30 / 90 days) sits above the stat tiles. Selection is
  persisted via `KEY_STATS_WINDOW_DAYS`. The activity timeline,
  hour histogram, and weekday histogram all re-fetch with the
  chosen window via the existing `windowDays` parameter on each
  repository method. The streak stat continues to use a fixed
  90-day lookback — that tile is documented as a 90-day max
  regardless of the user's view window.
- [x] **Backup screen — "Delete all backups".** New top-bar
  overflow with a single action that opens an AlertDialog and,
  on confirm, iterates `files` calling `backupService.delete(uri)`
  on each. Tracks per-file failures and reports
  "Deleted N of M (X failed)" when partial.
- [x] **StationDetail scroll-to-top FAB.** Same pattern as the
  other detail screens: SmallFloatingActionButton wired to the
  LazyColumn's state, threshold `firstVisibleItemIndex > 8`.
- [x] **Now Playing — tap position to seek.** The position label
  beneath the scrubber is now clickable; opens
  `SeekToTimeDialog` with a TextField pre-filled with the
  current `M:SS`. Accepts `M:SS`, `H:MM:SS`, or bare seconds via
  a new `parseSeekInput()` helper. Useful for "skip to 1:23" on
  long podcasts/sets where the slider's hit zone is ~10 seconds
  wide.

#### Phase 19 polish (continued, batch 9)

- [x] **Stats — Top genres tile.** New
  `selectTopPlayedGenres` SQL joins station_history → track →
  track_tag (key='GENRE') and aggregates plays per tag value.
  Renders as a chip-cloud (`TopGenresChips` via FlowRow) below
  the existing top-played-albums list, capped at 8 entries.
- [x] **History — summary header.** Above the date-bucketed
  list a Surface row shows total plays, summed listened time,
  and avg-per-day for the visible window. Aggregation re-runs
  whenever the search/station filter changes; hidden when the
  visible list is empty.
- [x] **Settings — Clear artwork cache.** New three-step reset:
  drops every `artwork_cache` DB row (new SQL
  `deleteAllArtwork` + `ArtworkCacheRepository.deleteAll()`),
  wipes the on-disk artwork blobs (new `ArtworkPersister.clearAll()`
  expect/actual; Android iterates `filesDir/artwork/`, iOS stub
  returns 0 for now), and nulls every `album.artwork_uri`
  pointer (new `clearAllAlbumArtwork` SQL +
  `LibraryRepository` method). Status text reports the number
  of files removed; the next Cover Art sweep refetches.
- [x] **Track Info — combined "Copy artist – title".** A new
  full-width OutlinedButton in the Track Info dialog copies a
  `"Artist – Title"` formatted string to the clipboard (or just
  the title when the artist is missing). Useful for pasting
  what's currently playing into a chat or the Spotify/YouTube
  search box without sharing the actual file.
- [x] **About — Credits & open-source disclosure.** New
  expandable card under the App Settings → About section lists
  the major direct OSS dependencies shipping with the APK
  (Kotlin Multiplatform, Compose Multiplatform, Material 3,
  Media3 ExoPlayer, SQLDelight, Koin, Coil 3, kotlinx-
  serialization/coroutines, multiplatform-settings) plus the
  CC0 attributions for LRClib + Cover Art Archive content
  sources. Collapsed by default; tap the row to expand.
- [x] **LikedSongsScreen — sort menu.** New Sort icon in the
  top bar opens a dropdown with Recently liked / Title A→Z /
  Album / Duration shortest / Duration longest. Default
  matches the prior most-recently-liked order via a
  passthrough `LikedSort.RecentlyLiked` arm. Sort state is
  session-local (resets on screen exit, matching the other
  detail screens' UX).
- [x] **Playlists — M3U import via system file picker.** A new
  "Import" action in the Playlists screen top bar opens the
  system file picker scoped to `audio/x-mpegurl`. Picked bytes
  are decoded UTF-8 line-by-line (skipping blanks +
  `#EXTM3U`/`#EXTINF` metadata lines), each non-comment path
  resolved through the same `getTrackByPathOrSuffix` matcher
  the backup-restore flow uses — so a relative path or suffix-
  match resolves to a library row when possible. Resulting
  tracks land in a new playlist named "Imported m3u (Apr 4)"
  via `formatShortDate(nowMillis())`. A status line above the
  playlist rows reports
  "Imported N tracks · M not in library." until the user
  navigates away.

#### Phase 19 polish (continued, batch 10)

- [x] **Configurable auto-backup retention.** New
  `KEY_BACKUP_RETENTION` int pref (range 3..30, default 10) plus
  `getBackupRetention()` / `setBackupRetention()` accessors on
  `SettingsStore`. `BackupService.maxBackupsToRetain` is now a
  computed property reading from settings on every prune pass, so
  a slider tweak takes effect on the next export without an app
  restart. The pref also round-trips through `dumpForBackup()` /
  `restoreFromBackup()` (Int type entry) so a user's preferred
  retention follows them across devices. UI is a new
  `BackupRetentionSlider` row in App Settings's Audio/Backup
  block, mirroring the EndFadeSlider lift-up-state pattern so
  prefs writes only fire on release.
- [x] **Sleep timer — pause vs stop on expire.** New
  `KEY_SLEEP_STOP_ON_EXPIRE` boolean pref (default false). When
  enabled, `SleepTimer.start()`'s expire branch and
  `StationPlayback`'s end-of-track-pause consumer call
  `audioEngine.stop()` instead of `pause()`, clearing the playable
  from the engine and the MediaSession off the lock screen.
  Surfaced as a ToggleRow in App Settings → Audio with a subtitle
  explaining the trade-off (stop = fully quiet vs pause = one-tap
  resume). `SleepTimer` constructor now also takes `SettingsStore`
  via Koin so the toggle can be read at expire time.
- [x] **Stations Home — Move to top / Move to bottom.** New
  `StationRepository.moveStationToEnd()` renumbers every row in a
  single `db.stationsQueries.transaction { … }` so the resulting
  sort positions are dense (0..N-1), avoiding the wide gaps that
  swap-pair reorders used to leave behind. Hooked into
  `StationActionSheet` as two new ActionRows (icons:
  `VerticalAlignTop` / `VerticalAlignBottom`), greyed at the
  boundary the move would be a no-op.
- [x] **Lyrics — long-press to copy a line.** Synced lines now
  use `combinedClickable` with `onLongClick = onCopyLine(text)`;
  `PlainLyrics` was rewritten to split its body on `\n` so each
  line is an individually long-pressable Text. Both call sites
  share a single `rememberCopyToClipboard()` lambda hoisted
  above the Scaffold so the existing top-bar Copy button and the
  new per-line gesture write through the same clipboard hook.
- [x] **Search — empty-state DSL tips.** Below the recent
  searches block (and visible even when there are no recent
  searches), a new `DslTipsBlock` lists five common shorthand
  tokens (`year:>2010`, `year:1995`, `artist:foo`, `album:bar`,
  `rating:>=4`) as one-tap AssistChips that prefill the query
  field. Wraps the empty-state in a Column with
  `verticalScroll(rememberScrollState())` so smaller screens can
  scroll between recent searches and the tips card.
- [x] **Stats — Avg tracks/day tile.** Below the existing
  Completion/Finished pair, when totalPlays > 0, a new tile
  reports `totalPlays / windowDays` paired with a Window tile
  showing the active days span. Two-decimal formatting under 10
  preserves meaningful precision for low-volume habits, integer
  formatting otherwise keeps the layout tidy.
- [x] **Settings — Reset settings to defaults.** New
  `SettingsStore.resetAllUserSettings()` removes every user-
  portable preference key (theme, font scale, audio/RG/EQ enable,
  sort orders, sleep behavior, backup retention, etc.) but
  leaves library, ratings, playlists, station definitions,
  custom EQ presets, listening history, search history, and
  one-shot migration flags untouched. UI is a destructive
  Surface row at the bottom of App Settings; tapping it opens an
  AlertDialog confirm. After reset, every observable state value
  in the screen is re-pulled so the toggles show defaults
  immediately, and the live theme / Material You / font-scale
  callbacks fire so the parent re-themes without a relaunch.
- [x] **Playlist — Remove duplicates.** New dropdown menu item
  drops every duplicate trackId in the playlist's order
  (preserving the first occurrence), then routes through the
  existing `repo.setTrackOrder()`. Greys out when no duplicates
  exist, so the menu doesn't lie about having work to do.
- [x] **Onboarding — "Skip tour" on Welcome / Scan.** Adds an
  `onSkip` lambda for `OnboardingStep.Welcome` (calls
  `onComplete()` directly) and `OnboardingStep.Scan` (cancels the
  in-flight scan job, then `onComplete()`). The TextButton label
  switches between "Skip" and "Skip tour" based on step so
  returning users / re-installs see the right framing. Folder
  step still routes Skip → Done as before to avoid stranding
  users at "Starting…" on an empty queue.

#### Phase 19 polish (continued, batch 11)

- [x] **Now Playing — playback speed selector.** New
  `AudioEngine.setPlaybackSpeed(Float)` interface method, wired
  through to `MediaController.setPlaybackSpeed` on Android (clamped
  0.5..2.0; iOS stub no-op). Persisted via new
  `KEY_PLAYBACK_SPEED` Float pref so the choice survives both
  track changes (Media3 resets per-item) and app restarts.
  Surfaced as a "Speed" item in the Now Playing overflow menu
  that opens a `PlaybackSpeedSheet` bottom sheet with 0.75 / 1 /
  1.25 / 1.5 / 2× FilterChips.
- [x] **Stations Home — "Surprise me" quick action.** New
  SQL `selectRandomTrack` (`ORDER BY RANDOM() LIMIT 1` over
  `is_available = 1`) plus `LibraryRepository.randomAvailableTrack()`.
  An OutlinedIconButton with a casino-die icon sits in the page
  header alongside Reorder / Add station; tap picks one library
  track and routes it through `stationPlayback.playTracks`,
  opening Now Playing.
- [x] **History — Export as CSV.** New `HistoryExporter`
  expect/actual + `HistoryCsvRow` payload. Android impl writes
  RFC-4180-quoted rows into `cacheDir/history/`, exposes them via
  FileProvider, and fires `ACTION_SEND` with `text/csv` so
  spreadsheet apps and email clients show up in the chooser. Top
  bar adds a new "Export as CSV…" overflow item below the existing
  trim / clear actions; disabled when `entries` is empty so the
  menu doesn't promise an empty share. New `formatIsoUtc()`
  expect/actual produces RFC-3339 timestamps that spreadsheet
  apps reliably sniff as date columns.
- [x] **Lyrics — in-page search.** Top bar gains a "Find" /
  "Hide find" toggle (synced + plain lyrics only); when on, a
  small OutlinedTextField appears above the lyric list. Synced
  lines compute `matchingIndices` once per `(lines, query)`,
  scroll the LazyColumn to the first match via a
  `LaunchedEffect`, and tint each matching row with a tertiary
  background for at-a-glance scan.
- [x] **Settings — Clear DJ cache.** New SQL `deleteAllDjAudio`
  + `DjCacheRepository.deleteAll()` and `listAll()` (re-uses the
  existing `selectDjAudioOlderThan` index with a `Long.MAX_VALUE`
  upper bound so the index drives the read). UI is a new
  `ClearDjCacheRow` mirroring the artwork row; tapping iterates
  the cached blob paths via a new `deleteFileIfExists()`
  expect/actual to wipe on-disk audio, then truncates the
  `dj_cache` table. Status text reports the file count for parity
  with the artwork action.
- [x] **Playlist — inline rename via long-press.** PlaylistRow
  now uses `combinedClickable` with `onLongClick` opening a
  RenamePlaylistDialog. Dialog uses `ImeAction.Done` so the
  on-screen keyboard's primary action submits, and routes
  through `PlaylistRepository.rename()` — which already trims +
  guards blank.
- [x] **About — surface build date.** New `BuildInfo.BUILD_DATE`
  constant, rendered alongside the version line in App Settings →
  About: "RadioShed v0.1.0 · built 2026-05-03". Manual stamp for
  now; Gradle automation (`git log -1 --format=%cd`) is on the
  follow-up list.
- [x] **Equalizer — copy gains as JSON.** New "Copy gains" top-
  bar action that dumps the live band-gain vector to the
  clipboard as a JSON array (e.g. `[1.5, -2, 0, 0.5, 1]`) using
  a `dbFormatForCopy` helper that drops trailing zeros to keep
  the string compact. Disabled until the EQ surface activates and
  has bands to copy.

#### Phase 19 polish (continued, batch 12)

- [x] **Settings — Keep screen on during playback.** New
  `KEY_KEEP_SCREEN_ON` boolean pref + `KeepScreenOn` expect/actual.
  Android impl walks the context chain to find the host Activity and
  toggles `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` based on
  the active state; cleared automatically on dispose. Now Playing
  applies it whenever the pref is on AND the engine state is
  `Playing`, so Pause / Idle releases the lock and the screen times
  out normally between tracks.
- [x] **Settings — Auto-fetch lyrics over network.** New
  `KEY_AUTO_FETCH_LYRICS` boolean (default true). LyricsService now
  takes a SettingsStore dependency and gates LRClib network calls
  on `forceRefresh OR settings.getAutoFetchLyrics()` — local sidecar
  + embedded lookups still run unconditionally. Manual force-refresh
  from the lyrics top-bar always proceeds.
- [x] **Settings — Haptic feedback toggle.** New
  `KEY_HAPTICS_ENABLED` boolean + `rememberHapticTick` Composable
  helper that wraps `LocalHapticFeedback` and short-circuits when
  the pref is off. App-wide single source of truth for "should this
  long-press buzz."
- [x] **Now Playing — long-press play/pause → toggle shuffle.**
  `BigIconButton` now optionally takes `onLongClick`; when set, it
  switches from a stock IconButton to a Surface + `combinedClickable`
  with the same hit-target. The play/pause button passes through
  `onLongPressTogglePlayPause` from CompactLayout / TwoPaneLayout up
  to the screen, which routes to `stationPlayback.toggleShuffle()`
  and a haptic tick.
- [x] **Now Playing — long-press queue → Save queue as playlist.**
  Queue badge's outer Modifier swaps `clickable` for
  `combinedClickable`; long-press snapshots `stationPlayback.upcoming(64)`
  and opens the existing Save-queue-as-playlist dialog directly
  (skipping the bottom-sheet step). No-op when the queue is empty.
- [x] **PlaylistsScreen — filter field.** Surfaces above the list
  when the user has ≥6 playlists (filter would be clutter below
  that). Substring match (case-insensitive) on playlist name; empty
  result renders a "No playlists match …" hint inline so the user
  doesn't see a blank screen and assume the list broke.
- [x] **Liked Songs — search filter.** Same find-in-place pattern
  as the History screen's text search. Composes with the existing
  sort dropdown (intersection of both); session-local state, mirrors
  the other detail-screen filter UX.
- [x] **Stats — this-week vs last-week delta.** New
  `Stations.sq listenedMsBetween` SQL + `StationRepository.listenedMsBetween`.
  Renders an arrow chip beside the Total listened tile (▲ green
  for growth, ▼ red for decline, • for flat). Hidden when last
  week had zero listened time so a 1ms blip doesn't show as ∞%.
  StatTile gained an optional `trailing` slot to host the chip.
- [x] **About — source URL row + copy build info.** Two new rows
  under About; one opens `github.com/anthropics/radioshed` via
  the new `openWebUrl` expect/actual (Android: ACTION_VIEW with
  FLAG_ACTIVITY_NEW_TASK; iOS: `UIApplication.openURL`), the other
  copies "RadioShed v0.1.0 (code 1) built 2026-05-04" to the
  clipboard for paste-into-bug-report flows.
- [x] **Search — "Clear all recents" confirmation.** RecentSearchesBlock
  Clear button now opens an AlertDialog reporting how many entries
  will be removed; previously a single tap nuked the list with no
  warning. Confirm button uses error-tinted text so accidental
  Enter doesn't fire it.
- [x] **Stations Home — long-press Surprise → Shuffle 25.** New
  SQL `selectRandomTracks` + `LibraryRepository.randomAvailableTracks(count)`.
  The casino-die icon switched from OutlinedIconButton to a Surface
  + `combinedClickable` so tap (one random track) and long-press
  (queue 25 random tracks, navigate to Now Playing) coexist. Falls
  back gracefully on small libraries — fewer rows just means a
  shorter queue.
- [x] **Track Info — Wikipedia search button.** Full-width
  OutlinedButton that opens `en.wikipedia.org/wiki/Special:Search`
  with the artist tag (URL-escaped via simple replace; the artist
  string is small enough that the full encoder isn't worth pulling
  in). Disabled when the track has no artist tag.
- [x] **Equalizer — Reset all bands.** New "Reset" top-bar action
  that flattens every band to 0 dB via the existing `applyGains`
  lambda — same path used by preset selection so SettingsStore +
  the live EQ session both update without a separate persistence
  branch.
- [x] **Track context sheet — "Copy artist – title" action.**
  Now resolves the primary artist's NAME alongside the id (the
  sheet was already fetching the artist row for the Go-to-artist
  button, so this is one more field on the same query). Renders
  a ContentCopy ActionRow above the Global-dislike toggle that
  routes the formatted "Artist – Title" string through
  `rememberCopyToClipboard`, dismissing the sheet after.

#### Phase 19 polish (continued, batch 13)

- [x] **Track Info — MusicBrainz / YouTube / Discogs search.** Three
  more full-width OutlinedButtons join the Wikipedia row, sharing a
  small `encodeForUrl` helper that hand-rolls percent-encoding (we
  don't depend on a URL-encoder for these search-form lookups).
  MusicBrainz + Discogs run on the artist tag; YouTube combines
  artist + title for a direct music-video hit and stays enabled even
  when the track has no artist tag.
- [x] **PlaylistsScreen — scroll-to-top FAB.** Wraps the LazyColumn
  in a Box; SmallFloatingActionButton at BottomStart so it doesn't
  overlap the existing primary "New playlist" FAB at BottomEnd.
  Same `firstVisibleItemIndex > 8` threshold as PlaylistDetail /
  StationDetail / Library Browser for cross-screen consistency.
- [x] **Liked songs — scroll-to-top FAB.** Same pattern; sits at
  BottomEnd here since LikedSongsScreen has no primary FAB.
- [x] **PlaylistsScreen — total track count subtitle.** TopAppBar
  title became a Column with "Playlists" + a bodySmall subtitle
  reading "N playlists · M tracks" derived from
  `trackCountByPlaylistId.values.sum()`. Hides when the playlist
  list is empty so the subtitle isn't a confusing "0 tracks" on
  cold start.
- [x] **PlaylistsScreen — long-press → Rename / Delete sheet.**
  Long-press now opens a ModalBottomSheet (`PlaylistActionsSheet`)
  with Rename + Delete rows. Rename routes through the existing
  dialog; Delete prompts a confirm (irreversible action) before
  calling `repo.delete(id)`. Pattern mirrors StationActionSheet.
- [x] **Liked songs — total duration in header.** Subtitle now
  reads "N tracks · Xh Ym" via `formatLikedTotalRuntime` (tiered
  hours/minutes formatting; sub-minute totals fall back to
  "<1m"). Hidden when total is zero so the cold-start row stays
  clean.
- [x] **Liked songs — filter matches title OR file name.** Was
  title-only; now matches file name too so users searching for
  rips with empty title tags ("Track01.mp3") still find them.
  Composes with the existing sort dropdown identically.
- [x] **Equalizer — long-press band frequency to reset.** The 56dp
  frequency label became a `combinedClickable` target with
  `onClick = {}` (so taps don't intercept slider scroll-edge
  gestures) and `onLongClick` flattening that one band to 0 dB
  via the existing `pendingValue` + `onChange(index, 0f)` path.
  Footer hint advertises the gesture so it's discoverable.
- [x] **Stats — "Listening since" tile.** New SQL
  `Stations.sq earliestPlayedAt` (`MIN(played_at) AS earliest_ms`)
  + `StationRepository.earliestPlayedAt(): Long?`. The Stats screen
  hydrates `listeningSinceMs` and renders a two-tile row (date
  + day count) below the streak tiles. Hides on cold-start when
  station_history is empty.
- [x] **History — time-window filter chips.** New
  `HistoryTimeWindow` enum (All / 24h / 7d / 30d) rendered as a
  horizontalScrollable Row of FilterChips above the station chip
  row. Composes with the station + free-text filters — visible
  list is the intersection of all three. Cutoff is recomputed at
  filter-eval time so leaving the screen open across midnight
  rolls the window forward correctly.
- [x] **History — Clear last 7 days.** New overflow-menu option
  alongside Clear last hour / 24h. Same monotonic-cutoff +
  trim-confirm-dialog flow as the existing window options;
  pairs nicely with the new Week filter chip for preview-then-trim.
- [x] **App Settings — Clear all online caches.** New
  `ClearAllCachesRow` below the granular ClearLyrics /
  ClearArtwork / ClearDjCache rows. One-tap wipes all three
  sequentially (sequential rather than parallel — both
  ArtworkPersister and DJ-blob deletion walk filesDir and trip
  on each other otherwise). Aggregate status string echoes back
  the per-cache counts.

#### Phase 19 polish (continued, batch 14)

- [x] **Track Info — SoundCloud + Bandcamp search buttons.** Two
  more web search rows alongside Wikipedia / MusicBrainz / Discogs
  / YouTube. SoundCloud's `/search?q=` and Bandcamp's `/search?q=`
  accept the same percent-encoded `encodeForUrl` payload. Use the
  artist tag when present, otherwise fall back to the track title
  (some Bandcamp listings are by track title only).
- [x] **History — Clear last 30 days.** Closes the parity gap with
  the time-window filter chips. Same monotonic-cutoff +
  trim-confirm flow as the existing 1h / 24h / 7d options.
- [x] **Liked Songs — confirm before unlike.** The trailing heart
  button used to fire `ratingRepo.unlike` on a single tap with no
  undo. Now routes through an AlertDialog showing the track title
  ("Unlike \"Title\"?") with an error-tinted Unlike button. Long-
  press still goes to the track context sheet (full action menu).
- [x] **Now Playing — long-press repeat → reverse cycle.** Tap is
  unchanged (Off → All → One). Long-press inverts the direction
  (One → All → Off → One), saving the over-shoot tap when the
  user wants to back out of "Repeat one." Switched the underlying
  IconButton to a `Surface(48dp) + combinedClickable` with
  `indication = null` — the codebase pattern for tap-vs-long-press
  on a single icon, since nesting clickable IconButtons inside a
  parent combinedClickable double-fires per #404.
- [x] **Stats — Distinct artists / albums tile.** New SQL
  `Stations.sq distinctArtistsPlayed / distinctAlbumsPlayed`
  joining station_history through track_artist / track. Renders
  as a two-tile row below the streak / listening-since rows so
  the user reads "breadth" alongside "depth". INNER JOIN is
  intentional — tracks deleted post-listen drop out rather than
  being re-attributed to NULL.
- [x] **Sleep timer — 10 + 90 minute options.** Adds 10 (quick
  EP-side) and 90 (long commute / album side) to the existing
  5/15/30/45/60 list. Capping at 90 still — beyond that the
  queue's natural length is the better runtime bound.
- [x] **App Settings — Report a bug + License rows.** Two new
  AboutLinkRows under About; one opens `radioshed/issues`, the
  other opens `LICENSE` on master. Pairs with the existing Copy-
  build-info row so a bug-reporter can grab the build string and
  jump straight to the issue tracker.
- [x] **PlaylistsScreen — filter-aware subtitle.** TopAppBar
  subtitle now reads "M of N playlists" while the filter is
  active, falling back to the lifetime "N playlists · M tracks"
  when the filter is empty. Mid-search the visible-count
  matters more than the lifetime track total.
- [x] **Now Playing — sleep timer icon long-press.** Long-press
  the bedtime icon arms a 30-min timer (the median pick) when
  idle, or cancels the running timer otherwise. Tap still opens
  the full menu either way. Saves a multi-step tap-tap-tap on
  the most common sleep-timer flows.
- [x] **Liked Songs — long-press header heart → Save as playlist.**
  Mirrors the overflow menu's "Save as playlist…" action. The
  56-dp header tile got a `combinedClickable` with `onClick = {}`
  (so taps don't intercept) and `onLongClick` opening the
  existing save-as dialog. No-op when there are zero liked
  tracks.
- [x] **History — tap summary to copy.** The summary surface
  (plays / listened / avg-per-day) became `clickable`; tap
  copies the formatted "{plays} plays · {listened} · {avg}/day"
  string to the clipboard. Single-tap is fine here — the row is
  purely informational, no other gesture to collide with.
- [x] **Track Info — long-press path row to copy file name.**
  The existing path row used a single `clickable` to copy the
  full path; switched to `combinedClickable` so long-press copies
  just the basename. Useful for tag-fix workflows that don't
  want the leading directory tree.

#### Phase 19 polish (continued, batch 14 — peer-review fixes)

A peer-review pass over batches 12–14 surfaced these regressions and
gaps. All shipped together as a follow-on pass before batch 15.

- [x] **FIX: NowPlaying `keepScreenOnPref` frozen at first composition.**
  `val keepScreenOnPref = remember { settings.getKeepScreenOn() }`
  read the pref once at composition and ignored subsequent writes
  — toggling Keep-screen-on from Settings had no effect on the
  active session until app restart. Added a `keepScreenOnFlow`
  `MutableStateFlow` mirror to `SettingsStore` (matching the
  existing `driveModeFlow` pattern) and switched NowPlayingScreen
  to `collectAsState` for live updates.
- [x] **FIX: `restoreFromBackup` and `resetAllUserSettings` bypass
  StateFlow mirrors.** Both writers `settings.putBoolean(...)`
  directly, which updates the underlying store but skips the
  setter side-effect that pushes new values through
  `_driveModeFlow` / `_keepScreenOnFlow`. Live consumers stayed
  on stale values until app restart. Added a private
  `republishMirrorFlows()` helper called at the end of both
  paths so flow values converge with the store.
- [x] **FIX: OverflowSheet (narrow screens) missing Playback Speed
  action.** Wide screens (≥360 dp) reach the speed sheet via the
  Now Playing DropdownMenu. Narrow screens (Galaxy Fold front
  cover, small budget phones) had no entry point at all because
  the menu collapses into `OverflowSheet` and that sheet was
  shipped without the Speed item. Added a `Speed (N×)` row using
  `Icons.Filled.Speed`, threaded `playbackSpeed` + `onSpeedPicker`
  callbacks from the call site.
- [x] **FIX: Stats "Avg tracks/day" used lifetime denominator.**
  `totalPlays.toDouble() / windowDays.toDouble()` divides the
  lifetime play count by the user-selected window — selecting
  "7 days" with 10 000 lifetime plays produced "1428.57/day".
  Switched to `windowedPlays = dailyActivity.sumOf { it.plays }`
  which is already windowed by the same picker, so the rate
  matches the chart above it.
- [x] **FIX: EQ band long-press reset missing haptic.** Per the
  CLAUDE.md "haptics on every long-press" convention. Plumbed
  `rememberHapticTick()` into `BandSliderRow` and called it
  alongside the `onChange(index, 0f)` write.
- [x] **FIX: Now Playing repeat reverse-cycle missing haptic.**
  Same convention. Long-press now calls `hapticTick()` after
  the `setRepeatMode(next)` write.
- [x] **FIX: Track Info `encodeForUrl` swallowed `+` and `/`.**
  Artist names containing `+` (e.g. "+44") or `/` (e.g. "AC/DC")
  produced broken URLs — the `+` was interpreted as a space
  on the receiver side and the `/` was parsed as a path
  separator. Added `%2B` (literal +), `%2F` (slash), and
  `%3D` (=) escapes; ordered after `%` itself so we don't
  double-encode. Affects every web search button (Wikipedia,
  MusicBrainz, Discogs, YouTube, SoundCloud, Bandcamp).

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
