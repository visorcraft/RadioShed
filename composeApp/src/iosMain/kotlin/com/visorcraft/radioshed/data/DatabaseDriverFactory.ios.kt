package com.visorcraft.radioshed.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.visorcraft.radioshed.db.RadioShedDatabase

actual class DatabaseDriverFactory {
    actual fun create(): SqlDriver =
        NativeSqliteDriver(
            schema = RadioShedDatabase.Schema,
            name = DATABASE_NAME,
        )

    companion object {
        const val DATABASE_NAME = "radioshed.db"
    }
}
