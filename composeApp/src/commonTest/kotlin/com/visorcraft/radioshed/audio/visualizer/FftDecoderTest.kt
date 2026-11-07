package com.visorcraft.radioshed.audio.visualizer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FftDecoderTest {
    @Test
    fun fftPeakIsNormalizedIntoStableBandCount() {
        val bytes = ByteArray(128)
        bytes[20] = 100
        bytes[21] = 0

        val bands = decodeFftBytes(bytes)

        assertEquals(48, bands.size)
        assertEquals(1f, bands.maxOrNull())
        assertTrue(bands.all { it in 0f..1f })
    }

    @Test
    fun aModerateBinIsNotForcedToFullScale() {
        val bytes = ByteArray(128)
        bytes[20] = 10

        val bands = decodeFftBytes(bytes)

        val peak = bands.maxOrNull() ?: 0f
        assertTrue(peak > 0f)
        assertTrue(peak < 0.5f)
    }

    @Test
    fun bothMetersTrackABassHeavyMix() {
        val frame =
            VisualizerFrame(
                spectrum = FloatArray(VISUALIZER_BANDS) { index -> if (index < 8) 0.8f else 0.05f },
                waveform = FloatArray(VISUALIZER_WAVEFORM_POINTS) { index -> if (index % 2 == 0) 0.6f else -0.6f },
                peaks = FloatArray(VISUALIZER_BANDS),
                bass = 0.8f,
                mid = 0.05f,
                treble = 0.02f,
                beat = 0.4f,
                live = true,
            )

        val levels = playbackMeterLevels(frame)

        assertEquals(2, levels.size)
        assertTrue(levels[0] > 0.45f)
        assertTrue(levels[1] > 0.45f)
    }

    @Test
    fun emptyAndSilentFftProduceSilence() {
        assertTrue(decodeFftBytes(ByteArray(0)).all { it == 0f })
        assertTrue(decodeFftBytes(ByteArray(128)).all { it == 0f })
    }

    @Test
    fun dcAndNyquistSlotsDoNotCreateBars() {
        val bytes = ByteArray(128)
        bytes[0] = 127
        bytes[1] = 127

        assertTrue(decodeFftBytes(bytes).all { it == 0f })
    }

    @Test
    fun waveformIsCenteredAndResampled() {
        val waveform = decodeWaveformBytes(byteArrayOf(0, 64, 128.toByte(), 192.toByte(), 255.toByte()))

        assertEquals(192, waveform.size)
        assertEquals(-1f, waveform.first())
        assertTrue(waveform.last() > 0.9f)
        assertTrue(waveform.all { it in -1f..1f })
    }
}
