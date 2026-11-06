package com.visorcraft.radioshed.source

import android.content.Context
import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android-side implementation of [BackupStorage]. Files live under
 * `<filesDir>/backups/`. We use filesDir (not cacheDir) so the OS won't
 * silently evict backups under storage pressure, and not external
 * storage so we don't need MANAGE_EXTERNAL_STORAGE for the write.
 *
 * The user can grab the file via a system Files app (RadioShed shows up
 * under "Internal storage > Android > data > com.visorcraft.radioshed >
 * files > backups" on devices that surface app-private dirs) or by
 * sharing it via Android's built-in share sheet — out of scope for v1
 * which only does on-device save / restore.
 */
class AndroidBackupStorage(private val context: Context) : BackupStorage {
    private val backupDir: File by lazy {
        File(context.filesDir, "backups").also { it.mkdirs() }
    }

    override suspend fun list(): List<BackupFile> =
        withContext(IODispatcher) {
            backupDir.listFiles { f ->
                // `.rsbak` is the new zip-bundled format (manifest.json +
                // artwork blobs). `.json` is the legacy plain-bundle format
                // we still produce when the platform packager is null.
                // Keep both so a user with mixed backups in their dir sees
                // them all in the Restore picker.
                f.isFile && (f.extension == "json" || f.extension == "rsbak")
            }
                ?.map { f ->
                    BackupFile(
                        uri = "file://${f.absolutePath}",
                        displayName = f.name,
                        sizeBytes = f.length(),
                        createdAt = f.lastModified(),
                    )
                }
                ?.sortedByDescending { it.createdAt }
                .orEmpty()
        }

    override suspend fun write(
        name: String,
        bytes: ByteArray,
    ): BackupFile? =
        withContext(IODispatcher) {
            runCatching {
                val safe = sanitizeFilename(name)
                val target = File(backupDir, safe)
                // Atomic-ish write via tmp + rename. A backup file caught
                // half-written would be invalid JSON and refuse to import.
                val tmp = File(backupDir, "$safe.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    // Cross-filesystem move or concurrent target creation.
                    // Don't return a URI to the orphaned tmp file — the
                    // caller would store a broken pointer. Clean up and
                    // fail explicitly.
                    tmp.delete()
                    return@runCatching null
                }
                BackupFile(
                    uri = "file://${target.absolutePath}",
                    displayName = target.name,
                    sizeBytes = target.length(),
                    createdAt = target.lastModified(),
                )
            }.getOrNull()
        }

    override suspend fun read(fileUri: String): ByteArray? =
        withContext(IODispatcher) {
            runCatching {
                val path = android.net.Uri.parse(fileUri).path ?: return@runCatching null
                File(path).takeIf { it.exists() && it.canRead() }?.readBytes()
            }.getOrNull()
        }

    override suspend fun delete(fileUri: String): Boolean =
        withContext(IODispatcher) {
            runCatching {
                val path = android.net.Uri.parse(fileUri).path ?: return@runCatching false
                File(path).delete()
            }.getOrDefault(false)
        }

    /** Strip path separators and weird control chars from user-supplied names. */
    private fun sanitizeFilename(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\x00-\x1F]"""), "_")
            .ifBlank { "backup.json" }
}
