# AGENTS.md

## Agent Working Style

- Be concise. Don't restate the plan unless it changed.
- Never scan `node_modules`, `.venv`, `dist`, `build`, log/archive dirs, or generated files.
- Cap output when searching or reading files. Defaults: `head -n 100` / `tail -n 100`, `grep -n "pattern" file | head`, `find . -type f | head -n 200`.

Guidelines for AI coding agents working in this repository. Derived from `.editorconfig`, `gradle.properties`, and dominant patterns in `composeApp/src/`. MUST be followed for new and modified code.

This file is the authoritative agent guide. `CLAUDE.md` redirects Claude-family agents here. The product roadmap lives in **`SPEC.md`**.

---

## Project Shape

RadioShed is a Kotlin Multiplatform / Compose Multiplatform music app targeting **Android 11+** (minSdk 30; targetSdk 36; compileSdk 37), **Windows 11+ / Linux** (Compose Desktop / JVM), and **iOS 16+** (stubbed pending Mac access).

There is **one Gradle module** — `:composeApp` — with source sets:

- `commonMain` / `commonTest`
- `jvmMain` — shared JVM code (jaudiotagger tag read/write, file enumeration) depended on by both `androidMain` and `desktopMain`
- `androidMain`
- `desktopMain`
- `iosMain`
- `desktopTest`

There is no separate `shared/`, `androidApp/`, or `iosApp` module.

The desktop JVM target is named `desktop` (`kotlin { jvm("desktop") }` in `composeApp/build.gradle.kts`); the source set follows that convention as `desktopMain`. Linux desktop compile, run, single-file `.AppImage`, and `.deb` packaging are verified and useful for iterating on Compose UI without an emulator.

`SPEC.md` is the canonical product roadmap. The phased plan in §20 uses checkboxes (`- [ ]` / `- [~]` / `- [x]`); when flipping a box, also write a one-line cite of the implementation (filename, function name, or task id). Stale checkboxes are worse than missing ones.

## Common Commands

The main Android dev host is **Windows 11**. `adb` lives at `C:\android-sdk\platform-tools\adb.exe` (not on PATH).

```bash
# Android
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:assembleRelease      # unsigned
./gradlew :composeApp:bundleRelease        # unsigned .aab
"C:\android-sdk\platform-tools\adb.exe" install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk

# Tests
./gradlew :composeApp:testDebugUnitTest
./gradlew :composeApp:testDebugUnitTest --tests "com.visorcraft.radioshed.data.SearchDslTest"
./gradlew :composeApp:desktopTest

# SQLDelight / licenses / build info
./gradlew :composeApp:generateCommonMainRadioShedDatabaseInterface --rerun-tasks
./gradlew :composeApp:generateThirdPartyLicenses

# Desktop
./gradlew :composeApp:run
./gradlew :composeApp:packageDistributionForCurrentOS
./gradlew :composeApp:packageSingleFileAppImage

# iOS (macOS only)
./gradlew :composeApp:linkReleaseFrameworkIosArm64
```

**No release signing is configured** (no `signingConfigs` in `android {}`), so `assembleRelease` / `bundleRelease` emit **unsigned** artifacts. Debug-sign + `zipalign` out-of-band to ship an installable APK (recipe under Releases), or add a real `signingConfig`. Keystores and `keystore.properties` / `*.jks` / `*.keystore` are gitignored and machine-specific — never commit them.

Building any Android variant needs an Android SDK with **platform `android-37.2`** and **build-tools `36.0.0`**, plus platform-tools and cmdline-tools. Point Gradle at it via `sdk.dir` in `local.properties` (or `ANDROID_HOME` / `ANDROID_SDK_ROOT`); `local.properties` is gitignored / per-machine — do not hardcode a path.

The desktop runtime needs **libvlc** (VLC 3.x) installed. `audio/NativeLibVlc.kt` discovers it via JNA at startup across standard per-OS install paths, with a `RADIOSHED_LIBVLC_PATH` env override and the Linux `libvlc.so.5` SONAME fallback. Without libvlc, `:run` launches but playback errors out and `App` shows the dismissible "VLC required" modal.

Linux packaging details:

- `TargetFormat.AppImage` is Compose/JPackage's app-image directory, not a user-facing single-file `.AppImage`.
- `:composeApp:packageSingleFileAppImage` wraps that directory with AppImageKit `appimagetool` and writes `composeApp/build/compose/binaries/main/appimage/RadioShed-<version>-x86_64.AppImage`.
- `APPIMAGETOOL=/path/to/appimagetool` or `appimagetool` on PATH takes precedence; otherwise Gradle downloads the x86_64 tool into `composeApp/build/compose/tools/`.
- `.deb` packaging needs `dpkg-deb` on PATH; Arch/CachyOS provide it via the `dpkg` package.

Local install on the Linux dev box (CachyOS / KDE Plasma) — exactly **one** install, updated in place:

- The single-file AppImage lives at `~/Applications/RadioShed.AppImage` (`chmod +x`). Never point the launcher into `composeApp/build/…` or at a versioned filename — those go stale on `clean` / version bumps.
- Launcher `~/.local/share/applications/radioshed.desktop`: `Exec`/`TryExec` → that stable path, `Icon=radioshed` (PNGs under `~/.local/share/icons/hicolor/<size>/apps/`), pinned in the KDE Task Manager.
- **To update:** `./gradlew :composeApp:packageSingleFileAppImage`, overwrite `~/Applications/RadioShed.AppImage`, `chmod +x`. Launcher and pin need no changes. All user-level — no `sudo`; playback still needs host libvlc.

## Architecture

### Layering

- `domain/` — pure-Kotlin data classes (`Station`, `Track`, `SourceRule`, `PlaybackRule`, `EqPreset`, `Lyrics`, `DriveModeTrigger`, etc.). No platform deps.
- `data/` — repositories over SQLDelight, plus `SettingsStore`. Every repository is a `single { }` in Koin's `sharedModule`. All DB I/O goes through `withContext(IODispatcher)`. Never call `multiplatform-settings` directly from a screen — go through `SettingsStore` with a `KEY_*` constant so backup/restore round-trips correctly.
- `source/` — outbound integrations (LRClib lyrics, Cover Art Archive, Subsonic, backup JSON, file browser, exporters, scrobbling, weather, Syncthing detection) plus the `MediaSource` plug-in abstraction (`MediaSource` / `MediaSourceRegistry` / `SourceRule.MediaSourceSelector`; `MockCloudSource` is the test/demo impl). Online fetchers must consult `NetworkPolicyGate` so the user's Always / Wi-Fi only / Never preference is honoured. HTTP clients are JDK-only (`HttpURLConnection`) — do **not** add Ktor/OkHttp client libraries. `SubsonicClient` is bound per platform: Android = `AndroidSubsonicClient` (token auth), desktop = `DesktopSubsonicClient`, iOS = `Null` stub.
- `audio/` — `AudioEngine` (expect/actual), `EqualizerController`, `SleepTimer`, crossfade planning. Android impl is Media3 ExoPlayer + MediaSession; iOS is a documented no-op stub; desktop is a Kotlin + JNA binding to libvlc 3.x (`audio/NativeLibVlc.kt`), **not** VLCJ. No Java-side libvlc event callbacks: `AudioEngineDesktop` polls state/position (~250 ms) and auto-advances on Ended. `isMediaPlaybackAvailable()` (expect/actual) reports whether libvlc was found (desktop) or `true` (Android/iOS), driving the desktop-only "VLC required" modal.
- `stations/` — `StationPlayback` (orchestrator), `PlaybackQueue` (priority queue + history), `SourceRuleEvaluator`, `StationPresets` / `PresetSeeder`, DJ/announcer/adaptive-mix logic. Queue mutations are serialized through `kotlinx.coroutines.sync.Mutex.withLock`.
- `scanner/` — library scan pipeline. `FileEnumerator` and `TagReader` are expect/actual; Android and desktop share the `jvmMain` jaudiotagger impl, iOS is stubbed.
- `car/` — Drive Mode UI and `DriveModeController`.
- `crash/` — local crash log capture/export.
- `licenses/` — license catalog + generation.
- `ui/<feature>/` — Compose screens named `<Feature>Screen.kt`. State is hoisted: screens take callbacks + state, `App.kt` wires them. Reusable bits live under the feature folder or `ui/util/`.

### Cross-Platform Abstractions

Common-side declarations use `expect val` / `expect fun` / `expect class`. Actual implementations live in `androidMain/.../<Name>.android.kt` and `iosMain/.../<Name>.ios.kt`; desktop actuals are in `desktopMain` or inherited from `jvmMain`. Before introducing a new `expect`, look for an existing peer to mirror. Current peers include:

- `data/DatabaseDriverFactory`, `data/Time.kt`, `data/IODispatcher`
- `source/FileEnumerator`, `source/TagReader`, `source/FileOps`, `source/StorageVolumes`, `source/DefaultMusicPath`, `source/SyncthingDetector`, `source/WebOpener`, `source/ArtworkPersister`, `source/FolderRevealer`, `source/BackupSharer`, `source/TrackSharer`, `source/PlaylistExporter`, `source/HistoryExporter`
- `audio/MediaPlaybackAvailability`
- `permissions/StoragePermissions`, `permissions/LocationPermissions`, `permissions/BluetoothPermissions`
- `ui/util/KeepScreenOn`, `ui/util/Haptics`, `ui/util/VoiceInput`, `ui/util/ImagePicker`, `ui/util/FilePicker`, `ui/util/PlatformScrollbar`, `ui/util/VlcInstallHint`, `ui/util/DragReorder`
- `nav/PlatformBackHandler`
- `Platform.kt`

The `-Xexpect-actual-classes` flag is enabled in `composeApp/build.gradle.kts` to suppress the beta warning — intentional; do not remove it.

### Persistence

Schema lives in `composeApp/src/commonMain/sqldelight/com/visorcraft/radioshed/db/`:

- `Library.sq`, `Stations.sq`, `Playlists.sq`, `Ratings.sq`, `SearchFts.sq`, `Scrobbles.sq`, `Folders.sq`, `Caches.sq`, `Backups.sq`, `Cast.sq`, `AudioSettings.sq`, `Widgets.sq`.
- Migrations are `migrations/<n>.sqm`. **`verifyMigrations` is enabled** — `databases/1.db` supplies the complete v1 schema for accumulated validation, and every `.sqm` re-states the FK targets it needs. Shipped `.sqm` files are immutable upgrade history — **never rewrite one in place**. Any schema change is a new `<n+1>.sqm` plus the matching `.sq` baseline edit.
- After editing `.sq` or `.sqm`, regenerate with `--rerun-tasks` because the task is cached aggressively.

The Android driver uses `com.github.requery:sqlite-android` (bundled SQLite 3.49 via JitPack), not the system SQLite. Reason: head-unit Android images ship SQLite 3.32 which predates `RETURNING` (3.35), which the generated queries use. The JitPack repo is clamped to `com.github.requery` only — keep it that way.

### DI

Koin is split:

- `platformModule` (expect val, actuals in platform source sets) binds `DatabaseDriverFactory`, `Settings`, `AudioEngine`, `FileEnumerator`, `TagReader`, `ArtworkPersister`, `ConnectivityChecker`, `LyricsFetcher`, `CoverArtFetcher`, `FolderRevealer`, etc.
- `sharedModule` binds repositories, `SettingsStore`, `Scanner`, `LyricsService`, `BackupService`, `StationPlayback`, `SleepTimer`, `MissingArtworkSync`, `PresetSeeder`, `MediaSourceRegistry`, `SourceRuleEvaluator`, etc. All are `single`.

When adding a constructor parameter to anything in `sharedModule`, update the `single { ... }` line. Too few positional `get()` calls is a compile error, but a `get()` whose resolved type has no binding only fails at runtime with Koin's "no definition found" — so also register any newly-required type.

`appModules()` loads `platformModule` **then** `sharedModule`, and Koin's last definition silently wins. Never re-bind a platform-bound interface (`SubsonicClient`, `CoverArtFetcher`, `AudioEngine`, …) in `sharedModule` — it replaces the working platform impl on every target.

### Backup Format

`SettingsStore.dumpForBackup()` and `restoreFromBackup()` round-trip a typed map of every `KEY_*` setting. Restore validates each value's type against `expectedTypeForKey(...)` before writing. Adding a new setting means adding its key to all three:

- getter/setter,
- `dumpForBackup(...)` in the right type bucket,
- `expectedTypeForKey(...)`.

Forgetting any of these is the most common backup bug; there are several FIX entries in `SPEC.md` for it.

### SPEC ↔ Task ↔ Memory Relationship

- The agent-managed auto-memory (maintained outside the repo) holds non-derivable facts such as batching feedback. Do not duplicate the SPEC's content there.
- The task list (`#NNN` ids) is the unit of granular tracking. New work gets a new id; ids monotonically increase.
- Each "build, install, document batch N" task closes a batch. That is when `SPEC.md` gets the batch entry and the `buildDate` field in `version.properties` (repo root) is bumped to today's UTC date. It flows into the generated `BuildInfo.BUILD_DATE` via the `generateBuildInfo` task; the generated `BuildInfo.kt` itself is do-not-edit.

## Coding Conventions

> **For AI Models:** Follow these rules strictly. Anything not specified here defers to the Kotlin official style guide (`kotlin.code.style=official` in `gradle.properties`).

### Formatting (from `.editorconfig`)

| Rule | Value |
| --- | --- |
| Indent | 4 spaces (no tabs, except `Makefile`) |
| Max line length | 120 columns |
| Charset | UTF-8 |
| Line endings | LF (`*.bat` / `gradlew.bat` use CRLF) |
| Final newline | Required |
| Trailing whitespace | Trimmed (except `*.md`) |
| `*.{yml,yaml,json,toml}` | 2-space indent |
| `*.md` | 2-space indent, trailing WS preserved |

Kotlin-specific:

- **Imports layout:** `*, java.**, javax.**, kotlin.**, ^` — third-party first, then `java.*`, `javax.*`, `kotlin.*`, then aliased imports. Optimize imports before committing.
- **Star imports:** disabled (`packages_to_use_import_on_demand = unset`).
- **Trailing commas:** required on multi-line parameter lists, argument lists, and collection literals — see existing code in `data/StationRepository.kt`, `domain/Track.kt`.

### Kotlin / KMP Conventions

#### Naming

| Element | Convention | Example |
| --- | --- | --- |
| Package | lowercase, dotted | `com.visorcraft.radioshed.stations` |
| Class / object / enum | `PascalCase` | `StationRepository`, `ThemeMode` |
| Data class | `PascalCase` | `HistoryEntryWithStation` |
| Function (top-level/member) | `camelCase` (verb) | `bumpStationPlayCount`, `toStation` |
| Composable function | `PascalCase` (noun) | `AppSettingsScreen`, `NowPlayingIndicator` |
| Variable / parameter | `camelCase` | `stationId`, `windowDays` |
| Property (member) | `camelCase` | `playCount`, `lastPlayedAt` |
| Top-level / object const | `SCREAMING_SNAKE_CASE` | `DAY_MS`, `KEY_THEME_MODE` |
| Generic type parameter | Single uppercase letter | `T`, `R` |
| Test function | `camelCase` (descriptive) | `quotedValuePreservesWhitespace` |
| Settings key constant | `KEY_<NAME>` prefix | `KEY_USER_VOLUME` |

DO NOT:

- Use Hungarian notation (`strName`, `mPath`).
- Prefix interfaces with `I`.
- Use `_` underscore prefix for private fields.
- Use abbreviations except: `id`, `url`, `db`, `ms`, `dp`, `eq`, `bpm`, `sha`.

#### File organization

- One primary class per file; file named after the class (`StationRepository.kt`, `AppSettingsScreen.kt`).
- Tightly coupled helper `data class`es and small `internal fun`s may live at the bottom of the same file.
- Compose screens: file is `<Feature>Screen.kt`, located under `ui/<feature>/`. Reusable bits live alongside or in `ui/util/`.
- Domain models live in `domain/`, are pure-Kotlin `data class`es, and carry `@kotlinx.serialization.Serializable` when persisted to JSON or the backup bundle.

#### Multi-platform (`expect` / `actual`)

- Common-side declarations use `expect val` / `expect fun` / `expect class`.
- Actual implementations live in `androidMain/.../<Name>.android.kt` and `iosMain/.../<Name>.ios.kt` (desktop actuals are in `desktopMain` or inherited from `jvmMain`).
- The compiler flag `-Xexpect-actual-classes` is intentionally set in `composeApp/build.gradle.kts` — DO NOT remove it.
- Before introducing a new `expect`, check "Cross-Platform Abstractions" above for an existing peer to mirror.

#### Patterns

- **Repositories:** declare as `class XRepository(private val db: RadioShedDatabase)`, bind as `single { ... }` in Koin's `sharedModule`. Every DB access goes through `withContext(IODispatcher)`. Multi-statement writes that must not tear go through `db.<table>Queries.transaction { ... }` or `transactionWithResult { ... }`.
- **Settings I/O:** never call `multiplatform-settings` directly from a screen — go through `SettingsStore` with a `KEY_*` constant. New keys must be added in **all three** of: getter/setter, `dumpForBackup(...)` type bucket, and `expectedTypeForKey(...)`.
- **Compose state:** state is hoisted. Screens accept callbacks + state-or-flows; `App.kt` wires them. Screens take Koin dependencies via `koinInject()` defaults on the parameter list.
- **Long-press menus:** route through `TrackContextSheet` or `CollectionContextSheets`, not ad-hoc `DropdownMenu`s.
- **Haptics:** use `rememberHapticTick()` from `ui/util/Haptics.kt`. It reads the user's master haptics toggle; do not call `LocalHapticFeedback` directly from a screen.
- **Online fetchers:** consult `NetworkPolicyGate` so the user's Always / Wi-Fi only / Never preference is honoured.
- **Imports vs aliases:** when two types share a simple name (e.g. domain `Station` vs SQLDelight-generated `Station`), alias the row type: `import com.visorcraft.radioshed.db.Station as StationRow`.

#### Style preferences

- Prefer `val` over `var`. Mutable state in repositories should be serialized via `Mutex.withLock { ... }` (see `PlaybackQueue`).
- Use named arguments when calling with **three or more** arguments.
- Prefer expression bodies for one-liners (`fun toStation(...): Station = ...`).
- Use string templates (`"$base (copy 2)"`), not concatenation.
- Use `runCatching { ... }.getOrDefault(...)` rather than `try/catch` when a parse / lookup failure should fall back to a default.
- Use `?.let { }` / Elvis (`?:`) instead of explicit null checks.
- Coerce user-provided numeric prefs on read AND write (`coerceIn(0.85f, 1.30f)`) — corrupted prefs should never blow up the UI.

#### Documentation

- KDoc (`/** ... */`) on **non-trivial public APIs**: repositories, services, top-level screen composables, anything with subtle invariants or platform quirks.
- Inline comments explain **why**, not **what**. Names cover the *what*. Long-form rationale (race conditions, format compatibility, vendor quirks like Samsung Noto glyph clipping or head-unit SQLite 3.32) is encouraged. Existing comments document hard-won lessons — **do not strip them as "noise"**.
- TODO format: `// TODO(<name-or-issue>): <what>`, e.g. `// TODO(SPEC §18): re-state schema in .sqm files`.
- Don't reference the current task / fix / caller in code comments — that belongs in the commit message.

#### Tests

- Test classes: `<ClassUnderTest>Test`, e.g. `SearchDslTest`, `PlaybackQueueTest`.
- Test methods: descriptive `camelCase` (e.g. `quotedValuePreservesWhitespace`).
- Use `kotlin.test` (`@Test`, `assertEquals`, `assertTrue`); for coroutine tests use `kotlinx-coroutines-test`.
- Tests live in `composeApp/src/commonTest/kotlin/...` (and a small amount in `desktopTest`) mirroring the `commonMain` package path. They execute via `testDebugUnitTest` (Android variant) and `desktopTest`.
- The `android.testOptions` block in `composeApp/build.gradle.kts` is load-bearing: `isReturnDefaultValues = true` plus the compose-resource assets dir on the unit-test classpath let `Res.readBytes()` resolve under `testDebugUnitTest` (`LicenseCatalogTest` fails without both).
- DB-backed tests build a real in-memory database — `JdbcSqliteDriver(IN_MEMORY)` + `RadioShedDatabase.Schema.create(driver)` — instead of mocking repositories (see `StationRoundTripCrashTest`).

### Build & Tooling

- Toolchain: **JDK 17+** (17 baseline; desktop + Android builds verified on JDK 21). No pinned Gradle Java toolchain. Bytecode targets: Android `JvmTarget.JVM_11`, desktop `JvmTarget.JVM_17`. AGP 9.5.1 / Gradle 9.8.2 / Kotlin 2.4.21.
- AGP 9's Android-KMP replacement is library-only. This single-module application keeps `android.builtInKotlin=false` and `android.newDsl=false` until an application-target migration is available; these legacy APIs are scheduled for removal in AGP 10.
- Versions live in `gradle/libs.versions.toml`. Add new dependencies there, never inline in `build.gradle.kts`.
- When adding or bumping a third-party dependency, also update `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/licenses/LicenseCatalog.kt` (and add its license text under `composeApp/src/commonMain/composeResources/files/licenses/` if a new SPDX id appears), then rerun `./gradlew :composeApp:generateThirdPartyLicenses`. The catalog is the single source of truth for the in-app About → Licenses screen and the generated `THIRD_PARTY_LICENSES` file. `LicenseCatalogTest` fails the build if a shipped coordinate is undocumented.
- `verifyMigrations` is enabled — every `.sqm` must re-state FK targets at the top (see Persistence).

### Releases

`version.properties` (repo root) is the single source of truth for the version and drives AGP `versionName`/`versionCode`, desktop `packageVersion`, and the generated `BuildInfo`. Bump rules: patch = bug fixes, minor = backwards-compatible features, major = breaking changes (e.g. backup-format incompatibility); `versionCode` is monotonic and **never decremented**; `buildDate` = today's UTC date on every ship.

Releases are tagged **`vX.Y.Z`** with a matching GitHub release titled `RadioShed X.Y.Z` on `visorcraft/RadioShed`, cut via the `gh` CLI. End-to-end flow: bump `version.properties` → commit → push → `git tag -a vX.Y.Z` → push the tag → `gh release create vX.Y.Z` → `gh release upload` the per-platform assets. Desktop binaries only build on/for their host OS, so each platform's assets are produced on that host.

The Windows MSI sets `menu`/`shortcut` and a **fixed `upgradeUuid`** that ties all versions into one product family for in-place upgrades. That GUID is **permanent — never change it** (a new value orphans prior installs).

Per-platform shippable artifacts:

| Artifact | Command | Output |
| --- | --- | --- |
| Android APK (release, **unsigned**) | `:composeApp:assembleRelease` | `composeApp/build/outputs/apk/release/composeApp-release-unsigned.apk` |
| Android AAB (**unsigned**) | `:composeApp:bundleRelease` | `composeApp/build/outputs/bundle/release/composeApp-release.aab` |
| Linux `.AppImage` | `:composeApp:packageSingleFileAppImage` | `composeApp/build/compose/binaries/main/appimage/RadioShed-<version>-x86_64.AppImage` |
| Linux `.deb` | `:composeApp:packageDistributionForCurrentOS` | `composeApp/build/compose/binaries/main/deb/radioshed_<version>_amd64.deb` |
| Windows `.msi` / macOS `.dmg` | `:composeApp:packageDistributionForCurrentOS` (on that host) | jpackage native installer for the host OS |

Arch / CachyOS ships as the AUR package `radioshed` (`packaging/aur/PKGBUILD`). It installs the public GitHub AppImage. It does not build from source. After the repository is public and the `vX.Y.Z` release asset is anonymously downloadable:

```bash
VERSION=X.Y.Z scripts/publish-aur.sh
```

The package installs that AppImage to `/opt/radioshed/RadioShed.AppImage`, links `/usr/bin/radioshed`, and depends on host `libvlc`. Do not publish a `radioshed-bin` package name.

Asset naming: `RadioShed-<v>-debug-signed.apk` (release APK debug-signed to be installable), `RadioShed-<v>-release.aab`, `RadioShed-<v>-x86_64.AppImage`, `radioshed_<v>_amd64.deb`. Debug-signing recipe (build-tools `36.0.0`; **zipalign before apksigner**):

```bash
zipalign -f 4 in.apk aligned.apk
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey --out <name>.apk aligned.apk
apksigner verify <name>.apk
```

(Don't ship the `.idsig` side-file.)

**License.** RadioShed is GPL-3.0-only (`LICENSE`), the same license as Grexa. Desktop audio is the Kotlin + JNA libvlc binding (libvlc LGPL-2.1-or-later, JNA Apache-2.0), not VLCJ. v1.2.0+ releases ship the JNA binding; only v1.1.0 assets contain VLCJ.

### Commits

- **Attribution (IMPORTANT):** Commits are authored solely by the human committer. Never attribute code changes to any AI agent, assistant, or tool — including, but not limited to, **Cursor**, **cursoragent**, **Claude**, and **Codex**. Do **not** add `Co-Authored-By` trailers, "Generated with…" / "Assisted-by" lines, or any other AI attribution in commit messages, PR titles/descriptions, code, or comments.
- Imperative present tense, ≤ 72-char subject.
- Reference SPEC items by phase number or task id (e.g. `#142`, `Phase 4`).

### Prohibited Practices

- **Do not** introduce new abstractions, helpers, or feature flags beyond what the task requires. Three similar lines is better than a premature abstraction.
- **Do not** add error-handling or fallbacks for scenarios that can't happen. Trust internal code; only validate at system boundaries.
- **Do not** create planning, decision, or analysis Markdown files unless explicitly asked. Work from the conversation, the SPEC, and the task list.
- **Do not** call `multiplatform-settings` directly from UI / screens. Go through `SettingsStore`.
- **Do not** remove the `-Xexpect-actual-classes` compiler flag, the comment block in `build.gradle.kts` about `verifyMigrations`, the Samsung Noto glyph note in the `materialIconsExtended` dependency, or the JitPack / requery-sqlite-android scoping rationale — these document hard-won lessons and are load-bearing.
- **Do not** widen the JitPack repository allow-list beyond `com.github.requery`.
- **Do not** rewrite shipped `.sqm` migration files in place — schema changes are a new `<n+1>.sqm` (see Persistence).
- **Do not** write `iosMain` code against platform APIs (AVFoundation, MediaPlayer, …) on a non-Mac host: the iOS target only compiles on macOS, so such code lands unverified. iOS stays a no-op stub until Mac access.
- **Do not** re-bind a platform-bound interface in `sharedModule` (Koin last-definition-wins; see DI).

## Prompt-file hygiene for subagents & peer reviewers (MANDATORY)

NEVER write prompts for subagents or peer code reviewers (e.g. `opencode run "$(cat ...)"`) to the shared generic path `/tmp/prompt.txt`. Multiple agent sessions run concurrently on this machine and follow the same conventions; a shared path WILL be clobbered between writing the prompt and the consumer reading it at launch.

Required: use a collision-proof path —

- per-repo: `/tmp/RadioShed-prompt.txt`, or
- better, a unique random path so multiple subagents can work on this repo concurrently with varying prompts: `PROMPT_FILE=$(mktemp /tmp/RadioShed-prompt-XXXXXXXX.txt)`

The same rule applies to any scratch file consumed via `$(cat ...)` or read by the subagent at launch time.
