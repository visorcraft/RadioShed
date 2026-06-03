# Desktop libvlc via JNA (drop VLCJ/GPL) + "VLC required" modal — Design

**Date:** 2026-06-03
**Status:** Approved (design); pending spec review → implementation
**Owner:** VisorCraft LLC

## 1. Problem & Goal

Two coupled desktop-audio changes:

1. **Remove the GPL.** The desktop build links **VLCJ** (`uk.co.caprica:vlcj`,
   **GPL-3.0-or-later**) — Caprica's Java bindings to libvlc — which conflicts
   with RadioShed's proprietary `LICENSE`. Replace VLCJ with our own thin
   **Kotlin + JNA** binding to **libvlc** itself. libvlc is **LGPL-2.1+** and is
   the *user-installed* native library (dynamically loaded, not bundled); JNA is
   **Apache-2.0** (its dual LGPL/Apache license — we use the Apache option). Net
   result: **no GPL artifact ships**, and the desktop GPL blocker is *resolved*,
   not just documented. (VLC the app and libvlc are never redistributed by us.)

2. **"VLC required" modal.** On desktop, if libvlc isn't installed, show a
   dismissible modal: *"Media cannot be played until VLC is installed."* with a
   **Get VLC** button (videolan.org). Triggers both at startup and on a play
   attempt. Android/iOS are unaffected (Media3 / AVAudioEngine).

The two share one discovery path, so they're built together.

## 2. Decisions locked

- **FFI = JNA** (Apache-2.0; works on JVM 17, no Panama/preview needed).
- **State/position = polling** a ~250 ms coroutine timer (no native event
  callbacks — avoids the crash-prone JNA callback + event-union path).
- **Modal: Both triggers** (startup + play-attempt re-check), **dismissible**.
- Target **libvlc 3.x** (the current stable line; AGENTS.md already requires it).

## 3. Component A — `NativeLibVlc` (desktopMain)

New file `composeApp/src/desktopMain/kotlin/com/visorcraft/radioshed/audio/NativeLibVlc.kt`.

### 3a. JNA interface (libvlc 3.x C API — the full surface we need)

A `com.sun.jna.Library` interface loaded via `Native.load("vlc", LibVlc::class.java)`:

```kotlin
internal interface LibVlc : Library {
    fun libvlc_new(argc: Int, argv: Array<String>?): Pointer?
    fun libvlc_release(instance: Pointer)
    fun libvlc_media_new_path(instance: Pointer, path: String): Pointer?
    fun libvlc_media_new_location(instance: Pointer, mrl: String): Pointer?
    fun libvlc_media_release(media: Pointer)
    fun libvlc_media_player_new(instance: Pointer): Pointer?
    fun libvlc_media_player_release(player: Pointer)
    fun libvlc_media_player_set_media(player: Pointer, media: Pointer)
    fun libvlc_media_player_play(player: Pointer): Int
    fun libvlc_media_player_set_pause(player: Pointer, doPause: Int)
    fun libvlc_media_player_stop(player: Pointer)              // 3.x void
    fun libvlc_media_player_set_time(player: Pointer, ms: Long) // 3.x void(mp, time)
    fun libvlc_media_player_get_time(player: Pointer): Long
    fun libvlc_media_player_get_length(player: Pointer): Long
    fun libvlc_media_player_get_state(player: Pointer): Int     // libvlc_state_t
    fun libvlc_audio_set_volume(player: Pointer, volume: Int): Int
    fun libvlc_media_player_set_rate(player: Pointer, rate: Float): Int
}
```

`libvlc_state_t` ints: NothingSpecial 0, Opening 1, Buffering 2, **Playing 3,
Paused 4, Stopped 5, Ended 6, Error 7**.

> **Version note:** 3.x `stop`/`set_time` are `void` with the simple signature;
> libvlc 4.x renamed them (`stop_async`, `set_time(.., fast)`). We bind 3.x.
> VLC 4.x is not yet a stable release, so this is the right target; document it.

### 3b. Discovery (`NativeLibVlc.available`, memoized)

Replicates a pragmatic subset of VLCJ's `NativeDiscovery`. `by lazy { … }` so it
runs **once** (shared by the engine and the modal). Steps:

1. Honor an explicit override: env `VLC_PLUGIN_PATH` / `jna.library.path` if
   already set, or a `RADIOSHED_LIBVLC_PATH` env var.
2. Per-OS candidate dirs:
   - **Linux:** rely on the default loader path (system libvlc in `/usr/lib*`),
     plus `/usr/lib`, `/usr/lib64`, `/usr/lib/x86_64-linux-gnu`, `/usr/local/lib`.
     Handle the **versioned SONAME** (`libvlc.so.5`) — if `Native.load("vlc")`
     fails, try the explicit `libvlc.so.5`. Plugins are found via libvlc's
     compiled-in path (no env needed in the common case).
   - **Windows:** `C:\Program Files\VideoLAN\VLC`,
     `C:\Program Files (x86)\VideoLAN\VLC` (and the `HKLM\Software\VideoLAN\VLC\
     InstallDir` registry value if easily readable). Append to `jna.library.path`
     and set `VLC_PLUGIN_PATH=<dir>\plugins`.
   - **macOS:** `/Applications/VLC.app/Contents/MacOS/lib` (+ `~/Applications/…`);
     plugins at `…/MacOS/plugins`. Append to `jna.library.path`; set
     `VLC_PLUGIN_PATH`.
3. Setting `VLC_PLUGIN_PATH` at runtime (Windows/macOS) needs a native `setenv`/
   `_putenv` (the JVM can't mutate its own env) — a tiny JNA helper
   (`c`/`msvcrt` `setenv`/`_putenv`), mirroring what VLCJ does. On Linux/macOS
   libvlc usually self-locates plugins relative to the loaded library, so this is
   belt-and-suspenders.
4. Attempt `Native.load("vlc", LibVlc::class.java)` then a probe `libvlc_new`.
   Cache the loaded `LibVlc` + instance on success; return availability.

> **Highest-risk area.** Per-OS discovery (esp. the Windows install-dir lookup
> and the Linux `.so.5` name) is exactly why VLCJ exists. The v1 here covers the
> standard installs; **it needs real-machine testing on each OS** and may need
> path tuning after. The env override is the escape hatch meanwhile.

## 4. Component B — `AudioEngineDesktop` rewrite

Same file, same `AudioEngine` contract and behavior (queue, auto-advance,
`resolveMrl` UNC handling, clean `close()`), but backed by `NativeLibVlc`:

- **Handles:** hold `instance: Pointer` + `player: Pointer` (created lazily on
  first `play()` via `NativeLibVlc.available`); `libvlcLoadFailed` as today. On
  discovery failure set the same `PlayerState.Error("libvlc not found — install
  VLC … and relaunch.")`.
- **`play(playable)`**: pick `libvlc_media_new_location` if the MRL has a URI
  scheme (`^[A-Za-z][A-Za-z0-9+.-]*://`) else `libvlc_media_new_path` (preserves
  raw/UNC paths); `set_media`; **release the media handle** (player retains its
  own ref); `play`. Set `_state = Buffering`; keep the stderr `play($mrl)` log.
- **Transport:** `pause` → `set_pause(1)`; `resume` → `play`; `togglePlayPause`
  off `_state`; `seekTo` → `set_time`; `setVolume` → `audio_set_volume(0..100)`;
  `setPlaybackSpeed` → `set_rate` (clamp 0.25..4); `stop` → `stop` + reset state.
- **Polling loop** (replaces VLCJ events): a coroutine on the existing
  crashGuard scope, started when the player is created; every **~250 ms** while a
  player exists, read `get_state` + `get_time` + `get_length`:
  - map state → `PlayerState` (3→Playing, 4→Paused, 1/2→Buffering, 5→Idle,
    6→Ended, 7→Error). Only emit on change.
  - update `_position = Position(get_time, get_length)`.
  - **Auto-advance:** on the *transition into* Ended (guarded by a
    `advancedForThisTrack` flag reset in `play()`), `scope.launch { skipNext() }`
    — matches today's `finished()` behavior, fires once.
- **`close()`**: cancel the poll loop; `libvlc_media_player_release(player)`;
  `libvlc_release(instance)`; null the handles (idempotent; safe if libvlc never
  loaded). Keeps the clean-audio-device-on-exit contract.

## 5. Component C — `isMediaPlaybackAvailable()` + the modal

- **`expect fun isMediaPlaybackAvailable(): Boolean`** in
  `commonMain/.../audio/`. Desktop actual → `NativeLibVlc.available`; Android &
  iOS actuals → `true`. (Makes the modal desktop-only with no UI platform
  branching.)
- **`VlcRequiredDialog(onDismiss, onGetVlc)`** — a common Material 3 `AlertDialog`
  (follow existing dialog patterns):
  - text: *"Media cannot be played until VLC is installed."* + a small second
    line *"RadioShed uses VLC's libvlc engine for desktop playback. Install VLC,
    then restart RadioShed."*
  - confirm **"Get VLC"** → `com.visorcraft.radioshed.source.openWebUrl("https://www.videolan.org/vlc/")`
    then dismiss; dismiss **"Continue"** → dismiss.
- **`App.kt` wiring** — one `var showVlcDialog by remember { mutableStateOf(false) }`:
  - startup: `LaunchedEffect(Unit) { if (!isMediaPlaybackAvailable()) showVlcDialog = true }`.
  - play-attempt: collect `AudioEngine.state` (koinInject); when it becomes
    `PlayerState.Error` **and** `!isMediaPlaybackAvailable()`, set
    `showVlcDialog = true` (re-shows after dismiss; robust vs. string matching —
    a normal file error with VLC present won't trigger it).
  - render `VlcRequiredDialog` when `showVlcDialog`.

## 6. Build & licensing changes

- **`gradle/libs.versions.toml`:** remove `vlcj` version + `vlcj` library; add
  `jna = "5.14.0"` (or latest stable) + `jna = { module = "net.java.dev.jna:jna", … }`.
- **`composeApp/build.gradle.kts` desktopMain:** replace `implementation(libs.vlcj)`
  with `implementation(libs.jna)`; update the VLCJ comment block.
- **License catalog (`LicenseCatalog.kt`):** remove `vlcj` and `vlcj-natives`;
  change JNA from transitive → **direct** `net.java.dev.jna:jna` (Apache-2.0,
  dual-license note retained); `libvlc` stays external/LGPL. Remove the
  `GPL_3_0_OR_LATER` License enum value and **delete `GPL-3.0.txt`** (no GPL
  component remains). Update `LicenseCatalogTest`'s expected/excluded sets.
- **Regenerate `THIRD_PARTY_LICENSES`** (`generateThirdPartyLicenses`).

## 7. Doc updates (GPL resolved)

- **SPEC.md Appendix C:** flip the VLCJ/GPL blocker bullet to **resolved** —
  "VLCJ replaced by a direct JNA binding to LGPL libvlc; desktop is GPL-free."
- **AGENTS.md:** (a) audio architecture note — desktop is now a "JNA libvlc
  binding," not VLCJ; (b) the `### Releases` desktop-publish blocker — remove/soften
  (no GPL blocker for *future* builds); (c) the VLCJ/libvlc wording clarification
  from question #2 (the GPL was the *binding*, not libvlc).
- v1.1.0's published release note stays as-is (it *did* ship VLCJ); the de-GPL
  applies to the next build/release.

## 8. Verification

- `./gradlew :composeApp:compileKotlinDesktop --offline` — covers `NativeLibVlc`,
  the rewritten engine, the modal, `App.kt`. **(EXIT 0 baseline.)**
- `./gradlew :composeApp:desktopTest --offline` — `LicenseCatalogTest` updated;
  add small pure-logic tests (MRL scheme detection → path vs location; state-int
  → `PlayerState` mapping).
- `generateThirdPartyLicenses` → confirm vlcj/GPL gone, jna direct.
- **Runtime NOT verifiable here** (headless / no audio): the "audio actually
  plays, events poll correctly, discovery finds VLC, no native crashes" check
  must be done by the user on a VLC-equipped machine per OS. This is the main
  caveat. The old VLCJ path is fully replaced, so there's no fallback — call this
  out to the user before they rely on desktop playback.

## 9. Risks & mitigations

- **Per-OS discovery** (highest): standard installs covered; env override
  (`RADIOSHED_LIBVLC_PATH`) as escape hatch; flagged for real-machine testing.
- **JNA signatures vs libvlc 3.x**: pinned to 3.x; documented. A 4.x-only user
  would hit a missing symbol on `stop`/`set_time` — acceptable (4.x unreleased),
  noted.
- **Polling latency** (~250 ms on state change / auto-advance): acceptable for a
  music app; not gapless-critical.
- **Native crash surface** minimized by polling (no callbacks on libvlc threads).
- **No runtime test here** — explicit user-testing handoff.

## 10. Out of scope

- iOS audio (still stubbed).
- Gapless playback / crossfade at the libvlc layer.
- A full reimplementation of VLCJ's entire discovery heuristics (registry deep
  scan, every distro path) — v1 covers standard installs + override.

## 11. Acceptance criteria

1. No `uk.co.caprica:vlcj` anywhere in the build; `net.java.dev.jna:jna` is a
   direct Apache-2.0 dep; **no GPL** component in the catalog or
   `THIRD_PARTY_LICENSES`; `GPL-3.0.txt` removed.
2. `AudioEngineDesktop` implements the full `AudioEngine` contract via
   `NativeLibVlc` with polling; queue/auto-advance/close behavior preserved.
3. Desktop shows the dismissible "VLC required" modal at startup and on a play
   attempt when libvlc is absent; never on Android/iOS.
4. `compileKotlinDesktop` + `desktopTest` green; `THIRD_PARTY_LICENSES`
   regenerated.
5. SPEC/AGENTS reflect the resolved GPL status and the JNA-binding architecture.
6. Runtime playback verified by the user on a VLC-equipped machine (handoff).
