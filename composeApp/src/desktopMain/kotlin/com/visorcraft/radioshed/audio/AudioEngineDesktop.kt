package com.visorcraft.radioshed.audio

import com.sun.jna.Pointer
import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
                    mapState(nativeState)?.let { _state.value = it }
                    // Auto-advance on the *transition into* Ended, once
                    // per track — matches the old VLCJ finished() behavior.
                    if (nativeState == LibVlcState.ENDED && !advancedForThisTrack) {
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
        // Scheme-bearing MRLs (http://, file://, smb://, …) go through
        // new_location; everything else is a local/UNC path → new_path,
        // which preserves the raw/UNC pass-through resolveMrl() relies on.
        val media = if (hasUriScheme(mrl)) {
            runCatching { loaded.libvlc_media_new_location(inst, mrl) }.getOrNull()
        } else {
            runCatching { loaded.libvlc_media_new_path(inst, mrl) }.getOrNull()
        }
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
        lib?.libvlc_audio_set_volume(p, (clamped * 100f).roundToInt())
    }

    override fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4f)
        val p = player ?: return
        lib?.libvlc_media_player_set_rate(p, clamped)
    }

    /**
     * Release native libvlc handles. Call from the application's
     * `onCloseRequest` so libvlc can flush write buffers and close the
     * audio device cleanly. Without this, exitApplication() terminates
     * the process while libvlc still owns the device, leaving it locked
     * for several seconds for the next app that opens it.
     *
     * Cancels *and joins* the poll loop before releasing, so no native call
     * can race the release: cancel() alone is cooperative (the loop only
     * observes it at the next isActive check), which leaves a window where a
     * poll iteration that already passed isActive calls get_state/get_time on
     * a pointer this method then frees — a native use-after-free the per-call
     * runCatching cannot catch (it crashes the process, not the coroutine).
     * The loop runs on Dispatchers.IO (a different thread than the
     * onCloseRequest caller), so the race is real. Joining bounds the wait to
     * one in-flight native libvlc call. Idempotent: subsequent calls are
     * no-ops because the handles are nulled. Safe to invoke even when libvlc
     * never loaded (all handles are still null).
     */
    fun close() {
        runBlocking { pollJob?.cancelAndJoin() }
        pollJob = null
        val loaded = lib
        runCatching { player?.let { loaded?.libvlc_media_player_release(it) } }
        runCatching { instance?.let { loaded?.libvlc_release(it) } }
        player = null
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
