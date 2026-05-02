package com.visorcraft.radioshed.source

/**
 * Fetches lyrics for a track from a remote provider. v1 wires Cover
 * Art Archive's parallel for lyrics: [LRClib](https://lrclib.net), a
 * free, no-API-key, MIT-licensed lyrics service that returns plain
 * text + synced LRC for ~1.4M tracks.
 *
 * Returns null when:
 *  - The track isn't in the provider's database (404)
 *  - The provider is unreachable
 *  - The response is malformed
 *
 * Callers (LyricsService) treat null as "couldn't fetch" — caching is
 * the orchestrator's job.
 */
interface LyricsFetcher {
    suspend fun fetch(
        artist: String,
        title: String,
        album: String? = null,
        durationSeconds: Long? = null,
    ): RawLyricsResponse?
}

/**
 * Provider-agnostic response shape — what every fetcher returns. The
 * orchestrator parses [syncedLrc] into [com.visorcraft.radioshed.domain.LyricLine]s
 * once and persists both forms via the cache.
 */
data class RawLyricsResponse(
    val plainLyrics: String?,
    val syncedLrc: String?,
    val instrumental: Boolean,
    val source: String,
)

class NullLyricsFetcher : LyricsFetcher {
    override suspend fun fetch(
        artist: String,
        title: String,
        album: String?,
        durationSeconds: Long?,
    ): RawLyricsResponse? = null
}
