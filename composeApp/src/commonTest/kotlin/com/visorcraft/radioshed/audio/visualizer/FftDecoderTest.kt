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
