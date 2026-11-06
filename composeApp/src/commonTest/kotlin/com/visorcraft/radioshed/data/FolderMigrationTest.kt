package com.visorcraft.radioshed.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.domain.Folder
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FolderMigrationTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: RadioShedDatabase

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun migrationNormalizesFreshV11FolderLayout() =
        runTest {
            verifyMigration(accentBeforeTimestamps = true)
        }

    @Test
    fun migrationNormalizesUpgradedV11FolderLayout() =
        runTest {
            verifyMigration(accentBeforeTimestamps = false)
        }

    @Test
    fun migrationPreservesJunctionWithForeignKeysDisabled() =
        runTest {
            verifyMigration(accentBeforeTimestamps = false, enforceForeignKeys = false)
        }

    private suspend fun verifyMigration(
        accentBeforeTimestamps: Boolean,
        enforceForeignKeys: Boolean = true,
    ) {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ${if (enforceForeignKeys) 1 else 0}", 0)
        val timestampColumns =
            """
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
            """.trimIndent()
        val finalColumns =
            if (accentBeforeTimestamps) {
                "accent_color INTEGER,\n$timestampColumns"
            } else {
                "$timestampColumns,\naccent_color INTEGER"
            }
        driver.execute(
            null,
            """
            CREATE TABLE folder (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                display_name TEXT NOT NULL,
                uri TEXT NOT NULL UNIQUE,
                recursive INTEGER NOT NULL DEFAULT 1,
                include_globs TEXT,
                exclude_globs TEXT,
                last_scanned_at INTEGER,
                track_count INTEGER NOT NULL DEFAULT 0,
                is_available INTEGER NOT NULL DEFAULT 1,
                $finalColumns
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "CREATE TABLE track (id INTEGER NOT NULL PRIMARY KEY)", 0)
        driver.execute(
            null,
            """
            CREATE TABLE track_folder (
                track_id INTEGER NOT NULL REFERENCES track(id) ON DELETE CASCADE,
                folder_id INTEGER NOT NULL REFERENCES folder(id) ON DELETE CASCADE,
                PRIMARY KEY (track_id, folder_id)
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "INSERT INTO track (id) VALUES (1)", 0)
        driver.execute(
            null,
            """
            INSERT INTO folder (
                id, display_name, uri, recursive, include_globs, exclude_globs,
                last_scanned_at, track_count, is_available, accent_color, created_at, updated_at
            ) VALUES (
                1, 'Music', '/music', 0, '["*.flac","*.mp3"]', '["live"]',
                123, 4, 0, 16711680, 100, 200
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "INSERT INTO track_folder (track_id, folder_id) VALUES (1, 1)", 0)
        db = RadioShedDatabase(driver)

        RadioShedDatabase.Schema.migrate(driver, oldVersion = 11, newVersion = 12)

        assertEquals(
            listOf(
                "id",
                "display_name",
                "uri",
                "recursive",
                "include_globs",
                "exclude_globs",
                "last_scanned_at",
                "track_count",
                "is_available",
                "accent_color",
                "created_at",
                "updated_at",
            ),
            folderColumns(),
        )
        assertEquals(
            Folder(
                id = 1,
                displayName = "Music",
                uri = "/music",
                recursive = false,
                includeGlobs = listOf("*.flac", "*.mp3"),
                excludeGlobs = listOf("live"),
                lastScannedAt = 123,
                trackCount = 4,
                isAvailable = false,
                accentColor = 16_711_680,
                createdAt = 100,
                updatedAt = 200,
            ),
            FolderRepository(db).getById(1),
        )
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM track_folder WHERE track_id = 1 AND folder_id = 1"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM pragma_foreign_key_check"))
    }

    private fun folderColumns(): List<String> =
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info(folder)",
            mapper = { cursor ->
                val columns = mutableListOf<String>()
                while (cursor.next().value) {
                    columns.add(cursor.getString(1)!!)
                }
                QueryResult.Value(columns)
            },
            parameters = 0,
        ).value

    private fun scalarLong(sql: String): Long =
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0)!!)
            },
            parameters = 0,
        ).value
}
