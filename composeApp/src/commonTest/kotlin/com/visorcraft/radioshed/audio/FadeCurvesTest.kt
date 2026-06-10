package com.visorcraft.radioshed.audio

import com.visorcraft.radioshed.domain.CrossfadeCurve
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FadeCurvesTest {

    @Test
    fun equalPowerSumsToUnityAtMidpoint() {
        val out = FadeCurves.gain(CrossfadeCurve.EqualPower, fraction = 0.5f)
        val inn = FadeCurves.gain(CrossfadeCurve.EqualPower, fraction = 0.5f, fadeIn = true)
        // equal-power: g_out^2 + g_in^2 == 1 (within float tolerance)
        assertEquals(1f, out * out + inn * inn, absoluteTolerance = 0.001f)
    }

    @Test
    fun equalPowerHoldsUnityAcrossTheWholeFade() {
        for (i in 0..20) {
            val f = i / 20f
            val out = FadeCurves.gain(CrossfadeCurve.EqualPower, f)
            val inn = FadeCurves.gain(CrossfadeCurve.EqualPower, f, fadeIn = true)
            assertEquals(1f, out * out + inn * inn, absoluteTolerance = 0.001f)
        }
    }

    @Test
    fun linearIsComplementary() {
        for (i in 0..20) {
            val f = i / 20f
            val out = FadeCurves.gain(CrossfadeCurve.Linear, f)
            val inn = FadeCurves.gain(CrossfadeCurve.Linear, f, fadeIn = true)
            assertEquals(1f, out + inn, absoluteTolerance = 0.001f)
        }
    }

    @Test
    fun curvesAreMonotonicAndClamped() {
        for (curve in CrossfadeCurve.entries) {
            assertEquals(1f, FadeCurves.gain(curve, -0.5f), absoluteTolerance = 0.0001f)
            assertEquals(0f, FadeCurves.gain(curve, 1.5f), absoluteTolerance = 0.0001f)
            assertEquals(0f, FadeCurves.gain(curve, -0.5f, fadeIn = true), absoluteTolerance = 0.0001f)
            assertEquals(1f, FadeCurves.gain(curve, 1.5f, fadeIn = true), absoluteTolerance = 0.0001f)
            var prev = FadeCurves.gain(curve, 0f)
            for (i in 1..20) {
                val g = FadeCurves.gain(curve, i / 20f)
                assertTrue(g <= prev + 0.0001f, "fade-out must be non-increasing for $curve")
                prev = g
            }
        }
    }

    @Test
    fun fadeInIsMonotonicForAllCurves() {
        // Mirror of the fade-out loop above: every curve's fade-in side must
        // rise (never dip) from 0 → 1 across the sweep, so the incoming track
        // can't briefly duck mid-fade.
        for (curve in CrossfadeCurve.entries) {
            var prev = FadeCurves.gain(curve, 0f, fadeIn = true)
            for (i in 1..20) {
                val g = FadeCurves.gain(curve, i / 20f, fadeIn = true)
                assertTrue(g >= prev - 0.0001f, "fade-in must be non-decreasing for $curve")
                prev = g
            }
        }
    }

    @Test
    fun sCurveFadeInStartsSlowEndsSlow() {
        // Mirror of sCurveStartsSlowEndsSlow for the fade-in direction: the
        // smoothstep rise is shallow at the edges and steepest at the midpoint.
        val early = FadeCurves.gain(CrossfadeCurve.SCurve, 0.05f, fadeIn = true) -
            FadeCurves.gain(CrossfadeCurve.SCurve, 0f, fadeIn = true)
        val mid = FadeCurves.gain(CrossfadeCurve.SCurve, 0.525f, fadeIn = true) -
            FadeCurves.gain(CrossfadeCurve.SCurve, 0.475f, fadeIn = true)
        assertTrue(early < mid, "s-curve fade-in derivative must be smaller near the edges than at the midpoint")
    }

    @Test
    fun sCurveStartsSlowEndsSlow() {
        val early = FadeCurves.gain(CrossfadeCurve.SCurve, 0f) - FadeCurves.gain(CrossfadeCurve.SCurve, 0.05f)
        val mid = FadeCurves.gain(CrossfadeCurve.SCurve, 0.475f) - FadeCurves.gain(CrossfadeCurve.SCurve, 0.525f)
        assertTrue(early < mid, "s-curve derivative must be smaller near the edges than at the midpoint")
    }
}
