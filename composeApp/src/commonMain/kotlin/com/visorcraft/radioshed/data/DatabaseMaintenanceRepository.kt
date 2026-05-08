package com.visorcraft.radioshed.data

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.withContext

/**
 * SQLite housekeeping that doesn't fit any single domain repository:
 * VACUUM (rebuild the DB file to reclaim space after large deletes)
 * and ANALYZE (refresh the query planner's statistics after big
 * inserts).
 *
 * SQLDelight's generated repositories can't host these because they
 * route through `transaction { }` blocks, and SQLite refuses to run
 * VACUUM inside a transaction. We sidestep by talking to the
 * [SqlDriver] directly, which issues the statement at the connection
 * level without an implicit transaction wrap.
 *
 * VACUUM runtime scales with the row count plus the free-page count
 * — on a typical 5k-track library with a few thousand history rows
 * it's a 1-2 second operation; the UI surfaces a spinner. On
 * head-unit-class storage (slow eMMC) it can take 10x longer, hence
 * the off-Main `withContext(IODispatcher)`.
 */
class DatabaseMaintenanceRepository(private val driver: SqlDriver) {

    /**
     * Reclaim space by rebuilding the DB file. Useful after a big
     * "Clear all caches" pass — without VACUUM the SQLite page
     * allocator hangs onto the freed pages, so the file stays large
     * even though the data is gone. Returns the size delta in bytes
     * (positive number = bytes freed) when the caller can resolve
     * the on-disk path; null otherwise.
     */
    suspend fun vacuum(): Unit = withContext(IODispatcher) {
        driver.execute(identifier = null, sql = "VACUUM", parameters = 0)
    }

    /**
     * Refresh query-planner statistics. Cheap (sub-second on typical
     * libraries) and worth running after a bulk scan finishes —
     * outdated stats can otherwise cause the planner to pick a
     * full-table-scan plan over an index for a hot query.
     */
    suspend fun analyze(): Unit = withContext(IODispatcher) {
        driver.execute(identifier = null, sql = "ANALYZE", parameters = 0)
    }
}
