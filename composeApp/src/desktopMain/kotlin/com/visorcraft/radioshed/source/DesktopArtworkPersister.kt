package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Desktop content-addressed artwork persister. Mirrors the Android
 * impl: bytes → SHA-256 → write to `<appAppDataDir>/artwork/<hex>.jpg`.
 * Returned URI is a `file://` for Coil to load directly.
 *
 * The `.jpg` suffix is conservative: we don't sniff the source bytes
 * to detect PNG vs JPEG (decoders accept either via the file://
 * loader regardless), and a fixed extension keeps the directory
 * easy to clean by glob.
 */
class DesktopArtworkPersister : ArtworkPersister {
    override suspend fun persist(bytes: ByteArray): String? =
        withContext(IODispatcher) {
            if (bytes.isEmpty()) return@withContext null
            runCatching {
                val dir = appArtworkDir()
                Files.createDirectories(dir)
                val hex = sha256Hex(bytes)
                val path = dir.resolve("$hex.jpg")
                if (!Files.exists(path)) {
                    Files.write(path, bytes)
                }
                path.toUri().toString()
            }.getOrNull()
        }

    override suspend fun clearAll(): Int =
        withContext(IODispatcher) {
            val dir = appArtworkDir()
            if (!Files.exists(dir)) return@withContext 0
            var count = 0
            runCatching {
                Files.list(dir).use { stream ->
                    stream.forEach { file ->
                        if (Files.deleteIfExists(file)) count++
                    }
                }
            }
            count
        }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
