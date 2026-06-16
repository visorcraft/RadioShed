package com.visorcraft.radioshed.stations

import com.visorcraft.radioshed.audio.AudioEngine
import com.visorcraft.radioshed.audio.CROSSFADE_MAX_SEC
import com.visorcraft.radioshed.audio.CrossfadePlan
import com.visorcraft.radioshed.audio.PlayerState
import com.visorcraft.radioshed.audio.TailSilenceAnalyzer
import com.visorcraft.radioshed.audio.effectiveCrossfadeDurationMs
import com.visorcraft.radioshed.audio.resolveCrossfadePlan
import com.visorcraft.radioshed.audio.shouldArmCrossfade
import com.visorcraft.radioshed.audio.toPlayable
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.RatingRepository
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.data.nowMillis
import com.visorcraft.radioshed.domain.Crossfade
import com.visorcraft.radioshed.domain.PlayOrder
import com.visorcraft.radioshed.domain.PlaybackRule
import com.visorcraft.radioshed.domain.Station
import com.visorcraft.radioshed.domain.Track
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val scrobbleSync: com.visorcraft.radioshed.source.ScrobbleSync,
    private val djAnnouncer: DjAnnouncer,
    private val tailSilenceAnalyzer: TailSilenceAnalyzer,
) {
    /**
     * Last error caught by the scope exception handler — exposed so the
     * UI can surface a friendly "Couldn't load station" message instead
     * of the silent failure that an uncaught coroutine exception would
     * otherwise become. Null = no recent failure. Cleared on a
     * successful playStation / playTracks.
     */
    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    fun clearPlaybackError() {
        _playbackError.value = null
    }

    /**
     * Catch-all for unhandled exceptions in any coroutine launched on
     * [scope]. Without this, an unhandled exception in evaluate /
     * advanceToNext / one of the auto-launch lambdas propagates to the
     * JVM's default UncaughtExceptionHandler — which on Android shows
     * the "Keeps stopping" dialog and kills the process. Instead we
     * funnel into a StateFlow that the UI surfaces as a snackbar /
     * error chip, and write a single println so the failure isn't
     * silent in logcat.
     *
     * SupervisorJob alone doesn't help here — it stops a child
     * failure from cancelling siblings, but the failure itself still
     * needs a handler installed on the parent context to avoid
     * uncaught propagation.
     */
    private val crashGuard =
        CoroutineExceptionHandler { _, throwable ->
            // CancellationException is the normal control-flow signal when
            // [stationLoadJob.cancel] interrupts a rapid station re-tap, or
            // when [scope] tears down at shutdown. Surfacing it as a user-
            // visible "Playback error" would flash a red banner during
            // perfectly healthy station switching — re-throw so the
            // standard cancellation path stays silent.
            if (throwable is kotlinx.coroutines.CancellationException) throw throwable
            val message = throwable.message ?: throwable::class.simpleName ?: "unknown"
            println("StationPlayback: unhandled exception — $message")
            throwable.printStackTrace()
            _playbackError.value = "Playback error: $message"
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashGuard)

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

    /**
     * Station id the DJ announcer last saw a track start under. Lets the
     * [playTrack] DJ hook tell a same-station advance from a station switch
     * (the station-id slot vs the up-next slot) without diffing flows. Null
     * until the first DJ-eligible track starts.
     */
    @Volatile private var lastDjStationId: Long? = null

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
     * Finite-queue position published as (currentIndex1Based, totalSize)
     * when [playTracks] is the active play surface (no station). Null
     * when in station-driven mode (the station is conceptually
     * infinite, so a "Track N of M" label is meaningless). The Now
     * Playing screen reads this flow to render a small "Track 4 of
     * 12" chip beneath the title.
     */
    private val _finiteQueueProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val finiteQueueProgress: StateFlow<Pair<Int, Int>?> =
        _finiteQueueProgress.asStateFlow()

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
        _repeatMode.value =
            when (_repeatMode.value) {
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
    val shuffleSession: StateFlow<Boolean> =
        run {
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
                val target =
                    if (currentEffective == PlayOrder.Sequential) {
                        PlayOrder.SmartShuffle
                    } else {
                        PlayOrder.Sequential
                    }
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
        val ruleCandidates =
            runCatching {
                sourceEvaluator.evaluate(station.source)
            }.getOrDefault(emptyList())
        val pinnedTracks = stationRepo.pinnedTracks(station.id)
        val candidates =
            if (pinnedTracks.isEmpty()) {
                ruleCandidates
            } else {
                val ids = ruleCandidates.mapTo(mutableSetOf()) { it.id }
                ruleCandidates + pinnedTracks.filter { it.id !in ids }
            }
        val disliked = ratingRepo.dislikedTrackIdsForStation(station.id).toSet()
        val candidateIds = candidates.map { it.id }
        val artistMap = libraryRepo.primaryArtistMap(candidateIds)
        val effectivePlayback = effectivePlaybackFor(station)
        val needsPlayCount =
            effectivePlayback.weighting ==
                com.visorcraft.radioshed.domain.Weighting.FavorUnderPlayed
        val needsRatings =
            effectivePlayback.weighting ==
                com.visorcraft.radioshed.domain.Weighting.FavorHigherRated
        val playCountMap =
            if (needsPlayCount) {
                stationRepo.playCountForTracks(candidateIds)
            } else {
                emptyMap()
            }
        val ratingMap =
            if (needsRatings) {
                ratingRepo.ratingsForTracks(candidateIds)
            } else {
                emptyMap()
            }
        queue =
            PlaybackQueue(
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

    /**
     * The finite list a [playTracks] caller seeded the queue with, kept
     * around so we can recompute "Track N of M" when the cursor
     * advances. Null in station mode (the candidate pool there is
     * conceptually infinite). Cleared on station entry / stop().
     */
    private var finiteTracksSnapshot: List<Track>? = null

    /**
     * Position-tracked cursor into [finiteTracksSnapshot]. Distinct
     * from a `indexOfFirst { id == … }` lookup because the same
     * track id can appear multiple times in a finite list (e.g. user
     * tapped "Add to queue" on the same song twice, or a playlist
     * legitimately repeats a track). Maintained sequentially: +1 on
     * normal advance, jumped on skipPrevious via best-effort
     * proximity search. -1 means "track isn't in the snapshot" and
     * the chip hides.
     */
    private var finiteCursor: Int = -1

    /** The track currently loaded into the AudioEngine. Used to push to
     *  history when the user advances forward. */
    private var currentTrack: Track? = null

    /**
     * Resolved crossfade plan for the active station (null = Off / no
     * crossfade → today's gapless / hard-cut path). Set when the station
     * changes. When non-null, the end-fade is suppressed (it would
     * double-attenuate the overlap) and the position poll arms a crossfade
     * into the next track instead.
     */
    @Volatile private var crossfadePlan: CrossfadePlan? = null

    /**
     * Set true once the position poll has triggered the crossfade into the
     * next track, so the same near-end window can't fire it twice. Reset on
     * every fresh track load.
     */
    @Volatile private var crossfadeArmed: Boolean = false

    /** Trailing silence on the outgoing track, when Smart Fades analysis completes. */
    @Volatile private var trailingSilenceMs: Long? = null

    /** In-flight tail analysis; cancelled when the track changes. */
    private var tailSilenceJob: Job? = null

    /**
     * Raised across a crossfade transition so the auto-advance `Ended`
     * collector ignores the OUTGOING track's natural end — the crossfade has
     * already consumed the queue and started the next track, so a second
     * advance here would skip a track. Lowered after a grace window long
     * enough for the outgoing player's Ended to drain.
     */
    @Volatile private var suppressEndedForCrossfade: Boolean = false

    /**
     * The delayed task that lowers [suppressEndedForCrossfade] after the
     * overlap (+ grace) drains. Tracked so an aborted crossfade (skip, stop,
     * station change) cancels it — a stale timer left running from a
     * cancelled crossfade would otherwise lower the latch a LATER crossfade
     * has raised, letting the outgoing track's Ended double-advance on top
     * of that in-flight crossfade.
     */
    private var crossfadeGraceJob: Job? = null

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
                if (state == PlayerState.Ended && !suppressAutoAdvance &&
                    !suppressEndedForCrossfade
                ) {
                    // Snapshot which track this Ended belongs to BEFORE taking
                    // the mutex: the crossfade grace watchdog (see
                    // maybeStartCrossfade) can win the race and advance first,
                    // in which case re-advancing here would skip a track.
                    val endedTrackId = currentTrack?.id
                    advanceMutex.withLock {
                        if (currentTrack?.id == endedTrackId) {
                            advanceAfterEndedLocked()
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
        // This is the single-player end-fade (SettingsStore.getEndFadeSec),
        // distinct from the per-station crossfade in PlaybackRule.crossfade.
        // End-fade only softens the cut; crossfade overlaps tracks via the
        // dual-player arm-and-fade path below. End-fade runs only when no
        // crossfade plan is active (see the plan != null guard below).
        scope.launch {
            audioEngine.position.collect { pos ->
                val track = currentTrack ?: return@collect
                if (pos.durationMs <= 0L) return@collect
                val remainingMs = pos.durationMs - pos.currentMs

                // Active crossfade takes precedence over the single-player
                // end-fade — running both would double-attenuate the overlap.
                // When armed and we're inside the crossfade window, advance
                // into the next track with an audible overlap instead of
                // letting the track hard-end.
                val plan = crossfadePlan
                if (plan != null) {
                    // Don't re-arm while a crossfade is already in flight: the
                    // position flow still reports the OUTGOING track (the
                    // session swaps only when the ramp completes), so without
                    // this guard the next poll tick would falsely see the old
                    // track near its end and arm a SECOND crossfade. Both
                    // latches are raised here (before launching the advance)
                    // so the Ended collector also sees the suppression if the
                    // outgoing track ends between this tick and the mutex
                    // acquisition inside maybeStartCrossfade.
                    if (!crossfadeArmed && !suppressEndedForCrossfade) {
                        // Fast-path: skip the expensive upcoming(1) call
                        // (which filters the entire candidate pool in
                        // shuffle modes) when we're nowhere near the
                        // arming window. The max arming window is the
                        // longest possible crossfade plus trailing
                        // silence; outside that, shouldArmCrossfade is
                        // guaranteed to return false so there's nothing
                        // to do.
                        val maxArmWindowMs =
                            (if (plan.smart) CROSSFADE_MAX_SEC * 1_000L else plan.durationMs) +
                                (trailingSilenceMs ?: 0L)
                        if (remainingMs > maxArmWindowMs) return@collect

                        val nextTrack = upcoming(1).firstOrNull()
                        val fadeMs =
                            effectiveCrossfadeDurationMs(
                                plan = plan,
                                outgoingDurationMs = pos.durationMs,
                                incomingDurationMs = nextTrack?.durationMs ?: 0L,
                                outgoingTrailingSilenceMs = trailingSilenceMs,
                            )
                        if (shouldArmCrossfade(
                                pos.currentMs,
                                pos.durationMs,
                                fadeMs,
                                trailingSilenceMs,
                            )
                        ) {
                            crossfadeArmed = true
                            suppressEndedForCrossfade = true
                            maybeStartCrossfade(plan, fadeMs)
                        }
                    }
                    return@collect
                }

                // No crossfade: the existing single-player end-fade.
                val fadeSec = settings.getEndFadeSec()
                if (fadeSec <= 0) return@collect
                val fadeWindowMs = fadeSec * 1_000L
                if (remainingMs in 1L..fadeWindowMs) {
                    val attenuation = remainingMs.toFloat() / fadeWindowMs.toFloat()
                    applyEffectiveVolumeAttenuated(track, attenuation)
                }
            }
        }
    }

    /**
     * What to do when the engine reports `Ended` and auto-advance is not
     * suppressed. Caller MUST hold [advanceMutex]. Shared between the state
     * collector (the normal path) and the crossfade grace-window watchdog in
     * [maybeStartCrossfade] (the rescue path for an Ended emission that was
     * swallowed while [suppressEndedForCrossfade] was raised — StateFlow
     * dedupes, so a swallowed Ended never re-emits on its own).
     */
    private suspend fun advanceAfterEndedLocked() {
        // Sleep timer (end-of-track mode) — pause the
        // engine here, INSIDE the mutex, BEFORE the
        // queue moves on. Previously SleepTimer had
        // its own coroutine watching for Ended, which
        // raced with this auto-advance: whichever ran
        // first decided whether the pause landed on
        // the intended track or the next one.
        if (sleepTimer.consumeEndOfTrackPause()) {
            if (settings.getSleepStopOnExpire()) {
                audioEngine.stop()
            } else {
                audioEngine.pause()
            }
            return
        }
        if (_repeatMode.value == RepeatMode.One) {
            replayCurrentTrack()
        } else {
            advanceToNext()
        }
    }

    /**
     * Begin a crossfade into the next queued track. Runs the SAME queue
     * bookkeeping as [advanceToNext] (record previous, push history, consume
     * the queue head, update [currentTrack]) so history / station_history /
     * DJ stay correct, then hands the audible overlap to the engine via
     * [AudioEngine.crossfadeTo] instead of a hard [AudioEngine.play].
     *
     * Mutex-guarded so it can't race with skipNext / advanceToNext. If the
     * queue is exhausted we leave the current track to end naturally (the
     * normal [advanceToNext] stop path then runs on Ended).
     */
    private fun maybeStartCrossfade(
        plan: CrossfadePlan,
        resolvedDurationMs: Long = plan.durationMs,
    ) {
        scope.launch {
            advanceMutex.withLock {
                // Re-check under the lock: a skip / stop / station change may
                // have run between the poll arming and acquiring the mutex (any
                // of those calls cancelCrossfadeIfActive, clearing the latches).
                if (!crossfadeArmed || !suppressEndedForCrossfade) return@withLock
                // No track to fade into → abort the crossfade and LOWER the
                // latches so the outgoing track's natural Ended is honoured
                // (RepeatMode.One replays; an exhausted queue stops). Leaving
                // them raised here would suppress that Ended and stall playback.
                val q = queue
                val next =
                    if (q == null || _repeatMode.value == RepeatMode.One) {
                        null
                    } else {
                        q.next(allowWrap = _repeatMode.value == RepeatMode.All)
                    }
                if (next == null) {
                    crossfadeArmed = false
                    suppressEndedForCrossfade = false
                    return@withLock
                }
                republishPriorityCount()
                // Mirror of shouldArmCrossfade's outgoing-side length check,
                // applied to the INCOMING track: a track shorter than twice
                // the fade would reach its own Ended while the suppression
                // latch is still raised, and the swallowed emission (StateFlow
                // dedupes) would stall playback. The queue head is already
                // consumed (peekUpcoming is not a reliable prediction under
                // shuffle), so instead of bailing we clamp the fade to fit —
                // half the incoming track's length, floor 1s. Unknown duration
                // (<= 0, e.g. some streams) keeps the full plan; the grace
                // watchdog below is the backstop for any residual case.
                val effectivePlan =
                    if (
                        next.durationMs > 0L && next.durationMs < resolvedDurationMs * 2L
                    ) {
                        plan.copy(durationMs = (next.durationMs / 2L).coerceAtLeast(1_000L))
                    } else {
                        plan.copy(durationMs = resolvedDurationMs)
                    }
                // suppressEndedForCrossfade is already raised (by the poll); it
                // keeps the OUTGOING track's natural Ended from triggering a
                // second advance on top of this crossfade. Lower it after the
                // overlap (+ grace) drains. The job is tracked so an aborted
                // crossfade (skip / stop / station change) cancels it instead
                // of letting a stale timer lower a LATER crossfade's latch.
                playTrack(next, recordHistory = true, crossfade = effectivePlan)
                crossfadeGraceJob?.cancel()
                crossfadeGraceJob =
                    scope.launch {
                        kotlinx.coroutines.delay(effectivePlan.durationMs + CROSSFADE_ENDED_GRACE_MS)
                        suppressEndedForCrossfade = false
                        // Watchdog: if the engine is sitting at Ended now that the
                        // latch is lowered, the emission was swallowed while it was
                        // raised (incoming track ended inside the window, or the
                        // engine-side crossfade failed and the outgoing track ran
                        // out). StateFlow never re-emits it, so re-dispatch the
                        // advance here or playback stalls forever. The id re-check
                        // inside the lock keeps this from double-advancing when the
                        // normal collector won the race.
                        if (audioEngine.state.value == PlayerState.Ended && !suppressAutoAdvance) {
                            advanceMutex.withLock {
                                if (audioEngine.state.value == PlayerState.Ended &&
                                    !suppressAutoAdvance && !suppressEndedForCrossfade &&
                                    currentTrack?.id == next.id
                                ) {
                                    advanceAfterEndedLocked()
                                }
                            }
                        }
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
    private fun applyEffectiveVolumeAttenuated(
        track: Track,
        attenuation: Float,
    ) {
        val user = _userVolume.value
        val rg =
            if (settings.getReplayGainEnabled()) {
                val gainDb = track.replayGainTrack ?: track.replayGainAlbum
                if (gainDb == null) {
                    1f
                } else {
                    val preamp = settings.getReplayGainPreampDb()
                    10.0.pow((gainDb + preamp) / 20.0).toFloat().coerceIn(0f, 1f)
                }
            } else {
                1f
            }
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
        //
        // Wrap evaluate in runCatching so a single bad row (corrupted
        // source JSON, DB query failure, etc.) yields an empty
        // candidate list and a surfaced error rather than crashing the
        // app. The user sees "Couldn't load tracks" via [_playbackError]
        // and the station is still selectable — they can fix the rule
        // in the editor without restarting.
        val ruleCandidates =
            runCatching {
                sourceEvaluator.evaluate(station.source)
            }.onFailure {
                _playbackError.value = "Couldn't evaluate \"${station.name}\": " +
                    (it.message ?: it::class.simpleName ?: "unknown rule error")
            }.getOrDefault(emptyList())
        val pinned = stationRepo.pinnedTracks(stationId)
        val candidates =
            if (pinned.isEmpty()) {
                ruleCandidates
            } else {
                val ids = ruleCandidates.mapTo(mutableSetOf()) { it.id }
                ruleCandidates + pinned.filter { it.id !in ids }
            }
        // Globally-disliked tracks ("never play this anywhere") and
        // per-station dislikes are unioned and passed to the queue
        // as a single filter set. Globals override pins — the user's
        // explicit "never" beats their old "always include here".
        val globallyDisliked = ratingRepo.globallyDislikedIds()
        val perStation = ratingRepo.dislikedTrackIdsForStation(stationId).toSet()
        val disliked = globallyDisliked + perStation
        // Pre-resolve trackId → primaryArtistId so PlaybackQueue can
        // apply artist-spacing without per-pick DB hits. One indexed
        // batch query for the whole candidate set; cheap on typical
        // 100-track stations.
        val candidateIds = candidates.map { it.id }
        val artistMap = libraryRepo.primaryArtistMap(candidateIds)
        // Pre-resolve play_count + rating maps too, but only when the
        // station's weighting actually needs them. Skipping the SQL
        // for Uniform / FavorRecent stations keeps cold-start cheap.
        val needsPlayCount =
            station.playback.weighting ==
                com.visorcraft.radioshed.domain.Weighting.FavorUnderPlayed
        val needsRatings =
            station.playback.weighting ==
                com.visorcraft.radioshed.domain.Weighting.FavorHigherRated
        val playCountMap =
            if (needsPlayCount) {
                stationRepo.playCountForTracks(candidateIds)
            } else {
                emptyMap()
            }
        val ratingMap =
            if (needsRatings) {
                ratingRepo.ratingsForTracks(candidateIds)
            } else {
                emptyMap()
            }
        val newQueue =
            PlaybackQueue(
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
            // A station switch mid-crossfade must abort the running ramp so it
            // doesn't promote the old station's next track on top of the new
            // station's first track.
            cancelCrossfadeIfActive()

            _currentStation.value = station
            queue = newQueue
            _candidateCount.value = newQueue.candidateCount
            _priorityCountFlow.value = 0
            // Resolve this station's crossfade rule and tell the engine, so its
            // dual-player pipeline (Android) knows whether to preload for an
            // overlap. Null plan reverts to the gapless / end-fade path.
            crossfadePlan = resolveCrossfadePlan(station.playback.crossfade)
            crossfadeArmed = false
            suppressEndedForCrossfade = false
            audioEngine.setCrossfade(station.playback.crossfade)
            // Surface a hint when a station resolves to zero tracks
            // instead of silently no-op'ing. Common causes: rule
            // matches nothing in the user's current library, all
            // candidates have been disliked, source folders have been
            // deleted. Clear the slot on a successful match so a
            // previous failure doesn't linger across station switches.
            _playbackError.value =
                if (newQueue.candidateCount == 0) {
                    "\"${station.name}\" matched 0 tracks. " +
                        "Edit the station's rule or scan more music."
                } else {
                    null
                }
            // Station mode: clear finite-queue progress flow — the
            // station's pool is conceptually unbounded, so "Track N
            // of M" doesn't make sense.
            finiteTracksSnapshot = null
            finiteCursor = -1
            _finiteQueueProgress.value = null

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
                cancelCrossfadeIfActive()
                advanceToNext()
            }
        }
    }

    /**
     * If a crossfade is mid-flight, abort the engine ramp and clear the
     * suppress latch so the upcoming explicit advance (skip / stop) hard-cuts
     * cleanly instead of racing the still-running fade. Caller holds the mutex.
     */
    private fun cancelCrossfadeIfActive() {
        if (suppressEndedForCrossfade) {
            audioEngine.cancelCrossfade()
            suppressEndedForCrossfade = false
            crossfadeArmed = false
            // Kill the pending latch-lowering timer too: left running, it
            // would fire during a LATER crossfade and lower that crossfade's
            // freshly-raised latch, re-exposing the double-advance it guards
            // against.
            crossfadeGraceJob?.cancel()
            crossfadeGraceJob = null
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
     * Reorder the priority buffer: move entry at [from] to [to].
     * Used by the Up Next sheet's drag-to-reorder. Same mutex
     * protection as the other queue mutators so it can't race with
     * auto-advance or skipNext.
     */
    fun moveQueueItem(
        from: Int,
        to: Int,
    ) {
        if (from == to) return
        scope.launch {
            advanceMutex.withLock {
                queue?.movePriority(from, to)
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
    fun playTracks(
        tracks: List<Track>,
        startIndex: Int = 0,
    ) {
        if (tracks.isEmpty()) return
        scope.launch {
            advanceMutex.withLock {
                // Record the previous track's listening session before
                // we tear down the station context so it doesn't get
                // silently dropped on tap-to-play from a list view.
                recordPreviousIfAny()
                cancelCrossfadeIfActive()
                _currentStation.value = null
                history.clear()
                currentTrack = null
                trackStartedAtMs = null
                // Ad-hoc playback (Library tap, playlist) has no station
                // crossfade rule — fall back to gapless butt-joins.
                crossfadePlan = null
                crossfadeArmed = false
                suppressEndedForCrossfade = false
                audioEngine.setCrossfade(Crossfade.Off)
                val newQueue =
                    PlaybackQueue(
                        candidates = tracks,
                        rule = PlaybackRule(order = PlayOrder.Sequential),
                        initialSequentialCursor = startIndex.coerceIn(0, tracks.size - 1),
                    )
                queue = newQueue
                _candidateCount.value = newQueue.candidateCount
                _priorityCountFlow.value = 0
                // Snapshot the finite list so we can compute "Track N
                // of M" against it as the cursor advances. Cleared on
                // station entry so station mode reverts to null.
                finiteTracksSnapshot = tracks
                finiteCursor = startIndex.coerceIn(0, tracks.size - 1)
                _finiteQueueProgress.value = (finiteCursor + 1) to tracks.size
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
                cancelCrossfadeIfActive()
                val prev = history.removeLastOrNull()
                if (prev == null) {
                    // No previous track to go to — seek-to-start is a
                    // graceful fallback. CRUCIAL: clear userSkipPending
                    // before returning. We set it on entry to suppress
                    // a parallel auto-advance from misattributing this
                    // press, but on the empty-history path no actual
                    // skip happens, so leaving the flag set would cause
                    // the *next* naturally-completed track to be
                    // recorded as a skip in the listening stats.
                    userSkipPending = false
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
                cancelCrossfadeIfActive()
                suppressAutoAdvance = true
                audioEngine.stop()
                _currentStation.value = null
                _candidateCount.value = 0
                queue = null
                _priorityCountFlow.value = 0
                finiteTracksSnapshot = null
                finiteCursor = -1
                _finiteQueueProgress.value = null
                history.clear()
                currentTrack = null
                trackStartedAtMs = null
                crossfadePlan = null
                crossfadeArmed = false
                suppressEndedForCrossfade = false
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
        val completed =
            prev.durationMs > 0L &&
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
                // ListenBrainz scrobble — only on completed plays per
                // ListenBrainz / Last.fm convention (>=50% or >=4min,
                // captured here by the COMPLETION_FRACTION threshold
                // already used for skip detection). ScrobbleSync handles
                // the enabled / no-token no-op, the connectivity gate,
                // the live-submit-then-enqueue fallback, and draining
                // the offline backlog — this site only resolves the
                // artist / album metadata it can't see.
                if (completed && settings.getListenBrainzEnabled()) {
                    val artistName =
                        libraryRepo.artistsForTrack(prev.id)
                            .firstOrNull()?.name
                    val albumName =
                        prev.albumId?.let {
                            libraryRepo.getAlbumById(it)?.title
                        }
                    if (!artistName.isNullOrBlank()) {
                        scrobbleSync.submitOrEnqueue(
                            artist = artistName,
                            track = prev.title,
                            album = albumName,
                            listenedAtSeconds = startedAt / 1000L,
                        )
                    }
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
        // allowWrap = Repeat All. Repeat Off / Repeat One both produce
        // allowWrap=false here — One short-circuits in the init block
        // before reaching advanceToNext, so this only fires for Off
        // and All. The previous default (always wrap) made Sequential
        // 1-track ad-hoc plays loop forever even with Repeat = Off;
        // differentiating the two finally honours the user-facing
        // Repeat toggle.
        val allowWrap = _repeatMode.value == RepeatMode.All
        val next =
            q.next(allowWrap = allowWrap) ?: run {
                // Queue exhausted (all dislikes, empty candidates, OR
                // Sequential reached its last track with Repeat = Off).
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
    private fun playTrack(
        track: Track,
        recordHistory: Boolean,
        crossfade: CrossfadePlan? = null,
    ) {
        if (recordHistory) {
            currentTrack?.let { prev ->
                history.addLast(prev)
                while (history.size > HISTORY_CAP) history.removeFirst()
            }
        }
        // Snapshot the just-ended track BEFORE recordPreviousIfAny nulls it
        // out — the DJ "recap"/transition copy may reference it.
        val endedTrack = currentTrack
        // Persist the previous track's listening session before swapping
        // it out. We record a row even on skip-previous (recordHistory =
        // false) — the user did listen to those seconds. Only ad-hoc
        // playTracks(...) without a station context skips this since
        // there's no station to attribute the play to.
        recordPreviousIfAny()
        currentTrack = track
        trackStartedAtMs = nowMillis()
        trailingSilenceMs = null
        tailSilenceJob?.cancel()
        if (crossfadePlan?.smart == true && track.path.isNotBlank()) {
            val trackId = track.id
            val trackPath = track.path
            val durationMs = track.durationMs
            tailSilenceJob =
                scope.launch {
                    val result = tailSilenceAnalyzer.trailingSilenceMs(trackPath, durationMs)
                    if (currentTrack?.id == trackId && currentTrack?.path == trackPath) {
                        trailingSilenceMs = result
                    }
                }
        }
        // DJ announcement hook. Fire-and-forget on [scope] so a slow TTS
        // synthesis or clip never blocks the queue advance; the announcer
        // swallows every failure internally (decorative, never fatal) and
        // self-gates on the user's enable/frequency/quiet-hours settings.
        // The crossfade plan is passed so the announcement waits out the
        // ramp — the duck and the ramp both write the player volume and
        // would fight each other during the overlap.
        maybeAnnounce(endedTrack = endedTrack, startingTrack = track, crossfade = crossfade)
        // Recompute finite-queue progress against the position
        // cursor (NOT a track-id lookup, since the same track can
        // appear multiple times in a list). The cursor advances
        // sequentially in the common case; skipPrevious can jump
        // backwards via the proximity search below. If the new
        // track isn't anywhere in the snapshot the chip hides.
        finiteTracksSnapshot?.let { snapshot ->
            val newCursor = resolveFiniteCursor(snapshot, finiteCursor, track.id)
            finiteCursor = newCursor
            _finiteQueueProgress.value =
                if (newCursor in snapshot.indices) {
                    (newCursor + 1) to snapshot.size
                } else {
                    null
                }
        }
        // A fresh load clears the crossfade-armed latch for the new track.
        // (maybeStartCrossfade set it for the OUTGOING track; the incoming
        // track must be able to arm its own crossfade when it nears its end.)
        crossfadeArmed = false
        // Apply ReplayGain to the player volume BEFORE issuing play(),
        // so the new track starts at the corrected level instead of
        // glitching at full and then snapping down. The track value is
        // preferred over the album value (matches what most players do
        // for shuffle-heavy listening — album values bias toward the
        // album's loudest cut rather than this specific track).
        //
        // Skip it on the crossfade path: the engine's dual-player ramp owns
        // the ExoPlayer volume during the overlap, so we must not also drive
        // the player volume mid-fade (it would fight the ramp). ReplayGain (and
        // user volume) are instead honoured by handing the incoming track's
        // effective volume to crossfadeTo as the ramp's fade-in target (below).
        if (crossfade == null) applyReplayGain(track)
        // Resolve the track's album artwork URI before handing the Playable
        // to the player. The lookup is one indexed primary-key SELECT and
        // takes <1ms; doing it inline (as a launch) keeps advance order
        // stable and avoids a flicker where the player loads then the UI
        // belatedly populates artwork.
        scope.launch {
            val artworkUri = track.albumId?.let { libraryRepo.getAlbumById(it)?.artworkUri }
            val playable = track.toPlayable(artworkUri = artworkUri)
            if (crossfade != null) {
                // Audible overlap into the already-resolved next track. The
                // service runs the dual-ExoPlayer ramp and re-points the
                // MediaSession at the incoming track. We DON'T call
                // applyEffectiveVolume here (that would drive the session
                // volume mid-fade and fight the ramp); instead the incoming
                // track's effective volume (user × ReplayGain) is passed to the
                // ramp as its fade-in target so it lands at the right level. The
                // engine's volume StateFlow re-syncs to that level after the
                // swap (AudioEngineAndroid observes the player's onVolumeChanged).
                audioEngine.crossfadeTo(
                    playable,
                    crossfade.durationMs,
                    crossfade.curve,
                    effectiveVolumeFor(track),
                )
            } else {
                audioEngine.play(playable)
                // Preload the following track so it's ready for a gapless
                // butt-join (crossfade Off) or an instant crossfade. Peek one
                // ahead without consuming the queue.
                upcoming(1).firstOrNull()?.let { up ->
                    val upArt = up.albumId?.let { libraryRepo.getAlbumById(it)?.artworkUri }
                    audioEngine.setUpNext(up.toPlayable(artworkUri = upArt))
                }
            }
        }
    }

    /**
     * Build a [TrackTransition] from what's in scope and hand it to the DJ
     * announcer on [scope]. Resolving artist names + the up-next track is a
     * couple of indexed reads, kept off the advance path by launching — and
     * skipped entirely when the DJ is disabled (the common case), so a
     * DJ-off user pays one boolean settings read per transition, nothing
     * more. The announcer still self-gates (frequency / quiet hours).
     *
     * When the transition is a crossfade, the announcement is deferred until
     * the ramp has drained: the DJ duck and the crossfade ramp both write the
     * player volume on a tick loop, so a duck issued mid-overlap is
     * overwritten by the next ramp tick and the clip plays over un-ducked
     * music. After the wait, bail if the user has already moved on.
     */
    private fun maybeAnnounce(
        endedTrack: Track?,
        startingTrack: Track,
        crossfade: CrossfadePlan? = null,
    ) {
        val station = _currentStation.value
        // Station switch = the track starts under a different station id than
        // the DJ last saw. First-ever start (null) is NOT a switch — it's the
        // initial tune-in, handled by the same up-next/station-id slot logic.
        // Tracked BEFORE the enabled gate so toggling the DJ on mid-session
        // doesn't misread the next transition as a station switch.
        val stationChanged =
            station != null &&
                lastDjStationId != null &&
                lastDjStationId != station.id
        lastDjStationId = station?.id
        if (!settings.getDjEnabled()) return
        scope.launch {
            if (crossfade != null) {
                kotlinx.coroutines.delay(crossfade.durationMs + CROSSFADE_ENDED_GRACE_MS)
                if (currentTrack?.id != startingTrack.id) return@launch
            }
            runCatching {
                val startingArtist =
                    libraryRepo.artistsForTrack(startingTrack.id)
                        .firstOrNull()?.name
                val nextTrack = upcoming(1).firstOrNull()
                val nextArtist =
                    nextTrack?.let {
                        libraryRepo.artistsForTrack(it.id).firstOrNull()?.name
                    }
                val endedArtist =
                    endedTrack?.let {
                        libraryRepo.artistsForTrack(it.id).firstOrNull()?.name
                    }
                djAnnouncer.announceIfDue(
                    TrackTransition(
                        stationName = station?.name,
                        endedTrack = endedTrack?.toPlayable(artistName = endedArtist),
                        startingTrack = startingTrack.toPlayable(artistName = startingArtist),
                        nextTrack = nextTrack?.toPlayable(artistName = nextArtist),
                        stationChanged = stationChanged,
                    ),
                )
                // Pre-cache one queue-step ahead so the NEXT transition's clip
                // is already synthesized when it becomes due. The predicted
                // transition starts on [nextTrack] (the track that will play
                // after the current one) and teases the track after that.
                // stationChanged is false: an intra-station advance is the
                // common case and a mid-queue station switch isn't knowable
                // here. Artist lookups reuse the reads above; the track after
                // next is an approximation off the current upcoming snapshot
                // (the queue may re-order before then, but a stale precache is
                // a harmless cache warm, never a wrong announcement).
                nextTrack?.let { upcomingTrack ->
                    val afterNext = upcoming(2).getOrNull(1)
                    val afterNextArtist =
                        afterNext?.let {
                            libraryRepo.artistsForTrack(it.id).firstOrNull()?.name
                        }
                    djAnnouncer.precacheUpcoming(
                        listOf(
                            TrackTransition(
                                stationName = station?.name,
                                endedTrack = startingTrack.toPlayable(artistName = startingArtist),
                                startingTrack = upcomingTrack.toPlayable(artistName = nextArtist),
                                nextTrack = afterNext?.toPlayable(artistName = afterNextArtist),
                                stationChanged = false,
                            ),
                        ),
                    )
                }
            }.onFailure {
                // The DJ is decorative — swallow real failures — but a
                // cancellation must unwind, not be converted into a normal
                // return (runCatching catches CancellationException too).
                if (it is kotlinx.coroutines.CancellationException) throw it
            }
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
        audioEngine.setVolume(effectiveVolumeFor(track))
    }

    /**
     * The engine volume the non-crossfade path would set for [track]:
     * userVolume × ReplayGain attenuation (clamped to ≤1.0). Single source of
     * truth for both [applyEffectiveVolume] (steady-state load) and the
     * crossfade target handed to [AudioEngine.crossfadeTo] — so a crossfaded
     * transition lands the incoming track at exactly the level a hard cut
     * would have, honouring both user volume and ReplayGain. v1 only
     * attenuates; boosting quiet tracks is deferred (see [applyEffectiveVolume]
     * history).
     */
    private fun effectiveVolumeFor(track: Track): Float {
        val user = _userVolume.value
        if (!settings.getReplayGainEnabled()) return user.coerceIn(0f, 1f)
        val gainDb = track.replayGainTrack ?: track.replayGainAlbum ?: return user.coerceIn(0f, 1f)
        val preamp = settings.getReplayGainPreampDb()
        val rg = 10.0.pow((gainDb + preamp) / 20.0).toFloat().coerceIn(0f, 1f)
        return (user * rg).coerceIn(0f, 1f)
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

        /** Extra slack after a crossfade's nominal duration before we stop
         *  suppressing the outgoing track's Ended — covers buffering jitter
         *  and the service-side ramp completing slightly late. */
        const val CROSSFADE_ENDED_GRACE_MS = 1_500L
    }
}

/**
 * Resolve where the active track sits within a finite playTracks
 * snapshot. Sequential advance is the common case (`prev + 1`); we
 * also handle replay (`prev` unchanged), skipPrevious (search
 * outward from the prior position), and the "track isn't in the
 * snapshot" case (returns -1, telling the caller to hide the chip).
 *
 * Crucially, this is position-based, not id-based — a track id that
 * appears multiple times in the snapshot resolves to whichever
 * occurrence is closest to the previous cursor, so a list that
 * contains "Foo, Bar, Foo" reports "Track 1 / Track 2 / Track 3"
 * correctly as it advances sequentially.
 */
internal fun resolveFiniteCursor(
    snapshot: List<Track>,
    previousCursor: Int,
    newTrackId: Long,
): Int {
    if (snapshot.isEmpty()) return -1
    // Common path: a sequential advance. Check this first so the
    // duplicate-track case ("Foo" at positions 0 and 5) lands on
    // the right index.
    val nextIdx = previousCursor + 1
    if (nextIdx in snapshot.indices && snapshot[nextIdx].id == newTrackId) {
        return nextIdx
    }
    // Replay-current path: same track, same cursor (e.g., user hit
    // skipPrevious within the first second of a track and the queue
    // restarted it).
    if (previousCursor in snapshot.indices &&
        snapshot[previousCursor].id == newTrackId
    ) {
        return previousCursor
    }
    // Outward search from the prior cursor — skipPrevious + history
    // can land us on a track that was earlier in the snapshot.
    // Walk distance 1..max so the closest match wins, which
    // matches the user's mental model when duplicates exist.
    val maxDist = maxOf(previousCursor + 1, snapshot.size - previousCursor)
    for (dist in 1..maxDist) {
        val back = previousCursor - dist
        if (back in snapshot.indices && snapshot[back].id == newTrackId) return back
        val fwd = previousCursor + dist
        if (fwd in snapshot.indices && snapshot[fwd].id == newTrackId) return fwd
    }
    return -1
}
