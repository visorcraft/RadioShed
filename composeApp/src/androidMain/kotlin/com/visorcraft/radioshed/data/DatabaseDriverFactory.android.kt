package com.visorcraft.radioshed.data

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.visorcraft.radioshed.db.RadioShedDatabase
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory

actual class DatabaseDriverFactory(private val context: Context) {
    /**
     * Use a bundled SQLite (requery) instead of Android's system SQLite. The
     * system version tracks the OS image: stock Android 13 ships SQLite 3.32,
     * which predates the `RETURNING` clause (3.35) — and we use RETURNING
     * across Folders / Stations / Playlists / Backups / AudioSettings to get
     * the inserted row id back in a single round-trip.
     *
     * Verified failing on AutoLinkR Elite (Bengal SoC, Android 13). The
     * bundled version is ~1MB per ABI and tracks SQLite upstream.
     */
    actual fun create(): SqlDriver =
        AndroidSqliteDriver(
            schema = RadioShedDatabase.Schema,
            context = context,
            name = DATABASE_NAME,
            factory = RequerySQLiteOpenHelperFactory(),
        )

    companion object {
        const val DATABASE_NAME = "radioshed.db"
    }
}
