package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.testutil.InMemorySettings
import kotlinx.coroutines.delay
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
 * filters, mid-walk error tolerance, the network gate, the bounded
 * fan-out, and the TTL walk cache are exercised without touching the
 * wire.
 *
 * The network gate is injected as an `allowNetwork: () -> Boolean`
 * lambda, mirroring [ScrobbleSync]; production binds it to
 * `NetworkPolicyGate.allowAutomaticFetch()` in `sharedModule`. The
 * clock is injected as `nowMs: () -> Long` so the cache TTL tests are
 * deterministic.
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
    fun idIsStableAndDisplayNameReflectsHost() {
        val source = SubsonicSource(FakeSubsonicClient(), settings, allowNetwork = { true })
        // setUp() configured "https://music.example.com/".
        assertEquals("subsonic", source.id, "id is fixed so registry key survives credential edits")
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

    @Test
    fun secondCollectionServesCacheWithoutRewalk() = runTest {
        val client = FakeSubsonicClient()
        val source = SubsonicSource(client, settings, allowNetwork = { true }, nowMs = { 0L })

        val first = source.listTracks().toList()
        val callsAfterFirstWalk = client.calls
        val second = source.listTracks().toList()

        assertEquals(first.map { it.uri }, second.map { it.uri }, "cache replays the identical enumeration")
        assertEquals(callsAfterFirstWalk, client.calls, "a fresh cache entry serves with zero client calls")
    }

    @Test
    fun cacheHoldsRawSongsSoFiltersStillApplyPerCollection() = runTest {
        val client = FakeSubsonicClient()
        val source = SubsonicSource(client, settings, allowNetwork = { true }, nowMs = { 0L })

        // Warm the cache with an unfiltered walk, then collect filtered.
        assertEquals(5, source.listTracks().toList().size)
        val callsAfterFirstWalk = client.calls

        val flacOnly = source.listTracks(SourceFilter(formats = listOf("flac"))).toList()

        assertEquals(listOf("Song 2.flac", "Song 4.flac"), flacOnly.map { it.fileName })
        assertEquals(callsAfterFirstWalk, client.calls, "a filtered collection still rides the cache")
    }

    @Test
    fun ttlExpiryRewalks() = runTest {
        var now = 0L
        val client = FakeSubsonicClient()
        val source = SubsonicSource(client, settings, allowNetwork = { true }, nowMs = { now })

        source.listTracks().toList()
        val callsAfterFirstWalk = client.calls

        // One ms past the 10-minute TTL — the entry must be stale.
        now = 10 * 60 * 1000L + 1
        val tracks = source.listTracks().toList()

        assertEquals(5, tracks.size)
        assertTrue(client.calls > callsAfterFirstWalk, "an expired entry triggers a fresh walk")
    }

    @Test
    fun configChangeInvalidatesCache() = runTest {
        val client = FakeSubsonicClient()
        val source = SubsonicSource(client, settings, allowNetwork = { true }, nowMs = { 0L })

        source.listTracks().toList()
        val callsAfterFirstWalk = client.calls

        settings.setSubsonicUrl("https://other.example.com")
        val tracks = source.listTracks().toList()

        assertTrue(client.calls > callsAfterFirstWalk, "an edited server config must invalidate the cache")
        assertTrue(
            tracks.all { it.uri.startsWith("https://other.example.com/") },
            "stream URLs are rebuilt against the new server, never replayed from the old one",
        )
    }

    @Test
    fun gateDenyServesNothingEvenWhenCached() = runTest {
        var allow = true
        val client = FakeSubsonicClient()
        val source = SubsonicSource(client, settings, allowNetwork = { allow }, nowMs = { 0L })

        assertEquals(5, source.listTracks().toList().size, "warm the cache while the gate allows")
        val callsAfterFirstWalk = client.calls

        allow = false
        val tracks = source.listTracks().toList()

        assertTrue(tracks.isEmpty(), "a denied gate must not serve cached tracks either")
        assertEquals(callsAfterFirstWalk, client.calls, "a denied gate makes zero client calls")
    }

    @Test
    fun partialWalkIsNotCachedAndRetries() = runTest {
        val client = FakeSubsonicClient(failAlbumsForArtist = "a2")
        val source = SubsonicSource(client, settings, allowNetwork = { true }, nowMs = { 0L })

        assertEquals(2, source.listTracks().toList().size, "partial walk still emits what it found")

        // Server recovers. The partial result must NOT have been pinned
        // for the TTL — the very next collection re-walks and sees all 5.
        client.failAlbumsForArtist = null
        assertEquals(5, source.listTracks().toList().size, "next collection after a partial walk retries")
    }

    @Test
    fun walkFanOutIsBoundedToFourConcurrentCalls() = runTest {
        val client = WideFakeSubsonicClient(artistCount = 12)
        val source = SubsonicSource(client, settings, allowNetwork = { true }, nowMs = { 0L })

        val tracks = source.listTracks().toList()

        assertEquals(12, tracks.size, "one song per artist's single album")
        assertTrue(client.maxInFlight > 1, "album/song fetches actually overlap")
        assertTrue(client.maxInFlight <= 4, "never more than 4 requests in flight")
        // Order is still deterministic despite the concurrent fan-out.
        assertEquals((1..12).map { "Song $it.mp3" }, tracks.map { it.fileName })
    }
}

/**
 * Scriptable fake — serves a fixed two-artist tree and counts every
 * suspend lookup so the gate-deny and cache tests can assert traffic.
 * Set [failAlbumsForArtist] to make [getAlbumsForArtist] throw for that
 * artist id (simulates a mid-walk failure); the failure knobs are `var`
 * so a test can "recover" the server between collections.
 */
private class FakeSubsonicClient(
    var failArtists: Boolean = false,
    var failAlbumsForArtist: String? = null,
    var failSongsForAlbum: String? = null,
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

/**
 * Wide fake for the fan-out bound: [artistCount] artists, each with one
 * album of one song. Every browse call suspends on a virtual-time
 * [delay] so overlapping requests are observable, and the high-water
 * mark of simultaneously in-flight calls lands in [maxInFlight].
 * Single-threaded under [runTest]'s dispatcher, so the plain counters
 * don't race.
 */
private class WideFakeSubsonicClient(private val artistCount: Int) : SubsonicClient {
    private var inFlight = 0
    var maxInFlight = 0
        private set

    /** Count the call as in-flight across one suspension point. */
    private suspend fun <T> counted(block: () -> T): T {
        inFlight += 1
        if (inFlight > maxInFlight) maxInFlight = inFlight
        delay(1)
        inFlight -= 1
        return block()
    }

    override suspend fun ping(server: SubsonicServer): SubsonicPingResult? =
        counted { SubsonicPingResult(version = "1.16.1", product = "Navidrome") }

    override suspend fun getArtists(server: SubsonicServer): List<SubsonicArtist> =
        counted { (1..artistCount).map { SubsonicArtist(id = "a$it", name = "Artist $it") } }

    override suspend fun getAlbumsForArtist(server: SubsonicServer, artistId: String): List<SubsonicAlbum> =
        counted {
            val n = artistId.removePrefix("a")
            listOf(SubsonicAlbum(id = "al$n", name = "Album $n", artist = "Artist $n"))
        }

    override suspend fun getSongs(server: SubsonicServer, albumId: String): List<SubsonicSong> =
        counted {
            val n = albumId.removePrefix("al")
            listOf(
                SubsonicSong(
                    id = "s$n",
                    title = "Song $n",
                    artist = "Artist $n",
                    album = "Album $n",
                    durationSec = 180,
                    suffix = "mp3",
                ),
            )
        }

    override fun streamUrl(server: SubsonicServer, songId: String): String =
        "${server.baseUrl}/rest/stream?id=$songId"

    override fun coverArtUrl(server: SubsonicServer, coverArtId: String, size: Int?): String =
        "${server.baseUrl}/rest/getCoverArt?id=$coverArtId"
}
