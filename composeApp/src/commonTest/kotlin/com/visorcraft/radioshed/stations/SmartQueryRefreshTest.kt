package com.visorcraft.radioshed.stations

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.data.StationRepository
import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.domain.PlaybackRule
import com.visorcraft.radioshed.domain.SmartQueryKind
import com.visorcraft.radioshed.domain.SourceRule
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.testutil.InMemorySettings
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TDD for [SmartQueryRefresh]. Uses a real in-memory database for
 * [StationRepository] (project convention — DB-backed tests use
 * JdbcSqliteDriver(IN_MEMORY) rather than mocks) and a counting
 * lambda for the evaluate seam.
 */
class SmartQueryRefreshTest {

    private val dayMs = 86_400_000L

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: RadioShedDatabase
    private lateinit var stationRepo: StationRepository
    private lateinit var settings: SettingsStore

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        RadioShedDatabase.Schema.create(driver)
        db = RadioShedDatabase(driver)
        stationRepo = StationRepository(db)
        settings = SettingsStore(InMemorySettings())
    }

    @Test
    fun firstRunNoMarkerRefreshes() = runTest {
        var callCount = 0
        val refresh = SmartQueryRefresh(settings, stationRepo) {
            callCount++
            emptyList()
        }
        stationRepo.insert(
            name = "Latest",
            source = SourceRule.SmartQuery(kind = SmartQueryKind.NewestN, limit = 10),
            playback = PlaybackRule(),
        )

        refresh.refreshIfDue(nowMs = dayMs)

        assertEquals(1, callCount)
        assertEquals(dayMs, settings.getSmartQueryRefreshLast())
    }

    @Test
    fun within24hIsNoOp() = runTest {
        settings.setSmartQueryRefreshLast(dayMs)
        var callCount = 0
        val refresh = SmartQueryRefresh(settings, stationRepo) {
            callCount++
            emptyList()
        }
        stationRepo.insert(
            name = "Latest",
            source = SourceRule.SmartQuery(kind = SmartQueryKind.NewestN, limit = 10),
            playback = PlaybackRule(),
        )

        refresh.refreshIfDue(nowMs = dayMs + 1L)

        assertEquals(0, callCount)
        assertEquals(dayMs, settings.getSmartQueryRefreshLast())
    }

    @Test
    fun olderThan24hRefreshes() = runTest {
        settings.setSmartQueryRefreshLast(0L)
        var callCount = 0
        val refresh = SmartQueryRefresh(settings, stationRepo) {
            callCount++
            emptyList()
        }
        stationRepo.insert(
            name = "Latest",
            source = SourceRule.SmartQuery(kind = SmartQueryKind.NewestN, limit = 10),
            playback = PlaybackRule(),
        )

        refresh.refreshIfDue(nowMs = dayMs + 1L)

        assertEquals(1, callCount)
        assertEquals(dayMs + 1L, settings.getSmartQueryRefreshLast())
    }

    @Test
    fun nonSmartQueryStationsAreSkipped() = runTest {
        var callCount = 0
        val refresh = SmartQueryRefresh(settings, stationRepo) {
            callCount++
            emptyList()
        }
        stationRepo.insert(
            name = "Folder",
            source = SourceRule.FolderSelector(folderIds = listOf(1L)),
            playback = PlaybackRule(),
        )

        refresh.refreshIfDue(nowMs = dayMs)

        assertEquals(0, callCount)
    }

    @Test
    fun nestedSmartQueryInUnionIsFound() = runTest {
        var callCount = 0
        val refresh = SmartQueryRefresh(settings, stationRepo) {
            callCount++
            emptyList()
        }
        stationRepo.insert(
            name = "Mixed",
            source = SourceRule.Union(
                children = listOf(
                    SourceRule.FolderSelector(folderIds = listOf(1L)),
                    SourceRule.SmartQuery(kind = SmartQueryKind.MostPlayed, limit = 5),
                ),
            ),
            playback = PlaybackRule(),
        )

        refresh.refreshIfDue(nowMs = dayMs)

        assertEquals(1, callCount)
    }

    @Test
    fun evaluatorFailureIsSwallowed() = runTest {
        var thrown = false
        val refresh = SmartQueryRefresh(settings, stationRepo) {
            thrown = true
            throw RuntimeException("boom")
        }
        stationRepo.insert(
            name = "Latest",
            source = SourceRule.SmartQuery(kind = SmartQueryKind.NewestN, limit = 10),
            playback = PlaybackRule(),
        )

        refresh.refreshIfDue(nowMs = dayMs)

        assertTrue(thrown)
        assertEquals(dayMs, settings.getSmartQueryRefreshLast())
    }
}
