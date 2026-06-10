package com.visorcraft.radioshed.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.visorcraft.radioshed.db.RadioShedDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * In-memory round-trip coverage for [ScrobbleQueueRepository]. Uses the
 * same SQLDelight schema that ships on Android / desktop via a real
 * [JdbcSqliteDriver] so FIFO ordering, deletion, attempt bumping, and
 * the 500-row cap are exercised against actual SQL rather than a mock.
 */
class ScrobbleQueueRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: RadioShedDatabase
    private lateinit var repo: ScrobbleQueueRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        RadioShedDatabase.Schema.create(driver)
        db = RadioShedDatabase(driver)
        repo = ScrobbleQueueRepository(db)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun enqueueThenDrainIsFifo() = runTest {
        repo.enqueue("Artist A", "Track 1", null, 100)
        repo.enqueue("Artist B", "Track 2", "Album B", 200)
        repo.enqueue("Artist C", "Track 3", null, 300)

        val batch = repo.oldestBatch(10)
        assertEquals(3, batch.size)
        assertEquals(listOf("Track 1", "Track 2", "Track 3"), batch.map { it.track })
        assertEquals(listOf(100L, 200L, 300L), batch.map { it.listenedAtSeconds })
    }

    @Test
    fun deleteByIdRemoves() = runTest {
        repo.enqueue("Artist A", "Track 1", null, 100)
        repo.enqueue("Artist B", "Track 2", null, 200)

        val first = repo.oldestBatch(10).first()
        repo.deleteById(first.id)

        val remaining = repo.oldestBatch(10)
        assertEquals(1, remaining.size)
        assertEquals("Track 2", remaining.first().track)
    }

    @Test
    fun bumpAttemptsIncrements() = runTest {
        repo.enqueue("Artist A", "Track 1", null, 100)
        val id = repo.oldestBatch(10).first().id

        repo.bumpAttempts(id)
        repo.bumpAttempts(id)

        assertEquals(2L, repo.oldestBatch(10).first().attempts)
    }

    @Test
    fun capPrunesOldest() = runTest {
        // 502 rows: ids 1..502. The enqueue-time pruneOverCap keeps the
        // queue at the 500 cap, dropping the two oldest (ids 1 and 2).
        // listenedAtSeconds == i, so the third enqueue (i=2) has listenedAtSeconds=2.
        repeat(502) { i ->
            repo.enqueue("A", "T$i", null, i.toLong())
        }

        assertEquals(500L, repo.countAll())
        val oldest = repo.oldestBatch(1).first()
        // Smallest surviving id is 3 — the two oldest rows are gone.
        assertEquals(3L, oldest.id)
        // Data-driven: the third enqueue was encoded with listenedAtSeconds=2.
        assertEquals(2L, oldest.listenedAtSeconds)
    }
}
