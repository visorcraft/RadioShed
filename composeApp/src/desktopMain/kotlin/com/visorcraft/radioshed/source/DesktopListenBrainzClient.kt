package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI

/**
 * ListenBrainz scrobbling over plain HTTPS POST. Same JDK-only HTTP
 * pattern as our other API clients — one POST per call, no extra
 * dependency.
 *
 * Body shape per <https://listenbrainz.readthedocs.io/en/latest/users/api/core.html>:
 *
 * ```
 * {
 *   "listen_type": "single",
 *   "payload": [
 *     {
 *       "listened_at": 1709999999,
 *       "track_metadata": {
 *         "artist_name": "...",
 *         "track_name": "...",
 *         "release_name": "..."   // optional
 *       }
 *     }
 *   ]
 * }
 * ```
 *
 * Auth header: `Authorization: Token <user-token>`. The token is opaque
 * to us — we just round-trip it.
 */
class DesktopListenBrainzClient : ListenBrainzClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun submit(
        listen: ListenBrainzClient.Listen,
    ): Boolean = withContext(IODispatcher) {
        if (listen.token.isBlank()) return@withContext false
        if (listen.artist.isBlank() || listen.track.isBlank()) return@withContext false
        // Hand-built JSON. A round-trip through `buildJsonObject` would
        // need separate scopes for the array element + nested object,
        // and the readability of the resulting code-vs-payload mapping
        // is worse than this string template — especially given the
        // payload structure is fixed by the ListenBrainz API.
        val albumLine = listen.album?.takeIf { it.isNotBlank() }?.let {
            ",\n      \"release_name\": ${jsonString(it)}"
        } ?: ""
        val payload = """
            {
              "listen_type": "single",
              "payload": [
                {
                  "listened_at": ${listen.listenedAtSeconds},
                  "track_metadata": {
                    "artist_name": ${jsonString(listen.artist)},
                    "track_name": ${jsonString(listen.track)}$albumLine
                  }
                }
              ]
            }
        """.trimIndent()

        val url = URI("$LB_BASE/1/submit-listens").toURL()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Token ${listen.token}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(payload.encodeToByteArray()) }
            val code = conn.responseCode
            code in 200..299
        } catch (e: java.io.IOException) {
            false
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun validateToken(token: String): Boolean = withContext(IODispatcher) {
        if (token.isBlank()) return@withContext false
        val url = URI("$LB_BASE/1/validate-token").toURL()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Token $token")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) return@withContext false
            val body = conn.inputStream.use { it.readBytes() }.decodeToString()
            // Response shape: {"code":200,"message":"...","valid":true,"user_name":"..."}
            runCatching {
                json.decodeFromString(ValidateResponse.serializer(), body).valid == true
            }.getOrDefault(false)
        } catch (e: java.io.IOException) {
            false
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Minimal JSON-string escaping. Just the chars that would break a
     * naive concatenation: backslash, double-quote, control characters
     * via `\u00XX`. ListenBrainz accepts standard JSON UTF-8.
     */
    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '\\' -> sb.append("\\\\")
                c == '"' -> sb.append("\\\"")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    @Serializable
    private data class ValidateResponse(
        val code: Int? = null,
        val valid: Boolean? = null,
        @kotlinx.serialization.SerialName("user_name")
        val userName: String? = null,
        val message: String? = null,
    )

    private companion object {
        const val LB_BASE = "https://api.listenbrainz.org"
        const val USER_AGENT =
            "RadioShed/0.1 (https://radioshed.app)"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
    }
}
