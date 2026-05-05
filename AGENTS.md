# AGENTS.md

Guidelines for AI coding agents (Claude Code, Codex, Gemini, etc.) working
in this repository. The conventions below are derived from
`.editorconfig`, `gradle.properties`, and dominant patterns in
`composeApp/src/`. They MUST be followed for new and modified code.

For project shape, build commands, architecture layering, persistence
quirks, and the SPEC ↔ task ↔ memory relationship, read **`CLAUDE.md`**
first — that document is authoritative and not duplicated here.
The product roadmap lives in **`SPEC.md`**.

---

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
- Before introducing a new `expect`, check `CLAUDE.md` §"Cross-platform
  abstractions" for an existing peer to mirror the pattern.

#### Patterns

- **Repositories:** declare as `class XRepository(private val db: RadioShedDatabase)`,
  bind as `single { ... }` in Koin's `sharedModule`. Every DB access goes
  through `withContext(IODispatcher)`. Multi-statement writes that must
  not tear go through `db.<table>Queries.transaction { ... }` or
  `transactionWithResult { ... }`.
- **Settings I/O:** never call `multiplatform-settings` directly from a
  screen — go through `SettingsStore` with a `KEY_*` constant. New keys
  must be added in **all three** of: getter/setter, `dumpForBackup(...)`
  type bucket, and `expectedTypeForKey(...)` (see `CLAUDE.md` →
  "Backup format").
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
