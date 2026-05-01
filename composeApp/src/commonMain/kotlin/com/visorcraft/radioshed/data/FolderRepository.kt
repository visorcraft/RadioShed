package com.visorcraft.radioshed.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.domain.Folder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import com.visorcraft.radioshed.db.Folder as FolderRow

class FolderRepository(private val db: RadioShedDatabase) {

    fun observeAll(): Flow<List<Folder>> =
        db.foldersQueries.selectAll()
            .asFlow()
            .mapToList(IODispatcher)
            .map { rows -> rows.map(::toDomain) }

    suspend fun list(): List<Folder> = withContext(IODispatcher) {
        db.foldersQueries.selectAll().executeAsList().map(::toDomain)
    }

    suspend fun getById(id: Long): Folder? = withContext(IODispatcher) {
        db.foldersQueries.selectById(id).executeAsOneOrNull()?.let(::toDomain)
    }

    suspend fun getByUri(uri: String): Folder? = withContext(IODispatcher) {
        db.foldersQueries.selectByUri(uri).executeAsOneOrNull()?.let(::toDomain)
    }

    suspend fun insert(
        displayName: String,
        uri: String,
        recursive: Boolean = true,
        includeGlobs: List<String> = emptyList(),
        excludeGlobs: List<String> = emptyList(),
    ): Long = withContext(IODispatcher) {
        val now = nowMillis()
        db.foldersQueries.insert(
            display_name = displayName,
            uri = uri,
            recursive = if (recursive) 1L else 0L,
            include_globs = encodeGlobs(includeGlobs),
            exclude_globs = encodeGlobs(excludeGlobs),
            created_at = now,
            updated_at = now,
        ).executeAsOne()
    }

    suspend fun rename(id: Long, displayName: String) = withContext(IODispatcher) {
        db.foldersQueries.renameDisplayName(displayName, nowMillis(), id)
    }

    suspend fun setAvailable(id: Long, available: Boolean) = withContext(IODispatcher) {
        db.foldersQueries.setAvailable(if (available) 1L else 0L, nowMillis(), id)
    }

    suspend fun setRecursive(id: Long, recursive: Boolean) = withContext(IODispatcher) {
        db.foldersQueries.setRecursive(if (recursive) 1L else 0L, nowMillis(), id)
    }

    suspend fun setGlobs(
        id: Long,
        includeGlobs: List<String>,
        excludeGlobs: List<String>,
    ) = withContext(IODispatcher) {
        db.foldersQueries.setGlobs(
            include_globs = encodeGlobs(includeGlobs),
            exclude_globs = encodeGlobs(excludeGlobs),
            updated_at = nowMillis(),
            id = id,
        )
    }

    suspend fun updateScanResult(id: Long, lastScannedAt: Long, trackCount: Long) =
        withContext(IODispatcher) {
            db.foldersQueries.updateScanResult(lastScannedAt, trackCount, nowMillis(), id)
        }

    suspend fun delete(id: Long) = withContext(IODispatcher) {
        db.foldersQueries.deleteById(id)
    }

    suspend fun count(): Long = withContext(IODispatcher) {
        db.foldersQueries.count().executeAsOne()
    }

    private fun toDomain(row: FolderRow): Folder = Folder(
        id = row.id,
        displayName = row.display_name,
        uri = row.uri,
        recursive = row.recursive == 1L,
        includeGlobs = decodeGlobs(row.include_globs),
        excludeGlobs = decodeGlobs(row.exclude_globs),
        lastScannedAt = row.last_scanned_at,
        trackCount = row.track_count,
        isAvailable = row.is_available == 1L,
        createdAt = row.created_at,
        updatedAt = row.updated_at,
    )

    private fun encodeGlobs(globs: List<String>): String? =
        if (globs.isEmpty()) null
        else RadioShedJson.encodeToString(ListSerializer(String.serializer()), globs)

    private fun decodeGlobs(json: String?): List<String> =
        if (json.isNullOrBlank()) emptyList()
        else RadioShedJson.decodeFromString(ListSerializer(String.serializer()), json)
}
