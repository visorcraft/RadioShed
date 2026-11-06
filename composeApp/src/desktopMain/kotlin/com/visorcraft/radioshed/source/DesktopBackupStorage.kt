package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.nio.file.Files

/**
 * Desktop [BackupStorage]. Bundle files live under
 * `<appAppDataDir>/backups/`. Same `.rsbak` (zip) and legacy `.json`
 * extensions Android uses, so a backup made on phone can be dropped
 * here and shown alongside desktop-native bundles in the Restore
 * picker. The cross-platform restore work (Android → Desktop or
 * Desktop → Android) is still SPEC-deferred but the storage layer
 * is now uniform.
 *
 * URIs are returned as `file:///<absolute>` so caller code (the
 * Backup Sharer / restore reader) can route through standard
 * java.net.URI parsing without platform-specific handling.
 */
class DesktopBackupStorage : BackupStorage {
    private val backupDir: File by lazy {
        val dir = appAppDataDir().resolve("backups")
        Files.createDirectories(dir)
        dir.toFile()
    }

    override suspend fun list(): List<BackupFile> =
        withContext(IODispatcher) {
            backupDir.listFiles { f ->
                f.isFile && (f.extension == "json" || f.extension == "rsbak")
            }
                ?.map { f ->
                    BackupFile(
                        uri = f.toURI().toString(),
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
                // Tmp + atomic rename so a process crash mid-write
                // can't leave a half-written file the restore reader
                // would try to parse.
                //
                // Files.move(REPLACE_EXISTING) instead of File.renameTo:
                // on Windows, renameTo silently fails when `target`
                // already exists (it maps to MoveFile, which doesn't
                // overwrite). Files.move uses MoveFileEx with
                // MOVEFILE_REPLACE_EXISTING and overwrites atomically,
                // so a re-write of the same backup name actually
                // replaces the prior file instead of dropping the new
                // bytes on the floor.
                val tmp = File(backupDir, "$safe.tmp")
                tmp.writeBytes(bytes)
                try {
                    java.nio.file.Files.move(
                        tmp.toPath(),
                        target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (e: java.io.IOException) {
                    tmp.delete()
                    throw e
                }
                BackupFile(
                    uri = target.toURI().toString(),
                    displayName = target.name,
                    sizeBytes = target.length(),
                    createdAt = target.lastModified(),
                )
            }.getOrNull()
        }

    override suspend fun read(fileUri: String): ByteArray? =
        withContext(IODispatcher) {
            runCatching {
                val file = File(URI(fileUri))
                file.takeIf { it.exists() && it.canRead() }?.readBytes()
            }.getOrNull()
        }

    override suspend fun delete(fileUri: String): Boolean =
        withContext(IODispatcher) {
            runCatching {
                File(URI(fileUri)).delete()
            }.getOrDefault(false)
        }

    /**
     * Strip path separators and reserved Windows characters from
     * user-supplied bundle names. Android uses the same regex; kept
     * consistent so a backup transferred between platforms doesn't
     * gain or lose characters in the round-trip.
     */
    private fun sanitizeFilename(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\x00-\x1F]"""), "_")
            .ifBlank { "backup.json" }
}
