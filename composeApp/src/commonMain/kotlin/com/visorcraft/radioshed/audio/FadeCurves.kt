package com.visorcraft.radioshed.audio

import com.visorcraft.radioshed.domain.CrossfadeCurve
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure gain-math for crossfade curves — the single source of truth for fade
 * shape calculations across all platforms.
 *
 * Callers:
 * - Android `CrossfadePlayerPair`: drives the dual-ExoPlayer volume ramp via
 *   `ExoPlayer.setVolume()` on each tick of the crossfade coroutine.
 * - Desktop `AudioEngineDesktop`: drives the dual-libvlc volume ramp via
 *   `libvlc_audio_set_volume(player, Int 0..100)` stepped at ~100 ms intervals.
 *
 * Why curve math must not live in platform code:
 * Gain curves must be identical on every platform so fade behaviour is
 * predictable regardless of runtime. Centralising the math here also means
 * it can be tested once in `commonTest` against exact invariants (unity power
 * sum, complementarity, monotonicity, smoothstep shape) without spinning up
 * any platform harness.
 */
object FadeCurves {

    /**
     * Returns the gain factor [0, 1] for one side of a crossfade at the given
     * [fraction] through the transition (0 = start, 1 = end).
     *
     * @param curve   The shape of the fade.
     * @param fraction Position in the crossfade, clamped to [0, 1] before use.
     *                 Values outside [0, 1] are clamped gracefully so callers
     *                 need not guard the edges.
     * @param fadeIn  `false` (default) = the outgoing track (starts at 1, falls
     *                to 0); `true` = the incoming track (starts at 0, rises to 1).
     *                For symmetric curves this is equivalent to `gainOut(1 - f)`.
     */
    fun gain(curve: CrossfadeCurve, fraction: Float, fadeIn: Boolean = false): Float {
        val f = fraction.coerceIn(0f, 1f)
        return when (curve) {
            CrossfadeCurve.EqualPower -> if (fadeIn) {
                sin(f * (PI / 2).toFloat())
            } else {
                cos(f * (PI / 2).toFloat())
            }
            CrossfadeCurve.Linear -> if (fadeIn) f else 1f - f
            CrossfadeCurve.SCurve -> {
                // Smoothstep: 3f² - 2f³ gives an S-shaped rise from 0→1
                val s = 3f * f * f - 2f * f * f * f
                if (fadeIn) s else 1f - s
            }
        }
    }
}
