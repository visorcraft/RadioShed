package com.visorcraft.radioshed.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.visorcraft.radioshed.db.RadioShedDatabase
import com.visorcraft.radioshed.domain.Folder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class FolderRepository(private val db: RadioShedDatabase) {

    fun observeAll(): Flow<List<Folder>> =
        db.foldersQueries.selectAll()
            .asFlow()
            .mapToList(IODispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    suspend fun list(): List<Folder> = withContext(IODispatcher) {
        db.foldersQueries.selectAll().executeAsList().map { it.toDomain() }
    }

    suspend fun getById(id: Long): Folder? = withContext(IODispatcher) {
        db.foldersQueries.selectById(id).executeAsOneOrNull()?.toDomain()
    }

    suspend fun getByUri(uri: String): Folder? = withContext(IODispatcher) {
        db.foldersQueries.selectByUri(uri).executeAsOneOrNull()?.toDomain()
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
            include_globs = encodeStringList(includeGlobs),
            exclude_globs = encodeStringList(excludeGlobs),
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
            include_globs = encodeStringList(includeGlobs),
            exclude_globs = encodeStringList(excludeGlobs),
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
}
