package com.visorcraft.radioshed.audio

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.io.File
import kotlin.math.roundToInt

/**
 * VLCJ-backed AudioEngine for the desktop target. Handles the
 * formats the rest of RadioShed handles on Android (MP3 / FLAC /
 * Opus / OGG / ALAC / AAC / WMA) without us shipping decoders —
 * libvlc does the work.
 *
 * libvlc is discovered at first-play via [NativeDiscovery]. If it
 * can't be found, [play] surfaces a [PlayerState.Error] with a
 * message instructing the user to install VLC; the app stays
 * functional otherwise (browsing, settings, scanning) so a missing
 * libvlc isn't a blocker for setup.
 *
 * Threading: VLCJ delivers events on its own internal threads. We
 * fan them out through StateFlows that Compose collects on the
 * UI thread; StateFlow's value-set is thread-safe so no extra
 * marshalling is needed for the simple state/position updates.
 */
class AudioEngineDesktop : AudioEngine {

    private val scope = CoroutineScope(SupervisorJob() + IODispatcher)

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
    private var factory: MediaPlayerFactory? = null
    private var player: MediaPlayer? = null
    private var libvlcLoadFailed: Boolean = false

    private fun ensurePlayer(): MediaPlayer? {
        player?.let { return it }
        if (libvlcLoadFailed) return null
        // NativeDiscovery probes typical install paths (Program Files,
        // /Applications, /usr/lib, …) and returns false when nothing
        // works. We treat that as a recoverable error rather than a
        // crash.
        val discovered = runCatching { NativeDiscovery().discover() }.getOrDefault(false)
        if (!discovered) {
            libvlcLoadFailed = true
            _state.value = PlayerState.Error(
                "libvlc not found — install VLC media player from videolan.org and relaunch.",
            )
            return null
        }
        val f = runCatching { MediaPlayerFactory() }.getOrElse {
            libvlcLoadFailed = true
            _state.value = PlayerState.Error("Failed to initialise libvlc: ${it.message}")
            return null
        }
        val p = runCatching { f.mediaPlayers().newMediaPlayer() }.getOrElse {
            libvlcLoadFailed = true
            _state.value = PlayerState.Error("Failed to create media player: ${it.message}")
            return null
        }
        p.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) {
                _state.value = PlayerState.Playing
            }
            override fun paused(mediaPlayer: MediaPlayer) {
                _state.value = PlayerState.Paused
            }
            override fun stopped(mediaPlayer: MediaPlayer) {
                _state.value = PlayerState.Idle
            }
            override fun finished(mediaPlayer: MediaPlayer) {
                _state.value = PlayerState.Ended
                // Auto-advance into queue if there's a follow-up.
                scope.launch { skipNext() }
            }
            override fun error(mediaPlayer: MediaPlayer) {
                val attempted = _nowPlaying.value?.uri
                System.err.println("AudioEngineDesktop: error event for uri=$attempted")
                _state.value = PlayerState.Error(
                    "VLC reported playback error for ${attempted?.substringAfterLast('\\') ?: "track"}.",
                )
            }
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                _position.value = Position(
                    currentMs = newTime,
                    durationMs = mediaPlayer.media().info()?.duration() ?: 0L,
                )
            }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                _position.value = _position.value.copy(durationMs = newLength)
            }
        })
        // VLC's volume range is 0..200 (200 = software amplification).
        // We seed at 100 so default volume is unchanged from the OS-level
        // playback level.
        p.audio().setVolume(100)
        factory = f
        player = p
        return p
    }

    private fun resolveMrl(uri: String): String {
        // VLC accepts file:// MRLs, bare local paths, AND raw UNC
        // paths (`\\server\share\file.mp3`). The instinct to convert
        // local paths into a `file://` URL via `File.toURI()` actually
        // breaks UNC: `File("\\\\host\\share\\f").toURI().toString()`
        // produces `file:////host/share/f` (4 slashes), which libvlc
        // parses by stripping the `file://` prefix and then handing
        // the remaining `//host/share/f` to its filesystem demuxer
        // — that ends up as the single-backslash path `\host\share\f`,
        // which Windows networking can't resolve. Just pass the raw
        // path through; VLC opens local + UNC + remote alike from a
        // bare string.
        return uri
    }

    override fun play(playable: Playable) {
        val p = ensurePlayer() ?: return
        _nowPlaying.value = playable
        _state.value = PlayerState.Buffering
        val mrl = resolveMrl(playable.uri)
        // Log to stderr so the gradle :run console captures what we
        // sent libvlc — invaluable when a track refuses to play and
        // the user reports "VLC reported playback error" without any
        // way to know what URI was attempted.
        System.err.println("AudioEngineDesktop: play($mrl)")
        p.media().play(mrl)
    }

    override fun playQueue(items: List<Playable>, startIndex: Int) {
        if (items.isEmpty()) return
        queue = items
        queueIndex = startIndex.coerceIn(0, items.lastIndex)
        play(items[queueIndex])
    }

    override fun pause() {
        player?.controls()?.setPause(true)
    }

    override fun resume() {
        player?.controls()?.play()
    }

    override fun togglePlayPause() {
        val p = player ?: return
        if (_state.value == PlayerState.Playing) p.controls().setPause(true)
        else p.controls().play()
    }

    override fun seekTo(positionMs: Long) {
        player?.controls()?.setTime(positionMs)
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
        player?.controls()?.stop()
        _state.value = PlayerState.Idle
        _position.value = Position(0L, 0L)
    }

    override fun setVolume(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        _volume.value = clamped
        // Map 0..1 → 0..100 (VLC's 1.0-equivalent reference level).
        // Above 100 is software gain we explicitly avoid.
        player?.audio()?.setVolume((clamped * 100f).roundToInt())
    }

    override fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4f)
        player?.controls()?.setRate(clamped)
    }

    /**
     * Release native libvlc handles. Call from the application's
     * `onCloseRequest` so libvlc can flush write buffers and close
     * the audio device cleanly. Without this, exitApplication() is
     * called while libvlc still owns the OS audio device, leaving
     * it locked for several seconds after RadioShed exits — an
     * audible regression for the next app to open the same device.
     *
     * Idempotent: subsequent calls are no-ops because both handles
     * are nulled. Safe to invoke even when libvlc never loaded
     * (factory + player are still null).
     */
    fun close() {
        runCatching { player?.release() }
        runCatching { factory?.release() }
        player = null
        factory = null
    }
}
