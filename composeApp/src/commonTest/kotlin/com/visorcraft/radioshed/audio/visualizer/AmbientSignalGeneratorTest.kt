package com.visorcraft.radioshed.audio.visualizer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AmbientSignalGeneratorTest {
    private val generator = AmbientSignalGenerator()

    @Test
    fun ambientFramesAreDeterministicForTrackAndTime() {
        val first = generator.frame(timeMs = 1_234L, trackId = 42L)
        val second = generator.frame(timeMs = 1_234L, trackId = 42L)

        assertTrue(first.spectrum.contentEquals(second.spectrum))
        assertTrue(first.waveform.contentEquals(second.waveform))
        assertEquals(first.bass, second.bass)
        assertFalse(first.live)
    }

    @Test
    fun ambientFrameChangesWithTimeAndTrack() {
        val initial = generator.frame(timeMs = 100L, trackId = 42L)
        val later = generator.frame(timeMs = 200L, trackId = 42L)
        val otherTrack = generator.frame(timeMs = 100L, trackId = 43L)

        assertFalse(initial.spectrum.contentEquals(later.spectrum))
        assertFalse(initial.spectrum.contentEquals(otherTrack.spectrum))
    }

    @Test
    fun ambientStructureBuildsIntoADrop() {
        val quiet = ambientStructureEnvelope(cyclePositionSeconds = 2f)
        val build = ambientStructureEnvelope(cyclePositionSeconds = 24f)
        val drop = ambientStructureEnvelope(cyclePositionSeconds = 27.5f)

        assertTrue(build > quiet)
        assertTrue(drop > build)
    }

    @Test
    fun ambientFrameIsNormalized() {
        val frame = generator.frame(timeMs = 9_999L, trackId = 7L)

        assertEquals(48, frame.spectrum.size)
        assertEquals(192, frame.waveform.size)
        assertTrue(frame.spectrum.all { it in 0f..1f })
        assertTrue(frame.waveform.all { it in -1f..1f })
        assertTrue(frame.bass in 0f..1f)
        assertTrue(frame.mid in 0f..1f)
        assertTrue(frame.treble in 0f..1f)
        assertTrue(frame.beat in 0f..1f)
    }
}
