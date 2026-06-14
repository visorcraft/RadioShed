package com.visorcraft.radioshed.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.source.appAppDataDir
import java.nio.file.Files

/**
 * Desktop SQLDelight driver. Stores the DB at
 * `<appAppDataDir>/radioshed.db`.
 *
 * Migration tracking: SQLDelight's `JdbcSqliteDriver` doesn't ship
 * its own SchemaVersionManager, so we drive `PRAGMA user_version`
 * by hand. Three cases:
 *
 *  1. **No tables exist**: brand new file, run `Schema.create` and
 *     stamp `user_version = Schema.version`.
 *  2. **Tables exist but `user_version = 0`**: legacy install path
 *     (this code didn't always stamp the version). Assume the
 *     schema is current — fresh `Schema.create` already lays down
 *     the latest tables, so a v0-stamped post-create DB == today's
 *     schema. Stamp the version and continue.
 *  3. **`user_version > 0` but < `Schema.version`**: real migration
 *     needed. Run `Schema.migrate` over the gap, then stamp.
 *
 * The first design here only considered (1) and a `current < target`
 * branch; case (2) is what produced the "table track_folder already
 * exists" crash on second launch.
 */
actual class DatabaseDriverFactory {
    actual fun create(): SqlDriver {
        val dir = appAppDataDir()
        Files.createDirectories(dir)
        val dbPath = dir.resolve(DATABASE_NAME).toAbsolutePath().toString()
        val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
        val target = RadioShedDatabase.Schema.version
        val current = currentVersion(driver)
        if (!hasUserTables(driver)) {
            RadioShedDatabase.Schema.create(driver)
            setVersion(driver, target)
        } else if (current == 0L) {
            // Tables exist but version was never stamped. Trust that
            // the on-disk schema matches today's `.sq` baseline (it
            // does, for any post-spike install). Just stamp.
            setVersion(driver, target)
        } else if (current < target) {
            RadioShedDatabase.Schema.migrate(driver, current, target)
            setVersion(driver, target)
        }
        return driver
    }

    /**
     * True when the DB has at least one of our user-defined tables.
     * Probing `track` specifically because it's been in the schema
     * since v1 and any RadioShed-shaped DB has it. A fresh JDBC
     * sqlite file has no tables at all and returns false here.
     */
    private fun hasUserTables(driver: SqlDriver): Boolean =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='track'",
            mapper = { cursor ->
                cursor.next()
                app.cash.sqldelight.db.QueryResult.Value((cursor.getLong(0) ?: 0L) > 0L)
            },
            parameters = 0,
        ).value

    private fun currentVersion(driver: SqlDriver): Long =
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor ->
                cursor.next()
                app.cash.sqldelight.db.QueryResult.Value(
                    cursor.getLong(0) ?: 0L,
                )
            },
            parameters = 0,
        ).value

    /**
     * Stamp `PRAGMA user_version` with the current schema version.
     * `user_version` doesn't accept bound parameters in SQLite so
     * we inline the literal — `version` is an internal Long we
     * control, never user input, so no injection surface.
     */
    private fun setVersion(
        driver: SqlDriver,
        version: Long,
    ) {
        driver.execute(null, "PRAGMA user_version = $version", 0)
    }

    companion object {
        const val DATABASE_NAME = "radioshed.db"
    }
}
