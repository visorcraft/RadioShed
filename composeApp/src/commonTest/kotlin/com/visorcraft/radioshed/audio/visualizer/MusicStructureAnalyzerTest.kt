package com.visorcraft.radioshed.audio.visualizer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MusicStructureAnalyzerTest {
    @Test
    fun sustainedBuildThenEnergyJumpProducesOneDrop() {
        val analyzer = MusicStructureAnalyzer()
        var structure = MusicStructure.EMPTY
        repeat(90) { index ->
            structure = analyzer.next(frameAt(index * 0.004f + 0.1f), deltaMs = 33L)
        }
        val tension = structure.tension

        val drop = analyzer.next(frameAt(1f), deltaMs = 33L)
        val sustained = analyzer.next(frameAt(1f), deltaMs = 33L)

        assertTrue(tension > 0.5f)
        assertTrue(drop.dropped)
        assertEquals(1f, drop.impact)
        assertFalse(sustained.dropped)
    }

    @Test
    fun ambientArcTriggersMusicalDirectorThroughRealProcessingPipeline() {
        val generator = AmbientSignalGenerator()
        val processor = VisualizerSignalProcessor()
        val analyzer = MusicStructureAnalyzer()
        var drops = 0

        for (timeMs in 0L..30_000L step 33L) {
            val generated = generator.frame(timeMs, trackId = 42L)
            val frame = processor.next(generated.spectrum, generated.waveform, live = false)
            val structure = analyzer.next(frame, deltaMs = 33L)
            if (structure.dropped) drops++
        }

        assertTrue(drops > 0)
    }

    @Test
    fun constantLoudnessAndSilenceDoNotProduceDrops() {
        val loudAnalyzer = MusicStructureAnalyzer()
        val quietAnalyzer = MusicStructureAnalyzer()

        assertFalse(repeatFrames(loudAnalyzer, energy = 0.9f, count = 300))
        assertFalse(repeatFrames(quietAnalyzer, energy = 0f, count = 300))
    }

    @Test
    fun dropImpactDecaysAndSecondDropInsideRefractoryIsSuppressed() {
        val analyzer = MusicStructureAnalyzer()
        buildEnergy(analyzer)
        analyzer.next(frameAt(1f), deltaMs = 33L)

        val decayed = analyzer.next(frameAt(0.8f), deltaMs = 300L)
        buildEnergy(analyzer, start = 0.5f)
        val suppressed = analyzer.next(frameAt(1f), deltaMs = 33L)

        assertTrue(decayed.impact > 0f && decayed.impact < 1f)
        assertFalse(suppressed.dropped)
    }

    private fun buildEnergy(
        analyzer: MusicStructureAnalyzer,
        start: Float = 0.1f,
    ) {
        repeat(90) { index -> analyzer.next(frameAt(start + index * 0.004f), deltaMs = 33L) }
    }

    private fun repeatFrames(
        analyzer: MusicStructureAnalyzer,
        energy: Float,
        count: Int,
    ): Boolean {
        var dropped = false
        repeat(count) { dropped = analyzer.next(frameAt(energy), deltaMs = 33L).dropped || dropped }
        return dropped
    }

    private fun frameAt(energy: Float): VisualizerFrame {
        val value = energy.coerceIn(0f, 1f)
        return VisualizerFrame(
            spectrum = FloatArray(VISUALIZER_BANDS) { value },
            waveform = FloatArray(VISUALIZER_WAVEFORM_POINTS),
            peaks = FloatArray(VISUALIZER_BANDS) { value },
            bass = value,
            mid = value,
            treble = value,
            beat = 0f,
            live = false,
        )
    }
}
