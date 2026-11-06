package com.visorcraft.radioshed.audio.visualizer

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Deterministic, track-seeded light-show input used when real PCM/FFT is unavailable. */
class AmbientSignalGenerator {
    fun frame(
        timeMs: Long,
        trackId: Long,
    ): VisualizerFrame {
        val t = timeMs.toDouble() / 1000.0
        val seed = trackId.toDouble() * 0.017
        val slowBeat = (sin(t * 2.0 * PI * 0.82 + seed) * 0.5 + 0.5).pow(5.0)
        val bassWave = sin(t * 1.7 + seed) * 0.5 + 0.5
        val midWave = sin(t * 1.13 + seed * 1.7) * 0.5 + 0.5
        val trebleWave = sin(t * 2.31 + seed * 2.3) * 0.5 + 0.5
        val cyclePosition = ((t + seed * 3.0) % 28.0 + 28.0) % 28.0
        val structure = ambientStructureEnvelope(cyclePosition.toFloat())
        val buildProgress = ((cyclePosition - 18.0) / 9.0).coerceIn(0.0, 1.0)
        val spectrum =
            FloatArray(VISUALIZER_BANDS) { index ->
                val x = index.toDouble() / (VISUALIZER_BANDS - 1)
                val envelope =
                    when {
                        x < 0.33 -> bassWave
                        x < 0.66 -> midWave
                        else -> trebleWave
                    }
                val dancing =
                    (
                        sin(x * 13.0 + t * 2.2 + seed) * 0.28 +
                            sin(x * 31.0 - t * 1.35 + seed * 0.7) * 0.16
                    ) * (1.0 - buildProgress * 0.85)
                val rollOff = 1.0 - x * 0.58
                (
                    0.10 + (0.46 + dancing) * rollOff * (0.55 + envelope * 0.55) +
                        slowBeat * (1.0 - x) * 0.22 + structure * (1.0 - x * 0.65)
                ).toFloat().coerceIn(0f, 1f)
            }
        val waveform =
            FloatArray(VISUALIZER_WAVEFORM_POINTS) { index ->
                val x = index.toDouble() / (VISUALIZER_WAVEFORM_POINTS - 1) * 2.0 * PI
                (
                    (
                        sin(x * 2.0 + t * 3.1 + seed) * (0.30 + bassWave * 0.24) +
                            sin(x * 5.0 - t * 2.2 + seed * 1.4) * (0.12 + midWave * 0.16) +
                            sin(x * 11.0 + t * 4.0) * trebleWave * 0.08
                    ) * (1.0 + structure * 1.4)
                ).toFloat().coerceIn(-1f, 1f)
            }
        return VisualizerFrame(
            spectrum = spectrum,
            waveform = waveform,
            peaks = spectrum.copyOf(),
            bass = average(spectrum, 0, 16),
            mid = average(spectrum, 16, 32),
            treble = average(spectrum, 32, VISUALIZER_BANDS),
            beat = (slowBeat + structure * 0.5).toFloat().coerceIn(0f, 1f),
            live = false,
        )
    }
}

internal fun ambientStructureEnvelope(cyclePositionSeconds: Float): Float {
    val position = cyclePositionSeconds.coerceIn(0f, 28f)
    val build = if (position < 18f) 0f else ((position - 18f) / 9f).coerceIn(0f, 1f).let { it * it * 0.30f }
    val drop = if (position < 27f) 0f else exp(-(position - 27f) * 2.0) * 0.60f
    return build + drop.toFloat()
}

private fun average(
    values: FloatArray,
    start: Int,
    end: Int,
): Float {
    var sum = 0f
    for (index in start until end) sum += values[index]
    return (sum / (end - start)).coerceIn(0f, 1f)
}
