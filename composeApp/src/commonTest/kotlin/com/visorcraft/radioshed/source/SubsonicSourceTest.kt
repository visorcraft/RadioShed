package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.testutil.InMemorySettings
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for [SubsonicSource] — the [MediaSource] adapter over a
 * [SubsonicClient]. Drives a [FakeSubsonicClient] returning a canned
 * artist → album → song tree so enumeration, the format / duration
 * filters, mid-walk error tolerance, and the network gate are exercised
 * without touching the wire.
 *
 * The network gate is injected as an `allowNetwork: () -> Boolean`
 * lambda, mirroring [ScrobbleSync]; production binds it to
 * `NetworkPolicyGate.allowAutomaticFetch()` in `sharedModule`.
 *
 * Canned tree (2 artists → 2 albums → 5 songs total):
 *  - Artist "a1" → album "al1" → songs s1 (mp3, 180s), s2 (flac, 240s)
 *  - Artist "a2" → album "al2" → songs s3 (mp3, 30s),  s4 (flac, 600s),
 *                                       s5 (mp3, 200s)
 */
class SubsonicSourceTest {

    private lateinit var settings: SettingsStore

    @BeforeTest
    fun setUp() {
        settings = SettingsStore(InMemorySettings())
        settings.setSubsonicUrl("https://music.example.com/")
        settings.setSubsonicUsername("alice")
        settings.setSubsonicPassword("secret")
    }

    @Test
    fun listTracksEmitsEverySongOnce() = runTest {
        val client = FakeSubsonicClient()
        val source = SubsonicSource(client, settings, allowNetwork = { true })

        val tracks = source.listTracks().toList()

        assertEquals(5, tracks.size, "every song should surface exactly once")
        // URIs match the client's streamUrl form.
        assertEquals(
            listOf("s1", "s2", "s3", "s4", "s5").map { client.streamUrl(client.server, it) },
            tracks.map { it.uri },
        )
        // File names are title + '.' + suffix.
        assertEquals(
            listOf("Song 1.mp3", "Song 2.flac", "Song 3.mp3", "Song 4.flac", "Song 5.mp3"),
            tracks.map { it.fileName },
        )
        // Format is the lowercased suffix.
        assertEquals(listOf("mp3", "flac", "mp3", "flac", "mp3"), tracks.map { it.format })
        // No duplicates.
        assertEquals(5, tracks.map { it.uri }.toSet().size, "each track exactly once")
        // Remote tracks carry no size / filesystem timestamps.
        assertTrue(tracks.all { it.fileSize == 0L && it.fileCtime == 0L && it.fileMtime == 0L })
    }

    @Test
    fun idStripsSchemeAndTrailingSlash() {
        val source = SubsonicSource(FakeSubsonicClient(), settings, allowNetwork = { true })
        // setUp() configured "https://music.example.com/".
        assertEquals("subsonic:music.example.com", source.id)
        assertEquals("Subsonic (music.example.com)", source.displayName)
    }

    @Test
    fun artistsFailureEmitsNothing() = runTest {
        val client = FakeSubsonicClient(failArtists = true)
        val source = SubsonicSource(client, settings, allowNetwork = { true })

        val tracks = source.listTracks().toList()

        assertTrue(tracks.isEmpty(), "an artists-listing failure yields an empty flow")
    }

    @Test
    fun songsFailureSkipsOnlyThatAlbum() = runTest {
        // getSongs throws for the first album — its songs are skipped, but
        // the second artist's album still surfaces (partial results).
        val client = FakeSubsonicClient(failSongsForAlbum = "al1")
        val source = SubsonicSource(client, settings, allowNetwork = { true })

        val tracks = source.listTracks().toList()

        assertEquals(listOf("Song 3.mp3", "Song 4.flac", "Song 5.mp3"), tracks.map { it.fileName })
    }

    @Test
    fun formatFilterApplies() = runTest {
        val client = FakeSubsonicClient()
        val source = SubsonicSource(client, settings, allowNetwork = { true })

        val tracks = source.listTracks(SourceFilter(formats = listOf("flac"))).toList()

        assertEquals(2, tracks.size)
        assertTrue(tracks.all { it.format == "flac" })
        assertEquals(listOf("Song 2.flac", "Song 4.flac"), tracks.map { it.fileName })
    }

    @Test
    fun durationFiltersApply() = runTest {
        val client = FakeSubsonicClient()
        val source = SubsonicSource(client, settings, allowNetwork = { true })

        // Window 60s..300s keeps s1 (180), s2 (240), s5 (200); excludes
        // s3 (30s, too short) and s4 (600s, too long).
        val tracks = source.listTracks(
            SourceFilter(minDurationMs = 60_000L, maxDurationMs = 300_000L),
        ).toList()

        assertEquals(listOf("Song 1.mp3", "Song 2.flac", "Song 5.mp3"), tracks.map { it.fileName })
    }

    @Test
    fun clientErrorMidEnumerationCompletesWithPartial() = runTest {
        // Throw when the second artist's albums are requested — the first
        // artist's songs must still survive; the flow must NOT throw.
        val client = FakeSubsonicClient(failAlbumsForArtist = "a2")
        val source = SubsonicSource(client, settings, allowNetwork = { true })

        val tracks = source.listTracks().toList()

        assertEquals(listOf("Song 1.mp3", "Song 2.flac"), tracks.map { it.fileName })
    }

    @Test
    fun gateDeniedEmitsNothing() = runTest {
        val client = FakeSubsonicClient()
        val source = SubsonicSource(client, settings, allowNetwork = { false })

        val tracks = source.listTracks().toList()

        assertTrue(tracks.isEmpty(), "denied gate emits nothing")
        assertEquals(0, client.calls, "denied gate makes zero client calls")
    }
}

/**
 * Scriptable fake — serves a fixed two-artist tree and counts every
 * suspend lookup so the gate-deny test can assert zero traffic. Set
 * [failAlbumsForArtist] to make [getAlbumsForArtist] throw for that
 * artist id (simulates a mid-walk failure).
 */
private class FakeSubsonicClient(
    private val failArtists: Boolean = false,
    private val failAlbumsForArtist: String? = null,
    private val failSongsForAlbum: String? = null,
) : SubsonicClient {
    val server = SubsonicServer(baseUrl = "https://music.example.com", username = "alice", password = "secret")
    var calls = 0

    private val artists = listOf(
        SubsonicArtist(id = "a1", name = "Artist One"),
        SubsonicArtist(id = "a2", name = "Artist Two"),
    )
    private val albumsByArtist = mapOf(
        "a1" to listOf(SubsonicAlbum(id = "al1", name = "Album One", artist = "Artist One")),
        "a2" to listOf(SubsonicAlbum(id = "al2", name = "Album Two", artist = "Artist Two")),
    )
    private val songsByAlbum = mapOf(
        "al1" to listOf(
            SubsonicSong(id = "s1", title = "Song 1", artist = "Artist One", album = "Album One", durationSec = 180, suffix = "mp3"),
            SubsonicSong(id = "s2", title = "Song 2", artist = "Artist One", album = "Album One", durationSec = 240, suffix = "FLAC"),
        ),
        "al2" to listOf(
            SubsonicSong(id = "s3", title = "Song 3", artist = "Artist Two", album = "Album Two", durationSec = 30, suffix = "mp3"),
            SubsonicSong(id = "s4", title = "Song 4", artist = "Artist Two", album = "Album Two", durationSec = 600, suffix = "flac"),
            SubsonicSong(id = "s5", title = "Song 5", artist = "Artist Two", album = "Album Two", durationSec = 200, suffix = "mp3"),
        ),
    )

    override suspend fun ping(server: SubsonicServer): SubsonicPingResult? {
        calls++
        return SubsonicPingResult(version = "1.16.1", product = "Navidrome")
    }

    override suspend fun getArtists(server: SubsonicServer): List<SubsonicArtist> {
        calls++
        if (failArtists) error("boom: artists")
        return artists
    }

    override suspend fun getAlbumsForArtist(server: SubsonicServer, artistId: String): List<SubsonicAlbum> {
        calls++
        if (artistId == failAlbumsForArtist) error("boom: $artistId")
        return albumsByArtist[artistId].orEmpty()
    }

    override suspend fun getSongs(server: SubsonicServer, albumId: String): List<SubsonicSong> {
        calls++
        if (albumId == failSongsForAlbum) error("boom: $albumId")
        return songsByAlbum[albumId].orEmpty()
    }

    override fun streamUrl(server: SubsonicServer, songId: String): String =
        "${server.baseUrl}/rest/stream?id=$songId"

    override fun coverArtUrl(server: SubsonicServer, coverArtId: String, size: Int?): String =
        "${server.baseUrl}/rest/getCoverArt?id=$coverArtId"
}
