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

    /** Set or clear the folder's accent color. Pass null to clear. */
    suspend fun setAccentColor(id: Long, color: Int?) = withContext(IODispatcher) {
        db.foldersQueries.setAccentColor(color?.toLong(), nowMillis(), id)
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

    /**
     * Count of tracks whose path begins with [prefix]. Used by the
     * Move-folder confirmation UI to surface "N tracks will be
     * relocated" before the destructive prefix swap fires.
     */
    suspend fun countTracksWithPathPrefix(prefix: String): Long =
        withContext(IODispatcher) {
            db.libraryQueries.countTracksWithPathPrefix("$prefix%").executeAsOne()
        }

    /**
     * Atomic "Move folder" operation. Updates folder.uri AND
     * rewrites every track.path that starts with the folder's old
     * URI prefix to use [newUri] instead, all inside one transaction
     * so a process crash mid-operation leaves the DB consistent
     * (no half-moved state where the folder points at the new path
     * but the tracks still point at the old, or vice versa).
     *
     * Preserves track ids — and therefore ratings, play counts,
     * station-pin membership, listening history, lyrics cache —
     * for every track whose relative location under the folder
     * survived the move. Tracks whose names/subpaths also changed
     * still won't resolve after the remap; a subsequent rescan
     * marks them unavailable and indexes their new locations as
     * fresh rows linked to the same artist/album entries.
     *
     * No-op if [newUri] equals the folder's current URI. Throws on
     * UNIQUE-constraint collision with another folder's URI.
     */
    suspend fun moveFolderUri(folderId: Long, newUri: String): Long =
        withContext(IODispatcher) {
            db.transactionWithResult {
                val now = nowMillis()
                val current = db.foldersQueries.selectById(folderId)
                    .executeAsOneOrNull()
                    ?: return@transactionWithResult 0L
                val oldUri = current.uri
                if (oldUri == newUri) return@transactionWithResult 0L
                val oldPrefixLike = "$oldUri%"
                val moved = db.libraryQueries
                    .countTracksWithPathPrefix(oldPrefixLike).executeAsOne()
                db.libraryQueries.remapTrackPathPrefix(
                    oldPrefix = oldUri,
                    newPrefix = newUri,
                    updatedAt = now,
                    oldPrefixLike = oldPrefixLike,
                )
                db.foldersQueries.updateUri(newUri, now, folderId)
                moved
            }
        }

    suspend fun count(): Long = withContext(IODispatcher) {
        db.foldersQueries.count().executeAsOne()
    }
}
