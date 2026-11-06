package com.visorcraft.radioshed.audio.visualizer

fun sceneIntroProgress(
    ageMs: Long,
    reduceMotion: Boolean,
): Float {
    val duration = if (reduceMotion) 150L else 420L
    return (ageMs.coerceAtLeast(0L).toFloat() / duration).coerceIn(0f, 1f)
}

fun sceneIntroProgress(
    ageMs: Long,
    reduceMotion: Boolean,
    playing: Boolean,
): Float = if (playing) sceneIntroProgress(ageMs, reduceMotion) else 1f
