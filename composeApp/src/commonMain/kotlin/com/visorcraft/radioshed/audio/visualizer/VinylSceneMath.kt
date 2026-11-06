package com.visorcraft.radioshed.audio.visualizer

fun vinylTonearmAngle(progress: Float): Float = 110f + progress.coerceIn(0f, 1f) * 15f

fun vinylRotationDegrees(
    timeMs: Long,
    reduceMotion: Boolean,
): Float = timeMs / 1_000f * if (reduceMotion) 2f else 200f

fun vinylProgressSweep(progress: Float): Float = progress.coerceIn(0f, 1f) * 360f
