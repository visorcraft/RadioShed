package com.visorcraft.radioshed.audio.visualizer

import kotlin.math.sqrt

/** Decode Android's interleaved FFT bytes into normalized, low-frequency-emphasized bands. */
fun decodeFftBytes(
    bytes: ByteArray,
    output: FloatArray = FloatArray(VISUALIZER_BANDS),
): FloatArray {
    output.fill(0f)
    val pairCount = bytes.size / 2 - 1
    if (pairCount <= 0 || output.isEmpty()) return output
    var peak = 0f
    for (pair in 0 until pairCount) {
        val offset = (pair + 1) * 2
        val real = bytes[offset].toFloat()
        val imaginary = bytes[offset + 1].toFloat()
        val magnitude = sqrt(real * real + imaginary * imaginary)
        val band =
            (sqrt(pair.toFloat() / pairCount) * output.size)
                .toInt()
                .coerceIn(0, output.lastIndex)
        output[band] = maxOf(output[band], magnitude)
        peak = maxOf(peak, magnitude)
    }
    if (peak > 0f) {
        for (index in output.indices) output[index] = (output[index] / peak).coerceIn(0f, 1f)
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
