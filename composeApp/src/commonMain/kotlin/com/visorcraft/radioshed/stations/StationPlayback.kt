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

    /** Wall-clock millis when [currentTrack] started playing. Used to
     *  compute `listened_ms` for the persistent station_history row
     *  written on the next transition. */
    private var trackStartedAtMs: Long? = null

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

        // Persist as the last-played station so the auto-resume-on-launch
        // path in App.kt can find it next time. We do this every time the
        // station starts (not just on app exit) so a kill / crash mid-
        // session still has a valid "last station" pointer.
        settings.setLastStationId(stationId)

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
        // Record the in-flight track first so its listening time isn't
        // lost; THEN clear state. Order matters — once currentStation
        // and currentTrack are nulled, recordPreviousIfAny is a no-op.
        recordPreviousIfAny()
        audioEngine.stop()
        _currentStation.value = null
        _candidateCount.value = 0
        queue = null
        history.clear()
        currentTrack = null
        trackStartedAtMs = null
    }

    /**
     * Persist a row in `station_history` describing the just-ended (or
     * about-to-be-replaced) track session. No-op when there's no track,
     * no station, or no recorded start time. The "completed" flag uses
     * the Last.fm convention of 80% playthrough — anything less is
     * counted as a skip.
     */
    private fun recordPreviousIfAny() {
        val prev = currentTrack ?: return
        val stationId = _currentStation.value?.id ?: return
        val startedAt = trackStartedAtMs ?: return
        val listenedMs = (nowMillis() - startedAt).coerceAtLeast(0L)
        val completed = prev.durationMs > 0L &&
            listenedMs >= (prev.durationMs * COMPLETION_FRACTION).toLong()
        scope.launch {
            runCatching {
                stationRepo.recordPlay(
                    stationId = stationId,
                    trackId = prev.id,
                    playedAt = startedAt,
                    completed = completed,
                    listenedMs = listenedMs,
                )
            }
        }
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
        // Persist the previous track's listening session before swapping
        // it out. We record a row even on skip-previous (recordHistory =
        // false) — the user did listen to those seconds. Only ad-hoc
        // playTracks(...) without a station context skips this since
        // there's no station to attribute the play to.
        recordPreviousIfAny()
        currentTrack = track
        trackStartedAtMs = nowMillis()
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

        /** Last.fm's "completed" threshold: a track played for at least
         *  this fraction of its duration counts as a real listen rather
         *  than a skip. 0.8 (80%) is the canonical value. */
        const val COMPLETION_FRACTION = 0.8
    }
}
