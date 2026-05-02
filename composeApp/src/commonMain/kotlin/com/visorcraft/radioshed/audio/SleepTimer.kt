package com.visorcraft.radioshed.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * Pauses playback after a fixed duration or at the end of the current
 * track. Singleton in Koin so the toggle in NowPlayingScreen, an
 * eventual quick-tile, and Auto's overflow can all observe the same
 * state.
 *
 * Why pause and not stop: the user almost always wants to wake up at
 * the same spot in the queue. Pausing keeps `nowPlaying` populated and
 * the queue intact; resuming is one tap away.
 *
 * Why a separate class instead of inlining into AudioEngine: the sleep
 * timer is fundamentally policy (when do we stop?), while AudioEngine
 * is mechanism (how do we play?). Mixing them would couple the two
 * platform impls (Android Media3, iOS AVAudioEngine) to a feature
 * neither needs to know about.
 */
class SleepTimer(private val audioEngine: AudioEngine) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Single timer job at a time — cancelled & replaced when the user retargets. */
    private var ticker: Job? = null
    private var endOfTrackWatcher: Job? = null

    /**
     * The volume captured at the moment the timer was armed. The fade
     * ramp starts from this value (so a track already attenuated by
     * ReplayGain still fades smoothly), and we restore it on cancel or
     * after the final pause so the next resume / next track plays at
     * the level the engine wanted before we touched it.
     */
    private var capturedVolume: Float? = null

    /** Schedule a hard pause after [durationMs] of wall-clock time. */
    fun start(durationMs: Long) {
        cancel()
        if (durationMs <= 0L) return
        val started = TimeSource.Monotonic.markNow()
        // Capture before starting so the fade ramp has the right ceiling
        // even if ReplayGain or another consumer mutates the volume mid-
        // timer. We restore this value on cancel / after final pause.
        val startVolume = audioEngine.volume.value
        capturedVolume = startVolume
        _state.value = State.Running(remainingMs = durationMs)
        ticker = scope.launch {
            while (isActive) {
                val elapsed = started.elapsedNow().inWholeMilliseconds
                val remaining = durationMs - elapsed
                if (remaining <= 0L) break
                _state.value = State.Running(remainingMs = remaining)
                // Fade ramp: linear from startVolume → 0 over the final
                // FADE_DURATION_MS. Outside the fade window, leave volume
                // alone so ReplayGain / pre-amp control the level.
                if (remaining < FADE_DURATION_MS) {
                    val fadeFraction = remaining.toFloat() / FADE_DURATION_MS.toFloat()
                    audioEngine.setVolume((startVolume * fadeFraction).coerceIn(0f, 1f))
                }
                // 1 Hz tick — finer doesn't help readability and just
                // recomposes the UI more often. The final pause fires
                // when `remaining <= 0` on the next loop iteration.
                delay(TICK_INTERVAL_MS.coerceAtMost(remaining))
            }
            audioEngine.pause()
            // Restore so a future resume isn't silent, and the next
            // track's RG re-application starts from a normal baseline.
            audioEngine.setVolume(startVolume)
            capturedVolume = null
            _state.value = State.Idle
        }
    }

    /**
     * Pause the moment the current track finishes. The player emits
     * [PlayerState.Ended] right before StationPlayback's auto-advance
     * kicks in — we observe that and pause before the queue moves on.
     *
     * If nothing is currently playing, this is a no-op (Idle).
     */
    fun startEndOfTrack() {
        cancel()
        _state.value = State.EndOfTrack
        // StateFlow already deduplicates, so no need for distinctUntilChanged
        // (which the compiler warns is a no-op on StateFlow anyway).
        endOfTrackWatcher = audioEngine.state
            .onEach { s ->
                if (s == PlayerState.Ended) {
                    audioEngine.pause()
                    _state.value = State.Idle
                    endOfTrackWatcher?.cancel()
                }
            }
            .launchIn(scope)
    }

    fun cancel() {
        ticker?.cancel()
        ticker = null
        endOfTrackWatcher?.cancel()
        endOfTrackWatcher = null
        // If we'd been mid-fade, snap back to where the user was. Without
        // this, cancelling at remaining=2s would leave them on a
        // 25%-volume track until the next track loads and RG re-applies.
        capturedVolume?.let { audioEngine.setVolume(it) }
        capturedVolume = null
        _state.value = State.Idle
    }

    /**
     * Add [extendByMs] to the currently running timer. No-op when the
     * timer is idle or in EndOfTrack mode (extending an end-of-track
     * trigger doesn't make sense).
     *
     * Implementation: read the remaining time, cancel + restart with
     * `remaining + extendByMs`. The brief gap leaves volume restored
     * (cancel restores capturedVolume) before [start] re-captures —
     * imperceptible since both calls happen synchronously here.
     */
    fun extend(extendByMs: Long) {
        val running = _state.value as? State.Running ?: return
        if (extendByMs <= 0L) return
        val newDuration = running.remainingMs + extendByMs
        start(newDuration)
    }

    sealed interface State {
        data object Idle : State
        data class Running(val remainingMs: Long) : State
        data object EndOfTrack : State
    }

    private companion object {
        const val TICK_INTERVAL_MS = 1_000L

        /** Length of the fade-out window before the final pause. 8s
         *  matches Spotify's sleep-timer behaviour and is short enough
         *  not to feel like a glitch but long enough to register. */
        const val FADE_DURATION_MS = 8_000L
    }
}
