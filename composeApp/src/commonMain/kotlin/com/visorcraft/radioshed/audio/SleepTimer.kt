package com.visorcraft.radioshed.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.visorcraft.radioshed.data.SettingsStore
import kotlin.time.TimeSource

/**
 * Pauses playback after a fixed duration or at the end of the current
 * track. Singleton in Koin so the toggle in NowPlayingScreen, an
 * eventual quick-tile, and Auto's overflow can all observe the same
 * state.
 *
 * Why pause is the default: the user almost always wants to wake up at
 * the same spot in the queue. Pausing keeps `nowPlaying` populated and
 * the queue intact; resuming is one tap away. Power users who want the
 * MediaSession off the lock screen can opt in to "Stop on expire" via
 * Settings → Audio.
 *
 * Why a separate class instead of inlining into AudioEngine: the sleep
 * timer is fundamentally policy (when do we stop?), while AudioEngine
 * is mechanism (how do we play?). Mixing them would couple the two
 * platform impls (Android Media3, iOS AVAudioEngine) to a feature
 * neither needs to know about.
 */
class SleepTimer(
    private val audioEngine: AudioEngine,
    private val settings: SettingsStore,
) {

    // crashGuard so a clock-skew / engine-call exception in the timer
    // coroutine doesn't surface as the OS-default crash dialog.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            com.visorcraft.radioshed.util.crashGuard("SleepTimer"),
    )

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

    /**
     * Real-time tracking of the in-flight timer. The StateFlow's
     * `remainingMs` is sampled at TICK_INTERVAL_MS and lags reality
     * by up to a full second; [extend] needs the precise remaining
     * time so a +15 minute extension produces a 15:00:00 window
     * rather than 15:00:00 ± 1s. Cleared in [cancel] and at natural
     * end of the running coroutine.
     */
    private var startedMark: kotlin.time.TimeSource.Monotonic.ValueTimeMark? = null
    private var currentDurationMs: Long = 0L

    /** Schedule a hard pause after [durationMs] of wall-clock time. */
    fun start(durationMs: Long) {
        cancel()
        if (durationMs <= 0L) return
        val started = TimeSource.Monotonic.markNow()
        startedMark = started
        currentDurationMs = durationMs
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
            if (settings.getSleepStopOnExpire()) audioEngine.stop() else audioEngine.pause()
            // Restore so a future resume isn't silent, and the next
            // track's RG re-application starts from a normal baseline.
            audioEngine.setVolume(startVolume)
            capturedVolume = null
            startedMark = null
            currentDurationMs = 0L
            _state.value = State.Idle
        }
    }

    /**
     * Arm a pause for the moment the current track finishes. Just
     * flips the state to [State.EndOfTrack]; the actual pause is
     * driven by [StationPlayback]'s Ended handler via
     * [consumeEndOfTrackPause]. We can't have two coroutines both
     * watching `audioEngine.state` for `Ended` — whichever one
     * scheduled later runs after the auto-advance has already
     * loaded the next track and the pause lands on the wrong song.
     *
     * If nothing is currently playing, this still arms the timer —
     * the next playback's natural end will trigger it.
     */
    fun startEndOfTrack() {
        cancel()
        _state.value = State.EndOfTrack
    }

    /**
     * Called by [StationPlayback] inside its Ended handler. Returns
     * true if the timer is armed and we should pause instead of
     * advancing. Resets the timer state to Idle on consumption so
     * the next track plays normally.
     */
    fun consumeEndOfTrackPause(): Boolean {
        if (_state.value !is State.EndOfTrack) return false
        _state.value = State.Idle
        return true
    }

    fun cancel() {
        ticker?.cancel()
        ticker = null
        // endOfTrackWatcher removed — StationPlayback now consumes
        // the EndOfTrack state directly. Keeping this null-safe in
        // case migration leaves a stale Job lying around on a hot
        // reload.
        endOfTrackWatcher?.cancel()
        endOfTrackWatcher = null
        // If we'd been mid-fade, snap back to where the user was. Without
        // this, cancelling at remaining=2s would leave them on a
        // 25%-volume track until the next track loads and RG re-applies.
        capturedVolume?.let { audioEngine.setVolume(it) }
        capturedVolume = null
        startedMark = null
        currentDurationMs = 0L
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
        if (_state.value !is State.Running) return
        if (extendByMs <= 0L) return
        // Compute the precise remaining time from the monotonic clock
        // rather than the StateFlow's tick-rate sample (which lags by
        // up to TICK_INTERVAL_MS = 1s). Without this the new window
        // is consistently off by a small but observable amount.
        val started = startedMark ?: return
        val elapsed = started.elapsedNow().inWholeMilliseconds
        val realRemaining = (currentDurationMs - elapsed).coerceAtLeast(0L)
        start(realRemaining + extendByMs)
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
