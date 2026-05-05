package com.visorcraft.radioshed.source

/**
 * Subsonic-protocol client. Speaks the public Subsonic API
 * (subsonic.org/pages/api.jsp) — same protocol that Navidrome,
 * Airsonic, OpenSubsonic, Funkwhale (with the subsonic compat
 * extension), Astiga, Supysonic, and Madsonic implement. Building
 * one client unlocks the entire ecosystem rather than committing to
 * a vendor-specific protocol.
 *
 * Auth: token-based per the Subsonic 1.13+ spec — `salt` is a fresh
 * random string per call, `token = md5(password + salt)`. Never
 * sends the cleartext password over the wire. Older `p=...`
 * cleartext-password mode is intentionally NOT implemented; if your
 * server is too old to support tokens (pre-1.13.0), upgrade.
 *
 * Scope (v1):
 *  - Browse: artists → albums → songs (the canonical drill-down).
 *  - Cover art: stable URLs we can hand to Coil for caching.
 *  - Stream URL: hands an HTTPS URL straight to ExoPlayer; no
 *    transcoding override (use whatever the server defaults to).
 *  - Ping: connection-test.
 *
 * Out of scope for v1 (deferred):
 *  - Playlists (server-side), starred items, search, scrobble-to-
 *    server, podcasts, internet radio entries, share endpoints.
 *  - Per-user transcoding bitrate cap.
 *  - Smart query integration: cloud tracks don't get persisted to
 *    the local SQLDelight library, so they can't seed stations or
 *    playlists yet. That's a real follow-up — needs a remote-track
 *    schema with a `source: 'subsonic'` discriminator and
 *    URL-rewriting at playback time.
 */
interface SubsonicClient {

    /**
     * Probe the server. Returns null if the configured server isn't
     * a Subsonic-compatible server, the auth fails, or the network
     * is down. Non-null result includes the server's reported
     * version + product name (Navidrome, Airsonic, etc.) which the
     * Settings UI can surface as a confirmation.
     */
    suspend fun ping(server: SubsonicServer): SubsonicPingResult?

    /**
     * List every indexed artist. The server returns these grouped
     * by alphabetical "index" letter; we flatten into a single list
     * sorted by display name for our own UI.
     */
    suspend fun getArtists(server: SubsonicServer): List<SubsonicArtist>

    /**
     * Albums for [artistId]. Subsonic's `getArtist` endpoint returns
     * the artist + their album list in one call; we surface only
     * the album list here since the caller already has the artist.
     */
    suspend fun getAlbumsForArtist(
        server: SubsonicServer,
        artistId: String,
    ): List<SubsonicAlbum>

    /**
     * Songs in [albumId]. Subsonic's `getAlbum` endpoint returns the
     * album + song list; we surface only the songs.
     */
    suspend fun getSongs(
        server: SubsonicServer,
        albumId: String,
    ): List<SubsonicSong>

    /**
     * Build a streaming URL for [songId]. The URL embeds auth so
     * ExoPlayer (which has no Subsonic awareness) can fetch it as
     * a plain HTTPS audio resource.
     */
    fun streamUrl(server: SubsonicServer, songId: String): String

    /**
     * Build a cover-art URL for [coverArtId] (typically an album's
     * `coverArt` field). Same auth-embedding as [streamUrl].
     */
    fun coverArtUrl(server: SubsonicServer, coverArtId: String, size: Int? = null): String
}

data class SubsonicServer(
    /** Base URL with protocol and no trailing slash, e.g. "https://music.example.com". */
    val baseUrl: String,
    val username: String,
    val password: String,
)

data class SubsonicPingResult(
    /** Server-reported Subsonic API version (`1.16.1` etc.). */
    val version: String,
    /**
     * Server product name when the server advertises it via the
     * `type` field — Navidrome / Airsonic / etc. Older Subsonic
     * itself doesn't set this and we default to "Subsonic".
     */
    val product: String,
)

data class SubsonicArtist(
    val id: String,
    val name: String,
    val albumCount: Int = 0,
    val coverArtId: String? = null,
)

data class SubsonicAlbum(
    val id: String,
    val name: String,
    val artist: String,
    val artistId: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val songCount: Int = 0,
    val durationSec: Int = 0,
    val coverArtId: String? = null,
)

data class SubsonicSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val durationSec: Int = 0,
    val bitRateKbps: Int? = null,
    val suffix: String? = null,
    val coverArtId: String? = null,
)

/** No-op stub for platforms with no HTTP wired in (iOS today). */
class NullSubsonicClient : SubsonicClient {
    override suspend fun ping(server: SubsonicServer): SubsonicPingResult? = null
    override suspend fun getArtists(server: SubsonicServer): List<SubsonicArtist> = emptyList()
    override suspend fun getAlbumsForArtist(
        server: SubsonicServer,
        artistId: String,
    ): List<SubsonicAlbum> = emptyList()
    override suspend fun getSongs(
        server: SubsonicServer,
        albumId: String,
    ): List<SubsonicSong> = emptyList()
    override fun streamUrl(server: SubsonicServer, songId: String): String = ""
    override fun coverArtUrl(
        server: SubsonicServer,
        coverArtId: String,
        size: Int?,
    ): String = ""
}
