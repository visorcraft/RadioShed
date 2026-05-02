package com.visorcraft.radioshed.stations

import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.PlayerState
import com.visorcraft.radioshed.audio.toPlayable
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.RatingRepository
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.data.nowMillis
import com.visorcraft.radioshed.domain.Station
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _currentStation = MutableStateFlow<Station?>(null)
    val currentStation: StateFlow<Station?> = _currentStation.asStateFlow()

    private val _candidateCount = MutableStateFlow(0)
    val candidateCount: StateFlow<Int> = _candidateCount.asStateFlow()

    private var queue: PlaybackQueue? = null

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

    fun stop() {
        audioEngine.stop()
        _currentStation.value = null
        _candidateCount.value = 0
        queue = null
    }

    private fun advanceToNext() {
        val q = queue ?: return
        val next = q.next() ?: run {
            // Queue exhausted (all dislikes, or empty candidates). Stop.
            audioEngine.stop()
            return
        }
        // Resolve the track's album artwork URI before handing the Playable
        // to the player. The lookup is one indexed primary-key SELECT and
        // takes <1ms; doing it inline (as a launch) keeps advance order
        // stable and avoids a flicker where the player loads then the UI
        // belatedly populates artwork.
        scope.launch {
            val artworkUri = next.albumId?.let { libraryRepo.getAlbumById(it)?.artworkUri }
            audioEngine.play(next.toPlayable(artworkUri = artworkUri))
        }
    }
}
