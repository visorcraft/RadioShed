package com.visorcraft.radioshed.ui.player.visualizer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisualizerRenderingTest {
    @Test
    fun waterfallHasSeparateCrestAndFillPathsForEveryRow() {
        assertEquals(WATERFALL_ROWS * 2, VisualizerPathCache().layers.size)
    }

    @Test
    fun circularSpectrumSamplingBlendsLastBandIntoFirst() {
        val spectrum = floatArrayOf(1f, 0f, 0f, 0f)

        assertEquals(0.5f, sampleCircularSpectrum(spectrum, 0.875f))
        assertEquals(1f, sampleCircularSpectrum(spectrum, 0f))
        assertEquals(1f, sampleCircularSpectrum(spectrum, 1f))
    }

    @Test
    fun vuMeterPanelsLeaveCenterGapOnDesktop() {
        val width = 1_280f
        val radius = vuMeterRadius(width = width, height = 800f)

        assertTrue(radius * 2.36f < width * 0.46f)
    }
}
