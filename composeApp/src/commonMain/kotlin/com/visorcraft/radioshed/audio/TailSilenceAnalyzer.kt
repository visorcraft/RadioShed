package com.visorcraft.radioshed.audio

/**
 * Estimates trailing silence at the end of a local audio file. Used by Smart
 * Fades to align crossfade length and arming with natural song endings.
 *
 * Returns null when analysis is unavailable (unknown format, IO error, or
 * platform stub).
 */
interface TailSilenceAnalyzer {
    /**
     * @param uri local file path
     * @param durationMs known track duration; non-positive skips analysis
     * @return milliseconds of near-silence detected at the track tail, or null
     */
    suspend fun trailingSilenceMs(
        uri: String,
        durationMs: Long,
    ): Long?
}

/** iOS stub — no local file analysis until AVFoundation lands. */
class NullTailSilenceAnalyzer : TailSilenceAnalyzer {
    override suspend fun trailingSilenceMs(
        uri: String,
        durationMs: Long,
    ): Long? = null
}
