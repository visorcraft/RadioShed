package com.visorcraft.radioshed.audio

import com.visorcraft.radioshed.domain.Crossfade
import com.visorcraft.radioshed.domain.CrossfadeCurve
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrossfadePlanTest {
    @Test
    fun offResolvesToNull() {
        assertNull(resolveCrossfadePlan(Crossfade.Off))
    }

    @Test
    fun fixedResolvesToSecondsAndCurve() {
        val plan = resolveCrossfadePlan(Crossfade.Fixed(seconds = 4, curve = CrossfadeCurve.SCurve))
        assertEquals(4_000L, plan?.durationMs)
        assertEquals(CrossfadeCurve.SCurve, plan?.curve)
    }

    @Test
    fun fixedZeroOrNegativeResolvesToNull() {
        assertNull(resolveCrossfadePlan(Crossfade.Fixed(seconds = 0)))
        assertNull(resolveCrossfadePlan(Crossfade.Fixed(seconds = -3)))
    }

    @Test
    fun fixedAboveMaxIsClamped() {
        val plan = resolveCrossfadePlan(Crossfade.Fixed(seconds = 99))
        assertEquals(CROSSFADE_MAX_SEC * 1_000L, plan?.durationMs)
    }

    @Test
    fun smartUsesDefaultDurationAndEqualPower() {
        val plan = resolveCrossfadePlan(Crossfade.Smart)
        assertEquals(SMART_CROSSFADE_DEFAULT_SEC * 1_000L, plan?.durationMs)
        assertEquals(CrossfadeCurve.EqualPower, plan?.curve)
        assertTrue(plan?.smart == true)
    }

    @Test
    fun smartFadeDurationScalesWithTrackLength() {
        assertEquals(2_000L, computeSmartFadeDurationMs(20_000L, 25_000L))
        assertEquals(12_000L, computeSmartFadeDurationMs(180_000L, 200_000L))
        assertEquals(
            SMART_CROSSFADE_DEFAULT_SEC * 1_000L,
            computeSmartFadeDurationMs(0L, 200_000L),
        )
    }

    @Test
    fun effectiveDurationUsesSmartWhenFlagged() {
        val plan = CrossfadePlan(durationMs = 6_000L, curve = CrossfadeCurve.EqualPower, smart = true)
        assertEquals(2_000L, effectiveCrossfadeDurationMs(plan, 20_000L, 30_000L))
        val fixed = plan.copy(smart = false)
        assertEquals(6_000L, effectiveCrossfadeDurationMs(fixed, 20_000L, 30_000L))
    }

    @Test
    fun smartFadeShortensWhenTrailingSilenceDetected() {
        val withSilence =
            computeSmartFadeDurationMs(
                outgoingDurationMs = 240_000L,
                incomingDurationMs = 240_000L,
                outgoingTrailingSilenceMs = 8_000L,
            )
        val withoutSilence =
            computeSmartFadeDurationMs(
                outgoingDurationMs = 240_000L,
                incomingDurationMs = 240_000L,
            )
        assertTrue(withSilence <= withoutSilence)
        assertEquals(8_000L, withSilence)
    }

    @Test
    fun trailingSilenceExtendsArmWindow() {
        val dur = 200_000L
        val xfade = 6_000L
        val silence = 4_000L
        assertFalse(
            shouldArmCrossfade(
                positionMs = 188_000L,
                durationMs = dur,
                crossfadeMs = xfade,
                trailingSilenceMs = silence,
            ),
        )
        assertTrue(
            shouldArmCrossfade(
                positionMs = 192_000L,
                durationMs = dur,
                crossfadeMs = xfade,
                trailingSilenceMs = silence,
            ),
        )
    }

    @Test
    fun armsWhenRemainingWithinWindow() {
        // 200s track, 6s fade: arm once we're at or past 194s.
        val dur = 200_000L
        val xfade = 6_000L
        assertFalse(shouldArmCrossfade(positionMs = 180_000L, durationMs = dur, crossfadeMs = xfade))
        assertTrue(shouldArmCrossfade(positionMs = 195_000L, durationMs = dur, crossfadeMs = xfade))
        // Exactly at the window edge (remaining == xfade) arms.
        assertTrue(shouldArmCrossfade(positionMs = 194_000L, durationMs = dur, crossfadeMs = xfade))
    }

    @Test
    fun doesNotArmAtExactEnd() {
        // remaining == 0 must not arm (the IntRange is 1..xfade) — there is no
        // tail left to overlap.
        assertFalse(shouldArmCrossfade(positionMs = 200_000L, durationMs = 200_000L, crossfadeMs = 6_000L))
    }

    @Test
    fun doesNotArmForUnknownDurationOrZeroCrossfade() {
        assertFalse(shouldArmCrossfade(positionMs = 1_000L, durationMs = 0L, crossfadeMs = 6_000L))
        assertFalse(shouldArmCrossfade(positionMs = 1_000L, durationMs = 200_000L, crossfadeMs = 0L))
    }

    @Test
    fun refusesToCrossfadeTracksShorterThanTwiceTheFade() {
        // A 10s track with a 6s fade would fade nearly its whole body — refuse.
        assertFalse(shouldArmCrossfade(positionMs = 5_000L, durationMs = 10_000L, crossfadeMs = 6_000L))
        // A 12s track == 2× fade is the boundary: allowed.
        assertTrue(shouldArmCrossfade(positionMs = 7_000L, durationMs = 12_000L, crossfadeMs = 6_000L))
    }

    // ---- Crossfade volume-scaling contract (PLAN 3.2) ----
    //
    // The Android dual-ExoPlayer ramp is platform code and can't run in
    // commonTest, but the gain × target composition it relies on is pure
    // FadeCurves math. These tests pin that contract so the ramp's invariants
    // (incoming lands at its effective target, outgoing fades from its current
    // level, neither side bypasses user volume / ReplayGain) can't silently
    // regress: the ramp computes incoming = gain(curve, f, fadeIn=true) ×
    // incomingTarget and outgoing = gain(curve, f) × outgoingStart.

    private fun incomingRamp(
        curve: CrossfadeCurve,
        f: Float,
        target: Float,
    ) = FadeCurves.gain(curve, f, fadeIn = true) * target

    private fun outgoingRamp(
        curve: CrossfadeCurve,
        f: Float,
        start: Float,
    ) = FadeCurves.gain(curve, f, fadeIn = false) * start

    @Test
    fun incomingFadeInLandsAtEffectiveTargetNotUnity() {
        // A track attenuated to 0.30 (user volume × ReplayGain) must end the
        // crossfade at 0.30, never 1.0 — the whole point of PLAN 3.2.
        val target = 0.30f
        assertEquals(0f, incomingRamp(CrossfadeCurve.Linear, 0f, target), 1e-4f)
        assertEquals(target, incomingRamp(CrossfadeCurve.Linear, 1f, target), 1e-4f)
        // Midpoint is scaled, never the raw curve value.
        assertEquals(0.5f * target, incomingRamp(CrossfadeCurve.Linear, 0.5f, target), 1e-4f)
    }

    @Test
    fun outgoingFadeOutStartsAtCurrentLevelNoStartJump() {
        // An outgoing track sitting at its attenuated level (0.30) must START
        // the fade AT 0.30 — not snap to 1.0 on the first tick (the sibling
        // bug). At f→0 the fade-out gain is 1.0, so the product is the captured
        // start level.
        val start = 0.30f
        assertEquals(start, outgoingRamp(CrossfadeCurve.Linear, 0f, start), 1e-4f)
        assertEquals(start, outgoingRamp(CrossfadeCurve.EqualPower, 0f, start), 1e-4f)
        assertEquals(start, outgoingRamp(CrossfadeCurve.SCurve, 0f, start), 1e-4f)
        // And it drains to silence at the end.
        assertEquals(0f, outgoingRamp(CrossfadeCurve.Linear, 1f, start), 1e-4f)
    }

    @Test
    fun scalingNeverExceedsTargetCeilings() {
        // Scaled gains stay within [0, level] across the whole sweep — the ramp
        // can't transiently overshoot a user's chosen / ReplayGain-capped level.
        val target = 0.40f
        val start = 0.55f
        for (curve in CrossfadeCurve.entries) {
            var f = 0f
            while (f <= 1f) {
                val incoming = incomingRamp(curve, f, target)
                val outgoing = outgoingRamp(curve, f, start)
                assertTrue(incoming in 0f..target + 1e-4f, "incoming $incoming > $target at f=$f/$curve")
                assertTrue(outgoing in 0f..start + 1e-4f, "outgoing $outgoing > $start at f=$f/$curve")
                f += 0.1f
            }
        }
    }

    @Test
    fun unityTargetReproducesRawCurve() {
        // The default targetVolume = 1f path (iOS/desktop no-op compat, or no
        // attenuation) must be identical to the unscaled curve.
        for (curve in CrossfadeCurve.entries) {
            assertEquals(
                FadeCurves.gain(curve, 0.5f, fadeIn = true),
                incomingRamp(curve, 0.5f, 1f),
                1e-6f,
            )
        }
    }
}
