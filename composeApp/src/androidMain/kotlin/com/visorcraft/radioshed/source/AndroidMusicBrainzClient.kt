package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * MusicBrainz public-API client. Read-only lookups for release-group
 * and artist MBIDs.
 *
 * Endpoint contract:
 *   GET https://musicbrainz.org/ws/2/release-group?query=...&fmt=json
 *
 * Compliance rules per <https://musicbrainz.org/doc/MusicBrainz_API>:
 *  - One request per second per IP (we don't enforce — the resolver
 *    is called at most once per album when the existing artwork
 *    pipeline misses, which is well below the rate cap in practice).
 *  - User-Agent must identify the application + contact. We send
 *    a static UA with the GitHub issues link as the contact channel.
 *  - JSON over Lucene-syntax queries — straightforward, no auth.
 *
 * Same JDK-only HTTP rationale as the LRClib + Cover Art clients:
 * one GET per call, not worth dragging in OkHttp/Ktor for ~80 lines.
 */
class AndroidMusicBrainzClient : MusicBrainzClient {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun lookupReleaseGroup(
        artist: String,
        album: String,
    ): String? =
        withContext(IODispatcher) {
            if (artist.isBlank() || album.isBlank()) return@withContext null
            // Lucene escape: backslash every special char so a release
            // titled "Foo!" doesn't break the query parser. The full
            // syntax also reserves `&&`, `||`, but those don't appear
            // in real-world tags often enough to bother stripping;
            // a malformed query just returns no results, no crash.
            val q = """release:"${escape(album)}" AND artist:"${escape(artist)}""""
            val params = "query=${urlEncode(q)}&fmt=json&limit=1"
            val raw = httpGet("$MB_BASE/ws/2/release-group?$params") ?: return@withContext null
            runCatching {
                json.decodeFromString(ReleaseGroupSearch.serializer(), raw)
                    .releaseGroups?.firstOrNull()?.id
            }.getOrNull()
        }

    override suspend fun lookupArtist(artist: String): String? =
        withContext(IODispatcher) {
            if (artist.isBlank()) return@withContext null
            val q = """artist:"${escape(artist)}""""
            val params = "query=${urlEncode(q)}&fmt=json&limit=1"
            val raw = httpGet("$MB_BASE/ws/2/artist?$params") ?: return@withContext null
            runCatching {
                json.decodeFromString(ArtistSearch.serializer(), raw)
                    .artists?.firstOrNull()?.id
            }.getOrNull()
        }

    private suspend fun httpGet(url: String): String? =
        withContext(IODispatcher) {
            val conn =
                (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                }
            try {
                val code = conn.responseCode
                if (code !in 200..299) return@withContext null
                conn.inputStream.use { it.readBytes() }.decodeToString()
            } catch (e: java.io.IOException) {
                null
            } finally {
                conn.disconnect()
            }
        }

    private fun escape(s: String): String =
        // Lucene reserved chars per
        // <https://lucene.apache.org/core/2_9_4/queryparsersyntax.html#Escaping%20Special%20Characters>
        // Order matters — `\\` must be first or it would re-escape.
        s.fold(StringBuilder()) { acc, c ->
            if (c in LUCENE_SPECIALS) acc.append('\\')
            acc.append(c)
        }.toString()

    private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    @Serializable
    private data class ReleaseGroupSearch(
        @kotlinx.serialization.SerialName("release-groups")
        val releaseGroups: List<ReleaseGroup>? = null,
    )

    @Serializable
    private data class ReleaseGroup(val id: String)

    @Serializable
    private data class ArtistSearch(
        val artists: List<Artist>? = null,
    )

    @Serializable
    private data class Artist(val id: String)

    private companion object {
        const val MB_BASE = "https://musicbrainz.org"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        val LUCENE_SPECIALS =
            setOf(
                '\\', '+', '-', '!', '(', ')', '{', '}', '[', ']', '^', '"',
                '~', '*', '?', ':', '/',
            )
    }
}
