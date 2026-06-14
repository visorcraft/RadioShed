package com.visorcraft.radioshed.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.visorcraft.radioshed.db.RadioShedDatabase
import kotlin.test.Test
import kotlin.test.assertNotNull

/** Ensures the current SQLDelight schema creates on a fresh in-memory DB. */
class FreshSchemaCreatesDatabaseTest {
    @Test
    fun freshSchemaCreatesDatabase() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        RadioShedDatabase.Schema.create(driver)
        val db = RadioShedDatabase(driver)
        assertNotNull(db.libraryQueries.countTracks().executeAsOne())
    }
}
