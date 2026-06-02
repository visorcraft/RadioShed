# AGENTS.md

Guidelines for AI coding agents (Claude Code, Codex, Gemini, etc.) working
in this repository. The conventions below are derived from
`.editorconfig`, `gradle.properties`, and dominant patterns in
`composeApp/src/`. They MUST be followed for new and modified code.

This file is the authoritative agent guide. `CLAUDE.md` is intentionally only
a stub that redirects Claude-family agents here. The product roadmap lives in
**`SPEC.md`**.

---

## Project Shape

RadioShed is a private Kotlin Multiplatform / Compose Multiplatform music app
targeting **Android 11+** (minSdk 30; target/compileSdk 36), **Windows 11+**
and **Linux** (Compose Desktop / JVM), and **iOS 16+** (the iOS branch is
stubbed pending Mac access). There
is **one Gradle module** — `:composeApp` — with `commonMain`, `androidMain`,
`desktopMain`, and `iosMain` source sets. There is no separate `shared/`,
`androidApp/`, or `iosApp` module.

The desktop target is the JVM target named `desktop` (per `kotlin {
jvm("desktop") }` in `composeApp/build.gradle.kts`); the source set follows
that convention as `desktopMain`. Linux desktop compile, run, single-file
`.AppImage`, and `.deb` packaging are verified and are useful when iterating
on Compose UI without spinning up an Android emulator.

`SPEC.md` is the canonical product roadmap. The phased plan in §20 uses
checkboxes (`- [ ]` / `- [~]` / `- [x]`); when flipping a box, also write a
one-line cite of the implementation (filename, function name, or task id).
Stale checkboxes are worse than missing ones.

## Common Commands

The main Android dev host is **Windows 11**. `adb` lives at
`C:\android-sdk\platform-tools\adb.exe` (not on PATH).

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

# Desktop dev loop — compile + launch the Compose Desktop window.
# First run pulls deps + a JBR JDK; subsequent runs are seconds.
./gradlew :composeApp:run

# Package desktop artifacts for the host OS. On Linux x86_64 this builds
# the jpackage app-image directory, a true single-file .AppImage, and .deb.
# On Windows/macOS this builds the configured native installer for that host.
./gradlew :composeApp:packageDistributionForCurrentOS

# Build only the true single-file Linux .AppImage.
./gradlew :composeApp:packageSingleFileAppImage

# iOS frameworks (macOS only). The iOS targets (iosArm64,
# iosSimulatorArm64) build a *static* framework named `ComposeApp` via
# Kotlin/Native link tasks, e.g. linkReleaseFrameworkIosArm64. No
# XCFramework holder is configured, so there is NO assembleXCFramework
# task — the iOS branch is stubbed pending Mac access (see Project Shape).
./gradlew :composeApp:linkReleaseFrameworkIosArm64
```

The desktop runtime needs **libvlc** (VLC 3.x) installed on the host. VLCJ
discovers it via `NativeDiscovery().discover()` at startup. On Linux that's
`apt install vlc` or the distro equivalent; on Windows it's the standard VLC
installer; on macOS it's the VLC.app bundle. Without libvlc, `:run` launches
but `AudioEngine` errors out the moment a track tries to play.

Linux packaging details:

- `TargetFormat.AppImage` is Compose/JPackage's app-image directory, not a
  user-facing single-file `.AppImage`.
- `:composeApp:packageSingleFileAppImage` wraps that directory with
  AppImageKit `appimagetool` and writes
  `composeApp/build/compose/binaries/main/appimage/RadioShed-<version>-x86_64.AppImage`.
- `APPIMAGETOOL=/path/to/appimagetool` or `appimagetool` on PATH takes
  precedence. Otherwise Gradle downloads the x86_64 AppImageKit tool into
  `composeApp/build/compose/tools/`.
- `.deb` packaging needs `dpkg-deb` on PATH; Arch/CachyOS provide it via the
  `dpkg` package.

JDK 17 is required (`compileOptions = VERSION_11` is the bytecode target, but
the Gradle/AGP toolchain itself needs 17).

## Architecture

### Layering

- `domain/` — pure-Kotlin data classes (`Station`, `Track`, `SourceRule`,
  `PlaybackRule`, `EqPreset`, `Lyrics`, etc.). No platform deps.
- `data/` — repositories over SQLDelight, plus `SettingsStore`. Every
  repository is a `single { }` in Koin's `sharedModule`. All DB I/O goes
  through `withContext(IODispatcher)`. Never call `multiplatform-settings`
  directly from a screen — go through `SettingsStore` with a `KEY_*`
  constant; this is what makes backup/restore round-trip correctly.
- `source/` — outbound integrations (LRClib lyrics, Cover Art Archive,
  backup JSON, file browser, exporters). Online fetchers must consult
  `NetworkPolicyGate` so the user's Always / Wi-Fi only / Never preference
  is honoured.
- `audio/` — `AudioEngine` (expect/actual), `EqualizerController`,
  `SleepTimer`. Android impl is Media3 ExoPlayer + MediaSession; iOS is
  AVAudioEngine; desktop is VLCJ over libvlc 3.x (the installed system
  libvlc, discovered at startup). VLCJ media-event callbacks fire on VLCJ's
  own threads; `AudioEngineDesktop` marshals StateFlow updates back onto the
  Compose UI thread via `kotlinx-coroutines-swing`.
- `stations/` — `StationPlayback` (the playback orchestrator),
  `PlaybackQueue` (priority queue + history), `SourceRuleEvaluator`,
  `StationPresets` / `PresetSeeder`. Queue mutations are serialized through
  `kotlinx.coroutines.sync.Mutex.withLock`.
- `scanner/` — library scan pipeline. `FileEnumerator` and `TagReader` are
  expect/actual (Android and desktop both use `jaudiotagger`, iOS is
  currently stubbed).
- `ui/<feature>/` — Compose screens named `<Feature>Screen.kt`. State is
  hoisted: screens take callbacks + state, `App.kt` wires them together.
  Reusable bits live under the feature folder or `ui/util/`.

### Cross-Platform Abstractions

These follow the `expect val ...` / `expect fun ...` pattern with platform
actuals. When adding a feature that needs a platform peer, look for an
existing pattern first. Current peers include:

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
to suppress the beta warning. That is intentional; do not remove it.

### Persistence

Schema lives in `composeApp/src/commonMain/sqldelight/com/visorcraft/radioshed/db/`:

- `Library.sq`, `Stations.sq`, `Playlists.sq`, `Ratings.sq`, `Folders.sq`,
  `Caches.sq`, `Backups.sq`, `Cast.sq`, `AudioSettings.sq`, `Widgets.sq`.
- Migrations are `migrations/<n>.sqm`. **`verifyMigrations` is intentionally
  off** — see the comment in `composeApp/build.gradle.kts`. The verifier
  parses each `.sqm` in isolation and cannot see FK targets defined in the
  baseline `.sq` files, so every FK reference fails with "No column found".
  Re-stating prior schema at the top of each `.sqm` to enable verification is
  tracked in SPEC Phase 18; do not flip the flag without that refactor.
- After editing `.sq` or `.sqm`, regenerate with `--rerun-tasks` because the
  task is cached aggressively.

The Android driver uses `com.github.requery:sqlite-android` (bundled SQLite
3.45 via JitPack), not the system SQLite. Reason: head-unit Android images
ship SQLite 3.32 which predates `RETURNING` (3.35), which the generated
queries use. The JitPack repo is clamped to `com.github.requery` only — keep
it that way.

### DI

Koin is split:

- `platformModule` (expect val, actuals in platform source sets) binds
  `DatabaseDriverFactory`, `Settings`, `AudioEngine`, `FileEnumerator`,
  `TagReader`, `ArtworkPersister`, `ConnectivityChecker`, `LyricsFetcher`,
  `CoverArtFetcher`, `FolderRevealer`, etc.
- `sharedModule` binds repositories, `SettingsStore`, `Scanner`,
  `LyricsService`, `BackupService`, `StationPlayback`, `SleepTimer`,
  `MissingArtworkSync`, `PresetSeeder`. All are `single`.

When adding a constructor parameter to anything in `sharedModule`, update the
`single { ... }` line. The DSL takes positional `get()` calls, so a wrong
arity produces a runtime "no definition for..." rather than a compile error.

### Backup Format

`SettingsStore.dumpForBackup()` and `restoreFromBackup()` round-trip a typed
map of every `KEY_*` setting. Restore validates each value's type against
`expectedTypeForKey(...)` before writing. Adding a new setting means adding
its key to all three:

- getter/setter,
- `dumpForBackup(...)` in the right type bucket,
- `expectedTypeForKey(...)`.

Forgetting any of these is the most common backup bug; there are several FIX
entries in `SPEC.md` for it.

### SPEC ↔ Task ↔ Memory Relationship

- The agent-managed auto-memory (maintained outside the repo) holds
  non-derivable facts such as batching feedback. Do not duplicate the SPEC's
  content there.
- The task list (`#NNN` ids) is the unit of granular tracking. New work gets
  a new id; ids monotonically increase.
- Each "build, install, document batch N" task closes a batch. That is when
  `SPEC.md` gets the batch entry and the `buildDate` field in
  `version.properties` (repo root) is bumped to today's UTC date. It flows
  into the generated `BuildInfo.BUILD_DATE` via the `generateBuildInfo`
  task; the generated `BuildInfo.kt` itself is do-not-edit.

## Coding Conventions

> **For AI Models:** Follow these rules strictly. Anything not specified
> here defers to the Kotlin official style guide
> (`kotlin.code.style=official` in `gradle.properties`).

### Formatting (from `.editorconfig`)

| Rule                        | Value                                  |
| --------------------------- | -------------------------------------- |
| Indent                      | 4 spaces (no tabs, except `Makefile`)  |
| Max line length             | 120 columns                            |
| Charset                     | UTF-8                                  |
| Line endings                | LF (`*.bat` / `gradlew.bat` use CRLF)  |
| Final newline               | Required                               |
| Trailing whitespace         | Trimmed (except `*.md`)                |
| `*.{yml,yaml,json,toml}`    | 2-space indent                         |
| `*.md`                      | 2-space indent, trailing WS preserved  |

Kotlin-specific (IntelliJ EditorConfig keys):

- **Imports layout:** `*, java.**, javax.**, kotlin.**, ^`
  — third-party first, then `java.*`, `javax.*`, `kotlin.*`, then aliased
  imports. Use the IDE's "Optimize Imports" before committing.
- **Star imports:** disabled (`packages_to_use_import_on_demand = unset`).
- **Trailing commas:** allowed and **required** on multi-line parameter
  lists, argument lists, and collection literals — see existing code in
  `data/StationRepository.kt`, `domain/Track.kt`, etc.

### Kotlin / KMP Conventions

#### Naming

| Element                   | Convention                | Example                                |
| ------------------------- | ------------------------- | -------------------------------------- |
| Package                   | lowercase, dotted         | `com.visorcraft.radioshed.stations`    |
| Class / object / enum     | `PascalCase`              | `StationRepository`, `ThemeMode`       |
| Data class                | `PascalCase`              | `HistoryEntryWithStation`              |
| Function (top-level/member)| `camelCase` (verb)        | `bumpStationPlayCount`, `toStation`    |
| Composable function       | `PascalCase` (noun)       | `AppSettingsScreen`, `NowPlayingIndicator` |
| Variable / parameter      | `camelCase`               | `stationId`, `windowDays`              |
| Property (member)         | `camelCase`               | `playCount`, `lastPlayedAt`            |
| Top-level / object const  | `SCREAMING_SNAKE_CASE`    | `DAY_MS`, `KEY_THEME_MODE`             |
| Generic type parameter    | Single uppercase letter   | `T`, `R`                               |
| Test function             | `camelCase` (descriptive) | `plainTextProducesFreeTerms`           |
| Settings key constant     | `KEY_<NAME>` prefix       | `KEY_USER_VOLUME`                      |

DO NOT:

- Use Hungarian notation (`strName`, `mPath`).
- Prefix interfaces with `I` (Kotlin convention — interfaces and classes
  share the `PascalCase` namespace; let the name describe behaviour).
- Use `_` underscore prefix for private fields.
- Use abbreviations except: `id`, `url`, `db`, `ms` (milliseconds),
  `dp` (density-independent pixels), `eq` (equalizer), `bpm`, `sha`.

#### File organization

- One primary class per file; the file is named after the class
  (`StationRepository.kt`, `AppSettingsScreen.kt`).
- Tightly coupled helper `data class`es and small `internal fun`s may
  live at the bottom of the same file (see `StationRepository.kt`'s
  `HistoryEntry`, `TopArtistEntry`, `bumpCopySuffix(...)`).
- Compose screens: file is `<Feature>Screen.kt`, located under
  `ui/<feature>/`. Reusable bits live alongside or in `ui/util/`.
- Domain models live in `domain/`, are pure-Kotlin `data class`es, and
  carry `@kotlinx.serialization.Serializable` when they're persisted to
  JSON or the backup bundle.

#### Multi-platform (`expect` / `actual`)

- Common-side declarations use `expect val` / `expect fun` / `expect class`.
- Actual implementations live in `androidMain/.../<Name>.android.kt` and
  `iosMain/.../<Name>.ios.kt`.
- The compiler flag `-Xexpect-actual-classes` is intentionally set in
  `composeApp/build.gradle.kts` — DO NOT remove it.
- Before introducing a new `expect`, check "Cross-Platform Abstractions"
  above for an existing peer to mirror the pattern.

#### Patterns

- **Repositories:** declare as `class XRepository(private val db: RadioShedDatabase)`,
  bind as `single { ... }` in Koin's `sharedModule`. Every DB access goes
  through `withContext(IODispatcher)`. Multi-statement writes that must
  not tear go through `db.<table>Queries.transaction { ... }` or
  `transactionWithResult { ... }`.
- **Settings I/O:** never call `multiplatform-settings` directly from a
  screen — go through `SettingsStore` with a `KEY_*` constant. New keys
  must be added in **all three** of: getter/setter, `dumpForBackup(...)`
  type bucket, and `expectedTypeForKey(...)` (see "Backup Format" above).
- **Compose state:** state is hoisted. Screens accept callbacks +
  state-or-flows; `App.kt` wires them. Screens take Koin dependencies
  via `koinInject()` defaults on the parameter list (see
  `AppSettingsScreen.kt:97-99`).
- **Long-press menus:** route through `TrackContextSheet` or
  `CollectionContextSheets`, not ad-hoc `DropdownMenu`s.
- **Haptics:** use `rememberHapticTick()` from `ui/util/Haptics.kt`. It
  reads the user's master haptics toggle; do not call
  `LocalHapticFeedback` directly from a screen.
- **Online fetchers:** consult `NetworkPolicyGate` so the user's
  Always / Wi-Fi only / Never preference is honoured.
- **Imports vs aliases:** when two types share a simple name (e.g. domain
  `Station` vs SQLDelight-generated `Station`), alias the row type:
  `import com.visorcraft.radioshed.db.Station as StationRow`.

#### Style preferences

- Prefer `val` over `var`. Mutable state in repositories should be
  serialized via `Mutex.withLock { ... }` (see `PlaybackQueue`).
- Use named arguments when calling with **three or more** arguments —
  matches existing call sites and keeps SQLDelight insert calls readable.
- Prefer expression bodies for one-liners (`fun toStation(...): Station = ...`).
- Use string templates (`"$base (copy 2)"`), not concatenation.
- Use `runCatching { ... }.getOrDefault(...)` rather than `try/catch`
  when a parse / lookup failure should fall back to a default
  (see `SettingsStore.getThemeMode()`).
- Use `?.let { }` / Elvis (`?:`) instead of explicit null checks.
- Coerce user-provided numeric prefs on read AND write
  (`coerceIn(0.85f, 1.30f)`) — corrupted prefs should never blow up the UI.

#### Documentation

- KDoc (`/** ... */`) on **non-trivial public APIs**: repositories,
  services, top-level screen composables, anything with subtle
  invariants or platform quirks.
- Inline comments explain **why**, not **what**. Names cover the *what*.
  Long-form rationale (race conditions, format compatibility, vendor
  quirks like Samsung Noto glyph clipping or head-unit SQLite 3.32) is
  encouraged. Existing comments document hard-won lessons —
  **do not strip them as "noise"**.
- TODO format: `// TODO(<name-or-issue>): <what>`, e.g.
  `// TODO(SPEC §18): re-state schema in .sqm files`.
- Don't reference the current task / fix / caller in code comments
  ("added for the X flow") — that belongs in the commit message.

#### Tests

- Test classes: `<ClassUnderTest>Test`, e.g. `SearchDslTest`,
  `PlaybackQueueTest`.
- Test methods: descriptive `camelCase` (e.g. `quotedValuePreservesWhitespace`).
- Use `kotlin.test` (`@Test`, `assertEquals`, `assertTrue`); for
  coroutine tests use `kotlinx-coroutines-test`.
- Tests live in `composeApp/src/commonTest/kotlin/...` mirroring the
  `commonMain` package path.

### Build & Tooling

- Toolchain: JDK 17 required (Microsoft OpenJDK 17 on the dev machine).
  Bytecode target is `JvmTarget.JVM_11`.
- Versions live in `gradle/libs.versions.toml`. Add new dependencies
  there, never inline in `build.gradle.kts`.
- After editing `.sq` / `.sqm` files, regenerate with
  `./gradlew :composeApp:generateCommonMainRadioShedDatabaseInterface --rerun-tasks`.
- Linux desktop packaging:
  `./gradlew :composeApp:packageDistributionForCurrentOS` builds the
  jpackage app-image directory, a true single-file `.AppImage`, and a
  `.deb` on Linux x86_64. `TargetFormat.AppImage` alone is only the
  app-image directory; the user-facing single file comes from
  `:composeApp:packageSingleFileAppImage`.
- `packageSingleFileAppImage` uses AppImageKit `appimagetool`. Prefer
  `APPIMAGETOOL=/path/to/appimagetool` or an `appimagetool` on PATH;
  otherwise the Gradle task downloads the x86_64 tool into
  `composeApp/build/compose/tools/`. `.deb` packaging needs `dpkg-deb`
  on PATH.
- `verifyMigrations` is intentionally **off** — do not flip it on
  without the schema-restating refactor tracked in SPEC Phase 18.

### Commits

- Imperative present tense, ≤ 72-char subject.
- Reference SPEC items by phase number or task id (e.g. `#142`, `Phase 4`).

### Prohibited Practices

- **Do not** introduce new abstractions, helpers, or feature flags
  beyond what the task requires. Three similar lines is better than a
  premature abstraction.
- **Do not** add error-handling or fallbacks for scenarios that can't
  happen. Trust internal code; only validate at system boundaries.
- **Do not** create planning, decision, or analysis Markdown files
  unless explicitly asked. Work from the conversation, the SPEC, and
  the task list.
- **Do not** call `multiplatform-settings` directly from UI / screens.
  Go through `SettingsStore`.
- **Do not** remove the `-Xexpect-actual-classes` compiler flag, the
  comment block in `build.gradle.kts` about `verifyMigrations`, the
  Samsung Noto glyph note in the `materialIconsExtended` dependency, or
  the JitPack / requery-sqlite-android scoping rationale — these
  document hard-won lessons and are load-bearing.
- **Do not** widen the JitPack repository allow-list beyond
  `com.github.requery`.
