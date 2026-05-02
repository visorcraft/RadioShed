package com.visorcraft.radioshed.source

/**
 * Platform-abstracted storage for backup JSON files. Android writes to
 * `<filesDir>/backups/`, iOS writes to the app's Documents directory
 * (TODO when Mac access lands), which makes both reachable from the
 * platform's "Files" app for sharing / off-device copies.
 */
interface BackupStorage {
    suspend fun list(): List<BackupFile>
    suspend fun write(name: String, bytes: ByteArray): BackupFile?
    suspend fun read(fileUri: String): ByteArray?
    suspend fun delete(fileUri: String): Boolean
}

data class BackupFile(
    val uri: String,        // file:// path
    val displayName: String,
    val sizeBytes: Long,
    val createdAt: Long,
)

class NullBackupStorage : BackupStorage {
    override suspend fun list(): List<BackupFile> = emptyList()
    override suspend fun write(name: String, bytes: ByteArray): BackupFile? = null
    override suspend fun read(fileUri: String): ByteArray? = null
    override suspend fun delete(fileUri: String): Boolean = false
}
