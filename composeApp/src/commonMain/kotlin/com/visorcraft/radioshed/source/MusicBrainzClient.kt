package com.visorcraft.radioshed.source

/**
 * Cross-platform MusicBrainz lookup interface. Resolves an
 * (artist, album) pair to a release-group MBID — the stable
 * identifier that Cover Art Archive uses to serve album art.
 *
 * Why MBID resolution lives in its own client (rather than baked into
 * the existing CoverArtFetcher): Cover Art Archive's "by-MBID" path is
 * faster and less error-prone than its "by-artist+album" search path,
 * and the MBID also doubles as a join key for future Wikipedia /
 * Wikidata enrichment. Keeping it standalone means each consumer can
 * cache the lookup independently.
 *
 * No API key required — MusicBrainz's public web service is rate-
 * limited at 1 req/sec/IP and asks every client to identify itself
 * via User-Agent. We comply with both rules in the Android impl.
 */
interface MusicBrainzClient {
    /**
     * Resolve a release-group MBID for [artist] / [album]. Returns
     * null when the search has no result, when the network is
     * unavailable, or when the implementation is a no-op stub
     * (iOS today).
     */
    suspend fun lookupReleaseGroup(artist: String, album: String): String?

    /**
     * Resolve an artist MBID. Useful for downstream Wikidata bio
     * lookup. Returns null on miss / unavailable.
     */
    suspend fun lookupArtist(artist: String): String?
}

/** No-op stub for platforms with no HTTP client wired in (iOS today). */
class NullMusicBrainzClient : MusicBrainzClient {
    override suspend fun lookupReleaseGroup(artist: String, album: String): String? = null
    override suspend fun lookupArtist(artist: String): String? = null
}
