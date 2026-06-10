package com.visorcraft.radioshed.audio

import com.sun.jna.Pointer
import com.visorcraft.radioshed.data.IODispatcher
import com.visorcraft.radioshed.domain.CrossfadeCurve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * libvlc-backed [AudioEngine] for the desktop target, bound directly to
 * the LGPL libvlc 3.x C API via JNA ([NativeLibVlc]) — no GPL VLCJ layer.
 * Handles the same formats the rest of RadioShed handles on Android
 * (MP3 / FLAC / Opus / OGG / ALAC / AAC / WMA) without us shipping
 * decoders: the user's installed libvlc does the work.
 *
 * libvlc is discovered at first play via [NativeLibVlc.available]. If it
 * can't be found, [play] surfaces a [PlayerState.Error] instructing the
 * user to install VLC; the app stays functional otherwise (browsing,
 * settings, scanning) so a missing libvlc isn't a blocker for setup. The
 * "VLC required" modal (see `App`) also reads [NativeLibVlc.available]
 * via `isMediaPlaybackAvailable`.
 *
 * Threading / state: libvlc has no Java-side event callbacks here. State
 * and position are read by a ~250 ms polling coroutine on the engine's
 * own [scope] (started when the player is created). Polling — rather than
 * binding libvlc's event manager — keeps native code off Java threads and
 * avoids the crash-prone JNA callback + event-union path. StateFlow value
 * sets are thread-safe, so Compose collects them directly.
 */
class AudioEngineDesktop : AudioEngine {

    // crashGuard so a libvlc-bridge exception (native error during
    // polling, a call racing shutdown, etc.) doesn't bubble to the JVM
    // uncaught-exception handler and pop the Win32 "RadioShed has stopped
    // working" message box.
    private val scope = CoroutineScope(
        SupervisorJob() + IODispatcher +
            com.visorcraft.radioshed.util.crashGuard("AudioEngineDesktop"),
    )

    private val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _nowPlaying = MutableStateFlow<Playable?>(null)
    override val nowPlaying: StateFlow<Playable?> = _nowPlaying.asStateFlow()

    private val _position = MutableStateFlow(Position(0L, 0L))
    override val position: StateFlow<Position> = _position.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    override val volume: StateFlow<Float> = _volume.asStateFlow()

    private var queue: List<Playable> = emptyList()
    private var queueIndex: Int = 0

    /** Lazily created on first play(); null if libvlc isn't installed. */
    private var lib: LibVlc? = null
    private var instance: Pointer? = null
    private var player: Pointer? = null
    private var libvlcLoadFailed: Boolean = false

    /** The poll loop; cancelled and recreated with the player. */
    private var pollJob: Job? = null

    /**
     * Guards one-shot auto-advance on the transition into Ended. Reset in
     * [play] so the next track can auto-advance once when it finishes.
     */
    private var advancedForThisTrack: Boolean = false

    /** Last state we emitted, to dedupe poll-loop emissions. */
    private var lastPolledState: Int = LibVlcState.NOTHING_SPECIAL

    /**
     * The pre-created standby player used as the crossfade INCOMING side,
     * mirroring Android's [CrossfadePlayerPair]. Created lazily by the first
     * [crossfadeTo] (or [setUpNext]) and reused for the rest of the JVM
     * lifetime: after a swap the *retired* player becomes the new standby, so
     * we keep at most two players alive. Shares the engine's libvlc [instance].
     */
    private var standby: Pointer? = null

    /** Last speed set via [setPlaybackSpeed]; carried across crossfades. */
    private var playbackRate: Float = 1f

    /**
     * Single-flight latch for the crossfade ramp, mirroring
     * [CrossfadePlayerPair]'s mutex. A [cancelCrossfade] cancels the in-flight
     * ramp via [rampJob]; a second [crossfadeTo] serializes behind the mutex.
     */
    private val crossfadeMutex = Mutex()

    /** The in-flight ramp's [Job], so [cancelCrossfade] can interrupt it. */
    @Volatile
    private var rampJob: Job? = null

    /**
     * True while a ramp is mid-flight. The poll loop reads it so the OUTGOING
     * player's transient Ended (which can fire as its tail drains during the
     * overlap) never triggers the engine-internal queue auto-advance — the
     * crossfade itself is the advance. StationPlayback raises its own
     * `suppressEndedForCrossfade` commonside for the same reason, but this
     * guards the engine's [playQueue] callers (MiniPlayer / Search) too.
     */
    @Volatile
    private var crossfadeInFlight: Boolean = false

    private fun ensurePlayer(): Pointer? {
        player?.let { return it }
        if (libvlcLoadFailed) return null
        // NativeLibVlc.available probes typical install paths (Program
        // Files, /Applications, /usr/lib, …) and the versioned Linux
        // SONAME. We treat a miss as a recoverable error, not a crash.
        val loaded = NativeLibVlc.lib
        if (loaded == null) {
            libvlcLoadFailed = true
            _state.value = PlayerState.Error(
                "libvlc not found — install VLC media player from videolan.org and relaunch.",
            )
            return null
        }
        val inst = runCatching { loaded.libvlc_new(0, null) }.getOrNull()
        if (inst == null) {
            libvlcLoadFailed = true
            _state.value = PlayerState.Error("Failed to initialise libvlc.")
            return null
        }
        val p = runCatching { loaded.libvlc_media_player_new(inst) }.getOrNull()
        if (p == null) {
            libvlcLoadFailed = true
            runCatching { loaded.libvlc_release(inst) }
            _state.value = PlayerState.Error("Failed to create media player.")
            return null
        }
        // libvlc's volume range is 0..200 (200 = software amplification).
        // Seed at 100 so default volume matches the OS-level level and we
        // never apply gain we didn't ask for.
        runCatching { loaded.libvlc_audio_set_volume(p, 100) }
        lib = loaded
        instance = inst
        player = p
        startPolling(loaded, p)
        return p
    }

    /**
     * Poll `get_state` / `get_time` / `get_length` every ~250 ms while a
     * player exists. Replaces VLCJ's event callbacks: state changes are
     * emitted on change, position is updated each tick, and the
     * transition into Ended auto-advances the queue exactly once.
     */
    private fun startPolling(loaded: LibVlc, p: Pointer) {
        pollJob?.cancel()
        lastPolledState = LibVlcState.NOTHING_SPECIAL
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val nativeState = runCatching { loaded.libvlc_media_player_get_state(p) }
                    .getOrDefault(LibVlcState.ERROR)
                val time = runCatching { loaded.libvlc_media_player_get_time(p) }.getOrDefault(0L)
                val length = runCatching { loaded.libvlc_media_player_get_length(p) }.getOrDefault(0L)
                _position.value = Position(currentMs = time.coerceAtLeast(0L), durationMs = length.coerceAtLeast(0L))

                if (nativeState != lastPolledState) {
                    lastPolledState = nativeState
                    // Suppress the sub-250 ms "Ended" flicker while a crossfade
                    // is mid-flight: the OUTGOING player's tail drains during the
                    // overlap and briefly reaches Ended before the swap completes.
                    // Let Error and all other states through so real failures are
                    // always surfaced; only Ended is gated here.
                    val suppressEnded = nativeState == LibVlcState.ENDED && crossfadeInFlight
                    if (!suppressEnded) {
                        mapState(nativeState)?.let { _state.value = it }
                    }
                    // Auto-advance on the *transition into* Ended, once
                    // per track — matches the old VLCJ finished() behavior.
                    // Suppress it while a crossfade is mid-flight: the OUTGOING
                    // player's Ended fires as its tail drains during the overlap,
                    // and the crossfade has ALREADY started the next track, so a
                    // second advance here would skip a track (mirrors
                    // StationPlayback's suppressEndedForCrossfade, but guards the
                    // engine's own playQueue auto-advance path).
                    if (nativeState == LibVlcState.ENDED && !advancedForThisTrack &&
                        !crossfadeInFlight
                    ) {
                        advancedForThisTrack = true
                        scope.launch { skipNext() }
                    }
                }
            }
        }
    }

    override fun play(playable: Playable) {
        val p = ensurePlayer() ?: return
        val loaded = lib ?: return
        val inst = instance ?: return
        advancedForThisTrack = false
        _nowPlaying.value = playable
        _state.value = PlayerState.Buffering
        val mrl = resolveMrl(playable.uri)
        // Log to stderr so the gradle :run console captures what we sent
        // libvlc — invaluable when a track refuses to play and the user
        // reports a playback error without any way to know which URI was
        // attempted.
        System.err.println("AudioEngineDesktop: play($mrl)")
        val media = newMedia(loaded, inst, mrl)
        if (media == null) {
            _state.value = PlayerState.Error("Failed to open ${mrl.substringAfterLast('\\').substringAfterLast('/')}.")
            return
        }
        loaded.libvlc_media_player_set_media(p, media)
        // The player retains its own reference to the media, so release
        // our handle immediately to avoid leaking one per track.
        loaded.libvlc_media_release(media)
        loaded.libvlc_media_player_play(p)
    }

    /**
     * Create a libvlc media handle for [mrl]. Scheme-bearing MRLs (http://,
     * file://, smb://, …) go through new_location; everything else is a
     * local/UNC path → new_path, which preserves the raw/UNC pass-through
     * [resolveMrl] relies on. Returns null on failure. The CALLER owns the
     * returned handle and must release it after `set_media` retains its own
     * reference (see [play] / [crossfadeTo]).
     */
    private fun newMedia(loaded: LibVlc, inst: Pointer, mrl: String): Pointer? =
        if (hasUriScheme(mrl)) {
            runCatching { loaded.libvlc_media_new_location(inst, mrl) }.getOrNull()
        } else {
            runCatching { loaded.libvlc_media_new_path(inst, mrl) }.getOrNull()
        }

    override fun playQueue(items: List<Playable>, startIndex: Int) {
        if (items.isEmpty()) return
        queue = items
        queueIndex = startIndex.coerceIn(0, items.lastIndex)
        play(items[queueIndex])
    }

    override fun pause() {
        val p = player ?: return
        lib?.libvlc_media_player_set_pause(p, 1)
    }

    override fun resume() {
        val p = player ?: return
        lib?.libvlc_media_player_play(p)
    }

    override fun togglePlayPause() {
        val p = player ?: return
        val loaded = lib ?: return
        if (_state.value == PlayerState.Playing) loaded.libvlc_media_player_set_pause(p, 1)
        else loaded.libvlc_media_player_play(p)
    }

    override fun seekTo(positionMs: Long) {
        val p = player ?: return
        lib?.libvlc_media_player_set_time(p, positionMs.coerceAtLeast(0L))
    }

    override fun skipNext() {
        if (queue.isEmpty()) return
        val next = queueIndex + 1
        if (next >= queue.size) {
            stop()
            return
        }
        queueIndex = next
        play(queue[next])
    }

    override fun skipPrevious() {
        if (queue.isEmpty()) return
        val prev = (queueIndex - 1).coerceAtLeast(0)
        queueIndex = prev
        play(queue[prev])
    }

    override fun stop() {
        val p = player ?: return
        runCatching { lib?.libvlc_media_player_stop(p) }
        _state.value = PlayerState.Idle
        _position.value = Position(0L, 0L)
        lastPolledState = LibVlcState.STOPPED
    }

    override fun setVolume(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        _volume.value = clamped
        // Map 0..1 → 0..100 (libvlc's 1.0-equivalent reference level).
        // Above 100 is software gain we explicitly avoid.
        val p = player ?: return
        lib?.libvlc_audio_set_volume(p, toLibVlcVolume(clamped))
    }

    /**
     * Convert a 0..1 Float gain to libvlc's integer volume (0..100, the
     * 1.0-equivalent reference level — above 100 is software amplification we
     * deliberately avoid). Coerced both ends so a curve overshoot or a
     * corrupted target can never hand libvlc an out-of-range value. The single
     * conversion every Float gain goes through before it meets the C ABI.
     */
    private fun toLibVlcVolume(gain: Float): Int =
        (gain.coerceIn(0f, 1f) * 100f).roundToInt().coerceIn(0, 100)

    override fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4f)
        playbackRate = clamped
        val p = player ?: return
        lib?.libvlc_media_player_set_rate(p, clamped)
    }

    /**
     * Pre-create the standby player so a future [crossfadeTo] starts ramping
     * without first allocating a player. Deliberately does NOT pre-roll the
     * media: libvlc 3.x has no "prepare but don't play" primitive — calling
     * `play()` to fill its buffers would make the standby audible, and there's
     * no callback to tell us when buffering finished so we could re-pause it
     * silently. (Android's ExoPlayer DOES have `prepare()` + `playWhenReady =
     * false`, which is why [CrossfadePlayerPair.preloadUpNext] can truly
     * pre-roll there — the desktop primitive is simply weaker.) The standby is
     * created at volume 0 so its first audible sample is the crossfade ramp's
     * tick-1 fade-in level, never a blip at full volume. Passing null is a
     * no-op; we keep the standby alive for the next crossfade rather than tear
     * it down.
     */
    override fun setUpNext(playable: Playable?) {
        if (playable == null) return
        // Create the standby under crossfadeMutex so two concurrent setUpNext
        // calls (or a setUpNext racing a crossfadeTo, which holds the same lock)
        // can't both observe standby==null and allocate two native players,
        // leaking the loser. crossfadeTo calls ensureStandby() while already
        // holding the lock, so all standby creation is serialized.
        scope.launch { crossfadeMutex.withLock { ensureStandby() } }
    }

    override fun crossfadeTo(
        playable: Playable,
        durationMs: Long,
        curve: CrossfadeCurve,
        targetVolume: Float,
    ) {
        scope.launch {
            crossfadeMutex.withLock {
                val loaded = lib
                val inst = instance
                val outgoing = player
                // No active player / no libvlc → nothing to fade FROM; fall
                // back to a plain hard play of the incoming track.
                if (loaded == null || inst == null || outgoing == null) {
                    play(playable)
                    return@withLock
                }
                val incoming = ensureStandby() ?: run {
                    play(playable)
                    return@withLock
                }

                // Open the incoming media BEFORE arming the ramp/swap. If it
                // fails we abort with the outgoing track untouched (it keeps
                // playing and ends naturally) — we must NOT enter the try below,
                // whose finally would otherwise stop the outgoing player and
                // swap to a silent incoming.
                val mrl = resolveMrl(playable.uri)
                System.err.println("AudioEngineDesktop: crossfadeTo($mrl)")
                val media = newMedia(loaded, inst, mrl)
                if (media == null) {
                    _state.value = PlayerState.Error(
                        "Failed to open ${mrl.substringAfterLast('\\').substringAfterLast('/')}.",
                    )
                    return@withLock
                }

                // Publish rampJob BEFORE crossfadeInFlight: cancelCrossfade()
                // reads rampJob without the mutex, so the reverse order would
                // leave a window where it sees crossfadeInFlight=true but
                // rampJob=null and no-ops, letting a skip/stop race the ramp's
                // finally for the player handles.
                rampJob = coroutineContext.job
                crossfadeInFlight = true
                val incomingTarget = targetVolume.coerceIn(0f, 1f)
                // Capture the outgoing player's live libvlc level so the
                // fade-out scales relative to whatever ReplayGain / the user
                // set it to, instead of snapping to full on tick 1.
                val outgoingStart =
                    runCatching { loaded.libvlc_audio_get_volume(outgoing) }.getOrDefault(100)
                        .coerceIn(0, 100) / 100f

                // Metadata follows the incoming track at fade start (mirrors
                // Android, where the swap re-points the session and fires
                // onMediaItemTransition). The poll target is still the OUTGOING
                // player until the swap in `finally`, so state/position stay
                // coherent for the audible-leading track during the overlap.
                _nowPlaying.value = playable

                try {
                    runCatching { loaded.libvlc_audio_set_volume(incoming, 0) }
                    loaded.libvlc_media_player_set_media(incoming, media)
                    loaded.libvlc_media_release(media)
                    loaded.libvlc_media_player_play(incoming)

                    val steps = (durationMs / STEP_MS).coerceAtLeast(1L)
                    for (i in 1..steps) {
                        val fraction = i.toFloat() / steps.toFloat()
                        runCatching {
                            loaded.libvlc_audio_set_volume(
                                outgoing,
                                toLibVlcVolume(FadeCurves.gain(curve, fraction, fadeIn = false) * outgoingStart),
                            )
                        }
                        runCatching {
                            loaded.libvlc_audio_set_volume(
                                incoming,
                                toLibVlcVolume(FadeCurves.gain(curve, fraction, fadeIn = true) * incomingTarget),
                            )
                        }
                        delay(STEP_MS)
                    }
                } finally {
                    // Land a coherent end state whether the ramp completed or
                    // was cancelled (a skip / stop / station change arrived). No
                    // suspend calls here, so cancellation can't interrupt the
                    // swap: incoming becomes the active player at its effective
                    // target volume, outgoing is stopped and retired to standby.
                    finishCrossfade(loaded, outgoing, incoming, incomingTarget)
                }
            }
        }
    }

    /**
     * Swap roles after a ramp (completed or cancelled): land [incoming] at
     * [incomingTarget], stop [outgoing], make [incoming] the new active
     * [player] (so the poll loop's state / position / Ended auto-advance follow
     * it), retire [outgoing] to [standby], and re-sync the [volume] flow to the
     * incoming track's effective level — same class of fix as Android's
     * `onVolumeChanged` re-sync, so the sleep-fade / DJ-duck baselines read the
     * post-swap level rather than the outgoing track's stale ceiling.
     */
    private fun finishCrossfade(
        loaded: LibVlc,
        outgoing: Pointer,
        incoming: Pointer,
        incomingTarget: Float,
    ) {
        runCatching { loaded.libvlc_audio_set_volume(incoming, toLibVlcVolume(incomingTarget)) }
        runCatching { loaded.libvlc_media_player_stop(outgoing) }
        // The retired player becomes the next crossfade's standby. Park it at
        // full volume; every future audible use re-sets it first (a crossfade
        // overwrites both sides from tick 1; a plain play() seeds 100 on a
        // fresh player), so this is a neutral parking value, never heard.
        runCatching { loaded.libvlc_audio_set_volume(outgoing, 100) }
        player = incoming
        // Standby players are created at rate 1.0; carry the user's speed across
        // the swap — mirrors Android's CrossfadePlayerPair.
        runCatching { loaded.libvlc_media_player_set_rate(incoming, playbackRate) }
        standby = outgoing
        advancedForThisTrack = false
        _volume.value = incomingTarget
        // Re-point the poll loop at the new active player so state / position /
        // the Ended auto-advance track the incoming song. Lowering the in-flight
        // flag BEFORE restarting polling is safe: startPolling resets
        // lastPolledState, so the incoming player's first Ended (far in the
        // future) still auto-advances normally.
        crossfadeInFlight = false
        rampJob = null
        startPolling(loaded, incoming)
    }

    /**
     * Lazily create the standby player on the engine's libvlc [instance],
     * reusing it across crossfades. Returns null if libvlc isn't loaded yet
     * (no active player means nothing to crossfade from anyway).
     */
    private fun ensureStandby(): Pointer? {
        standby?.let { return it }
        val loaded = lib ?: return null
        val inst = instance ?: return null
        val p = runCatching { loaded.libvlc_media_player_new(inst) }.getOrNull() ?: return null
        runCatching { loaded.libvlc_audio_set_volume(p, 0) }
        standby = p
        return p
    }

    override fun cancelCrossfade() {
        // Jump-cut per the Android contract: cancel the ramp; its `finally`
        // (finishCrossfade) then lands the incoming track at its target volume
        // and retires the outgoing player. Idempotent — a no-op when no ramp
        // is running.
        //
        // cancelAndJoin (not bare cancel): StationPlayback calls this
        // immediately before a hard advanceToNext()/stop() on a DIFFERENT
        // thread than the ramp coroutine. The finally swaps `player` to the
        // incoming handle and stops the outgoing one; without joining, that
        // follow-up play()/stop() could act on the pre-swap `player` and race
        // the finally for the same native handle. Joining bounds the wait to
        // the (delay-free) finally and guarantees `player` is post-swap first.
        val job = rampJob ?: return
        runBlocking { runCatching { job.cancelAndJoin() } }
    }

    /**
     * Release native libvlc handles. Call from the application's
     * `onCloseRequest` so libvlc can flush write buffers and close the
     * audio device cleanly. Without this, exitApplication() terminates
     * the process while libvlc still owns the device, leaving it locked
     * for several seconds for the next app that opens it.
     *
     * Cancels the whole engine [scope] *and joins* its children before
     * releasing, so no native call can race the release: cancel() alone is
     * cooperative (the poll loop / crossfade ramp only observe it at the next
     * suspension point), which leaves a window where an in-flight iteration that
     * already passed its check calls get_state/get_time/set_volume on a pointer
     * this method then frees — a native use-after-free the per-call runCatching
     * cannot catch (it crashes the process, not the coroutine). Cancelling the
     * SCOPE (not just the captured pollJob/rampJob) also prevents an
     * already-launched-but-not-yet-started crossfadeTo coroutine from running
     * AFTER the release and touching freed handles. The children run on
     * Dispatchers.IO (a different thread than the onCloseRequest caller), so the
     * race is real. Joining bounds the wait to one in-flight native libvlc call.
     * Idempotent: subsequent calls are no-ops because the handles are nulled and
     * the cancelled scope launches nothing. Safe to invoke even when libvlc
     * never loaded (all handles are still null).
     */
    fun close() {
        // Cancel the scope so no queued ramp/poll coroutine can start after the
        // release below, then drain whatever is already in-flight before freeing
        // the native handles. joinAll on the captured jobs (the scope's own Job
        // is cancelled, so we can't join it from inside) bounds the wait to one
        // in-flight native call apiece.
        scope.cancel()
        runBlocking { runCatching { listOfNotNull(rampJob, pollJob).joinAll() } }
        rampJob = null
        pollJob = null
        val loaded = lib
        runCatching { player?.let { loaded?.libvlc_media_player_release(it) } }
        runCatching { standby?.let { loaded?.libvlc_media_player_release(it) } }
        runCatching { instance?.let { loaded?.libvlc_release(it) } }
        player = null
        standby = null
        instance = null
        lib = null
    }
}

/**
 * libvlc accepts file:// MRLs, bare local paths, AND raw UNC paths
 * (`\\server\share\file.mp3`). The instinct to convert local paths into a
 * `file://` URL via `File.toURI()` actually breaks UNC:
 * `File("\\\\host\\share\\f").toURI().toString()` produces
 * `file:////host/share/f` (4 slashes), which libvlc parses by stripping
 * the `file://` prefix and then handing the remaining `//host/share/f` to
 * its filesystem demuxer — that ends up as the single-backslash path
 * `\host\share\f`, which Windows networking can't resolve. Just pass the
 * raw path through; libvlc opens local + UNC + remote alike from a bare
 * string (new_path for paths, new_location for scheme-bearing MRLs).
 */
internal fun resolveMrl(uri: String): String = uri

/**
 * True when [mrl] starts with a URI scheme (`http://`, `file://`, `smb://`,
 * …) per RFC 3986's `scheme "://"` shape. Drives the new_location vs
 * new_path choice in [AudioEngineDesktop.play]; a bare Windows drive path
 * like `C:\Music\x.mp3` is deliberately NOT treated as a scheme (the `://`
 * requirement excludes it), so it routes through new_path.
 */
internal fun hasUriScheme(mrl: String): Boolean = URI_SCHEME_REGEX.containsMatchIn(mrl)

private val URI_SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

private const val POLL_INTERVAL_MS = 250L

/**
 * Crossfade volume-ramp tick. ~100 ms ≈ 10 fps — smooth enough to the ear for
 * a multi-second fade while keeping the native `libvlc_audio_set_volume` call
 * rate low (libvlc's volume is integer 0..100, so finer steps wouldn't change
 * the value most ticks anyway). Coarser than Android's 50 ms because libvlc's
 * integer volume has fewer distinguishable levels than ExoPlayer's float.
 */
private const val STEP_MS = 100L

/**
 * Map a `libvlc_state_t` int onto a [PlayerState], or null when the state
 * carries no UI-visible change (NothingSpecial / Opening). Buffering covers
 * both Opening-adjacent buffering and the explicit Buffering state.
 */
internal fun mapState(nativeState: Int): PlayerState? = when (nativeState) {
    LibVlcState.PLAYING -> PlayerState.Playing
    LibVlcState.PAUSED -> PlayerState.Paused
    LibVlcState.OPENING, LibVlcState.BUFFERING -> PlayerState.Buffering
    LibVlcState.STOPPED -> PlayerState.Idle
    LibVlcState.ENDED -> PlayerState.Ended
    LibVlcState.ERROR -> PlayerState.Error("VLC reported a playback error.")
    else -> null
}
