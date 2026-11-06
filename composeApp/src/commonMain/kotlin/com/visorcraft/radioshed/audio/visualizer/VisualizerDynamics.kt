package com.visorcraft.radioshed.audio.visualizer

internal fun advanceVisualizerTime(
    elapsedMs: Double,
    deltaMs: Long,
    speed: Float,
    reduceMotion: Boolean,
): Double = elapsedMs + deltaMs * speed * if (reduceMotion) 0.15f else 1f

/** Fast attack and gentle release for the analog meter needles. */
class VuNeedle {
    private var value = 0f

    fun next(target: Float): Float {
        val clamped = target.coerceIn(0f, 1f)
        val factor = if (clamped > value) 0.46f else 0.12f
        value += (clamped - value) * factor
        return value
    }
}

/** Allocation-free ring of spectrum snapshots for the waterfall renderer. */
class SpectralHistory(
    private val depth: Int = 72,
) {
    private val frames = Array(depth.coerceAtLeast(1)) { FloatArray(VISUALIZER_BANDS) }
    private var newest = -1
    private var count = 0

    fun add(values: FloatArray) {
        newest = (newest + 1) % frames.size
        val target = frames[newest]
        for (index in target.indices) target[index] = values.getOrElse(index) { 0f }.coerceIn(0f, 1f)
        count = (count + 1).coerceAtMost(frames.size)
    }

    fun valueAtNewestFirst(
        row: Int,
        band: Int,
    ): Float {
        if (row < 0 || row >= count || band !in 0 until VISUALIZER_BANDS) return 0f
        val index = (newest - row + frames.size) % frames.size
        return frames[index][band]
    }
}

/** Allocation-free persistence of recent waveforms for luminous oscilloscope echoes. */
class WaveformHistory(
    depth: Int = 6,
) {
    private val frames = Array(depth.coerceAtLeast(1)) { FloatArray(VISUALIZER_WAVEFORM_POINTS) }
    private var newest = -1
    private var count = 0

    fun add(values: FloatArray) {
        newest = (newest + 1) % frames.size
        val target = frames[newest]
        for (index in target.indices) {
            target[index] = values.getOrElse(index) { 0f }.coerceIn(-1f, 1f)
        }
        count = (count + 1).coerceAtMost(frames.size)
    }

    fun valueAtNewestFirst(
        frame: Int,
        point: Int,
    ): Float {
        if (frame < 0 || frame >= count || point !in 0 until VISUALIZER_WAVEFORM_POINTS) return 0f
        val index = (newest - frame + frames.size) % frames.size
        return frames[index][point]
    }
}

/** Beat-onset-driven ring slots shared by radial scenes; no per-frame particle allocation. */
internal class ImpactRingPool(
    private val capacity: Int = 10,
    private val durationMs: Long = 1_400L,
    private val refractoryMs: Long = 240L,
) {
    private val startedAt = LongArray(capacity.coerceAtLeast(1)) { -durationMs }
    private val strengths = FloatArray(startedAt.size)
    private var cursor = 0
    private var previousBeat = 0f
    private var lastTrigger = -refractoryMs

    fun update(
        timeMs: Long,
        beat: Float,
    ) {
        val clamped = beat.coerceIn(0f, 1f)
        if (clamped > 0.58f && clamped > previousBeat + 0.06f && timeMs - lastTrigger >= refractoryMs) {
            startedAt[cursor] = timeMs
            strengths[cursor] = clamped
            cursor = (cursor + 1) % startedAt.size
            lastTrigger = timeMs
        }
        previousBeat = clamped
    }

    fun burst(
        timeMs: Long,
        strength: Float,
    ) {
        repeat(3) { index ->
            startedAt[cursor] = timeMs + index * 100L
            strengths[cursor] = strength.coerceIn(0f, 1f)
            cursor = (cursor + 1) % startedAt.size
        }
        lastTrigger = timeMs + 200L
    }

    inline fun forEachActive(
        timeMs: Long,
        block: (age: Float, strength: Float) -> Unit,
    ) {
        for (index in startedAt.indices) {
            val elapsed = timeMs - startedAt[index]
            if (elapsed !in 0..durationMs) continue
            block(elapsed.toFloat() / durationMs, strengths[index])
        }
    }
}
