package com.visorcraft.radioshed.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.visorcraft.radioshed.db.RadioShedDatabase
import kotlinx.coroutines.withContext

/**
 * FTS5-backed library search via raw [SqlDriver] queries. SQLDelight's parser
 * does not accept MATCH, so these statements bypass the generated query layer.
 */
class FtsSearch(
    private val driver: SqlDriver,
    private val db: RadioShedDatabase,
) {
    suspend fun searchTrackIds(
        query: String,
        limit: Long,
    ): List<Long> =
        withContext(IODispatcher) {
            val ftsQuery = buildFtsMatchQuery(query) ?: return@withContext emptyList()
            if (!hasTrackFts()) return@withContext emptyList()
            runCatching {
                driver.executeQuery(
                    identifier = null,
                    sql =
                        """
                        SELECT track_id FROM track_fts
                        WHERE track_fts MATCH ?
                        ORDER BY rank
                        LIMIT ?
                        """.trimIndent(),
                    mapper = { cursor ->
                        val ids = mutableListOf<Long>()
                        while (cursor.next().value) {
                            ids.add(cursor.getLong(0)!!)
                        }
                        QueryResult.Value(ids)
                    },
                    parameters = 2,
                    binders = {
                        bindString(0, ftsQuery)
                        bindLong(1, limit)
                    },
                ).value
            }.getOrElse { emptyList() }
        }

    suspend fun rebuildTrackIndex() =
        withContext(IODispatcher) {
            if (!hasTrackFts()) return@withContext
            driver.execute(null, "DELETE FROM track_fts", 0)
            driver.execute(
                null,
                "INSERT INTO track_fts(track_id, title) SELECT id, title FROM track",
                0,
            )
        }

    private fun hasTrackFts(): Boolean =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='track_fts'",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value((cursor.getLong(0) ?: 0L) > 0L)
            },
            parameters = 0,
        ).value
}

/**
 * Builds an FTS5 MATCH string with quoted prefix tokens so `/`, `-`, `'`, etc.
 * in artist/title text do not throw syntax errors.
 */
internal fun buildFtsMatchQuery(raw: String): String? {
    val tokens =
        raw.trim()
            .split(Regex("\\s+"))
            .mapNotNull { quoteFtsPrefixToken(it) }
    if (tokens.isEmpty()) return null
    return tokens.joinToString(" ")
}

internal fun quoteFtsPrefixToken(rawToken: String): String? {
    val token = rawToken.trim().removeSurrounding("\"").trim()
    if (token.isEmpty()) return null
    val escaped = token.replace("\"", "\"\"")
    return "\"$escaped\"*"
}
