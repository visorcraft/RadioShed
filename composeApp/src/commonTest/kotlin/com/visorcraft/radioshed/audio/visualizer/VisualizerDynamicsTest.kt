package com.visorcraft.radioshed.audio.visualizer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisualizerDynamicsTest {
    @Test
    fun vuNeedleAttacksQuicklyAndReleasesSmoothly() {
        val needle = VuNeedle()
        val attack = needle.next(1f)
        val release = needle.next(0f)

        assertTrue(attack in 0f..1f)
        assertTrue(release < attack)
        assertTrue(release > 0f)
    }

    @Test
    fun waterfallHistorySaturatesAtFullDepth() {
        val history = SpectralHistory()
        repeat(80) { value -> history.add(floatArrayOf(value / 100f)) }

        assertEquals(0.79f, history.valueAtNewestFirst(0, 0))
        assertEquals(0.72f, history.valueAtNewestFirst(7, 0))
        assertEquals(0.09f, history.valueAtNewestFirst(70, 0))
        assertEquals(0.08f, history.valueAtNewestFirst(71, 0))
        assertEquals(0f, history.valueAtNewestFirst(72, 0))
    }

    @Test
    fun waterfallHistoryKeepsNewestFramesFirst() {
        val history = SpectralHistory(depth = 2)
        history.add(floatArrayOf(0.25f, 0.5f))
        history.add(floatArrayOf(0.75f, 1f))
        history.add(floatArrayOf(1f, 0.25f))

        assertEquals(1f, history.valueAtNewestFirst(0, 0))
        assertEquals(0.75f, history.valueAtNewestFirst(1, 0))
        assertEquals(0f, history.valueAtNewestFirst(2, 0))
    }

    @Test
    fun impactRingsTriggerOncePerBeatOnset() {
        val rings = ImpactRingPool()
        rings.update(timeMs = 0L, beat = 0.2f)
        rings.update(timeMs = 33L, beat = 0.9f)
        rings.update(timeMs = 66L, beat = 0.91f)

        var count = 0
        rings.forEachActive(timeMs = 500L) { _, _ -> count++ }

        assertEquals(1, count)
    }

    @Test
    fun impactRingsIgnoreRetriggersInsideRefractoryWindow() {
        val rings = ImpactRingPool()
        rings.update(timeMs = 100L, beat = 0.9f)
        rings.update(timeMs = 150L, beat = 0.2f)
        rings.update(timeMs = 200L, beat = 0.9f)

        var count = 0
        rings.forEachActive(timeMs = 200L) { _, _ -> count++ }

        assertEquals(1, count)
    }

    @Test
    fun impactRingsExpireAfterTheirAnimation() {
        val rings = ImpactRingPool()
        rings.update(timeMs = 0L, beat = 0.9f)
        rings.update(timeMs = 1_600L, beat = 0f)

        var count = 0
        rings.forEachActive(timeMs = 1_600L) { _, _ -> count++ }

        assertEquals(0, count)
    }

    @Test
    fun dropBurstCreatesThreeStaggeredImpactRings() {
        val rings = ImpactRingPool()
        rings.burst(timeMs = 1_000L, strength = 1f)

        var initialCount = 0
        rings.forEachActive(timeMs = 1_000L) { _, _ -> initialCount++ }
        var expandedCount = 0
        rings.forEachActive(timeMs = 1_200L) { _, _ -> expandedCount++ }

        assertEquals(1, initialCount)
        assertEquals(3, expandedCount)
    }

    @Test
    fun waveformHistoryKeepsNewestFramesFirst() {
        val history = WaveformHistory(depth = 2)
        history.add(FloatArray(VISUALIZER_WAVEFORM_POINTS) { 0.25f })
        history.add(FloatArray(VISUALIZER_WAVEFORM_POINTS) { 0.75f })
        history.add(FloatArray(VISUALIZER_WAVEFORM_POINTS) { 1f })

        assertEquals(1f, history.valueAtNewestFirst(0, 0))
        assertEquals(0.75f, history.valueAtNewestFirst(1, 0))
        assertEquals(0f, history.valueAtNewestFirst(2, 0))
    }

    @Test
    fun visualClockAccumulatesEveryDisplayFrame() {
        var elapsed = 0.0
        repeat(2) {
            elapsed = advanceVisualizerTime(elapsed, deltaMs = 17L, speed = 1f, reduceMotion = false)
        }

        assertEquals(34.0, elapsed)
    }

    @Test
    fun visualClockKeepsFractionalReducedMotionTime() {
        val elapsed = advanceVisualizerTime(0.0, deltaMs = 17L, speed = 1f, reduceMotion = true)

        assertEquals(2.55, elapsed, 0.0001)
    }
}
