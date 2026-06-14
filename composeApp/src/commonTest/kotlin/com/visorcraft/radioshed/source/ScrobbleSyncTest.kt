package com.visorcraft.radioshed.source

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.visorcraft.radioshed.data.ScrobbleQueueRepository
import com.visorcraft.radioshed.data.SettingsStore
import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.testutil.InMemorySettings
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Queue-then-drain coverage for [ScrobbleSync]. Wires a real
 * [ScrobbleQueueRepository] over an in-memory [JdbcSqliteDriver] and a
 * real [SettingsStore] over a shared [InMemorySettings] fake (token set), so
 * FIFO drain order, attempt bumping, the max-attempts drop, and the
 * network gate are exercised against actual SQL rather than a mock.
 *
 * The network gate is injected as a `allowNetwork: () -> Boolean`
 * lambda — the production [NetworkPolicyGate] decision depends on
 * several knobs (ConnectivityPolicy + online + unmetered), so a lambda
 * is the simplest honest seam; production binds it to
 * `gate.allowAutomaticFetch()` in `sharedModule`.
 */
class ScrobbleSyncTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: RadioShedDatabase
    private lateinit var queue: ScrobbleQueueRepository
    private lateinit var settings: SettingsStore

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        RadioShedDatabase.Schema.create(driver)
        db = RadioShedDatabase(driver)
        queue = ScrobbleQueueRepository(db)
        settings = SettingsStore(InMemorySettings())
        settings.setListenBrainzEnabled(true)
        settings.setListenBrainzToken("test-token")
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun submitSuccessLeavesQueueEmpty() =
        runTest {
            val client = FakeListenBrainzClient(succeed = { true })
            val sync = ScrobbleSync(queue, client, settings, allowNetwork = { true })

            sync.submitOrEnqueue("Artist", "Track", "Album", 100)

            assertEquals(0L, queue.countAll())
            assertEquals(1, client.submitted.size)
            assertEquals("Track", client.submitted.first().track)
        }

    @Test
    fun submitFailureEnqueues() =
        runTest {
            val client = FakeListenBrainzClient(succeed = { false })
            val sync = ScrobbleSync(queue, client, settings, allowNetwork = { true })

            sync.submitOrEnqueue("Artist", "Track", "Album", 100)

            assertEquals(1L, queue.countAll())
            assertEquals(0L, queue.oldestBatch(10).first().attempts)
        }

    @Test
    fun drainSubmitsFifoAndStopsAtFirstFailure() =
        runTest {
            queue.enqueue("A1", "T1", null, 100)
            queue.enqueue("A2", "T2", null, 200)
            queue.enqueue("A3", "T3", null, 300)

            // Succeed once, then fail on every subsequent call.
            var calls = 0
            val client = FakeListenBrainzClient(succeed = { calls++ == 0 })
            val sync = ScrobbleSync(queue, client, settings, allowNetwork = { true })

            sync.drain()

            val remaining = queue.oldestBatch(10)
            // First row submitted + deleted; second failed (attempts 1, still
            // queued); third never touched (attempts 0).
            assertEquals(listOf("T2", "T3"), remaining.map { it.track })
            assertEquals(1L, remaining[0].attempts)
            assertEquals(0L, remaining[1].attempts)
            assertEquals(2, client.submitted.size)
        }

    @Test
    fun drainRespectsNetworkDeny() =
        runTest {
            queue.enqueue("A1", "T1", null, 100)
            val client = FakeListenBrainzClient(succeed = { true })
            val sync = ScrobbleSync(queue, client, settings, allowNetwork = { false })

            sync.drain()

            assertEquals(1L, queue.countAll())
            assertEquals(0, client.submitted.size)
        }

    @Test
    fun drainFlushesMultipleBatches() =
        runTest {
            // Enqueue 30 rows — more than the default batch size of 25 — so
            // drain() must loop across at least two fetches to empty the queue.
            repeat(30) { i ->
                queue.enqueue("Artist$i", "Track$i", null, (100L + i))
            }

            val client = FakeListenBrainzClient(succeed = { true })
            val sync = ScrobbleSync(queue, client, settings, allowNetwork = { true })

            sync.drain()

            assertEquals(0L, queue.countAll())
            assertEquals(30, client.submitted.size)
        }

    @Test
    fun drainDropsAfterMaxAttempts() =
        runTest {
            queue.enqueue("Poison", "Dead", null, 100)
            val poisonId = queue.oldestBatch(1).first().id
            repeat(10) { queue.bumpAttempts(poisonId) }
            // A fresh row enqueued after the poisoned one (FIFO: poison first).
            queue.enqueue("Fresh", "Alive", null, 200)

            val client = FakeListenBrainzClient(succeed = { true })
            val sync = ScrobbleSync(queue, client, settings, allowNetwork = { true })

            sync.drain()

            // Poison row dropped WITHOUT submitting; fresh row submitted.
            assertEquals(0L, queue.countAll())
            assertEquals(1, client.submitted.size)
            assertEquals("Alive", client.submitted.first().track)
        }
}

/**
 * Scriptable fake — [succeed] decides each call's outcome and every
 * submitted listen is recorded for assertions. [validateToken] is
 * unused here; returns true.
 */
private class FakeListenBrainzClient(
    private val succeed: () -> Boolean,
) : ListenBrainzClient {
    val submitted = mutableListOf<ListenBrainzClient.Listen>()

    override suspend fun submit(listen: ListenBrainzClient.Listen): Boolean {
        submitted.add(listen)
        return succeed()
    }

    override suspend fun validateToken(token: String): Boolean = true
}
