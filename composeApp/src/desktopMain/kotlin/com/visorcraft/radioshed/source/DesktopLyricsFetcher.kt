package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Desktop LRClib-backed lyrics fetcher. Identical to the Android
 * impl — both are pure JVM hitting `https://lrclib.net/api/get` over
 * `HttpURLConnection`. No-API-key, MIT-licensed, ~1.4M-track corpus.
 */
class DesktopLyricsFetcher : LyricsFetcher {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetch(
        artist: String,
        title: String,
        album: String?,
        durationSeconds: Long?,
    ): RawLyricsResponse? =
        withContext(IODispatcher) {
            if (artist.isBlank() || title.isBlank()) return@withContext null

            fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

            val params =
                buildString {
                    append("artist_name=").append(urlEncode(artist))
                    append("&track_name=").append(urlEncode(title))
                    album?.takeIf { it.isNotBlank() }?.let {
                        append("&album_name=").append(urlEncode(it))
                    }
                    durationSeconds?.takeIf { it > 0 }?.let {
                        append("&duration=").append(it)
                    }
                }
            // URI(...).toURL() over the deprecated URL(String) constructor
            // — same wire behaviour, but URI is the modern parsing layer
            // and gives us deterministic encoding semantics.
            val url = URI("$LRCLIB_BASE/api/get?$params").toURL()
            val conn =
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                }
            try {
                val code = conn.responseCode
                if (code == 404) return@withContext null
                if (code == 429 || code in 500..599) {
                    throw TransientLyricsFetchException("LRClib returned HTTP $code")
                }
                if (code !in 200..299) return@withContext null
                val body = conn.inputStream.use { it.readBytes() }.decodeToString()
                val parsed =
                    runCatching {
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
