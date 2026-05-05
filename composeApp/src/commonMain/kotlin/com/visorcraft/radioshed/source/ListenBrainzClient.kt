package com.visorcraft.radioshed.source

/**
 * ListenBrainz scrobbling client. Token-based — the user pastes
 * their personal token (from <https://listenbrainz.org/profile/>)
 * into Settings, and the client signs every submission with it.
 *
 * Why ListenBrainz over Last.fm:
 *  - No OAuth flow. Token is a single string the user copies; we
 *    don't need to register a public app key, host a callback URL,
 *    or maintain refresh tokens.
 *  - Offline-friendly: failed submissions can sit on disk and re-
 *    submit later without re-authenticating.
 *  - Same shape of data (artist / track / album / unix timestamp).
 *  - Public, AGPL-licensed backend rather than a hostile commercial
 *    one.
 *
 * Endpoint: POST <https://api.listenbrainz.org/1/submit-listens>
 *
 * Scope (v1): one listen per call. Batching multiple listens into one
 * payload is supported by the API and is a deferred follow-up — a
 * burst of catch-up scrobbles after a network outage would be more
 * efficient that way, but a single-listen path is simpler to reason
 * about and well within ListenBrainz's per-IP rate limits for typical
 * single-user playback.
 */
interface ListenBrainzClient {
    /**
     * Submit a single completed listen. Returns true on 200/201, false
     * on every other outcome (auth failure, network error, missing
     * token, etc.). Callers should not retry indefinitely on `false`
     * — surface to the user via Settings status if it stays false.
     */
    suspend fun submit(listen: Listen): Boolean

    /**
     * Quick token validation — POSTs to /1/validate-token. Returns
     * true if the token is recognized by ListenBrainz, false otherwise.
     * Used by Settings to give immediate feedback when the user pastes
     * a token rather than waiting for the first scrobble to fail.
     */
    suspend fun validateToken(token: String): Boolean

    data class Listen(
        val artist: String,
        val track: String,
        val album: String? = null,
        /** Unix epoch SECONDS at which the listen completed. */
        val listenedAtSeconds: Long,
        /** User's personal API token (from listenbrainz.org/profile/). */
        val token: String,
    )
}

/** Stub for platforms with no HTTP wiring. */
class NullListenBrainzClient : ListenBrainzClient {
    override suspend fun submit(listen: ListenBrainzClient.Listen): Boolean = false
    override suspend fun validateToken(token: String): Boolean = false
}
