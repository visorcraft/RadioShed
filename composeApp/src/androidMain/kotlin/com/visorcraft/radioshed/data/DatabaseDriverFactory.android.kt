package com.visorcraft.radioshed.data

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.visorcraft.radioshed.db.RadioShedDatabase

actual class DatabaseDriverFactory(private val context: Context) {
    actual fun create(): SqlDriver = AndroidSqliteDriver(
        schema = RadioShedDatabase.Schema,
        context = context,
        name = DATABASE_NAME,
    )

    companion object {
        const val DATABASE_NAME = "radioshed.db"
    }
}
