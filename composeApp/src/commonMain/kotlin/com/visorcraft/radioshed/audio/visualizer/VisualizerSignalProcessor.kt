package com.visorcraft.radioshed.audio.visualizer

/** Resamples platform captures, smooths abrupt changes, and tracks peaks and bass onsets. */
class VisualizerSignalProcessor(
    private val smoothing: Float = 0.72f,
) {
    private val smoothed = FloatArray(VISUALIZER_BANDS)
    private val peaks = FloatArray(VISUALIZER_BANDS)
    private var previousBass = 0f
    private var beat = 0f

    fun next(
        spectrum: FloatArray,
        waveform: FloatArray,
        live: Boolean,
    ): VisualizerFrame {
        var rawBass = 0f
        for (index in 0 until VISUALIZER_BANDS) {
            val input = sample(spectrum, index, VISUALIZER_BANDS).coerceIn(0f, 1f)
            if (index < 16) rawBass += input
            smoothed[index] = (smoothed[index] * smoothing + input * (1f - smoothing)).coerceIn(0f, 1f)
            peaks[index] = maxOf(smoothed[index], peaks[index] - PEAK_FALL_PER_FRAME)
        }
        rawBass /= 16
        val bass = smoothed.averageRange(0, 16)
        val mid = smoothed.averageRange(16, 32)
        val treble = smoothed.averageRange(32, VISUALIZER_BANDS)
        val onset = (rawBass - previousBass).coerceAtLeast(0f)
        beat = maxOf(beat * BEAT_DECAY, (rawBass * 0.35f + onset * 3.5f).coerceIn(0f, 1f))
        previousBass = rawBass
        return VisualizerFrame(
            spectrum = smoothed.copyOf(),
            waveform = FloatArray(VISUALIZER_WAVEFORM_POINTS) { sample(waveform, it, VISUALIZER_WAVEFORM_POINTS) },
            peaks = peaks.copyOf(),
            bass = bass,
            mid = mid,
            treble = treble,
            beat = beat,
            live = live,
        )
    }
}

private fun sample(
    values: FloatArray,
    outputIndex: Int,
    outputSize: Int,
): Float {
    if (values.isEmpty()) return 0f
    val position = outputIndex.toFloat() * values.size / outputSize
    val left = position.toInt().coerceIn(0, values.lastIndex)
    val right = (left + 1).coerceAtMost(values.lastIndex)
    val fraction = (position - position.toInt()).coerceIn(0f, 1f)
    return (values[left] * (1f - fraction) + values[right] * fraction).coerceIn(-1f, 1f)
}

private fun FloatArray.averageRange(
    start: Int,
    end: Int,
): Float {
    var sum = 0f
    for (index in start until end) sum += this[index]
    return (sum / (end - start)).coerceIn(0f, 1f)
}

private const val PEAK_FALL_PER_FRAME = 0.012f
private const val BEAT_DECAY = 0.82f
