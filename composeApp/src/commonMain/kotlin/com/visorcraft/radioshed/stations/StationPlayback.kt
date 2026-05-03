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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val sleepTimer: com.visorcraft.radioshed.audio.SleepTimer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Serializes all queue-mutating operations so the auto-advance
     * collector and explicit skipNext / playStation / playTracks
     * calls can't race. Without this, two concurrent advances would
     * pop two tracks from the queue, double-record station_history,
     * and leave one track flashing past unplayed.
     */
    private val advanceMutex = Mutex()

    /**
     * Suppresses the Ended → advance bridge while we're transitioning
     * between stations. ExoPlayer's setMediaItem path can emit Ended
     * for the prior item just as the new station's first track is
     * being scheduled — without this guard the collector would advance
     * past the new station's first track immediately.
     */
    @Volatile private var suppressAutoAdvance: Boolean = false

    private val _currentStation = MutableStateFlow<Station?>(null)
    val currentStation: StateFlow<Station?> = _currentStation.asStateFlow()

    private val _candidateCount = MutableStateFlow(0)
    val candidateCount: StateFlow<Int> = _candidateCount.asStateFlow()

    /**
     * Live count of user-queued (Play next + Add to queue) entries.
     * Mirrors `queue.priority.size` so the Now Playing topbar can
     * surface a numeric badge over the queue icon without polling.
     * Updated on every mutation that touches the priority buffer.
     */
    private val _priorityCountFlow = MutableStateFlow(0)
    val priorityCountFlow: StateFlow<Int> = _priorityCountFlow.asStateFlow()
    private fun republishPriorityCount() {
        _priorityCountFlow.value = queue?.prioritySnapshot()?.size ?: 0
    }

    /**
     * Repeat mode applied at track-transition time:
     *  - [RepeatMode.Off] / [RepeatMode.All]: natural advance via
     *    [advanceToNext]. The underlying [PlaybackQueue] already
     *    cycles in shuffle / sequential, so "all" matches the
     *    default behaviour.
     *  - [RepeatMode.One]: replay the same track on natural end.
     *    User skip-next still advances normally; only the
     *    auto-advance Ended path replays.
     */
    private val _repeatMode = MutableStateFlow(RepeatMode.Off)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    fun setRepeatMode(mode: RepeatMode) {
        _repeatMode.value = mode
    }

    fun cycleRepeatMode() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.Off -> RepeatMode.All
            RepeatMode.All -> RepeatMode.One
            RepeatMode.One -> RepeatMode.Off
        }
    }

    /**
     * Per-session shuffle override. When non-null, replaces the
     * active station's PlayOrder when the queue is built. Cleared
     * automatically on station change so the override doesn't
     * silently bleed into a different station the user picks next.
     *
     * Toggling [shuffleSession] from the Now Playing UI rebuilds the
     * current queue in place: candidates stay the same, only the
     * pick policy changes.
     */
    private val _shuffleOverride = MutableStateFlow<PlayOrder?>(null)

    /** Live "shuffle is on" flag for the Now Playing UI. Combines
     *  override (if set) with the active station's natural order. */
    val shuffleSession: StateFlow<Boolean> = run {
        kotlinx.coroutines.flow.combine(_currentStation, _shuffleOverride) { station, override ->
            val effective = override ?: station?.playback?.order
            effective != null && effective != PlayOrder.Sequential
        }.stateIn(
            scope = scope,
            started = kotlinx.coroutines.flow.SharingStarted.Eagerly,
            initialValue = false,
        )
    }

    fun toggleShuffle() {
        // Flip between Sequential and SmartShuffle. The station's
        // saved PlayOrder isn't touched — override stays in memory
        // until the user changes station or app restart.
        scope.launch {
            advanceMutex.withLock {
                val station = _currentStation.value ?: return@withLock
                val currentEffective = _shuffleOverride.value ?: station.playback.order
                val target = if (currentEffective == PlayOrder.Sequential) PlayOrder.SmartShuffle
                             else PlayOrder.Sequential
                _shuffleOverride.value = target
                rebuildQueueForCurrentStation()
            }
        }
    }

    /**
     * Rebuild the in-flight PlaybackQueue with the current station's
     * candidates and the (possibly overridden) playback rule.
     * Called when [toggleShuffle] flips the override mid-session.
     * Caller already holds [advanceMutex].
     */
    private suspend fun rebuildQueueForCurrentStation() {
        val station = _currentStation.value ?: return
        val ruleCandidates = sourceEvaluator.evaluate(station.source)
        val pinnedTracks = stationRepo.pinnedTracks(station.id)
        val candidates = if (pinnedTracks.isEmpty()) ruleCandidates
        else {
            val ids = ruleCandidates.mapTo(mutableSetOf()) { it.id }
            ruleCandidates + pinnedTracks.filter { it.id !in ids }
        }
        val disliked = ratingRepo.dislikedTrackIdsForStation(station.id).toSet()
        val candidateIds = candidates.map { it.id }
        val artistMap = libraryRepo.primaryArtistMap(candidateIds)
        val effectivePlayback = effectivePlaybackFor(station)
        val needsPlayCount = effectivePlayback.weighting ==
            com.visorcraft.radioshed.domain.Weighting.FavorUnderPlayed
        val needsRatings = effectivePlayback.weighting ==
            com.visorcraft.radioshed.domain.Weighting.FavorHigherRated
        val playCountMap = if (needsPlayCount) stationRepo.playCountForTracks(candidateIds)
                           else emptyMap()
        val ratingMap = if (needsRatings) ratingRepo.ratingsForTracks(candidateIds)
                        else emptyMap()
        queue = PlaybackQueue(
            candidates = candidates,
            rule = effectivePlayback,
            dislikedIds = disliked,
            artistByTrackId = artistMap,
            playCountByTrackId = playCountMap,
            ratingByTrackId = ratingMap,
        )
        _candidateCount.value = queue?.candidateCount ?: 0
        // Don't re-trigger advanceToNext — the user wanted to keep
        // listening to the current track and only change the
        // upcoming pick policy. The next natural-end / skip will
        // pull from the new queue.
    }

    /** Apply the per-session override on top of a station's saved rule. */
    private fun effectivePlaybackFor(station: Station): PlaybackRule {
        val override = _shuffleOverride.value ?: return station.playback
        return station.playback.copy(order = override)
    }

    private var queue: PlaybackQueue? = null

    /** The track currently loaded into the AudioEngine. Used to push to
     *  history when the user advances forward. */
    private var currentTrack: Track? = null

    /** Wall-clock millis when [currentTrack] started playing. Used to
     *  compute `listened_ms` for the persistent station_history row
     *  written on the next transition. */
    private var trackStartedAtMs: Long? = null

    /**
     * True when the next [recordPreviousIfAny] should additionally
     * insert a skip_log row (because the user hit skip-next /
     * dislike / skip-previous, rather than the track ending
     * naturally). Cleared after each recording call.
     */
    @Volatile private var userSkipPending: Boolean = false

    /** Played-track history for skip-previous. Most recent at the END.
     *  Capped at HISTORY_CAP to avoid unbounded growth on long sessions. */
    private val history: ArrayDeque<Track> = ArrayDeque()

    init {
        // Auto-advance: when the engine reports Ended, ask the queue for the
        // next track. StateFlow already deduplicates emissions, so a plain
        // collect is enough — we'll only see Ended once per real transition.
        // Calls go through advanceMutex so they can't race with explicit
        // skipNext / playStation / playTracks invocations.
        //
        // RepeatMode.One short-circuits to a re-play of the current
        // track instead of advancing. Off/All both fall through to the
        // standard advance — the queue already cycles, so "all" matches
        // default behaviour.
        scope.launch {
            audioEngine.state.collect { state ->
                if (state == PlayerState.Ended && !suppressAutoAdvance) {
                    advanceMutex.withLock {
                        // Sleep timer (end-of-track mode) — pause the
                        // engine here, INSIDE the mutex, BEFORE the
                        // queue moves on. Previously SleepTimer had
                        // its own coroutine watching for Ended, which
                        // raced with this auto-advance: whichever ran
                        // first decided whether the pause landed on
                        // the intended track or the next one.
                        if (sleepTimer.consumeEndOfTrackPause()) {
                            audioEngine.pause()
                            return@withLock
                        }
                        if (_repeatMode.value == RepeatMode.One) {
                            replayCurrentTrack()
                        } else {
                            advanceToNext()
                        }
                    }
                }
            }
        }
        // End-of-track fade-out. Polls position at the engine's
        // ~2 Hz cadence; when remaining time enters the configured
        // fade window, ramps the user-volume multiplier down toward
        // 0 linearly. Restored to the persisted user-volume on the
        // next track-load (applyEffectiveVolume re-reads
        // _userVolume.value, which we don't mutate here).
        //
        // This is NOT true crossfade — there's no overlap with the
        // next track. But it eliminates the most jarring symptom:
        // hard cuts mid-decay. Real crossfade needs dual ExoPlayer
        // and is tracked in SPEC's deferred list.
        scope.launch {
            audioEngine.position.collect { pos ->
                val fadeSec = settings.getEndFadeSec()
                if (fadeSec <= 0) return@collect
                val track = currentTrack ?: return@collect
                if (pos.durationMs <= 0L) return@collect
                val remainingMs = pos.durationMs - pos.currentMs
                val fadeWindowMs = fadeSec * 1_000L
                if (remainingMs in 1L..fadeWindowMs) {
                    val attenuation = remainingMs.toFloat() / fadeWindowMs.toFloat()
                    applyEffectiveVolumeAttenuated(track, attenuation)
                }
            }
        }
    }

    /**
     * Like [applyEffectiveVolume] but with an extra multiplicative
     * attenuation in [0..1]. Used by the end-of-track fade.
     * Doesn't mutate persisted state — the next track-load resets
     * back to the user's chosen volume × ReplayGain.
     */
    private fun applyEffectiveVolumeAttenuated(track: Track, attenuation: Float) {
        val user = _userVolume.value
        val rg = if (settings.getReplayGainEnabled()) {
            val gainDb = track.replayGainTrack ?: track.replayGainAlbum
            if (gainDb == null) 1f
            else {
                val preamp = settings.getReplayGainPreampDb()
                10.0.pow((gainDb + preamp) / 20.0).toFloat().coerceIn(0f, 1f)
            }
        } else 1f
        audioEngine.setVolume((user * rg * attenuation.coerceIn(0f, 1f)).coerceIn(0f, 1f))
    }

    /**
     * Replay [currentTrack] from the start. Skips the recordPrevious
     * logic since the user effectively just listened to the whole
     * track — re-recording would inflate listened_ms erroneously.
     * Falls back to advanceToNext when there's no current track.
     */
    private fun replayCurrentTrack() {
        val track = currentTrack ?: return advanceToNext()
        // Reset the start-time so the re-play counts as a fresh
        // listening session rather than appending to the previous one.
        trackStartedAtMs = nowMillis()
        scope.launch {
            val artworkUri = track.albumId?.let { libraryRepo.getAlbumById(it)?.artworkUri }
            audioEngine.play(track.toPlayable(artworkUri = artworkUri))
        }
    }

    /**
     * In-flight station-load job. Each call to [playStation] cancels
     * the previous one before launching its own pre-build. Without
     * this, two rapid taps on different stations would each pre-build
     * concurrently (both reading source-rule candidates, both pre-
     * resolving the artist map, both reading play counts), serialise
     * at the mutex, and the second tap's queue would clobber the
     * first — but the first's audio engine load would still race
     * against the swap. The audio listener would briefly see the
     * first station's tracks before the second's queue takes over.
     */
    @Volatile private var stationLoadJob: kotlinx.coroutines.Job? = null

    /**
     * Start (or restart) the given station. Replaces any current
     * queue. Fire-and-forget: cancels any previous in-flight station
     * load so a rapid tap on a second station tile doesn't race with
     * the first.
     */
    fun playStation(stationId: Long) {
        stationLoadJob?.cancel()
        stationLoadJob = scope.launch { playStationInternal(stationId) }
    }

    private suspend fun playStationInternal(stationId: Long) {
        val station = stationRepo.getById(stationId) ?: return

        // Reset the per-session shuffle override on every station
        // switch — it's intentional that toggling shuffle on Latest
        // Hits doesn't persist when the user picks Forgotten Gems
        // next. Cleared BEFORE we evaluate the new station's rules.
        _shuffleOverride.value = null

        // Pre-build everything that depends on suspending I/O *before*
        // taking the mutex and mutating shared state. If the coroutine is
        // cancelled mid-build, queue/currentStation stay coherent (still
        // pointing at the previous station, not partial new state).
        // Pinned tracks are unioned in regardless of whether the source
        // rule would otherwise surface them — that's the whole point of
        // pinning ("always include in this station's pool").
        val ruleCandidates = sourceEvaluator.evaluate(station.source)
        val pinned = stationRepo.pinnedTracks(stationId)
        val candidates = if (pinned.isEmpty()) {
            ruleCandidates
        } else {
            val ids = ruleCandidates.mapTo(mutableSetOf()) { it.id }
            ruleCandidates + pinned.filter { it.id !in ids }
        }
        val disliked = ratingRepo.dislikedTrackIdsForStation(stationId).toSet()
        // Pre-resolve trackId → primaryArtistId so PlaybackQueue can
        // apply artist-spacing without per-pick DB hits. One indexed
        // batch query for the whole candidate set; cheap on typical
        // 100-track stations.
        val candidateIds = candidates.map { it.id }
        val artistMap = libraryRepo.primaryArtistMap(candidateIds)
        // Pre-resolve play_count + rating maps too, but only when the
        // station's weighting actually needs them. Skipping the SQL
        // for Uniform / FavorRecent stations keeps cold-start cheap.
        val needsPlayCount = station.playback.weighting ==
            com.visorcraft.radioshed.domain.Weighting.FavorUnderPlayed
        val needsRatings = station.playback.weighting ==
            com.visorcraft.radioshed.domain.Weighting.FavorHigherRated
        val playCountMap = if (needsPlayCount) {
            stationRepo.playCountForTracks(candidateIds)
        } else emptyMap()
        val ratingMap = if (needsRatings) {
            ratingRepo.ratingsForTracks(candidateIds)
        } else emptyMap()
        val newQueue = PlaybackQueue(
            candidates = candidates,
            rule = station.playback,
            dislikedIds = disliked,
            artistByTrackId = artistMap,
            playCountByTrackId = playCountMap,
            ratingByTrackId = ratingMap,
        )

        advanceMutex.withLock {
            // Record the in-flight track BEFORE swapping currentStation —
            // otherwise the listening session gets attributed to the new
            // station the user just switched to.
            recordPreviousIfAny()

            _currentStation.value = station
            queue = newQueue
            _candidateCount.value = newQueue.candidateCount
            _priorityCountFlow.value = 0

            // Switching stations clears history — the previous station's tracks
            // aren't a logical "back" target from the new station's playback.
            history.clear()
            currentTrack = null
            trackStartedAtMs = null

            // Persist as the last-played station so the auto-resume-on-launch
            // path in App.kt can find it next time. We do this every time the
            // station starts (not just on app exit) so a kill / crash mid-
            // session still has a valid "last station" pointer.
            settings.setLastStationId(stationId)

            // Suppress the Ended-bridge for the brief window between
            // setMediaItem swap and the new track's first audio frame.
            suppressAutoAdvance = true

            // Bump play_count atomically (single-statement increment) so
            // two stations starting in rapid succession don't clobber
            // each other's count via read-modify-write race. The delayed
            // un-suppress is launched from inside the lock so a caller-
            // side cancellation between the lock and any code after it
            // can't strand the flag at `true` forever.
            scope.launch {
                stationRepo.bumpStationPlayCount(stationId, nowMillis())
            }
            scheduleClearSuppress()

            advanceToNext()
        }
    }

    /**
     * Schedule the [suppressAutoAdvance] flag back to false after a
     * grace window. Always launched into [scope] from inside the
     * mutex so caller-side cancellation can't leave the flag stuck.
     */
    private fun scheduleClearSuppress() {
        scope.launch {
            kotlinx.coroutines.delay(SUPPRESS_GRACE_MS)
            suppressAutoAdvance = false
        }
    }

    fun skipNext() {
        // Record the in-flight track BEFORE advancing — otherwise a
        // user mash on skip-next could pop several queue items
        // without ever recording the listening sessions for the
        // popped tracks (advanceToNext → playTrack → recordPrevious
        // works for the immediately previous track, but if next
        // returns null we'd skip recording entirely). The userSkip
        // flag tells recordPreviousIfAny to insert a skip_log row in
        // addition to the station_history row when the listening
        // session counts as a skip (didn't reach the completion
        // threshold). Auto-advance via PlayerState.Ended doesn't set
        // the flag — that's a natural end, not a user skip.
        //
        // Set the flag INSIDE the lock so a concurrent
        // PlayerState.Ended path can't observe it before our
        // advanceToNext runs and mis-attribute the previous track.
        scope.launch {
            advanceMutex.withLock {
                userSkipPending = true
                advanceToNext()
            }
        }
    }

    /**
     * Inject [track] to play immediately after the current track. If
     * nothing is queued (no station, no prior playTracks), starts
     * playback with [track] as the head of an ad-hoc one-item queue.
     */
    fun playNext(track: Track) {
        val q = queue
        if (q == null) {
            playTracks(listOf(track), startIndex = 0)
            return
        }
        scope.launch {
            advanceMutex.withLock {
                q.enqueueNext(track)
                // Publish INSIDE the lock so an interleaved
                // advanceToNext can't snatch the priority head
                // between the enqueue and the count publish, which
                // would leak a stale count to the badge.
                republishPriorityCount()
            }
        }
    }

    /**
     * Inject [tracks] in their given order to play right after the
     * current track. Use this for "Play this album next" / "Play this
     * artist next" — a single mutex-protected enqueue preserves order
     * without callers having to know about the underlying LIFO/FIFO
     * mechanics. Falls back to ad-hoc playback when no queue exists.
     */
    fun playNextAll(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val q = queue
        if (q == null) {
            playTracks(tracks, startIndex = 0)
            return
        }
        scope.launch {
            advanceMutex.withLock {
                q.enqueueNextAll(tracks)
                republishPriorityCount()
            }
        }
    }

    /**
     * Append [track] to the end of the in-memory priority buffer. Same
     * empty-queue fallback as [playNext]: no station active means the
     * track just becomes the only thing.
     */
    fun addToQueue(track: Track) {
        val q = queue
        if (q == null) {
            playTracks(listOf(track), startIndex = 0)
            return
        }
        scope.launch {
            advanceMutex.withLock {
                q.enqueueLast(track)
                republishPriorityCount()
            }
        }
    }

    /**
     * Append [tracks] to the priority buffer's tail in order. Single
     * mutex acquisition for the whole batch so the queue can't be
     * advanced mid-iteration.
     */
    fun addAllToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val q = queue
        if (q == null) {
            playTracks(tracks, startIndex = 0)
            return
        }
        scope.launch {
            advanceMutex.withLock {
                q.enqueueLastAll(tracks)
                republishPriorityCount()
            }
        }
    }

    /**
     * Drop everything the user queued via Play next / Add to queue. The
     * underlying station / sequential cursor is untouched — the next
     * track will come from the rule pipeline as usual. Safe to call
     * with no active queue.
     */
    fun clearQueue() {
        scope.launch {
            advanceMutex.withLock {
                queue?.clearPriority()
                republishPriorityCount()
            }
        }
    }

    /**
     * Remove a single entry from the priority buffer at the visible
     * Up-Next index. Bounds-checked inside the queue. Mutex-protected
     * so it can't race with auto-advance.
     */
    fun removeFromQueue(index: Int) {
        scope.launch {
            advanceMutex.withLock {
                queue?.removePriorityAt(index)
                republishPriorityCount()
            }
        }
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
        scope.launch {
            advanceMutex.withLock {
                // Record the previous track's listening session before
                // we tear down the station context so it doesn't get
                // silently dropped on tap-to-play from a list view.
                recordPreviousIfAny()
                _currentStation.value = null
                history.clear()
                currentTrack = null
                trackStartedAtMs = null
                val newQueue = PlaybackQueue(
                    candidates = tracks,
                    rule = PlaybackRule(order = PlayOrder.Sequential),
                    initialSequentialCursor = startIndex.coerceIn(0, tracks.size - 1),
                )
                queue = newQueue
                _candidateCount.value = newQueue.candidateCount
                _priorityCountFlow.value = 0
                suppressAutoAdvance = true
                scheduleClearSuppress()
                advanceToNext()
            }
        }
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
            // Rewind-semantic path is safe outside the mutex — it
            // doesn't touch shared collections, just seeks the engine.
            audioEngine.seekTo(0L)
            return
        }
        // Skipping back IS a skip of whatever's currently playing
        // (the user gave up on it before it ended naturally). Both
        // the userSkipPending flag write AND the history mutation
        // need to be inside the mutex so an interleaved auto-advance
        // can't see a half-applied state — without this guard the
        // auto-advance path could read userSkipPending=true,
        // attribute the previous track's record to a "skip" event,
        // clear the flag, and the actual skipPrevious work would
        // then run unaccounted.
        scope.launch {
            advanceMutex.withLock {
                userSkipPending = true
                val prev = history.removeLastOrNull()
                if (prev == null) {
                    audioEngine.seekTo(0L)
                    return@withLock
                }
                recordPreviousIfAny()
                playTrack(prev, recordHistory = false)
            }
        }
    }

    fun stop() {
        // Pre-clear suppression so the Ended event from audioEngine.stop()
        // doesn't get swallowed if it ever needs to re-trigger.
        scope.launch {
            advanceMutex.withLock {
                // Record the in-flight track first so its listening time isn't
                // lost; THEN clear state. Order matters — once currentStation
                // and currentTrack are nulled, recordPreviousIfAny is a no-op.
                recordPreviousIfAny()
                suppressAutoAdvance = true
                audioEngine.stop()
                _currentStation.value = null
                _candidateCount.value = 0
                queue = null
                _priorityCountFlow.value = 0
                history.clear()
                currentTrack = null
                trackStartedAtMs = null
            }
        }
    }

    /**
     * Persist a row in `station_history` describing the just-ended (or
     * about-to-be-replaced) track session. No-op when there's no track,
     * no station, or no recorded start time. The "completed" flag uses
     * the Last.fm convention of 80% playthrough — anything less is
     * counted as a skip.
     *
     * Idempotent: nulls out trackStartedAtMs immediately so a re-entrant
     * call (e.g. stop() racing with a delayed Ended event) is a no-op
     * the second time around.
     */
    private fun recordPreviousIfAny() {
        val prev = currentTrack ?: return
        val stationId = _currentStation.value?.id ?: return
        val startedAt = trackStartedAtMs ?: return
        // Capture and immediately null so any concurrent caller short-
        // circuits at the trackStartedAtMs == null guard above.
        trackStartedAtMs = null
        // Capture-and-clear the skip flag so concurrent recorders
        // can't double-fire on the same transition.
        val wasUserSkip = userSkipPending
        userSkipPending = false
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
                // Bump the denormalized total_listen_ms column so the
                // Stats screen's "Top stations" subtitle reflects real
                // accumulated time without re-querying station_history.
                stationRepo.bumpStationListenedMs(stationId, listenedMs)
                // Skip-log: only insert when the user explicitly
                // skipped AND the track wasn't otherwise completed.
                // A user mashing skip at 99% should not count as a
                // skip; the completion threshold guards against that.
                if (wasUserSkip && !completed) {
                    ratingRepo.recordSkip(
                        trackId = prev.id,
                        stationId = stationId,
                        positionMs = listenedMs,
                    )
                }
            }
        }
    }

    /**
     * Upcoming tracks for the Up Next UI. Sequential queues return an
     * exact projection; shuffle queues return an unordered candidate
     * pool (see [PlaybackQueue.peekUpcoming]).
     */
    fun upcoming(count: Int = 12): List<Track> = queue?.peekUpcoming(count).orEmpty()

    /** How many of [upcoming] are user-queued (Play next / Add to queue). */
    fun priorityCount(): Int = queue?.prioritySnapshot()?.size ?: 0

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
        // q.next() consumes the priority head when one is queued, so
        // the badge count needs to refresh — without this the UI's
        // queue-button badge stays at the pre-consume count until the
        // user does another playNext/addToQueue/clearQueue op.
        republishPriorityCount()
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
     * Live in-app user volume scalar (0..1). Defaults to the
     * persisted setting on init. Updates flow back to the engine
     * via [applyEffectiveVolume].
     */
    private val _userVolume = MutableStateFlow(settings.getUserVolume())
    val userVolume: StateFlow<Float> = _userVolume.asStateFlow()

    /**
     * Live-update the engine volume without persisting to settings.
     * The slider drag fires this on every frame so the audio
     * responds in real time, but the settings write is deferred to
     * [commitUserVolume] which the UI calls on drag-end. This
     * avoids 30+ SharedPreferences writes per gesture.
     */
    fun previewUserVolume(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _userVolume.value = clamped
        currentTrack?.let { applyEffectiveVolume(it) }
    }

    /**
     * Persist the user volume after a drag gesture ends. Idempotent —
     * safe to call repeatedly with the same value. Replaces the
     * previous always-persist behaviour of [setUserVolume].
     */
    fun commitUserVolume(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _userVolume.value = clamped
        settings.setUserVolume(clamped)
        currentTrack?.let { applyEffectiveVolume(it) }
    }

    /**
     * Backwards-compat wrapper: legacy callers (Bluetooth, notification
     * actions, programmatic adjusts) still use the single-call API.
     * For interactive sliders use [previewUserVolume] +
     * [commitUserVolume] instead so we don't thrash settings.
     */
    fun setUserVolume(value: Float) = commitUserVolume(value)

    /**
     * Compute and push the engine volume = userVolume × replayGain
     * attenuation. v1 only attenuates (clamped to ≤1.0) — boosting
     * quiet tracks would require a session-wide pre-amp to avoid
     * clipping and is deferred. When ReplayGain is disabled in
     * settings (or the track has no gain value), the user volume
     * stands alone.
     */
    private fun applyEffectiveVolume(track: Track) {
        val user = _userVolume.value
        if (!settings.getReplayGainEnabled()) {
            audioEngine.setVolume(user)
            return
        }
        val gainDb = track.replayGainTrack ?: track.replayGainAlbum
        if (gainDb == null) {
            audioEngine.setVolume(user)
            return
        }
        val preamp = settings.getReplayGainPreampDb()
        val rg = 10.0.pow((gainDb + preamp) / 20.0).toFloat().coerceIn(0f, 1f)
        audioEngine.setVolume((user * rg).coerceIn(0f, 1f))
    }

    /** Backwards-compat alias for the call site that used to invoke
     *  the old [applyReplayGain]. New code uses
     *  [applyEffectiveVolume] directly. */
    private fun applyReplayGain(track: Track) = applyEffectiveVolume(track)

    /**
     * Now-Playing repeat behaviour. Order is the cycle order:
     * Off → All → One → Off.
     */
    enum class RepeatMode { Off, All, One }

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

        /** How long [suppressAutoAdvance] stays raised after a station
         *  switch / playTracks / stop. Long enough for ExoPlayer to
         *  emit any spurious Ended from the prior media item; short
         *  enough that a real natural-end fires through promptly. */
        const val SUPPRESS_GRACE_MS = 600L
    }
}
