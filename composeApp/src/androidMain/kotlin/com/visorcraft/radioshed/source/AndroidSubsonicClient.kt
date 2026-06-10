package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.random.Random

/**
 * java.net-backed Subsonic client. Same JDK-only HTTP rationale as
 * the rest of the source/ clients — one GET per call, no extra
 * dependency.
 *
 * Auth: token mode introduced in Subsonic API 1.13. For each call:
 *  - Generate a 12-char random `salt`.
 *  - Compute `token = md5(password + salt)` (lowercase hex).
 *  - Send `u=...&t=<token>&s=<salt>&v=1.16.1&c=RadioShed&f=json`.
 *
 * The server never sees the cleartext password. The `c` parameter
 * identifies the client name — Subsonic shows it in the
 * server-side "Active connections" admin page so the user can
 * recognise their phone.
 *
 * Response shape: every Subsonic call returns `{"subsonic-response":
 * {"status": "ok|failed", ...}}`. We parse that envelope once and
 * surface the inner data, returning empty / null on `status: failed`
 * (auth error, missing entity, etc).
 */
class AndroidSubsonicClient : SubsonicClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun ping(server: SubsonicServer): SubsonicPingResult? =
        withContext(IODispatcher) {
            val raw = httpGetJson(server, "ping") ?: return@withContext null
            runCatching {
                val env = json.decodeFromString(Envelope.serializer(), raw)
                val inner = env.subsonicResponse ?: return@runCatching null
                if (inner.status != "ok") return@runCatching null
                SubsonicPingResult(
                    version = inner.version ?: "?",
                    product = (inner.type ?: "Subsonic").replaceFirstChar { it.uppercase() },
                )
            }.getOrNull()
        }

    override suspend fun getArtists(server: SubsonicServer): List<SubsonicArtist> =
        withContext(IODispatcher) {
            val raw = httpGetJson(server, "getArtists") ?: return@withContext emptyList()
            runCatching {
                val env = json.decodeFromString(Envelope.serializer(), raw)
                val groups = env.subsonicResponse?.artists?.index.orEmpty()
                groups.flatMap { it.artist.orEmpty() }
                    .map {
                        SubsonicArtist(
                            id = it.id,
                            name = it.name,
                            albumCount = it.albumCount ?: 0,
                            coverArtId = it.coverArt,
                        )
                    }
                    .sortedBy { it.name.lowercase() }
            }.getOrDefault(emptyList())
        }

    override suspend fun getAlbumsForArtist(
        server: SubsonicServer,
        artistId: String,
    ): List<SubsonicAlbum> = withContext(IODispatcher) {
        val raw = httpGetJson(server, "getArtist", "id=${urlEncode(artistId)}")
            ?: return@withContext emptyList()
        runCatching {
            val env = json.decodeFromString(Envelope.serializer(), raw)
            env.subsonicResponse?.artist?.album.orEmpty().map {
                SubsonicAlbum(
                    id = it.id,
                    name = it.name ?: it.title ?: "(unknown)",
                    artist = it.artist.orEmpty(),
                    artistId = it.artistId,
                    year = it.year,
                    genre = it.genre,
                    songCount = it.songCount ?: 0,
                    durationSec = it.duration ?: 0,
                    coverArtId = it.coverArt,
                )
            }.sortedWith(compareBy({ it.year ?: Int.MAX_VALUE }, { it.name.lowercase() }))
        }.getOrDefault(emptyList())
    }

    override suspend fun getSongs(
        server: SubsonicServer,
        albumId: String,
    ): List<SubsonicSong> = withContext(IODispatcher) {
        val raw = httpGetJson(server, "getAlbum", "id=${urlEncode(albumId)}")
            ?: return@withContext emptyList()
        runCatching {
            val env = json.decodeFromString(Envelope.serializer(), raw)
            env.subsonicResponse?.album?.song.orEmpty().map {
                SubsonicSong(
                    id = it.id,
                    title = it.title ?: "(untitled)",
                    artist = it.artist.orEmpty(),
                    album = it.album.orEmpty(),
                    albumId = it.albumId,
                    track = it.track,
                    discNumber = it.discNumber,
                    year = it.year,
                    genre = it.genre,
                    durationSec = it.duration ?: 0,
                    bitRateKbps = it.bitRate,
                    suffix = it.suffix,
                    coverArtId = it.coverArt,
                )
            }.sortedWith(
                compareBy(
                    { it.discNumber ?: 1 },
                    { it.track ?: Int.MAX_VALUE },
                    { it.title.lowercase() },
                ),
            )
        }.getOrDefault(emptyList())
    }

    override fun streamUrl(server: SubsonicServer, songId: String): String {
        val (params, _) = authParams(server)
        return "${normalizeBase(server.baseUrl)}/rest/stream?id=${urlEncode(songId)}&$params"
    }

    override fun coverArtUrl(
        server: SubsonicServer,
        coverArtId: String,
        size: Int?,
    ): String {
        val (params, _) = authParams(server)
        val sizeParam = size?.let { "&size=$it" }.orEmpty()
        return "${normalizeBase(server.baseUrl)}/rest/getCoverArt?id=${urlEncode(coverArtId)}" +
            "$sizeParam&$params"
    }

    private fun httpGetJson(
        server: SubsonicServer,
        endpoint: String,
        extraParams: String = "",
    ): String? {
        val base = normalizeBase(server.baseUrl)
        // Subsonic needs an absolute http(s) base URL. Bail early when
        // the field is empty / scheme-less so the "user hit Test &
        // save before typing anything" path returns a clean null
        // (which the UI surfaces as a connection failure) instead of
        // crashing the process via MalformedURLException out of the
        // URL constructor on the IO worker.
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            return null
        }
        val (params, _) = authParams(server)
        val combined = if (extraParams.isEmpty()) params else "$extraParams&$params"
        val urlString = "$base/rest/$endpoint?$combined"
        return try {
            val conn = (URI(urlString).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) null
                else conn.inputStream.use { it.readBytes() }.decodeToString()
            } finally {
                conn.disconnect()
            }
        } catch (e: java.io.IOException) {
            // Covers MalformedURLException (still possible if the URL
            // passes the prefix check but is otherwise unparseable),
            // connect/read timeouts, and the generic IO failures.
            null
        }
    }

    /**
     * Build the auth + format query string. The salt is regenerated
     * each call so a captured URL on a hostile network can't be
     * replayed in perpetuity (still snapshotted to that one salt
     * window, but each fresh call rotates).
     */
    private fun authParams(server: SubsonicServer): Pair<String, String> {
        val salt = randomSalt()
        val token = md5Hex("${server.password}$salt")
        val params = buildString {
            append("u=").append(urlEncode(server.username))
            append("&t=").append(token)
            append("&s=").append(salt)
            append("&v=").append(API_VERSION)
            append("&c=").append(CLIENT_NAME)
            append("&f=json")
        }
        return params to salt
    }

    private fun randomSalt(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val sb = StringBuilder(SALT_LENGTH)
        repeat(SALT_LENGTH) { sb.append(chars[Random.nextInt(chars.length)]) }
        return sb.toString()
    }

    private fun md5Hex(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(s.encodeToByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun urlEncode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun normalizeBase(s: String): String =
        s.trimEnd('/')

    @Serializable
    private data class Envelope(
        @kotlinx.serialization.SerialName("subsonic-response")
        val subsonicResponse: Inner? = null,
    )

    @Serializable
    private data class Inner(
        val status: String? = null,
        val version: String? = null,
        val type: String? = null,
        val artists: ArtistsBlock? = null,
        val artist: ArtistDetail? = null,
        val album: AlbumDetail? = null,
    )

    @Serializable
    private data class ArtistsBlock(val index: List<ArtistGroup>? = null)

    @Serializable
    private data class ArtistGroup(val name: String? = null, val artist: List<ArtistRow>? = null)

    @Serializable
    private data class ArtistRow(
        val id: String,
        val name: String,
        val albumCount: Int? = null,
        val coverArt: String? = null,
    )

    @Serializable
    private data class ArtistDetail(val album: List<AlbumRow>? = null)

    @Serializable
    private data class AlbumRow(
        val id: String,
        val name: String? = null,
        val title: String? = null,
        val artist: String? = null,
        val artistId: String? = null,
        val year: Int? = null,
        val genre: String? = null,
        val songCount: Int? = null,
        val duration: Int? = null,
        val coverArt: String? = null,
    )

    @Serializable
    private data class AlbumDetail(val song: List<SongRow>? = null)

    @Serializable
    private data class SongRow(
        val id: String,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumId: String? = null,
        val track: Int? = null,
        val discNumber: Int? = null,
        val year: Int? = null,
        val genre: String? = null,
        val duration: Int? = null,
        val bitRate: Int? = null,
        val suffix: String? = null,
        val coverArt: String? = null,
    )

    private companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "RadioShed"
        const val SALT_LENGTH = 12
        const val USER_AGENT =
            "RadioShed/0.1 (https://radioshed.app)"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
    }
}
