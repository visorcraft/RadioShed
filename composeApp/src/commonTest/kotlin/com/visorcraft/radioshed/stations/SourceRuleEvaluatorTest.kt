package com.visorcraft.radioshed.stations

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.PlaylistRepository
import com.visorcraft.radioshed.data.RatingRepository
import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.domain.SourceRule
import com.visorcraft.radioshed.domain.Tag
import com.visorcraft.radioshed.source.MediaSourceRegistry
import com.visorcraft.radioshed.source.MockCloudSource
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression lock for compound rules that mix local selectors with
 * [SourceRule.MediaSourceSelector]. The evaluator already routes both
 * branches through the registry, so these tests verify the integration
 * end-to-end (in-memory DB + fake source).
 */
class SourceRuleEvaluatorTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: RadioShedDatabase
    private lateinit var library: LibraryRepository
    private lateinit var evaluator: SourceRuleEvaluator
    private val registry = MediaSourceRegistry()

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        RadioShedDatabase.Schema.create(driver)
        db = RadioShedDatabase(driver)
        library = LibraryRepository(db)
        evaluator = SourceRuleEvaluator(
            library,
            RatingRepository(db),
            PlaylistRepository(db),
            registry,
        )
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun unionOfLocalTagFilterAndMediaSourceYieldsBoth() = runTest {
        registry.register(MockCloudSource())
        seedTrackWithGenre("Local Rock Song", "Rock")

        val rule = SourceRule.Union(
            listOf(
                SourceRule.TagFilter("GENRE", listOf("Rock")),
                SourceRule.MediaSourceSelector("mock-cloud"),
            ),
        )

        val tracks = evaluator.evaluate(rule)

        // 1 local track + 3 mock-cloud tracks
        assertEquals(4, tracks.size, "Expected 4 tracks (1 local + 3 remote)")
        val paths = tracks.map { it.path }.toSet()
        assertTrue(paths.contains("/seed/local-rock-song.flac"), "Local track missing")
        assertTrue(paths.contains("https://mock-cloud.com/tracks/1"), "Remote track 1 missing")
        assertTrue(paths.contains("https://mock-cloud.com/tracks/2"), "Remote track 2 missing")
        assertTrue(paths.contains("https://mock-cloud.com/tracks/3"), "Remote track 3 missing")
    }

    @Test
    fun intersectionOfLocalAndMediaSourceRequiresBoth() = runTest {
        registry.register(MockCloudSource())
        seedTrackWithGenre("Local Rock Song", "Rock")

        val rule = SourceRule.Intersection(
            listOf(
                SourceRule.TagFilter("GENRE", listOf("Rock")),
                SourceRule.MediaSourceSelector("mock-cloud"),
            ),
        )

        val tracks = evaluator.evaluate(rule)

        // Intersection of disjoint sets → empty
        assertEquals(0, tracks.size, "Intersection of local-only and remote-only should be empty")
    }

    @Test
    fun differenceRemovesMediaSourceTracksFromLocalPool() = runTest {
        registry.register(MockCloudSource())
        seedTrackWithGenre("Local Rock Song", "Rock")

        val rule = SourceRule.Difference(
            left = SourceRule.TagFilter("GENRE", listOf("Rock")),
            right = SourceRule.MediaSourceSelector("mock-cloud"),
        )

        val tracks = evaluator.evaluate(rule)

        // Local minus remote (disjoint) → local track survives
        assertEquals(1, tracks.size, "Expected 1 local track after difference")
        assertEquals("/seed/local-rock-song.flac", tracks.first().path)
    }

    private suspend fun seedTrackWithGenre(title: String, genre: String) {
        library.upsertTrack(
            folderId = null,
            path = "/seed/${title.lowercase().replace(" ", "-")}.flac",
            fileName = "$title.flac",
            fileSize = 1L,
            fileCtime = 0L,
            fileMtime = 0L,
            title = title,
            albumId = null,
            trackNumber = null,
            discNumber = null,
            durationMs = 180_000L,
            bitrateKbps = null,
            sampleRateHz = null,
            channels = null,
            format = "flac",
            replayGainTrack = null,
            replayGainAlbum = null,
            replayGainTrackPeak = null,
            bpm = null,
            musicbrainzId = null,
            artistIdsAndRoles = null,
            customTags = listOf(Tag("GENRE", genre, 0)),
            personnel = null,
        )
    }
}
