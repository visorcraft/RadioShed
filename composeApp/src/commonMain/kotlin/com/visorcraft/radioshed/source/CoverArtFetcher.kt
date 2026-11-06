package com.visorcraft.radioshed.source

/**
 * Fetches album artwork from the Cover Art Archive (CAA) given a
 * MusicBrainz Release ID. The MBID is sourced from the file's tags
 * during scan (`MUSICBRAINZ_RELEASEID` Vorbis comment / `MusicBrainz
 * Album Id` ID3v2 TXXX frame), so we never have to do a fuzzy search —
 * the lookup is direct and authoritative.
 *
 * V1 fetches a single 500px thumbnail (`/release/{mbid}/front-500`). The
 * 500px size is chosen because:
 *  - It's large enough for full-screen NowPlaying on a phone (≥ 360dp)
 *    without obvious blur.
 *  - It's small enough (~50–80KB JPEG) that a full library sweep over
 *    a metered connection is reasonable.
 *  - CAA pre-renders these alongside the full-size original; requesting
 *    the size means we never round-trip through archive.org's full asset.
 *
 * Failures (404 = no art on file at CAA, network error, etc.) return
 * null. Callers treat null as "leave the album unchanged" — better than
 * persisting a placeholder that masks the missing-art state.
 */
interface CoverArtFetcher {
    suspend fun fetchByReleaseMbid(mbid: String): ByteArray?
}

/** Stub used on platforms that don't have HTTP wired yet (iOS, etc.). */
class NullCoverArtFetcher : CoverArtFetcher {
    override suspend fun fetchByReleaseMbid(mbid: String): ByteArray? = null
}
