# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project shape

RadioShed is a private Kotlin Multiplatform / Compose Multiplatform music app
targeting Android 13+ and iOS 16+. There is **one Gradle module** —
`:composeApp` — with `commonMain`, `androidMain`, and `iosMain` source sets.
There is no separate `shared/`, `androidApp/`, or `iosApp/` module despite
what `README.md` describes for "target" layout; that document precedes the
current single-module reality.

`SPEC.md` is the canonical product roadmap. The phased plan in §20 uses
checkboxes (`- [ ]` / `- [~]` / `- [x]`); when flipping a box, also write a
one-line cite of the implementation (filename, function name, or task id).
Stale checkboxes are worse than missing ones.

## Common commands

The dev host is **Windows 11**. `adb` lives at
`C:\android-sdk\platform-tools\adb.exe` (not on PATH). The Galaxy Fold 7 test
device id is in `memory/user_device.md`.

```bash
# Build a debug APK (output: composeApp/build/outputs/apk/debug/composeApp-debug.apk)
./gradlew :composeApp:assembleDebug

# Build release
./gradlew :composeApp:assembleRelease

# Install latest debug build on the connected device
"C:\android-sdk\platform-tools\adb.exe" install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk

# Run JVM-side unit tests (commonTest sources execute via the Android variant)
./gradlew :composeApp:testDebugUnitTest

# Single test class
./gradlew :composeApp:testDebugUnitTest --tests "com.visorcraft.radioshed.data.SearchDslTest"

# Force SQLDelight regen after editing .sq / .sqm files
./gradlew :composeApp:generateCommonMainRadioShedDatabaseInterface --rerun-tasks

# iOS framework (macOS only)
./gradlew :composeApp:assembleXCFramework
```

JDK 17 is required (`compileOptions = VERSION_11` is the *bytecode* target,
but the Gradle/AGP toolchain itself needs 17).

## Architecture

### Layering

- `domain/` — pure-Kotlin data classes (`Station`, `Track`, `SourceRule`,
  `PlaybackRule`, `EqPreset`, `Lyrics`, …). No platform deps.
- `data/` — repositories over SQLDelight, plus `SettingsStore`. Every
  repository is a `single { }` in Koin's `sharedModule`. All DB I/O goes
  through `withContext(IODispatcher)`. **Never call `multiplatform-settings`
  directly from a screen — go through `SettingsStore`** with a `KEY_*`
  constant; this is what makes backup/restore round-trip correctly.
- `source/` — outbound integrations (LRClib lyrics, Cover Art Archive,
  backup JSON, file browser, exporters). Online fetchers must consult
  `NetworkPolicyGate` so the user's Always / Wi-Fi only / Never preference
  is honoured.
- `audio/` — `AudioEngine` (expect/actual), `EqualizerController`,
  `SleepTimer`. Android impl is Media3 ExoPlayer + MediaSession; iOS is
  AVAudioEngine.
- `stations/` — `StationPlayback` (the playback orchestrator),
  `PlaybackQueue` (priority queue + history), `SourceRuleEvaluator`,
  `StationPresets` / `PresetSeeder`. Queue mutations are serialized
  through `kotlinx.coroutines.sync.Mutex.withLock`.
- `scanner/` — library scan pipeline. `FileEnumerator` and `TagReader` are
  expect/actual (Android uses `jaudiotagger`, iOS is currently stubbed).
- `ui/<feature>/` — Compose screens named `<Feature>Screen.kt`. State is
  hoisted: screens take callbacks + state, `App.kt` wires them together.
  Reusable bits live under the feature folder or `ui/util/`.

### Cross-platform abstractions

These follow the `expect val ...` / `expect fun ...` pattern with `.android.kt`
and `.ios.kt` actuals. When adding a feature that needs a platform peer, look
for an existing pattern first — there are already actuals for:

- `data/DatabaseDriverFactory` — Android uses `requery:sqlite-android`
  (bundled modern SQLite, see below); iOS uses NativeSqliteDriver.
- `data/Time.kt` — `nowMillis`, `currentYear`, `formatShortDate`,
  `formatIsoUtc`.
- `source/FileEnumerator`, `TagReader`, `ArtworkPersister`, `FolderRevealer`,
  `WebOpener`, `RingtoneSetter`, `BackupSharer`, `TrackSharer`,
  `PlaylistExporter`, `HistoryExporter`, `FileOps.deleteFileIfExists`.
- `permissions/` — runtime permissions (Android-only impl that no-ops on iOS).
- `ui/util/KeepScreenOn`, `ui/util/Haptics` (haptics are gated by
  `SettingsStore.getHapticsEnabled()`), `ui/util/VoiceInput`,
  `ui/util/ImagePicker`, `ui/util/FilePicker`.
- `nav/PlatformBackHandler`.

The `-Xexpect-actual-classes` flag is enabled in `composeApp/build.gradle.kts`
to suppress the beta warning — that's intentional, do not remove.

### Persistence

Schema lives in `composeApp/src/commonMain/sqldelight/com/visorcraft/radioshed/db/`:

- `Library.sq`, `Stations.sq`, `Playlists.sq`, `Ratings.sq`, `Folders.sq`,
  `Caches.sq`, `Backups.sq`, `Cast.sq`, `AudioSettings.sq`, `Widgets.sq`.
- Migrations are `migrations/<n>.sqm`. **`verifyMigrations` is intentionally
  OFF** — see the comment in `composeApp/build.gradle.kts`. The verifier
  parses each `.sqm` in isolation and can't see the FK targets defined in the
  baseline `.sq` files, so every FK reference fails with "No column found".
  Re-stating prior schema at the top of each `.sqm` to enable verification is
  tracked in SPEC Phase 18; do not flip the flag without that refactor.
- After editing `.sq` or `.sqm`, regenerate with `--rerun-tasks` (the task is
  cached aggressively).

The Android driver uses `com.github.requery:sqlite-android` (bundled SQLite
3.45 via JitPack), not the system SQLite. Reason: head-unit Android images
ship SQLite 3.32 which predates `RETURNING` (3.35), which the generated
queries use. The JitPack repo is clamped to `com.github.requery` only — keep
it that way.

### DI

Koin is split:
- `platformModule` (expect val, actuals in `androidMain/di` and `iosMain/di`)
  binds `DatabaseDriverFactory`, `Settings`, `AudioEngine`, `FileEnumerator`,
  `TagReader`, `ArtworkPersister`, `ConnectivityChecker`, `LyricsFetcher`,
  `CoverArtFetcher`, `FolderRevealer`, etc.
- `sharedModule` binds repositories, `SettingsStore`, `Scanner`,
  `LyricsService`, `BackupService`, `StationPlayback`, `SleepTimer`,
  `MissingArtworkSync`, `PresetSeeder`. All `single`.

When adding a constructor parameter to anything in `sharedModule`, update the
`single { ... }` line — the DSL takes positional `get()` calls, so a wrong
arity produces a runtime "no definition for…" rather than a compile error.

### Backup format

`SettingsStore.dumpForBackup()` and `restoreFromBackup()` round-trip a typed
map of every `KEY_*` setting. Restore validates each value's type against
`expectedTypeForKey(...)` before writing — adding a new setting means adding
its key to **all three**: getters/setters, `dumpForBackup` (in the right
type bucket), and `expectedTypeForKey`. Forgetting any of these is the most
common backup bug — there are several FIX entries in SPEC.md for it.

## Conventions

- **Kotlin:** 4-space indent, trailing commas on multi-line parameter lists.
  Named arguments when calling with three or more parameters.
- **Comments:** explain *why*, not *what*. Names cover the *what*. Long-form
  rationale (race conditions, format compatibility, vendor quirks) is
  encouraged — many existing comments document hard-won lessons (Samsung
  Noto glyph clipping, head-unit SQLite version, JitPack supply-chain
  scoping). Don't strip these as "noise".
- **Compose:** screen composable per file, named `<Feature>Screen.kt`.
  Long-press menus go through `TrackContextSheet` /
  `CollectionContextSheets` rather than ad-hoc `DropdownMenu`s — that's
  what gets the consistent "Add to playlist", "Go to album/artist", "Play
  next", "Add to queue", "Track info" actions.
- **Haptics:** use `rememberHapticTick()` from `ui/util/Haptics.kt`. It
  reads `SettingsStore.getHapticsEnabled()` so the user-facing toggle works.
- **Commits:** imperative present, ≤ 72-char subject. Reference SPEC items
  by phase number or task id.

## SPEC ↔ task ↔ memory relationship

- The auto-memory at `memory/MEMORY.md` holds non-derivable facts (test
  device, batching feedback). Don't duplicate the SPEC's content there.
- The task list (`#NNN` ids) is the unit of granular tracking. New work
  gets a new id; ids monotonically increase.
- Each "build, install, document batch N" task closes a batch — that's
  when SPEC.md gets the batch entry and `BuildInfo.BUILD_DATE` is bumped
  manually to today's UTC date.
