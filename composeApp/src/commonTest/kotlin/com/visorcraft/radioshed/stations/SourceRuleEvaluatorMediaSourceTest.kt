package com.visorcraft.radioshed.stations

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.PlaylistRepository
import com.visorcraft.radioshed.data.RatingRepository
import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.domain.SourceRule
import com.visorcraft.radioshed.source.MediaSourceRegistry
import com.visorcraft.radioshed.source.MockCloudSource
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [SourceRuleEvaluator]'s MediaSourceSelector path: registry lookup
 * plus the DiscoveredTrack → Track conversion. Uses [MockCloudSource] so the
 * flow-based enumeration runs without any network (runTest skips its
 * simulated-latency delays via virtual time).
 */
class SourceRuleEvaluatorMediaSourceTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var evaluator: SourceRuleEvaluator
    private val registry = MediaSourceRegistry()

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        RadioShedDatabase.Schema.create(driver)
        val db = RadioShedDatabase(driver)
        evaluator =
            SourceRuleEvaluator(
                LibraryRepository(db),
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
    fun registeredSourceTracksConvertToTracks() =
        runTest {
            registry.register(MockCloudSource())

            val tracks = evaluator.evaluate(SourceRule.MediaSourceSelector("mock-cloud"))

            assertEquals(3, tracks.size)
            assertEquals("https://mock-cloud.com/tracks/1", tracks[0].path)
            assertEquals("Song One.mp3", tracks[0].fileName)
            assertEquals("mp3", tracks[0].format)
            assertEquals(3, tracks.map { it.id }.toSet().size, "Track ids must be distinct")
        }

    @Test
    fun unknownSourceIdEvaluatesToEmpty() =
        runTest {
            val tracks = evaluator.evaluate(SourceRule.MediaSourceSelector("not-registered"))
            assertTrue(tracks.isEmpty())
        }

    @Test
    fun unregisteredSourceEvaluatesToEmptyAfterRemoval() =
        runTest {
            registry.register(MockCloudSource())
            registry.unregister("mock-cloud")

            val tracks = evaluator.evaluate(SourceRule.MediaSourceSelector("mock-cloud"))
            assertTrue(tracks.isEmpty())
        }
}
