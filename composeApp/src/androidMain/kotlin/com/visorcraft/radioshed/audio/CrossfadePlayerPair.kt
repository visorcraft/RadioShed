package com.visorcraft.radioshed.audio

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.visorcraft.radioshed.domain.CrossfadeCurve
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/**
 * Owns the primary + secondary [ExoPlayer] used for true (overlapping)
 * crossfade and gapless preload inside [RadioShedMediaSessionService].
 *
 * Why a pair lives in the service and not in `AudioEngineAndroid`: the
 * in-app [AudioEngine] is only a `MediaController` proxy across an IPC
 * boundary; the real ExoPlayer instances live in this service's process.
 * A second player therefore has to be created and driven here, where it can
 * share the audio session and be promoted to the [androidx.media3.session.MediaSession]'s
 * player on swap.
 *
 * Model:
 *  - [active] is whichever ExoPlayer is currently the session player and is
 *    audible at full volume during steady-state playback.
 *  - [standby] is idle (or holding a preloaded up-next item) until a
 *    crossfade promotes it.
 *  - [crossfadeTo] prepares [standby] with the incoming item at volume 0,
 *    starts it, ramps `active → 0` and `standby → 1` along [FadeCurves.gain]
 *    in ~50 ms steps, then swaps the roles and resets the retired player.
 *
 * A single [Mutex] guarantees only one crossfade is in flight; a second
 * request (e.g. the user skips during a fade) cancels the running ramp via
 * coroutine cancellation, and the `finally` block lands both volumes in a
 * sane end state (active = 1, standby reset to 0) so no track is left stuck
 * half-attenuated.
 *
 * All player access is expected to happen on the service's main thread
 * (ExoPlayer requires a single application thread); the suspending ramp runs
 * on the main dispatcher the service launches it from.
 */
class CrossfadePlayerPair(
    private val primary: ExoPlayer,
    private val secondary: ExoPlayer,
    /**
     * Called whenever [active] changes (after a completed crossfade) so the
     * service can re-point the MediaSession at the new active player. The new
     * active ExoPlayer already holds the incoming item, so the session's
     * notification / Auto metadata flips to the incoming track at this point.
     */
    private val onActivePlayerChanged: (ExoPlayer) -> Unit,
) {

    private var primaryIsActive = true

    /** The audible, session-backing player in steady state. */
    val active: ExoPlayer get() = if (primaryIsActive) primary else secondary
    private val standby: ExoPlayer get() = if (primaryIsActive) secondary else primary

    private val crossfadeMutex = Mutex()

    /** The coroutine [kotlinx.coroutines.Job] of the in-flight ramp, captured so
     *  [cancelActiveCrossfade] can interrupt it from outside (a skip / stop). */
    @Volatile
    private var rampJob: kotlinx.coroutines.Job? = null

    /** True while a crossfade ramp is mid-flight; lets the service suppress its
     *  position-poll re-arming so one transition can't spawn two ramps. */
    @Volatile
    var crossfadeInFlight: Boolean = false
        private set

    /**
     * Cancel an in-flight crossfade ramp (a skip / stop arrived). The ramp's
     * `finally` then jump-cuts: incoming → full volume + promoted, outgoing
     * stopped and reset. Safe to call when no crossfade is running.
     */
    fun cancelActiveCrossfade() {
        rampJob?.cancel()
    }

    /**
     * Preload [item] on the standby player so a future [crossfadeTo] (or a
     * gapless promote) starts instantly instead of buffering from cold. Safe
     * to call repeatedly; a no-op when the standby already holds [item].
     * Prepared paused at volume 0 so it makes no sound until promoted.
     */
    fun preloadUpNext(item: MediaItem) {
        if (crossfadeInFlight) return
        val sb = standby
        val alreadyLoaded = sb.currentMediaItem?.mediaId == item.mediaId &&
            sb.mediaItemCount > 0
        if (alreadyLoaded) return
        runCatching {
            sb.volume = 0f
            sb.setMediaItem(item)
            sb.prepare()
            sb.playWhenReady = false
        }.onFailure { Log.e(TAG, "preloadUpNext failed", it) }
    }

    /**
     * Crossfade from [active] to [item] over [durationMs] using [curve].
     *
     * Cancel-safe: if the coroutine is cancelled (a skip arrives mid-fade),
     * the `finally` jump-cuts to the incoming track at full volume and resets
     * the retired player, so we never strand a track at partial volume.
     */
    suspend fun crossfadeTo(item: MediaItem, durationMs: Long, curve: CrossfadeCurve) {
        crossfadeMutex.withLock {
            crossfadeInFlight = true
            rampJob = coroutineContext.job
            val outgoing = active
            val incoming = standby
            try {
                // Prepare incoming at silence unless preloadUpNext already did.
                val preloaded = incoming.currentMediaItem?.mediaId == item.mediaId &&
                    incoming.mediaItemCount > 0
                incoming.volume = 0f
                if (!preloaded) {
                    incoming.setMediaItem(item)
                    incoming.prepare()
                }
                // Carry the outgoing player's playback rate onto the incoming
                // one so a non-1.0 speed survives the swap.
                incoming.playbackParameters = PlaybackParameters(
                    outgoing.playbackParameters.speed.coerceAtLeast(0.01f),
                )
                incoming.playWhenReady = true
                incoming.play()

                val steps = (durationMs / STEP_MS).coerceAtLeast(1L)
                for (i in 1..steps) {
                    val fraction = i.toFloat() / steps.toFloat()
                    outgoing.volume = FadeCurves.gain(curve, fraction, fadeIn = false)
                    incoming.volume = FadeCurves.gain(curve, fraction, fadeIn = true)
                    delay(STEP_MS)
                }
            } finally {
                // Land in a coherent end state whether we finished the ramp or
                // were cancelled: incoming is the new active at full volume,
                // outgoing is stopped/cleared and reset to silence so a later
                // promote of it starts clean.
                incoming.volume = 1f
                runCatching {
                    incoming.playWhenReady = true
                }
                runCatching {
                    outgoing.stop()
                    outgoing.clearMediaItems()
                    outgoing.volume = 1f
                }
                primaryIsActive = !primaryIsActive
                crossfadeInFlight = false
                rampJob = null
                runCatching { onActivePlayerChanged(active) }
            }
        }
    }

    /** Release both players. The service owns lifecycle; call from onDestroy. */
    fun release() {
        runCatching { primary.release() }
        runCatching { secondary.release() }
    }

    private companion object {
        const val TAG = "RadioShedXfade"

        /** Volume-ramp tick. ~50 ms ≈ 20 fps, smooth to the ear while cheap on
         *  the main thread (one setVolume per player per tick). */
        const val STEP_MS = 50L
    }
}
