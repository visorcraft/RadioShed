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
}
