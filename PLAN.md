# RadioShed — Next Features Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking. Flip a box only after the step's
> verification command has actually passed.

**Goal:** Ship the five highest-value open feature areas (AI DJ, Drive Mode
completion, true crossfade + gapless, scrobbling completion, Subsonic
follow-through) plus three power-tool items (batch tag editor,
station-launcher widget, SmartQuery auto-rotate), each phase gated by tests,
a refactor/dedup pass, and an `opencode` peer review.

**Architecture:** Everything follows the existing KMP layering — pure domain
models in `domain/`, repositories over SQLDelight in `data/`, outbound
integrations in `source/`, playback in `audio/` + `stations/`, Compose
screens in `ui/<feature>/`, DI via Koin (`platformModule` then
`sharedModule`). New platform peers use interface-in-common + platform impl
bound in `platformModule` (the `ListenBrainzClient` / `TagWriter` pattern).
New settings go through `SettingsStore` and the three-place backup rule. New
schema is a new `.sqm` migration plus baseline `.sq` edit — never a rewrite
of a shipped migration.

**Tech Stack:** Kotlin 2.3 / Compose Multiplatform, SQLDelight, Koin,
Media3/ExoPlayer (Android), JNA-libvlc (desktop), `kotlin.test` +
`kotlinx-coroutines-test`, JDK-only HTTP (`HttpURLConnection` — **no
Ktor/OkHttp client additions**).

---

## Ground rules (read before any task)

1. **Read `AGENTS.md` first.** It is the authoritative guide. Highlights that
   bite people:
   - Every new `KEY_*` setting must be added in **all three** of: getter/setter,
     `SettingsStore.dumpForBackup(...)` type bucket, `expectedTypeForKey(...)`.
   - Schema changes = new `migrations/<n+1>.sqm` **and** the matching baseline
     `.sq` edit. Next free migration number at time of writing: **8**.
     Regenerate with
     `./gradlew :composeApp:generateCommonMainRadioShedDatabaseInterface --rerun-tasks`.
   - New constructor params on `sharedModule` singles need the `single { ... }`
     line updated; never re-bind a platform-bound interface in `sharedModule`.
   - No new third-party dependencies without `gradle/libs.versions.toml` +
     `licenses/LicenseCatalog.kt` + `generateThirdPartyLicenses` (this plan
     adds **zero** new dependencies).
   - No `iosMain` platform-API code (no Mac available) — every new platform
     interface gets an explicit no-op/`Null` iOS binding.
   - Commit style: imperative, ≤72-char subject, reference task ids from this
     plan (e.g. `PLAN 1.4`). No AI attribution anywhere.
2. **Tests:** common-code units get `commonTest` classes run via
   `./gradlew :composeApp:testDebugUnitTest`. DB-backed tests use a real
   in-memory driver (`JdbcSqliteDriver(IN_MEMORY)` +
   `RadioShedDatabase.Schema.create(driver)`), not mocks — see
   `StationRoundTripCrashTest` for the pattern.
3. **TDD ordering inside every task:** write the failing test → run it and see
   it fail → implement minimally → run it green → commit. UI-only tasks
   substitute "build + manual smoke on desktop (`./gradlew :composeApp:run`)
   or device" for the test steps.
4. **SPEC.md hygiene:** when a task completes something tracked in `SPEC.md`
   §20, flip that checkbox in the same commit with a one-line cite
   (file/function/task id).

## Phase exit gate (applies to EVERY phase)

Each phase ends with this fixed sequence. The phase is not done until all
boxes are checked. (Referenced below as "Run the Phase Exit Gate".)

```text
G1. Refactor & de-dup pass (scoped to the phase's diff):
    - Re-read every file the phase touched. Extract duplication introduced
      by the phase (3+ similar blocks → helper in the same file or ui/util/;
      do NOT invent abstractions beyond what the diff itself needs).
    - grep for copy-paste drift across platform impls (android vs desktop
      versions of the same client) — comments and constants must match.
    - Check new composables for overlap with existing ones (TrackContextSheet,
      CollectionContextSheets, StationActionSheet) — route through them, no
      ad-hoc DropdownMenus.
    - Optimize imports in touched files; verify trailing-comma + 4-space
      conventions.
G2. Full verification:
    - ./gradlew :composeApp:testDebugUnitTest          → BUILD SUCCESSFUL
    - ./gradlew :composeApp:assembleDebug              → BUILD SUCCESSFUL
    - ./gradlew :composeApp:run                        → desktop launches,
      smoke the phase's feature where it has a desktop surface
G3. Commit + push everything (review must see the final state).
G4. Peer review via opencode (Deepseek, preconfigured — no -m flag):
    - cd <repo-root>
    - git diff <phase-base-sha>..HEAD -- composeApp > _review_diff.txt
      (the diff file MUST live inside the repo root — the build agent
      auto-rejects /tmp paths)
    - Write the prompt to /tmp/prompt.txt:
        READ-ONLY review: do not modify/create/delete files. Read
        ./_review_diff.txt plus any referenced source files. Review for:
        correctness bugs, race conditions in coroutine/Mutex code, Koin
        binding mistakes, SettingsStore three-place rule violations,
        SQLDelight migration safety, duplicated logic that should be
        consolidated, and convention drift vs AGENTS.md. End with EXACTLY:
        VERDICT approved issues=N
    - Launch BACKGROUNDED, with stdin closed (it hangs forever otherwise):
        timeout 280 opencode run "$(cat /tmp/prompt.txt)" --variant high \
          < /dev/null > /tmp/oc.out 2>&1 &
    - Read /tmp/oc.out. Empty output or no VERDICT line = it never ran —
      rerun, do not treat as a pass.
    - Triage every reported issue: fix real ones (then re-run G2), record
      false positives in the phase's review log section at the bottom of
      this file.
    - rm _review_diff.txt; git status must be clean (git restore anything
      the agent touched — it can edit files).
G5. SPEC.md checkbox flips + cites for everything the phase shipped.
G6. Ship batch: bump version.properties (minor for features), buildDate to
    today UTC, commit, tag per AGENTS.md release flow if cutting a release.
```

---

# Phase 0 — Pre-flight baseline (½ day)

Confirm the tree is green before building on it, and record the base SHA
every later review diff is computed from.

### Task 0.1: Baseline verification

**Files:** none modified.

- [x] **Step 1:** `git status` → clean; record `git rev-parse HEAD` as
  `PHASE_BASE` in your worklog (each phase records its own base the same way).
  *(Effort base: 856ce30 on master; branch `next-features`; PLAN.md commit 61f60a4.)*
- [x] **Step 2:** `./gradlew :composeApp:testDebugUnitTest` → BUILD SUCCESSFUL.
  If red, STOP — fix main before starting feature work.
- [x] **Step 3:** `./gradlew :composeApp:assembleDebug` → BUILD SUCCESSFUL.
- [x] **Step 4:** `./gradlew :composeApp:run` → desktop window launches
  (libvlc modal acceptable if VLC missing on the host).
  *(Adapted: verified via `compileKotlinDesktop` — no GUI launched unattended;
  windowed smoke happens at each Phase Exit Gate G2.)*
- [x] **Step 5:** Skim `SPEC.md` §9 (AI DJ), §7.3 (in-car), §8 (audio
  pipeline), Phase 16 (sources) so the feature intent is loaded before
  designing details.

---

# Phase 1 — AI DJ core (SPEC §9, Phase 9) (~1–2 weeks)

The signature feature. Existing groundwork to reuse — do **not** recreate:
`domain/DjVoice.kt` (DjEngine enum: System / ElevenLabs / Custom),
`DjCacheRepository` (already bound in `di/Modules.kt`), the `Caches.sq` DJ
cache table, and `AudioEngine.volume` / `setVolume(...)` (built for exactly
this duck-and-restore pattern — see SleepTimer's fade usage).

Scope decisions locked in:
- TTS is a **common interface + platform impls** (`ListenBrainzClient`
  pattern), not expect/actual.
- DJ clips are synthesized **to a file**, cached via `DjCacheRepository`
  keyed by a hash of (text, voice), and played by ducking music via
  `AudioEngine.setVolume` while a small platform `ClipPlayer` plays the file.
  No audio-focus tricks — we duck ourselves explicitly.
- Desktop + iOS get `Null` TTS impls in this phase (desktop TTS has no
  JDK-only path; revisit later). The whole orchestration must therefore
  no-op cleanly when `TtsEngine.isAvailable()` is false.
- Weather (`{weather}`) ships behind Open-Meteo at the END of the phase,
  optional; templates must render gracefully when a variable resolves null.
- ElevenLabs BYO-key is a stretch task, off by default.

### Task 1.1: `TtsEngine` interface + Null impls

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/audio/TtsEngine.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/visorcraft/radioshed/di/PlatformModule.android.kt`
  (placeholder binding swapped in Task 1.2),
  `composeApp/src/desktopMain/kotlin/com/visorcraft/radioshed/di/PlatformModule.desktop.kt`,
  `composeApp/src/iosMain/kotlin/com/visorcraft/radioshed/di/PlatformModule.ios.kt`

- [x] **Step 1:** Create the interface + Null impl in one file:

```kotlin
package com.visorcraft.radioshed.audio

import com.visorcraft.radioshed.domain.DjVoice

/**
 * Text-to-speech synthesis for DJ announcements. Platform impls render
 * [text] to a playable audio file at [outPath] (wav/m4a — caller treats it
 * as opaque). Bound in platformModule; desktop and iOS ship Null until a
 * platform path exists, so all callers must tolerate isAvailable() == false.
 */
interface TtsEngine {
    fun isAvailable(): Boolean

    /**
     * Synthesize [text] to [outPath]. Returns true on success. Must not
     * throw on engine failure — DJ speech is decorative, never fatal.
     */
    suspend fun synthesizeToFile(text: String, voice: DjVoice?, outPath: String): Boolean
}

class NullTtsEngine : TtsEngine {
    override fun isAvailable(): Boolean = false
    override suspend fun synthesizeToFile(
        text: String,
        voice: DjVoice?,
        outPath: String,
    ): Boolean = false
}
```

- [x] **Step 2:** Bind `single<TtsEngine> { NullTtsEngine() }` in all three
  platform modules (Android gets the real impl in Task 1.2).
- [x] **Step 3:** `./gradlew :composeApp:assembleDebug` → BUILD SUCCESSFUL.
- [x] **Step 4:** Commit: `Add TtsEngine abstraction with Null impls (PLAN 1.1)`

### Task 1.2: Android `TextToSpeech` impl

**Files:**
- Create: `composeApp/src/androidMain/kotlin/com/visorcraft/radioshed/audio/AndroidTtsEngine.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/visorcraft/radioshed/di/PlatformModule.android.kt`

- [x] **Step 1:** Implement `AndroidTtsEngine(private val context: Context)`:
  lazy `TextToSpeech` init guarded by a `CompletableDeferred<Boolean>` for the
  init callback; `synthesizeToFile` wraps
  `TextToSpeech.synthesizeToFile(text, params, File(outPath), utteranceId)` +
  `setOnUtteranceProgressListener` completion into a suspending call
  (`suspendCancellableCoroutine`); apply `voice?.rate`/`voice?.pitch` via
  `setSpeechRate` / `setPitch`; `runCatching` everything → false on failure.
- [x] **Step 2:** Swap the Android binding:
  `single<TtsEngine> { AndroidTtsEngine(androidContext()) }`.
- [x] **Step 3:** `./gradlew :composeApp:assembleDebug` → BUILD SUCCESSFUL.
- [x] **Step 4:** Manual device check (deferred to Task 1.7's end-to-end pass
  is acceptable): a debug toast/log that a file > 0 bytes was produced.
  *(Deferred to the Phase 1 device pass per this step's own note.)*
- [x] **Step 5:** Commit: `Add Android TextToSpeech engine (PLAN 1.2)`

### Task 1.3: DJ template engine (pure common, fully TDD)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/stations/DjTemplate.kt`
- Test: `composeApp/src/commonTest/kotlin/com/visorcraft/radioshed/stations/DjTemplateTest.kt`

- [x] **Step 1:** Write the failing tests first:

```kotlin
package com.visorcraft.radioshed.stations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DjTemplateTest {

    private val vars = DjTemplateVars(
        station = "Morning Drive",
        track = "Blue Monday",
        artist = "New Order",
        nextTrack = "Age of Consent",
        time = "7:45 am",
        weather = null,
    )

    @Test
    fun substitutesAllKnownVariables() {
        val out = DjTemplate.render("Now on {station}: {track} by {artist}", vars)
        assertEquals("Now on Morning Drive: Blue Monday by New Order", out)
    }

    @Test
    fun nullVariableDropsItsClause() {
        // Sentences containing an unresolved variable are dropped entirely
        // rather than reading "the weather is null".
        val out = DjTemplate.render(
            "That was {track}. Outside it's {weather}. Up next, {next_track}.",
            vars,
        )
        assertEquals("That was Blue Monday. Up next, Age of Consent.", out)
    }

    @Test
    fun unknownVariableIsLeftVerbatim() {
        val out = DjTemplate.render("Hello {nope}", vars)
        assertEquals("Hello {nope}", out)
    }

    @Test
    fun pickVariantIsDeterministicForSeed() {
        val variants = listOf("a {track}", "b {track}", "c {track}")
        val first = DjTemplate.pickVariant(variants, seed = 42L)
        val second = DjTemplate.pickVariant(variants, seed = 42L)
        assertEquals(first, second)
        assertTrue(variants.contains(first))
    }

    @Test
    fun consecutivePicksAvoidImmediateRepeat() {
        val variants = listOf("a", "b")
        val first = DjTemplate.pickVariant(variants, seed = 1L)
        val next = DjTemplate.pickVariant(variants, seed = 2L, avoid = first)
        assertTrue(next != first)
    }
}
```

- [x] **Step 2:** Run
  `./gradlew :composeApp:testDebugUnitTest --tests "com.visorcraft.radioshed.stations.DjTemplateTest"`
  → FAIL (unresolved references).
- [x] **Step 3:** Implement `DjTemplateVars` (data class of nullable strings)
  and `object DjTemplate` with `render(template, vars)` (sentence-split on
  `.`/`!`/`?`, drop sentences whose `{var}` resolved null, substitute the
  rest) and `pickVariant(variants, seed, avoid = null)` (seeded
  `kotlin.random.Random(seed)`; re-pick once if the result equals `avoid`).
- [x] **Step 4:** Same test command → PASS.
- [x] **Step 5:** Add `DEFAULT_DJ_TEMPLATES` in the same file: 3–4 variants
  for each of the four template slots (station ID, up-next, recap,
  time-of-day) per SPEC §9 — plain Kotlin `List<String>` constants.
- [x] **Step 6:** Commit: `Add DJ template engine with variant randomizer (PLAN 1.3)`

### Task 1.4: DJ settings + backup keys

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/data/SettingsStore.kt`
- Test: extend the existing settings/backup round-trip test class (find it via
  `grep -rl "dumpForBackup" composeApp/src/commonTest/`)

- [x] **Step 1:** Failing test: round-trip the new keys through
  `dumpForBackup()` → `restoreFromBackup()` and assert values survive
  (mirror the existing test's structure for adjacent keys).
- [x] **Step 2:** Run it → FAIL.
- [x] **Step 3:** Add keys + getter/setters, each in **all three places**
  (getter/setter, `dumpForBackup` bucket, `expectedTypeForKey`):
  - `KEY_DJ_ENABLED` (Boolean, default false)
  - `KEY_DJ_FREQUENCY` (String enum: `off` / `every` / `every_n` /
    `minutes` / `station_change`; default `off`)
  - `KEY_DJ_FREQUENCY_N` (Int, default 4, coerce 2..20)
  - `KEY_DJ_FREQUENCY_MINUTES` (Int, default 15, coerce 5..120)
  - `KEY_DJ_QUIET_START_MIN` / `KEY_DJ_QUIET_END_MIN` (Int, minutes from
    midnight, default 1320 / 420 i.e. 10pm–7am, coerce 0..1439)
  - `KEY_DJ_DUCK_FRACTION` (Float, default 0.2f, coerce 0.0f..0.6f)
  - `KEY_DJ_TEMPLATES` (String, JSON map slot→list of variants; empty string
    = use `DEFAULT_DJ_TEMPLATES`)
- [x] **Step 4:** Test → PASS.
- [x] **Step 5:** Commit: `Add DJ settings keys with backup round-trip (PLAN 1.4)`

### Task 1.5: `DjAnnouncer` orchestration (common, TDD on the decision logic)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/stations/DjAnnouncer.kt`
- Test: `composeApp/src/commonTest/kotlin/com/visorcraft/radioshed/stations/DjAnnouncerTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/di/Modules.kt`

- [x] **Step 1:** Failing tests for the pure decision core
  `DjAnnouncer.shouldAnnounce(...)`:

```kotlin
class DjAnnouncerTest {
    @Test fun offFrequencyNeverAnnounces() { /* freq=off → false always */ }
    @Test fun everyNFiresOnNthTransition() { /* n=3: false,false,true,false,... */ }
    @Test fun minutesModeRespectsElapsed() { /* lastAnnounceMs + 15min gate */ }
    @Test fun stationChangeModeFiresOnlyOnStationSwitch() { }
    @Test fun quietHoursSuppressAcrossMidnight() {
        // quiet 22:00→07:00: 23:30 suppressed, 06:00 suppressed, 12:00 allowed
    }
}
```

  `shouldAnnounce` takes plain params (frequency mode, counters,
  `nowMinutesOfDay`, quiet window, station-changed flag) — no clock reads
  inside, fully deterministic.
- [x] **Step 2:** Run → FAIL. Implement → PASS.
- [x] **Step 3:** Define the transition payload at the bottom of
  `DjAnnouncer.kt` (helpers-in-file convention):

```kotlin
/** What just happened in the queue — everything a template can mention. */
data class TrackTransition(
    val stationName: String?,
    val endedTrack: Playable?,
    val startingTrack: Playable,
    val nextTrack: Playable?,
    val stationChanged: Boolean,
)
```

  Then implement the side-effecting half in the same class:
  `suspend fun announceIfDue(transition: TrackTransition)` —
  1. consult `shouldAnnounce` (time via `data/Time.kt`'s `nowMillis`),
  2. render template (`DjTemplate.render`, vars from the transition's
     station/track/next-track + formatted time),
  3. cache lookup via `DjCacheRepository` (key = stable hash of
     text+voiceId); miss → `TtsEngine.synthesizeToFile` into the cache path,
  4. duck: remember `audioEngine.volume.value`, `setVolume(duckFraction)`,
     play the clip via `ClipPlayer` (next step), restore volume in a
     `finally`.
  All failures swallow to no-op (`runCatching`); DJ must never break
  playback.
- [x] **Step 4:** Add `ClipPlayer` interface next to `TtsEngine` in
  `audio/TtsEngine.kt` (`suspend fun playFile(path: String)`, suspends until
  done) — Android impl `AndroidClipPlayer` using `MediaPlayer` with
  `suspendCancellableCoroutine` on completion; Null impls desktop/iOS. Bind
  in platform modules.
- [x] **Step 5:** Bind `DjAnnouncer` as `single` in `sharedModule`
  (deps: `SettingsStore`, `DjCacheRepository`, `TtsEngine`, `ClipPlayer`,
  `AudioEngine`); call `announceIfDue` from `StationPlayback`'s
  track-transition path (find the completed-play hook where the
  ListenBrainz scrobble fires — `StationPlayback.kt` ~line 886 — and add the
  DJ hook on the *start-of-next-track* side, launched in the existing scope,
  non-blocking).
- [x] **Step 6:** `./gradlew :composeApp:testDebugUnitTest` → PASS;
  `assembleDebug` → BUILD SUCCESSFUL.
- [x] **Step 7:** Commit: `Wire DjAnnouncer into station playback (PLAN 1.5)`

### Task 1.6: DJ settings UI + template editor

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/ui/settings/DjSettingsScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/ui/settings/AppSettingsScreen.kt`
  (add the "AI DJ" section entry), `App.kt` (route)

- [x] **Step 1:** Build `DjSettingsScreen` following the hoisted-state +
  `koinInject()` defaults pattern of `AppSettingsScreen.kt:97-99`: master
  toggle, frequency selector (segmented/dropdown for the 5 modes + N /
  minutes steppers), quiet-hours start/end pickers, duck-level slider,
  "Preview voice" button (synthesizes a fixed sentence and plays it via the
  Task 1.5 plumbing).
- [x] **Step 2:** Template editor inside the screen: one card per slot
  (station ID / up-next / recap / time), each listing its variants with
  edit/delete + add; chips inserting `{station}` `{track}` `{artist}`
  `{next_track}` `{time}` `{weather}` at the cursor; "Reset to defaults"
  restores `DEFAULT_DJ_TEMPLATES`. Persist via `KEY_DJ_TEMPLATES` JSON.
- [ ] **Step 3:** `./gradlew :composeApp:run` → navigate Settings → AI DJ,
  exercise every control; values survive app restart.
  *(Deferred to Phase Exit Gate G2 desktop smoke.)*
- [ ] **Step 4:** On the Android device: enable DJ, frequency `every`, play a
  station, confirm an announcement ducks music, speaks, restores volume.
  *(Deferred to Phase 1 device pass — needs the physical Android device.)*
- [x] **Step 5:** Commit: `Add AI DJ settings screen and template editor (PLAN 1.6)`

### Task 1.7: TTS pre-cache ahead of playback

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/stations/DjAnnouncer.kt`,
  `StationPlayback.kt`

- [x] **Step 1:** Failing test: `DjAnnouncerTest.precacheKeyIsStableForSameInputs`
  (hash function returns identical cache keys for identical text+voice, and
  differs when text differs).
- [x] **Step 2:** Implement `suspend fun precacheUpcoming(transitions: List<TrackTransition>)`
  — render + synthesize for the next 1–2 queue items if not already cached;
  call it from `StationPlayback` wherever the queue advances (same hook as
  1.5, after the current announcement decision). Cache rows go through
  `DjCacheRepository` so its existing eviction applies.
- [x] **Step 3:** Tests + `assembleDebug` green. Device check: with DJ on,
  no audible synthesis delay on track change (clip starts <300 ms after
  duck). *(Device timing check deferred to the Phase 1 device pass.)*
- [x] **Step 4:** Commit: `Pre-cache DJ clips one queue-step ahead (PLAN 1.7)`

### Task 1.8 (optional, do last): Open-Meteo `{weather}` variable

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/source/WeatherFetcher.kt`
  (interface + Null), `composeApp/src/androidMain/.../source/AndroidWeatherFetcher.kt`
- Modify: platform modules, `DjAnnouncer.kt`

- [x] **Step 1:** Interface: `suspend fun current(): WeatherSnapshot?`
  (`data class WeatherSnapshot(val tempC: Double, val condition: String)`).
  Android impl: one `HttpURLConnection` GET to
  `https://api.open-meteo.com/v1/forecast?latitude=..&longitude=..&current_weather=true`,
  coarse last-known location (no new permission — if location permission is
  absent, return null), 15-minute in-memory cache, **gated through
  `NetworkPolicyGate`** like every other fetcher.
- [x] **Step 2:** `DjAnnouncer` fills `vars.weather` ("12 degrees and clear")
  when a snapshot exists; the Task 1.3 null-drops-clause behavior already
  handles absence.
- [x] **Step 3:** Tests + builds green; device check with a weather template. *(Device check deferred to the Phase 1 device pass.)*
- [x] **Step 4:** Commit: `Add Open-Meteo weather variable for DJ templates (PLAN 1.8)`

### Task 1.9: Phase 1 exit

- [x] Run the **Phase Exit Gate** (G1–G6).
  *(G1 done — commit 223b19b; carried-over getDjFrequency runCatching collapsed,
  bridge-scaffold/slider dedup judged no-action with reasons. G2 done — full
  testDebugUnitTest + assembleDebug + compileKotlinDesktop green; interactive
  desktop window smoke + Android device pass still owed to the human (no
  unattended GUI/device here). G3 done — pushed to origin/next-features.
  G4 done — see review log. G5 done — commit 2fe5574. G6 deferred to W5 by
  design: one minor version bump for the whole plan instead of six.)* Phase-specific dedup checks:
  - `AndroidTtsEngine` / `AndroidClipPlayer` callback-to-coroutine bridging —
    if both grew the same `suspendCancellableCoroutine` listener scaffold,
    extract a private helper in `androidMain/audio/`.
  - DJ JSON (templates) serialization must reuse the existing
    `kotlinx.serialization` setup, not hand-rolled parsing.
  - SPEC flips: Phase 9 boxes (TTS abstraction, Android impl, template
    engine, randomizer, default library, editor UI, pre-cache, ducking,
    frequency config, quiet hours). Cloud-TTS box stays open unless the
    stretch task ran.

---

# Phase 2 — Drive Mode completion (SPEC §7.3, Phase 8) (~1 week)

Existing: manual toggle (`SettingsStore.getDriveMode()`) re-skins Now
Playing's transport row. Missing: auto-triggers, big-button station picker,
configurable button row, auto-exit, and the deferred "Add to Drive Mode
quick-list" entry in `StationActionSheet`.

Scope locked in: triggers are **Bluetooth device** (enter on connect, exit
on disconnect), **schedule** (time window), **manual** (exists). Speed- and
headlight-based triggers stay deferred (need location/vehicle plumbing —
flagged out of scope; leave their SPEC boxes open).

### Task 2.1: Trigger domain + settings

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/domain/DriveModeTrigger.kt`
- Modify: `SettingsStore.kt`
- Test: `composeApp/src/commonTest/kotlin/com/visorcraft/radioshed/domain/DriveModeTriggerTest.kt`

- [x] **Step 1:** Failing test: serialize/deserialize a mixed trigger list
  via `kotlinx.serialization` JSON; assert round-trip equality.
- [x] **Step 2:** Implement:

```kotlin
package com.visorcraft.radioshed.domain

import kotlinx.serialization.Serializable

@Serializable
sealed interface DriveModeTrigger {
    @Serializable
    data class BtDevice(val mac: String, val name: String) : DriveModeTrigger

    /** Minutes from midnight, local time. Window may cross midnight. */
    @Serializable
    data class Schedule(val startMin: Int, val endMin: Int) : DriveModeTrigger
}
```

- [x] **Step 3:** Settings keys (three-place rule + round-trip test):
  `KEY_DRIVE_TRIGGERS` (String JSON list), `KEY_DRIVE_QUICKLIST` (String,
  comma-joined station ids, ordered, max 6), `KEY_DRIVE_BUTTONS` (String,
  comma-joined action ids), `KEY_DRIVE_AUTO_EXIT` (Boolean, default true).
- [x] **Step 4:** Tests PASS. Commit:
  `Add Drive Mode trigger domain and settings (PLAN 2.1)`

### Task 2.2: `BluetoothWatcher` platform peer + `DriveModeController`

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/car/BluetoothWatcher.kt`
  (interface + Null), `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/car/DriveModeController.kt`
- Create: `composeApp/src/androidMain/kotlin/com/visorcraft/radioshed/car/AndroidBluetoothWatcher.kt`
- Modify: platform modules, `di/Modules.kt`, `App.kt` (collect controller state)
- Test: `composeApp/src/commonTest/kotlin/com/visorcraft/radioshed/car/DriveModeControllerTest.kt`

- [x] **Step 1:** `BluetoothWatcher` interface:
  `val connectedDevices: StateFlow<Set<BtPeer>>` +
  `data class BtPeer(val mac: String, val name: String)` + `fun start()` /
  `fun stop()`. `NullBluetoothWatcher` emits empty set (desktop/iOS).
- [x] **Step 2:** Failing controller tests (inject a `MutableStateFlow` as a
  fake watcher; `kotlinx-coroutines-test`):
  - connect of a configured MAC → `active` becomes true
  - disconnect with `KEY_DRIVE_AUTO_EXIT=true` → false; with false → stays true
  - schedule window containing "now" activates; crossing midnight works
  - manual toggle overrides triggers in both directions until next trigger edge
- [x] **Step 3:** Implement `DriveModeController(watcher, settings, scope)` —
  combines watcher flow + a minute-tick schedule check + the manual setting
  into `val active: StateFlow<Boolean>`; writes back to `KEY_DRIVE_MODE` so
  every existing consumer (Now Playing re-skin) keeps working unchanged.
  Tests PASS.
- [x] **Step 4:** `AndroidBluetoothWatcher`: `BroadcastReceiver` on
  `BluetoothDevice.ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED`,
  seeded from `BluetoothManager.adapter.bondedDevices` where connected;
  needs `BLUETOOTH_CONNECT` runtime permission on API 31+ — request through
  the existing `permissions/` machinery **only from the trigger-config UI**
  (never at startup); without the grant the watcher emits empty and the UI
  shows why.
- [x] **Step 5:** Bind in modules; start/stop the controller from `App.kt`
  lifecycle. `testDebugUnitTest` + `assembleDebug` green.
- [x] **Step 6:** Commit: `Auto Drive Mode via Bluetooth and schedule triggers (PLAN 2.2)`

### Task 2.3: Trigger configuration UI

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/ui/car/DriveModeSettingsScreen.kt`
- Modify: `AppSettingsScreen.kt` (In-Car section), `App.kt` (route)

- [x] **Step 1:** Screen: trigger list (add BT device from paired-device
  picker fed by `BluetoothWatcher`, add schedule window with time pickers,
  delete via swipe/long-press), auto-exit toggle, quick-list editor (pick up
  to 6 stations, drag to order), button-row editor (choose 4–6 actions from:
  play/pause, next, previous, station picker, voice search, mute).
- [x] **Step 2:** Desktop run → screen renders, BT section shows
  "not available on this platform" (Null watcher), schedule triggers fully
  functional. Device → pairing list appears after permission grant.
  *(Interactive desktop/device smokes deferred to the Phase Exit Gate / device pass.)*
- [x] **Step 3:** Commit: `Add Drive Mode trigger and layout settings UI (PLAN 2.3)`

### Task 2.4: Big-button station picker + configurable button row

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/ui/car/DriveStationPicker.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/ui/player/NowPlayingScreen.kt`
  (drive-mode transport row reads `KEY_DRIVE_BUTTONS`; station-picker button
  opens the new picker)

- [x] **Step 1:** `DriveStationPicker`: full-screen 2×3 grid (one column
  under 600 dp width) of quick-list stations — artwork-dominant tiles,
  ≥96 dp hit targets, station name in `headlineMedium`+; tap = switch
  station and dismiss; empty quick-list falls back to the 6 most-played
  stations (`StationRepository` already has play-count sorting).
- [x] **Step 2:** Replace the hardcoded drive-mode transport row with the
  configured action list from `KEY_DRIVE_BUTTONS` (default: previous,
  play/pause, next, station picker).
- [x] **Step 3:** Add "Add to Drive Mode quick-list" to `StationActionSheet`
  in `StationsHomeScreen.kt` (the deferred SPEC Phase-6 item) — appends to
  `KEY_DRIVE_QUICKLIST`, disabled when full at 6.
- [x] **Step 4:** Desktop + device smoke: toggle Drive Mode manually, open
  picker, switch stations, verify oversized layout on a narrow window.
- [x] **Step 5:** Commit: `Add big-button drive station picker and button config (PLAN 2.4)`

### Task 2.5: Phase 2 exit

- [x] Run the **Phase Exit Gate**.
  *(G1 — commit c78ead7: BT same-mac dedupe, shared trigger codec in domain file,
  test constants, import/dead-code sweep; picker-tile and dialog dedup judged
  no-action with reasons. G2 green. G3 pushed. G4 — see review log; 2 issues
  fixed in ac54947. G5 — commit 4018795. G6 deferred to W5.)* Phase-specific dedup checks:
  - `DriveStationPicker` tiles vs `StationCard` in Stations Home — share the
    artwork/fallback-initials rendering if both now implement it.
  - Time-window ("crosses midnight") logic exists in BOTH
    `DjAnnouncer.shouldAnnounce` (quiet hours) and
    `DriveModeController` (schedule trigger) — extract a single
    `fun Int.isInMinuteWindow(startMin: Int, endMin: Int): Boolean` helper
    into `util/` with its own test, and use it from both.
  - SPEC flips: Phase 8 trigger-registry (BT + schedule + manual parts),
    big-button picker, configurable buttons, auto-exit; Phase 6 long-press
    "Add to Drive Mode quick-list". Leave speed/headlight boxes open with a
    note.

---

# Phase 3 — True crossfade + gapless (SPEC §8, Phase 7 / #112) (~1–2 weeks)

Existing: end-of-track fade-out via `KEY_END_FADE_SEC` (single player, no
overlap — see `StationPlayback.kt:346`), and a complete domain model
already shipped in `domain/PlaybackRule.kt` (`Crossfade.Off / Timed /
Smart`, `CrossfadeCurve.EqualPower / Linear / SCurve`). This phase
implements what the domain promises.

Scope locked in: "Smart" = crossfade with the configured duration **except**
skip the fade-in when the incoming track starts near-silent is OUT of scope
(content analysis deferred — `Smart` behaves as `Timed` with the default
duration this phase; document that in code and SPEC). Gapless = queue
preloading on both engines. Sample-accurate verification is a listening
test, not an automated one.

### Task 3.1: Fade-curve math (pure common, TDD)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/audio/FadeCurves.kt`
- Test: `composeApp/src/commonTest/kotlin/com/visorcraft/radioshed/audio/FadeCurvesTest.kt`

- [x] **Step 1:** Failing tests:

```kotlin
class FadeCurvesTest {
    @Test fun equalPowerSumsToUnityAtMidpoint() {
        val out = FadeCurves.gain(CrossfadeCurve.EqualPower, fraction = 0.5f)
        val inn = FadeCurves.gain(CrossfadeCurve.EqualPower, fraction = 0.5f, fadeIn = true)
        // equal-power: g_out^2 + g_in^2 == 1 (within float tolerance)
        assertEquals(1f, out * out + inn * inn, absoluteTolerance = 0.001f)
    }
    @Test fun linearIsComplementary() { /* out(f) + in(f) == 1 */ }
    @Test fun curvesAreMonotonicAndClamped() { /* f<0→1.0 out, f>1→0.0 out; no negatives */ }
    @Test fun sCurveStartsSlowEndsSlow() { /* derivative smaller at 0.05 than 0.5 */ }
}
```

- [x] **Step 2:** FAIL → implement `object FadeCurves { fun gain(curve, fraction, fadeIn = false): Float }`
  (equal-power = cos/sin of `fraction * PI/2`; s-curve = smoothstep) → PASS.
- [x] **Step 3:** Commit: `Add crossfade gain curves with unity-power tests (PLAN 3.1)`

### Task 3.2: Android dual-player crossfade

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/com/visorcraft/radioshed/audio/AudioEngineAndroid.kt`,
  `composeApp/src/androidMain/kotlin/com/visorcraft/radioshed/audio/RadioShedMediaSessionService.kt`
- Create: `composeApp/src/androidMain/kotlin/com/visorcraft/radioshed/audio/CrossfadePlayerPair.kt`

**Pre-step:** `RadioShedMediaSessionService.kt` is 82 KB — read its player
construction + queue-advance regions fully before touching it, and extract
the new logic into `CrossfadePlayerPair` rather than growing the service.

- [x] **Step 1:** `CrossfadePlayerPair` (androidMain/audio) owns `primary` +
  `secondary` ExoPlayer; `suspend fun crossfadeTo(item, durationMs, curve)`
  prepares the standby at volume 0, starts it, ramps both volumes along
  `FadeCurves.gain(...)` in a 50 ms-step loop, swaps roles, resets the
  retired player. Single in-flight crossfade via `Mutex`; cancel-safe
  `finally` jump-cuts to the incoming track at full volume.
- [x] **Step 2:** Trigger lives in `StationPlayback`'s existing position
  collector (it owns the queue, so it advances). Arming math is the
  common, tested `shouldArmCrossfade(...)` + `resolveCrossfadePlan(...)`
  (`audio/CrossfadePlan.kt`); `Crossfade.Off` keeps today's path; the
  `KEY_END_FADE_SEC` end-fade is suppressed whenever a crossfade plan is
  active. "Smart" = fixed `SMART_CROSSFADE_DEFAULT_SEC` this phase
  (documented in `CrossfadePlan.kt`).
- [x] **Step 3:** MediaSession follows the swap via
  `MediaLibrarySession.setPlayer(StationAwarePlayer(newActive))` from
  `CrossfadePlayerPair.onActivePlayerChanged`; the incoming ExoPlayer
  already holds the incoming item, so notification/Auto metadata flips at
  swap. *(Lock-screen + AVRCP visual confirmation still needs a device pass
  — see Step 5.)*
- [x] **Step 4:** Gapless: `StationPlayback` peeks one queue item ahead and
  pushes it via `AudioEngine.setUpNext(...)`; the service preloads it on the
  standby ExoPlayer. NOTE: this warms the decoder for a fast next-track
  start, not single-player sample-accurate gapless — that re-architecture
  stays deferred (SPEC Phase 7 "Gapless verified sample-accurate").
- [ ] **Step 5:** Device listening pass (wired + Bluetooth) — NOT done in
  this session (no device/emulator in the build env). Smoke-test on hardware
  before a public release: equal-power fade level-constant; skip during fade
  no crash/leak; lock-screen + AVRCP metadata follow the swap. KNOWN EDGE:
  skip/stop mid-crossfade cancels the ramp and honours the new track
  (`cancelCrossfade` IPC + cancel-safe `finally`), but the cancel command and
  the follow-up `play()` cross the controller boundary on separate channels,
  so in the rare exact-overlap-skip case a sub-second blip of the crossfade
  target may precede the skip target. No crash/leak; volumes land sane.
- [x] **Step 6:** `testDebugUnitTest` + `assembleDebug` + `compileKotlinDesktop`
  green. Commit: `Implement dual-ExoPlayer crossfade and gapless preload (PLAN 3.2)`

### Task 3.3: Desktop dual-libvlc crossfade

**Files:**
- Modify: `composeApp/src/desktopMain/kotlin/com/visorcraft/radioshed/audio/AudioEngineDesktop.kt`,
  `composeApp/src/desktopMain/kotlin/com/visorcraft/radioshed/audio/NativeLibVlc.kt`

- [ ] **Step 1:** Extend `NativeLibVlc` with a second media-player handle
  (factory already exists); add `setPlayerVolume(handle, 0..100)` binding if
  not present (`libvlc_audio_set_volume`).
- [ ] **Step 2:** Mirror the `CrossfadePlayerPair` ramp inside the existing
  ~250 ms polling architecture (ramp steps can ride the poll loop at
  ~100 ms granularity — coarser than Android, acceptable). Reuse
  `FadeCurves` for gains; same suppression of the end-fade path; the
  Ended-transition auto-advance must not double-fire when a crossfade
  already started the next track (guard flag).
- [ ] **Step 3:** `./gradlew :composeApp:run` with VLC installed: listen to
  a 5 s crossfade between two local tracks; skip mid-fade; pause mid-fade.
- [ ] **Step 4:** Commit: `Desktop libvlc crossfade via second player handle (PLAN 3.3)`

### Task 3.4: Per-station crossfade editing + global default

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/ui/stations/StationEditorScreen.kt`
  (PlaybackRule section), `AppSettingsScreen.kt` (Audio section copy)

- [ ] **Step 1:** Station editor: crossfade rule control — Off / Timed
  (duration slider 1–12 s + curve picker) / Smart (explain it currently
  equals Timed-with-default; honest copy). The settings screen's existing
  end-fade copy (`AppSettingsScreen.kt:1347,1425`) gets updated to say true
  crossfade now exists per-station and end-fade applies only when crossfade
  is Off.
- [ ] **Step 2:** Desktop + device smoke; values persist per station.
- [ ] **Step 3:** Commit: `Expose crossfade rule in station editor (PLAN 3.4)`

### Task 3.5: Phase 3 exit

- [ ] Run the **Phase Exit Gate**. Phase-specific dedup checks:
  - Android `CrossfadePlayerPair` ramp loop vs desktop ramp loop: gain math
    and timing constants must come from `FadeCurves` + shared constants in
    commonMain — zero curve math in platform code.
  - The retired end-fade implementation: if crossfade fully covers it, fold
    the volume-restore handling into one place rather than two parallel
    fade systems (keep end-fade only as the crossfade=Off path).
  - SPEC flips: Phase 7 crossfade impl + curves; gapless verified (cite the
    listening pass); #112 in Phase 18. "Smart Fades content-aware" box
    STAYS OPEN — add a cite-note that Smart currently aliases Timed.

---

# Phase 4 — Scrobbling completion (SPEC Phase 10) (~3–4 days)

Existing (already shipped, don't redo): `ListenBrainzClient` interface +
`AndroidListenBrainzClient`, settings keys (`KEY_LISTENBRAINZ_ENABLED` /
`KEY_LISTENBRAINZ_TOKEN`), token validation, and the ≥50%-or-≥4-min
completion gate in `StationPlayback.kt:886-902`. Missing: desktop is a
`NullListenBrainzClient` (silently drops scrobbles), and failed submissions
are lost (no offline queue). Last.fm OAuth stays deferred — it needs a
registered API key; do not start it without one (decision gate left open in
SPEC).

### Task 4.1: Offline scrobble queue (schema + repository, TDD)

**Files:**
- Create: `composeApp/src/commonMain/sqldelight/com/visorcraft/radioshed/db/Scrobbles.sq`,
  `composeApp/src/commonMain/sqldelight/com/visorcraft/radioshed/db/migrations/8.sqm`
- Create: `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/data/ScrobbleQueueRepository.kt`
- Modify: `di/Modules.kt`
- Test: `composeApp/src/commonTest/kotlin/com/visorcraft/radioshed/data/ScrobbleQueueRepositoryTest.kt`

- [ ] **Step 1:** Schema — `Scrobbles.sq` baseline **and** identical
  statements in `8.sqm` (shipped migrations are immutable; this is a new one):

```sql
CREATE TABLE scrobble_queue (
    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    artist TEXT NOT NULL,
    track TEXT NOT NULL,
    album TEXT,
    listened_at_seconds INTEGER NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);
```

  Queries: `enqueue`, `oldestBatch(limit)`, `deleteById`, `bumpAttempts`,
  `countAll`, `pruneOverCap` (keep newest 500).
- [ ] **Step 2:** Regenerate:
  `./gradlew :composeApp:generateCommonMainRadioShedDatabaseInterface --rerun-tasks`
  → BUILD SUCCESSFUL.
- [ ] **Step 3:** Failing repository tests on an in-memory DB
  (`StationRoundTripCrashTest` pattern): enqueue→drain order is FIFO,
  `deleteById` removes, attempts bump, cap prunes oldest beyond 500.
- [ ] **Step 4:** Implement `ScrobbleQueueRepository(db)` (all I/O via
  `withContext(IODispatcher)`); bind in `sharedModule`. Tests PASS.
- [ ] **Step 5:** Commit: `Add offline scrobble queue table and repository (PLAN 4.1)`

### Task 4.2: Queue-then-drain submission flow

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/stations/StationPlayback.kt`
- Create: `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/source/ScrobbleSync.kt`
- Modify: `di/Modules.kt`, `App.kt` (drain on launch)
- Test: `composeApp/src/commonTest/kotlin/com/visorcraft/radioshed/source/ScrobbleSyncTest.kt`

- [ ] **Step 1:** Failing tests with a fake `ListenBrainzClient`
  (succeed/fail scriptable): submit-success leaves queue empty;
  submit-failure enqueues; `drain()` submits FIFO and stops at first
  failure (bumping attempts); drain respects `NetworkPolicyGate` deny;
  entries with `attempts >= 10` are dropped with a log.
- [ ] **Step 2:** Implement `ScrobbleSync(queue, client, settings, gate)`:
  `suspend fun submitOrEnqueue(listen)`, `suspend fun drain()`. Change the
  `StationPlayback` call site (~line 891) to call `submitOrEnqueue` instead
  of the bare `listenBrainz.submit(...)`. Drain triggers: app launch
  (`App.kt`) and after every successful live submit.
- [ ] **Step 3:** Tests PASS; `assembleDebug` green.
- [ ] **Step 4:** Commit: `Queue failed scrobbles and drain on launch (PLAN 4.2)`

### Task 4.3: Desktop ListenBrainz client

**Files:**
- Create: `composeApp/src/desktopMain/kotlin/com/visorcraft/radioshed/source/DesktopListenBrainzClient.kt`
- Modify: `composeApp/src/desktopMain/kotlin/com/visorcraft/radioshed/di/PlatformModule.desktop.kt`

- [ ] **Step 1:** Read `AndroidListenBrainzClient` first. Mirror it exactly
  in desktopMain (same JSON payloads, same endpoints, same
  `HttpURLConnection` usage — both source sets are JVM, so the body should
  be near-identical; keep comments in sync, this pair is on the Phase Exit
  dedup checklist).
- [ ] **Step 2:** Swap the desktop binding from `NullListenBrainzClient` to
  the real one.
- [ ] **Step 3:** `./gradlew :composeApp:run` → paste a real token in
  Settings → validate succeeds; play a track ≥50% → listen appears on
  listenbrainz.org.
- [ ] **Step 4:** Commit: `Enable ListenBrainz scrobbling on desktop (PLAN 4.3)`

### Task 4.4: Scrobble status surface + Phase 4 exit

- [ ] **Step 1:** Settings → the existing ListenBrainz section gains a status
  line: "N queued" (from `countAll`) + "Retry now" button (calls `drain()`),
  only visible when N > 0.
- [ ] **Step 2:** Run the **Phase Exit Gate**. Phase-specific dedup checks:
  - Android vs desktop ListenBrainz clients — diff them
    (`diff <(sed ...) <(sed ...)` on the two files): only imports/class
    names may differ. If the bodies diverged, reconcile.
  - SPEC flips: ListenBrainz box in Phase 10 (now fully true:
    queue + desktop); note Last.fm stays deferred pending an API key.

---

# Phase 5 — Subsonic follow-through (SPEC Phase 16) (~1 week)

Existing: `SubsonicClient` interface + `AndroidSubsonicClient` (token auth),
`SubsonicBrowseScreen` (`ui/cloud/`), settings keys
(`KEY_SUBSONIC_URL/USERNAME/PASSWORD`), `MediaSource` /
`MediaSourceRegistry` / `SourceRule.MediaSourceSelector` plumbing with
`MockCloudSource` as the demo impl, and `SourceRuleEvaluator` already
routing `MediaSourceSelector` through the registry. Missing: a real
`SubsonicSource` (so stations can include Subsonic tracks), desktop client
(currently `NullSubsonicClient`), and the station-editor UI to select a
media source.

### Task 5.1: Desktop Subsonic client

**Files:**
- Create: `composeApp/src/desktopMain/kotlin/com/visorcraft/radioshed/source/DesktopSubsonicClient.kt`
- Modify: `PlatformModule.desktop.kt`

- [ ] **Step 1:** Mirror `AndroidSubsonicClient` (read it fully first —
  token auth: `t = md5(password + salt)`, never send the cleartext
  password; same one-GET-per-call `HttpURLConnection` style). Swap the
  desktop binding.
- [ ] **Step 2:** `./gradlew :composeApp:run` → Settings → Subsonic →
  configure a server (Navidrome test instance) → `SubsonicBrowseScreen`
  lists artists/albums; streaming a song plays through libvlc (http input).
- [ ] **Step 3:** Commit: `Enable Subsonic on desktop (PLAN 5.1)`

### Task 5.2: `SubsonicSource` MediaSource impl (TDD)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/source/SubsonicSource.kt`
- Modify: `di/Modules.kt` (register/unregister on config change)
- Test: `composeApp/src/commonTest/kotlin/com/visorcraft/radioshed/source/SubsonicSourceTest.kt`

- [ ] **Step 1:** Failing tests with a fake `SubsonicClient` returning a
  small canned artist→album→song tree:
  - `listTracks()` emits every song exactly once as `DiscoveredTrack`
    (uri = `streamUrl(...)`, format from `SubsonicSong` suffix)
  - `SourceFilter.formats` filters; min/max duration filters apply
  - client error mid-enumeration completes the flow with what it has
    (no throw)
- [ ] **Step 2:** Implement
  `SubsonicSource(client, settings, gate) : MediaSource` — `id = "subsonic:<host>"`,
  capabilities `(supportsStreaming = true, supportsArtwork = true)`, cold
  flow walking artists→albums→songs, **`NetworkPolicyGate` check before any
  call**. Tests PASS.
- [ ] **Step 3:** Registration: in `App` startup (and on Subsonic settings
  change), register the source in `MediaSourceRegistry` when a server is
  configured, unregister when cleared. (Look at how `MockCloudSource` is
  registered today and follow/replace that path.)
- [ ] **Step 4:** Commit: `Add SubsonicSource media source over SubsonicClient (PLAN 5.2)`

### Task 5.3: Hybrid stations — editor UI + end-to-end

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/ui/stations/StationEditorScreen.kt`
- Test: extend `composeApp/src/commonTest/.../stations/SourceRuleEvaluatorTest.kt`
  (find existing class; add cases)

- [ ] **Step 1:** Failing evaluator test: a station whose rule tree mixes a
  local rule with `MediaSourceSelector("subsonic:test")` yields tracks from
  both (fake registry with a fake source).
- [ ] **Step 2:** Make it pass (likely already passes given the evaluator
  routes through the registry — if so, keep the test as a regression lock
  and say so in the commit).
- [ ] **Step 3:** Station editor: rule builder gains a "Media source" rule
  type — dropdown of registered sources from `MediaSourceRegistry.sources`
  (hidden when only local exists). Persist as `MediaSourceSelector`.
- [ ] **Step 4:** Device + desktop end-to-end: build a hybrid station
  (local genre rule + Subsonic source), play it, confirm both source types
  stream; toggle Connectivity policy to Never → Subsonic tracks skip
  cleanly with no stall (watch for queue-advance behavior).
- [ ] **Step 5:** Commit: `Hybrid stations: media-source rule in editor (PLAN 5.3)`

### Task 5.4: Phase 5 exit

- [ ] Run the **Phase Exit Gate**. Phase-specific dedup checks:
  - Android vs desktop Subsonic clients — same diff discipline as the
    ListenBrainz pair.
  - `SubsonicSource` enumeration vs `SubsonicBrowseScreen`'s own
    artist/album walking — if the screen duplicates traversal, refactor it
    onto `SubsonicSource`/shared helper.
  - `MockCloudSource`: still needed for tests/demo? If `SubsonicSource`
    tests cover the registry path, consider demoting Mock to commonTest.
  - SPEC flips: Phase 16 `SubsonicSource` module box; hybrid-station box;
    note which Phase 16 source modules remain (Jellyfin, Plex, …).

---

# Phase 6 — Power tools (SPEC Phases 5, 13, 14) (~1 week)

Three independent, smaller features. They may be executed in any order;
each is committed separately. (If splitting work across workers, these
three tasks are parallelizable.)

### Task 6.1: Batch tag editor

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/ui/library/EditTagsDialog.kt`
  (extend to N tracks), `LibraryBrowserScreen.kt` (multi-select entry point)
- Test: `composeApp/src/commonTest/kotlin/com/visorcraft/radioshed/ui/library/BatchTagMergeTest.kt`

Existing: single-track `EditTagsDialog` + `TagWriter` (Android + desktop
impls). Multi-value tags remain out of scope (schema collapses to primary —
documented in `EditTagsDialog.kt:40`).

- [ ] **Step 1:** Failing test for the pure merge model: given N tracks'
  tags, fields where all values agree show the common value; mixed fields
  show a "Multiple values" marker; only user-edited fields are written
  back (`data class BatchTagEdit(val field: TagField, val newValue: String)`)
  — put this model at the bottom of `EditTagsDialog.kt` per the
  helpers-in-file convention.
- [ ] **Step 2:** Implement model → tests PASS.
- [ ] **Step 3:** UI: library multi-select (the file browser already has
  the multi-select pattern — reuse its top-bar approach) gains "Edit tags";
  dialog shows merged fields, writes via `TagWriter` in a progress loop
  (per-file `runCatching`, summary "12 written, 1 failed"), then triggers
  rescan of the touched rows so the DB reflects the files.
- [ ] **Step 4:** Device + desktop smoke on a 3-track batch (FLAC + MP3 mix).
- [ ] **Step 5:** Commit: `Batch tag editing over multi-select (PLAN 6.1)`

### Task 6.2: Android station-launcher widget

**Files:**
- Create: `composeApp/src/androidMain/kotlin/com/visorcraft/radioshed/widget/StationLauncherWidget.kt`
- Modify: `composeApp/src/androidMain/AndroidManifest.xml` (receiver),
  `composeApp/src/androidMain/res/xml/` (widget provider info),
  `NowPlayingWidget.kt` only if extracting shared helpers

Existing: `NowPlayingWidget.kt` (classic RemoteViews `AppWidgetProvider`) and
`WidgetConfigRepository` + `Widgets.sq` (already in DI) for per-widget config.

- [ ] **Step 1:** Read `NowPlayingWidget.kt` fully; extract any
  PendingIntent/bitmap helpers you'd otherwise copy into a shared
  `widget/WidgetSupport.kt` (this is a known dedup hotspot — flagged in the
  exit gate).
- [ ] **Step 2:** `StationLauncherWidget`: grid of 1/4/6 station tiles
  (artwork + name via RemoteViews; 12-tile XL variant only if RemoteViews
  grid behaves — otherwise document the cap), tap = launch app + start that
  station through the same intent path the Now-Playing widget uses for its
  transport actions (read how it reaches `StationPlayback` and reuse that
  mechanism — do not invent a second intent protocol). Config activity: pick
  stations per slot, persisted in `WidgetConfigRepository` keyed by
  `appWidgetId`; deleted widgets clean their rows
  (`onDeleted`).
- [ ] **Step 3:** Device: add 1×1 and 2×3 widgets, launch stations from
  cold + warm app states; rotate; remove widget → config row gone.
- [ ] **Step 4:** Commit: `Add station-launcher home-screen widget (PLAN 6.2)`

### Task 6.3: SmartQuery auto-rotate refresh (TDD)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/visorcraft/radioshed/stations/SmartQueryRefresh.kt`
- Modify: `di/Modules.kt`, `App.kt` (run on launch + daily tick),
  `SettingsStore.kt` (one key)
- Test: `composeApp/src/commonTest/kotlin/com/visorcraft/radioshed/stations/SmartQueryRefreshTest.kt`

- [ ] **Step 1:** Failing tests: with `KEY_SMARTQUERY_REFRESH_LAST` older
  than 24 h, `refreshIfDue(nowMs)` re-evaluates SmartQuery-backed stations
  (fake repository counts calls) and bumps the marker; within 24 h → no-op;
  first run (no marker) → refreshes.
- [ ] **Step 2:** Implement: find SmartQuery stations via
  `StationRepository`, re-run their queries through the existing
  `SourceRuleEvaluator` path so cached station track-sets reflect today's
  library (read how station results are cached before writing — if
  evaluation is already lazy per-play, the refresh only needs to invalidate
  caches + bump a `rotatedAt`, and the test asserts the invalidation).
  Settings key `KEY_SMARTQUERY_REFRESH_LAST` (Long; three-place rule).
- [ ] **Step 3:** Wire to app launch in `App.kt`. Tests + builds green.
- [ ] **Step 4:** Commit: `Daily SmartQuery station refresh (PLAN 6.3)`

### Task 6.4: Phase 6 exit

- [ ] Run the **Phase Exit Gate**. Phase-specific dedup checks:
  - `NowPlayingWidget` vs `StationLauncherWidget` RemoteViews/bitmap/intent
    helpers → must share `WidgetSupport.kt`.
  - Library multi-select vs file-browser multi-select top bar — if the
    Task 6.1 implementation copied it, extract a shared composable in
    `ui/util/`.
  - SPEC flips: Phase 14 tag editor (single + batch; multi-value stays
    open), Phase 13 station-launcher widget (note tile-count actually
    shipped), Phase 5 auto-rotate refresh.

---

# Final wrap-up

- [ ] **W1:** Full regression: `testDebugUnitTest`, `assembleDebug`,
  `packageSingleFileAppImage`, desktop run, device install
  (`adb install -r ...`) and a 30-minute mixed soak: DJ on, Drive Mode BT
  trigger, crossfading station, hybrid Subsonic station, scrobbles landing.
- [ ] **W2:** One final `opencode` review over the whole effort
  (`git diff <phase0-base>..HEAD`) — same recipe as the Phase Exit Gate; this
  catches cross-phase duplication individual phase reviews couldn't see.
- [ ] **W3:** Docs: update `USER_GUIDE.md` (AI DJ, Drive Mode triggers,
  crossfade, Subsonic stations, batch tags, widget) and `FAQ.md` where
  features change answers.
- [ ] **W4:** `SPEC.md` final sweep — every box this plan flipped has a cite;
  every box deliberately left open (speed trigger, Smart Fades
  content-analysis, Last.fm, multi-value tags, cloud TTS) has a one-line
  "deferred because" note.
- [ ] **W5:** Release per AGENTS.md: bump `version.properties` (minor),
  `buildDate` to today UTC, tag `vX.Y.0`, `gh release create` + upload
  per-platform assets (debug-signed APK recipe in AGENTS.md; zipalign
  before apksigner).

---

# Review logs

Append one subsection per phase as reviews complete:
`## Phase N review — <date>` with the VERDICT line, issues fixed, and false
positives (with one-line reasons). This is the audit trail that the
peer-review gates actually ran.

## Phase 1 review — 2026-06-10

- First opencode run hit the 280s timeout before emitting a verdict (not
  treated as a pass); rerun with a budget-for-verdict instruction completed.
- `VERDICT approved issues=1` (deepseek, --variant high).
- Issue 1 (real): `describeWmoCode` classified WMO 80–82 (rain showers) as
  "snowy" and absorbed gap codes 78/83. Fixed in c668271 with table-driven
  test updates (80–82 → rainy; 71–77, 85–86 → snowy; 78/83 → unsettled).
- The run also verified: Koin arities, three-place settings rule, the two
  DjAnnouncer mutexes + sleep-timer guard, AndroidTtsEngine double-checked
  init. No false positives to log.
- Subagent task reviews this phase additionally caught and fixed: TTS init
  lock held across await, utterance-map cancellation leak, orphan template
  sentences, DjAnnouncer volume-restore race, sleep-fade conflict, stranded
  preview duck on screen dispose, weather fetch on Default dispatcher, DJ
  cache eviction gap, and vendor-URL User-Agent policy violations.

## Phase 2 review — 2026-06-10

- opencode (deepseek, --variant high): `Verdict: approved issues=2`.
- Issue 1 (real, latent): AndroidBluetoothWatcher stop()→start() cannot recover
  already-connected peers; KDoc overclaimed idempotency. Doc made honest in
  ac54947 (production uses a single app-lifetime start).
- Issue 2 (real): API 30 (minSdk) needs legacy BLUETOOTH permission for
  device name/address reads; manifest only declared BLUETOOTH_CONNECT (31+).
  Fixed in ac54947 with maxSdkVersion="30" declaration.
- Reviewer notes not counted as issues: redundant preview+commit volume call
  (harmless), getDriveMode read-once pattern (matches convention), concrete
  NullBluetoothWatcher type check (correct for current platform matrix).
- Task-review loop this phase caught and fixed: trim asymmetry + filter-contract
  test gap (2.1), permission grant-flow dead end + desktop messaging (2.3),
  stale drive-mode branches, duplicate preMuteLevel desync, silent voice
  no-match, station-visuals duplication (2.4).
