package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * LRClib-backed [LyricsFetcher]. Hits the public, no-key endpoint:
 *
 *   GET https://lrclib.net/api/get
 *       ?artist_name=...&track_name=...&album_name=...&duration=...
 *
 * The duration parameter improves match precision when multiple
 * versions of a track exist (album / single / live cuts) but isn't
 * required.
 *
 * Same JDK-only HTTP rationale as [AndroidCoverArtFetcher]: one GET,
 * not worth dragging in Ktor/OkHttp for ~50 lines of code.
 */
class AndroidLyricsFetcher : LyricsFetcher {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetch(
        artist: String,
        title: String,
        album: String?,
        durationSeconds: Long?,
    ): RawLyricsResponse? = withContext(IODispatcher) {
        if (artist.isBlank() || title.isBlank()) return@withContext null
        // URLEncoder.encode produces application/x-www-form-urlencoded
        // (spaces → '+', and `/` → `%2F`). For URL query strings we
        // technically want RFC 3986 percent-encoding (spaces → '%20').
        // LRClib accepts both, but normalize to '%20' so weird CDN /
        // proxy layers in front of the API don't get tripped up by the
        // form encoding's looser rules.
        fun urlEncode(s: String): String =
            URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        val params = buildString {
            append("artist_name=").append(urlEncode(artist))
            append("&track_name=").append(urlEncode(title))
            album?.takeIf { it.isNotBlank() }?.let {
                append("&album_name=").append(urlEncode(it))
            }
            durationSeconds?.takeIf { it > 0 }?.let {
                append("&duration=").append(it)
            }
        }
        val url = URI("$LRCLIB_BASE/api/get?$params").toURL()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            // 404 = real "not in database"; cacheable as a miss.
            if (code == 404) return@withContext null
            // Transient errors: rate-limit, server overload, gateway
            // timeout. Treat distinctly from miss so the caller
            // (LyricsService) doesn't write a long-TTL miss entry
            // for what's actually a temporary network state.
            if (code == 429 || code in 500..599) {
                throw TransientLyricsFetchException("LRClib returned HTTP $code")
            }
            if (code !in 200..299) return@withContext null
            val body = conn.inputStream.use { it.readBytes() }.decodeToString()
            val parsed = runCatching {
                json.decodeFromString(LrclibResponse.serializer(), body)
            }.getOrNull() ?: return@withContext null
            RawLyricsResponse(
                plainLyrics = parsed.plainLyrics?.takeIf { it.isNotBlank() },
                syncedLrc = parsed.syncedLyrics?.takeIf { it.isNotBlank() },
                instrumental = parsed.instrumental == true,
                source = "lrclib",
            )
        } catch (e: TransientLyricsFetchException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            throw TransientLyricsFetchException("LRClib timed out: ${e.message}")
        } catch (e: java.io.IOException) {
            // Connection refused, DNS failure, etc. — same class as
            // server errors from the user's perspective. Don't cache
            // as "not found"; let the next attempt try again.
            throw TransientLyricsFetchException("LRClib unreachable: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    @Serializable
    private data class LrclibResponse(
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
        val instrumental: Boolean? = null,
    )

    private companion object {
        const val LRCLIB_BASE = "https://lrclib.net"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
    }
}
