package com.visorcraft.radioshed.audio.visualizer

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Decode Android's interleaved FFT bytes into low-frequency-emphasized bands.
 * Magnitudes stay on a fixed scale. Dividing every frame by its own peak made
 * a quiet bar and a chorus look equally "full", so the show stopped following
 * the music.
 */
fun decodeFftBytes(
    bytes: ByteArray,
    output: FloatArray = FloatArray(VISUALIZER_BANDS),
): FloatArray {
    output.fill(0f)
    val pairCount = bytes.size / 2 - 1
    if (pairCount <= 0 || output.isEmpty()) return output
    for (pair in 0 until pairCount) {
        val offset = (pair + 1) * 2
        val real = bytes[offset].toFloat()
        val imaginary = bytes[offset + 1].toFloat()
        val magnitude = sqrt(real * real + imaginary * imaginary)
        val band =
            (sqrt(pair.toFloat() / pairCount) * output.size)
                .toInt()
                .coerceIn(0, output.lastIndex)
        output[band] = maxOf(output[band], (magnitude / FFT_REFERENCE).coerceIn(0f, 1f))
    }
    return output
}

/** Decode Android's unsigned, 128-centered waveform bytes into normalized PCM samples. */
fun decodeWaveformBytes(
    bytes: ByteArray,
    output: FloatArray = FloatArray(VISUALIZER_WAVEFORM_POINTS),
): FloatArray {
    if (bytes.isEmpty() || output.isEmpty()) {
        output.fill(0f)
        return output
    }
    for (index in output.indices) {
        val position = index.toFloat() * bytes.size / output.size
        val left = position.toInt().coerceIn(0, bytes.lastIndex)
        val right = (left + 1).coerceAtMost(bytes.lastIndex)
        val fraction = (position - position.toInt()).coerceIn(0f, 1f)
        val first = ((bytes[left].toInt() and 0xFF) - 128) / 128f
        val second = ((bytes[right].toInt() and 0xFF) - 128) / 128f
        output[index] = (first * (1f - fraction) + second * fraction).coerceIn(-1f, 1f)
    }
    return output
}

/**
 * Levels for the two analog meters. Android's visualizer capture is a mono
 * mix, so a real left/right split is not available. Both needles follow the
 * waveform loudness (with a small bass/mid tilt) instead of plotting treble
 * on the right, which sat on the stop during an ordinary full mix.
 */
fun playbackMeterLevels(frame: VisualizerFrame): FloatArray {
    var sum = 0f
    var peak = 0f
    for (sample in frame.waveform) {
        val magnitude = abs(sample)
        sum += magnitude
        if (magnitude > peak) peak = magnitude
    }
    val mean = if (frame.waveform.isEmpty()) 0f else sum / frame.waveform.size
    val waveform = (mean * 2.6f + peak * 0.4f).coerceIn(0f, 1f)
    val spectral = (frame.bass * 0.7f + frame.mid * 0.45f + frame.treble * 0.3f).coerceIn(0f, 1f)
    val loudness = maxOf(waveform, spectral)
    val tilt = ((frame.bass - frame.mid) * 0.1f).coerceIn(-0.1f, 0.1f)
    return floatArrayOf(
        (loudness + tilt).coerceIn(0f, 1f),
        (loudness - tilt).coerceIn(0f, 1f),
    )
}

private const val FFT_REFERENCE = 40f
