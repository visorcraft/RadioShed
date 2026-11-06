package com.visorcraft.radioshed.audio.visualizer

import kotlin.math.sqrt

/** Bounded rolling analysis that turns sustained builds and sudden energy jumps into scene choreography. */
class MusicStructureAnalyzer {
    private val energies = FloatArray(240)
    private var newest = -1
    private var count = 0
    private var sum = 0f
    private var sumSquares = 0f
    private var buildFrames = 0
    private var elapsedMs = 0L
    private var lastDropMs = -DROP_REFRACTORY_MS
    private var impact = 0f

    fun next(
        frame: VisualizerFrame,
        deltaMs: Long,
    ): MusicStructure {
        val energy = (frame.bass * 0.5f + frame.mid * 0.3f + frame.treble * 0.2f).coerceIn(0f, 1f)
        val short = averageLast(15)
        val older = averageBetween(15, 45)
        val slope = if (count < 45) 0f else short - older
        val mean = if (count == 0) energy else sum / count
        val variance = if (count == 0) 0f else (sumSquares / count - mean * mean).coerceAtLeast(0f)
        val deviation = sqrt(variance)
        val jump = energy - if (count < 45) energy else older

        buildFrames =
            if (slope > 0.004f && short > 0.03f) {
                (buildFrames + 1).coerceAtMost(90)
            } else {
                (buildFrames - 2).coerceAtLeast(0)
            }
        elapsedMs += deltaMs.coerceIn(0L, 500L)
        impact = (impact - deltaMs / 900f).coerceIn(0f, 1f)
        val dropped =
            buildFrames >= 20 &&
                short < 0.85f &&
                jump > maxOf(0.18f, deviation * 2.2f) &&
                elapsedMs - lastDropMs >= DROP_REFRACTORY_MS
        val tension =
            if (dropped) {
                0f
            } else {
                (buildFrames / 45f).coerceIn(0f, 1f) * (slope / 0.04f).coerceIn(0f, 1f)
            }
        if (dropped) {
            impact = 1f
            lastDropMs = elapsedMs
            buildFrames = 0
        }
        add(energy)
        return MusicStructure(tension = tension, impact = impact, dropped = dropped)
    }

    private fun add(energy: Float) {
        newest = (newest + 1) % energies.size
        if (count == energies.size) {
            val removed = energies[newest]
            sum -= removed
            sumSquares -= removed * removed
        }
        energies[newest] = energy
        sum += energy
        sumSquares += energy * energy
        count = (count + 1).coerceAtMost(energies.size)
    }

    private fun averageLast(amount: Int): Float = averageBetween(0, amount)

    private fun averageBetween(
        start: Int,
        end: Int,
    ): Float {
        val available = count.coerceAtMost(end) - start
        if (available <= 0) return 0f
        var total = 0f
        for (offset in start until start + available) {
            val index = (newest - offset + energies.size) % energies.size
            total += energies[index]
        }
        return total / available
    }
}

data class MusicStructure(
    val tension: Float,
    val impact: Float,
    val dropped: Boolean,
) {
    companion object {
        val EMPTY = MusicStructure(tension = 0f, impact = 0f, dropped = false)
    }
}

private const val DROP_REFRACTORY_MS = 9_000L
