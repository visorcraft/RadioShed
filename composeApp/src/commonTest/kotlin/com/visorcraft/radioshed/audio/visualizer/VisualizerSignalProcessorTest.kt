package com.visorcraft.radioshed.audio.visualizer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisualizerSignalProcessorTest {
    @Test
    fun processorResamplesToStableRenderSizes() {
        val processor = VisualizerSignalProcessor(smoothing = 0f)
        val frame =
            processor.next(
                spectrum = FloatArray(4) { 1f },
                waveform = floatArrayOf(-1f, 0f, 1f, 0f),
                live = true,
            )

        assertEquals(48, frame.spectrum.size)
        assertEquals(192, frame.waveform.size)
        assertEquals(1f, frame.bass)
        assertEquals(1f, frame.mid)
        assertEquals(1f, frame.treble)
        assertTrue(frame.live)
    }

    @Test
    fun beatPulseDecaysWhenInputGoesQuiet() {
        val processor = VisualizerSignalProcessor(smoothing = 0f)
        val loud = processor.next(FloatArray(48) { 1f }, FloatArray(192), live = true)
        val quiet = processor.next(FloatArray(48), FloatArray(192), live = true)

        assertTrue(loud.beat > 0f)
        assertTrue(quiet.beat < loud.beat)
    }

    @Test
    fun peaksFallAfterTheSignalDrops() {
        val processor = VisualizerSignalProcessor(smoothing = 0f)
        val loud = processor.next(FloatArray(48) { 1f }, FloatArray(192), live = true)
        val quiet = processor.next(FloatArray(48), FloatArray(192), live = true)

        assertEquals(1f, loud.peaks[0])
        assertTrue(quiet.peaks[0] < loud.peaks[0])
    }

    @Test
    fun smoothingAvoidsSuddenFullScaleJumps() {
        val processor = VisualizerSignalProcessor(smoothing = 0.8f)
        processor.next(FloatArray(48), FloatArray(192), live = true)
        val frame = processor.next(FloatArray(48) { 1f }, FloatArray(192), live = true)

        assertTrue(frame.spectrum.all { it > 0f && it < 1f })
    }

    @Test
    fun beatOnsetUsesRawBassBeforeVisualSmoothing() {
        val processor = VisualizerSignalProcessor()
        processor.next(FloatArray(48) { 0.1f }, FloatArray(192), live = true)
        val frame = processor.next(FloatArray(48) { 0.3f }, FloatArray(192), live = true)

        assertTrue(frame.beat > 0.6f)
        assertTrue(frame.bass < 0.2f)
    }
}
