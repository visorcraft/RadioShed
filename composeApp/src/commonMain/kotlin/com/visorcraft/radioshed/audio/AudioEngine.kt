package com.visorcraft.radioshed.audio

import com.visorcraft.radioshed.domain.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * Cross-platform audio playback contract. Android wraps Media3 / ExoPlayer
 * via a [MediaSessionService] + [MediaController] pair so background
 * playback, lock-screen controls, and Bluetooth AVRCP metadata Just Work.
 * iOS implementation lands when Mac access is available — for now it's a
 * no-op stub.
 *
 * All transport methods are fire-and-forget. State is observed via the
 * three flows below. Implementations dispatch suspending DB or framework
 * work to the appropriate dispatcher internally.
 */
interface AudioEngine {

    /** Current playback state — Idle / Buffering / Playing / Paused / Ended / Error. */
    val state: StateFlow<PlayerState>

    /** Currently loaded track, or null when nothing is queued. */
    val nowPlaying: StateFlow<Playable?>

    /** Playback position; both fields update at ~2 Hz while playing. */
    val position: StateFlow<Position>

    /**
     * Current player volume as a 0..1 fraction. Used by features that
     * need to read the level (sleep-timer fade) before they apply their
     * own attenuation, so they can restore it when their work is done.
     * Defaults to 1.0 until the first [setVolume] call.
     */
    val volume: StateFlow<Float>

    fun play(playable: Playable)

    /** Replace the queue with [items] and start at [startIndex]. */
    fun playQueue(items: List<Playable>, startIndex: Int = 0)

    fun pause()
    fun resume()
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun skipNext()
    fun skipPrevious()
    fun stop()

    /**
     * Set the player volume as a 0.0..1.0 fraction. Used by the
     * ReplayGain pipeline to attenuate loud tracks down toward a
     * common reference level so loudness matches across the queue.
     *
     * Out-of-range values are clamped. Calling with 1.0 returns to
     * unattenuated output.
     */
    fun setVolume(fraction: Float)

    /**
     * Playback rate multiplier — 1.0 = normal speed, 1.5 = 50% faster,
     * 0.75 = 25% slower. Pitch correction is on (Media3's default for
     * `setPlaybackParameters`), so vocals stay listenable at non-1.0
     * speeds. Out-of-range values are clamped to a safe band by the
     * implementation; common UI targets are 0.75 / 1.0 / 1.25 / 1.5 /
     * 2.0. iOS stub is a no-op for now.
     */
    fun setPlaybackSpeed(speed: Float)
}

/**
 * Minimal info needed to play a track. Constructed from a domain [Track]
 * via [toPlayable] and used to build a Media3 `MediaItem` on Android (and
 * an `AVAudioPlayerNode` schedule on iOS, eventually).
 */
data class Playable(
    val trackId: Long,
    val uri: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L,
    val artworkUri: String? = null,
    /** For cue-split tracks: where the playable region starts in the underlying file. */
    val cueOffsetStartMs: Long? = null,
    /** For cue-split tracks: where the playable region ends. */
    val cueOffsetEndMs: Long? = null,
)

sealed interface PlayerState {
    data object Idle : PlayerState
    data object Buffering : PlayerState
    data object Playing : PlayerState
    data object Paused : PlayerState
    data object Ended : PlayerState
    data class Error(val message: String) : PlayerState
}

data class Position(
    val currentMs: Long,
    val durationMs: Long,
) {
    val fraction: Float
        get() = if (durationMs > 0L) (currentMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * Convert a domain [Track] to a [Playable]. Artist/album lookup is the
 * caller's responsibility — Track only stores album_id, not the album/artist
 * names. The MiniPlayer / SearchScreen pass nulls and let the UI fall back
 * to the track title; richer wiring lands when Stations Home arrives.
 */
fun Track.toPlayable(
    artistName: String? = null,
    albumTitle: String? = null,
    artworkUri: String? = null,
): Playable = Playable(
    trackId = id,
    uri = path,
    title = title,
    artist = artistName,
    album = albumTitle,
    durationMs = durationMs,
    artworkUri = artworkUri,
    cueOffsetStartMs = cueOffsetStartMs,
    cueOffsetEndMs = cueOffsetEndMs,
)
