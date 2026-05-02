package com.visorcraft.radioshed.source

/**
 * Reads lyrics from a sibling file alongside the audio track —
 * `<basename>.lrc` (synced) or `<basename>.txt` (plain) in the same
 * directory. .lrc is preferred when both exist.
 *
 * Used by [LyricsService] as the offline-first source: a track with
 * a curated user-supplied lyrics file will use that, even when the
 * device is online and LRClib has the song.
 */
interface LocalLyricsLoader {
    /**
     * Returns a [LocalLyrics] result if a sibling lyrics file exists
     * and is readable, or null. The caller is responsible for parsing
     * the LRC content and routing through the cache.
     */
    suspend fun load(audioFilePath: String): LocalLyrics?
}

/**
 * One of `synced` or `plain` is non-null. The loader doesn't parse the
 * LRC syntax — that stays in [LyricsService] so we share one parser
 * with the LRClib path.
 */
data class LocalLyrics(
    val synced: String?,
    val plain: String?,
)

class NullLocalLyricsLoader : LocalLyricsLoader {
    override suspend fun load(audioFilePath: String): LocalLyrics? = null
}
