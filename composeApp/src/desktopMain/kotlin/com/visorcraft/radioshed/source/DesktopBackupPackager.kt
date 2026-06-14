package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Desktop [BackupPackager]. Mirrors `AndroidBackupPackager` —
 * java.util.zip is available on every JVM, so the implementation is
 * the same byte-for-byte. Lives in its own file (not DesktopStubs.kt)
 * because it's a real impl, not a stub.
 */
class DesktopBackupPackager : BackupPackager {
    override suspend fun pack(
        manifestJson: ByteArray,
        blobs: Map<String, ByteArray>,
    ): ByteArray? =
        withContext(IODispatcher) {
            runCatching {
                val out = ByteArrayOutputStream()
                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry(MANIFEST_NAME))
                    zip.write(manifestJson)
                    zip.closeEntry()
                    for ((name, bytes) in blobs) {
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
                out.toByteArray()
            }.getOrNull()
        }

    override suspend fun unpack(bytes: ByteArray): BackupPackager.Unpacked? =
        withContext(IODispatcher) {
            runCatching {
                var manifest: ByteArray? = null
                val blobs = mutableMapOf<String, ByteArray>()
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val data = zip.readBytes()
                        if (entry.name == MANIFEST_NAME) {
                            manifest = data
                        } else if (!entry.isDirectory) {
                            // Zip-slip guard — reject `..` and absolute
                            // paths so a malformed bundle can't write
                            // anywhere outside our artwork dir on
                            // restore. ArtworkPersister sanitises
                            // again on the receive side.
                            val name = entry.name
                            if (!name.contains("..") &&
                                !name.startsWith("/") &&
                                !name.startsWith("\\")
                            ) {
                                blobs[name] = data
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
                val m = manifest ?: return@runCatching null
                BackupPackager.Unpacked(manifestJson = m, blobs = blobs)
            }.getOrNull()
        }

    private companion object {
        const val MANIFEST_NAME = "manifest.json"
    }
}
