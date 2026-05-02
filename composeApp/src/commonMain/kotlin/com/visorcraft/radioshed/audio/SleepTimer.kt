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

    /** Schedule a hard pause after [durationMs] of wall-clock time. */
    fun start(durationMs: Long) {
        cancel()
        if (durationMs <= 0L) return
        val started = TimeSource.Monotonic.markNow()
        _state.value = State.Running(remainingMs = durationMs)
        ticker = scope.launch {
            while (isActive) {
                val elapsed = started.elapsedNow().inWholeMilliseconds
                val remaining = durationMs - elapsed
                if (remaining <= 0L) break
                _state.value = State.Running(remainingMs = remaining)
                // 1 Hz tick — finer doesn't help readability and just
                // recomposes the UI more often. The final pause fires
                // when `remaining <= 0` on the next loop iteration.
                delay(TICK_INTERVAL_MS.coerceAtMost(remaining))
            }
            audioEngine.pause()
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
        _state.value = State.Idle
    }

    sealed interface State {
        data object Idle : State
        data class Running(val remainingMs: Long) : State
        data object EndOfTrack : State
    }

    private companion object {
        const val TICK_INTERVAL_MS = 1_000L
    }
}
