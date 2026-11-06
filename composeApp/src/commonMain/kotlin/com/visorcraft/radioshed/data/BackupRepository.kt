package com.visorcraft.radioshed.data

import com.visorcraft.radioshed.db.RadioShedDatabase
import kotlinx.coroutines.withContext

class BackupRepository(private val db: RadioShedDatabase) {
    suspend fun list(): List<BackupEntry> =
        withContext(IODispatcher) {
            db.backupsQueries.selectAllBackups().executeAsList().map { row ->
                BackupEntry(
                    id = row.id,
                    fileUri = row.file_uri,
                    sizeBytes = row.size_bytes,
                    encrypted = row.encrypted == 1L,
                    contentsSummaryJson = row.contents_summary_json,
                    createdAt = row.created_at,
                )
            }
        }

    suspend fun getById(id: Long): BackupEntry? =
        withContext(IODispatcher) {
            db.backupsQueries.selectBackupById(id).executeAsOneOrNull()?.let { row ->
                BackupEntry(
                    id = row.id,
                    fileUri = row.file_uri,
                    sizeBytes = row.size_bytes,
                    encrypted = row.encrypted == 1L,
                    contentsSummaryJson = row.contents_summary_json,
                    createdAt = row.created_at,
                )
            }
        }

    suspend fun insert(
        fileUri: String,
        sizeBytes: Long,
        encrypted: Boolean,
        contentsSummaryJson: String = "{}",
    ): Long =
        withContext(IODispatcher) {
            db.backupsQueries.insertBackup(
                file_uri = fileUri,
                size_bytes = sizeBytes,
                encrypted = if (encrypted) 1L else 0L,
                contents_summary_json = contentsSummaryJson,
                created_at = nowMillis(),
            ).executeAsOne()
        }

    suspend fun olderThan(threshold: Long): List<BackupEntry> =
        withContext(IODispatcher) {
            db.backupsQueries.selectBackupsOlderThan(threshold).executeAsList().map { row ->
                BackupEntry(
                    id = row.id,
                    fileUri = row.file_uri,
                    sizeBytes = row.size_bytes,
                    encrypted = row.encrypted == 1L,
                    contentsSummaryJson = row.contents_summary_json,
                    createdAt = row.created_at,
                )
            }
        }

    suspend fun delete(id: Long) =
        withContext(IODispatcher) {
            db.backupsQueries.deleteBackup(id)
        }

    suspend fun count(): Long =
        withContext(IODispatcher) {
            db.backupsQueries.countBackups().executeAsOne()
        }
}

data class BackupEntry(
    val id: Long,
    val fileUri: String,
    val sizeBytes: Long,
    val encrypted: Boolean,
    val contentsSummaryJson: String,
    val createdAt: Long,
)
