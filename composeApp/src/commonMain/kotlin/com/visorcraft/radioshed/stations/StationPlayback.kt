package com.visorcraft.radioshed.stations

import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.PlayerState
import com.visorcraft.radioshed.audio.toPlayable
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.RatingRepository
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.data.nowMillis
import com.visorcraft.radioshed.domain.PlayOrder
import com.visorcraft.radioshed.domain.PlaybackRule
import com.visorcraft.radioshed.domain.Station
import com.visorcraft.radioshed.domain.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.pow

/**
 * Orchestrates playback for a station: evaluates the source rule into a
 * candidate list, builds a [PlaybackQueue] from the playback rule, kicks
 * off the first track via [AudioEngine.play], and queues the next one
 * automatically when the engine reports `Ended`.
 *
 * Singleton in Koin so all UI surfaces (Home cards, MiniPlayer, future
 * Now Playing) observe the same state.
 *
 * Phase 5 part 1 keeps this minimal — no queue persistence across process
 * death, no station-history recording (Phase 5 part 2 will hook
 * `StationRepository.recordPlay` so the SmartQuery refresher can adapt).
 */
class StationPlayback(
    private val stationRepo: StationRepository,
    private val ratingRepo: RatingRepository,
    private val sourceEvaluator: SourceRuleEvaluator,
    private val audioEngine: AudioEngine,
    private val libraryRepo: LibraryRepository,
    private val settings: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _currentStation = MutableStateFlow<Station?>(null)
    val currentStation: StateFlow<Station?> = _currentStation.asStateFlow()

    private val _candidateCount = MutableStateFlow(0)
    val candidateCount: StateFlow<Int> = _candidateCount.asStateFlow()

    private var queue: PlaybackQueue? = null

    /** The track currently loaded into the AudioEngine. Used to push to
     *  history when the user advances forward. */
    private var currentTrack: Track? = null

    /** Played-track history for skip-previous. Most recent at the END.
     *  Capped at HISTORY_CAP to avoid unbounded growth on long sessions. */
    private val history: ArrayDeque<Track> = ArrayDeque()

    init {
        // Auto-advance: when the engine reports Ended, ask the queue for the
        // next track. StateFlow already deduplicates emissions, so a plain
        // collect is enough — we'll only see Ended once per real transition.
        scope.launch {
            audioEngine.state.collect { state ->
                if (state == PlayerState.Ended) advanceToNext()
            }
        }
    }

    /** Start (or restart) the given station. Replaces any current queue. */
    suspend fun playStation(stationId: Long) {
        val station = stationRepo.getById(stationId) ?: return
        _currentStation.value = station

        val candidates = sourceEvaluator.evaluate(station.source)
        val disliked = ratingRepo.dislikedTrackIdsForStation(stationId).toSet()
        val newQueue = PlaybackQueue(
            candidates = candidates,
            rule = station.playback,
            dislikedIds = disliked,
        )
        queue = newQueue
        _candidateCount.value = newQueue.candidateCount

        // Switching stations clears history — the previous station's tracks
        // aren't a logical "back" target from the new station's playback.
        history.clear()
        currentTrack = null

        // Bump station stats. Don't await — fire-and-forget keeps UI snappy.
        scope.launch {
            stationRepo.updateStats(
                id = stationId,
                stats = station.stats.copy(
                    playCount = station.stats.playCount + 1,
                    lastPlayedAt = nowMillis(),
                ),
            )
        }

        advanceToNext()
    }

    fun skipNext() {
        advanceToNext()
    }

    /**
     * Ad-hoc playback: play [tracks] sequentially starting at [startIndex].
     * Used by the Library Browser when the user taps a track — the rest of
     * the visible list becomes the queue, so skip-next walks through it
     * and skip-previous goes back through history. There's no station
     * selected during ad-hoc playback (`currentStation` is null), so the
     * "Continue Listening" callout on Stations Home stays hidden.
     */
    fun playTracks(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        _currentStation.value = null
        history.clear()
        currentTrack = null
        val newQueue = PlaybackQueue(
            candidates = tracks,
            rule = PlaybackRule(order = PlayOrder.Sequential),
            initialSequentialCursor = startIndex.coerceIn(0, tracks.size - 1),
        )
        queue = newQueue
        _candidateCount.value = newQueue.candidateCount
        advanceToNext()
    }

    /**
     * Spotify-style skip-previous:
     *  - If we're more than [PREVIOUS_RESTART_THRESHOLD_MS] into the
     *    current track, restart it (rewind semantic).
     *  - Otherwise, pop the most recent track from history and play it.
     *  - If history is empty, restart the current track.
     *
     * Skipping previous does NOT push the current track back onto history
     * — going back-and-forth-and-back-and-forth would otherwise loop. The
     * track popped from history simply replaces the current one.
     */
    fun skipPrevious() {
        val positionMs = audioEngine.position.value.currentMs
        if (positionMs > PREVIOUS_RESTART_THRESHOLD_MS) {
            audioEngine.seekTo(0L)
            return
        }
        val prev = history.removeLastOrNull()
        if (prev == null) {
            audioEngine.seekTo(0L)
            return
        }
        playTrack(prev, recordHistory = false)
    }

    fun stop() {
        audioEngine.stop()
        _currentStation.value = null
        _candidateCount.value = 0
        queue = null
        history.clear()
        currentTrack = null
    }

    /**
     * Upcoming tracks for the Up Next UI. Sequential queues return an
     * exact projection; shuffle queues return an unordered candidate
     * pool (see [PlaybackQueue.peekUpcoming]).
     */
    fun upcoming(count: Int = 12): List<Track> = queue?.peekUpcoming(count).orEmpty()

    /**
     * Recently-played tracks, oldest first → newest at the END. Used by
     * the Up Next sheet to also show a short "just played" slice above
     * the upcoming list. Skip-previous reads from the same buffer.
     */
    fun recentSnapshot(): List<Track> = history.toList()

    private fun advanceToNext() {
        val q = queue ?: return
        val next = q.next() ?: run {
            // Queue exhausted (all dislikes, or empty candidates). Stop.
            audioEngine.stop()
            return
        }
        playTrack(next, recordHistory = true)
    }

    /**
     * Hand a [Track] to the AudioEngine as a Playable, resolving the
     * album's artwork inline. When [recordHistory] is true, the
     * previously-current track is pushed onto [history] (capped) so
     * skip-previous can navigate back through it.
     */
    private fun playTrack(track: Track, recordHistory: Boolean) {
        if (recordHistory) {
            currentTrack?.let { prev ->
                history.addLast(prev)
                while (history.size > HISTORY_CAP) history.removeFirst()
            }
        }
        currentTrack = track
        // Apply ReplayGain to the player volume BEFORE issuing play(),
        // so the new track starts at the corrected level instead of
        // glitching at full and then snapping down. The track value is
        // preferred over the album value (matches what most players do
        // for shuffle-heavy listening — album values bias toward the
        // album's loudest cut rather than this specific track).
        applyReplayGain(track)
        // Resolve the track's album artwork URI before handing the Playable
        // to the player. The lookup is one indexed primary-key SELECT and
        // takes <1ms; doing it inline (as a launch) keeps advance order
        // stable and avoids a flicker where the player loads then the UI
        // belatedly populates artwork.
        scope.launch {
            val artworkUri = track.albumId?.let { libraryRepo.getAlbumById(it)?.artworkUri }
            audioEngine.play(track.toPlayable(artworkUri = artworkUri))
        }
    }

    /**
     * Convert the track's ReplayGain dB value into a 0..1 volume
     * fraction and push it to the engine. v1 only attenuates (clamped to
     * ≤1.0) — boosting quiet tracks would require a session-wide pre-amp
     * to avoid clipping and is deferred. When ReplayGain is disabled in
     * settings (or the track has no gain value), volume is reset to 1.0
     * so a previous quiet track doesn't bleed into the next one.
     */
    private fun applyReplayGain(track: Track) {
        if (!settings.getReplayGainEnabled()) {
            audioEngine.setVolume(1f)
            return
        }
        val gainDb = track.replayGainTrack ?: track.replayGainAlbum
        if (gainDb == null) {
            audioEngine.setVolume(1f)
            return
        }
        val preamp = settings.getReplayGainPreampDb()
        val raw = 10.0.pow((gainDb + preamp) / 20.0).toFloat()
        audioEngine.setVolume(raw.coerceIn(0f, 1f))
    }

    private companion object {
        /** Spotify / Apple Music industry-standard threshold: within the
         *  first 3 seconds of a track, skip-previous goes to the actual
         *  previous track. Past that, it restarts the current one. */
        const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L

        /** Bounded history; long sessions don't need infinite back-stack. */
        const val HISTORY_CAP = 50
    }
}
