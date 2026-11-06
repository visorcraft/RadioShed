package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * java.util.zip-backed implementation of [BackupPackager].
 *
 * Compression strategy: STORED for the JSON (small + already-compact),
 * DEFLATE for artwork (saves ~5-15% on JPEGs). Actually we let the
 * stream pick the default level — JPEGs/PNGs are already compressed,
 * but the default-level overhead is negligible and the simpler code
 * path is more important than the marginal size savings.
 */
class AndroidBackupPackager : BackupPackager {
    override suspend fun pack(
        manifestJson: ByteArray,
        blobs: Map<String, ByteArray>,
    ): ByteArray? =
        withContext(IODispatcher) {
            runCatching {
                val out = ByteArrayOutputStream()
                ZipOutputStream(out).use { zip ->
                    // manifest.json comes first by convention. Some zip
                    // tools list entries in the order they were written;
                    // putting the manifest at index 0 makes a curl-driven
                    // diagnostic easier ("just unzip and read the first
                    // file").
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
                        // Read every entry into memory. Backups are
                        // bounded in practice — even a 10k-track
                        // library with embedded album art rarely
                        // produces a bundle over ~50 MB. Streaming
                        // would buy us nothing here and complicate
                        // the API.
                        val data = zip.readBytes()
                        if (entry.name == MANIFEST_NAME) {
                            manifest = data
                        } else if (!entry.isDirectory) {
                            // Defensive: reject any path that tries
                            // to escape the archive root via `..` or
                            // absolute paths. ArtworkPersister applies
                            // its own filename sanitisation on
                            // restore, but we belt-and-suspenders here
                            // because zip-slip has bitten enough
                            // libraries to be worth the four-line
                            // guard.
                            val name = entry.name
                            if (!name.contains("..") && !name.startsWith("/")) {
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
