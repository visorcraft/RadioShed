package com.visorcraft.radioshed.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.domain.SourceRule
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StationRepositoryStatsTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: RadioShedDatabase
    private lateinit var stations: StationRepository
    private lateinit var library: LibraryRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        RadioShedDatabase.Schema.create(driver)
        db = RadioShedDatabase(driver)
        stations = StationRepository(db)
        library = LibraryRepository(db)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun completionStatsReturnsZeroesOnEmptyHistory() =
        runTest {
            assertEquals(0L to 0L, stations.completionStats())
        }

    @Test
    fun completionStatsCountsCompletedAndTotalPlays() =
        runTest {
            val now = nowMillis()
            seedHistory(now, now - DAY_MS)

            assertEquals(1L to 2L, stations.completionStats())
        }

    @Test
    fun listeningStreaksFindsConsecutiveActiveDays() =
        runTest {
            val now = nowMillis()
            seedHistory(now, now - DAY_MS)

            assertEquals(2, stations.listeningStreaks().second)
        }

    private suspend fun seedHistory(vararg playedAts: Long) {
        val stationId =
            stations.insert(
                name = "Stats",
                source = SourceRule.TagFilter(key = "GENRE", values = listOf("Rock")),
            )
        val trackId =
            library.upsertTrack(
                folderId = null,
                path = "/seed/stats.flac",
                fileName = "stats.flac",
                fileSize = 1L,
                fileCtime = 0L,
                fileMtime = 0L,
                title = "Stats",
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
                artistIdsAndRoles = emptyList(),
                customTags = emptyList(),
                personnel = null,
            )
        playedAts.forEachIndexed { index, playedAt ->
            db.stationsQueries.recordPlay(
                station_id = stationId,
                track_id = trackId,
                played_at = playedAt,
                completed = if (index == 0) 1L else 0L,
                listened_ms = 180_000L,
            ).executeAsOne()
        }
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
