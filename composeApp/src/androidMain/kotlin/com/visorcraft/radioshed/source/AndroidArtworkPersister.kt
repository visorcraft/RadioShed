package com.visorcraft.radioshed.source

import android.content.Context
import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Android impl: writes art bytes to `<filesDir>/artwork/<sha256>.bin`.
 *
 * Why `filesDir` and not `cacheDir`: the OS may evict cacheDir under
 * pressure, which would orphan every album's artwork_uri pointer. We'd
 * rather take the (modest) disk-space hit and have stable URIs across
 * sessions. A future cleanup pass driven by [com.visorcraft.radioshed.data.ArtworkCacheRepository]
 * can prune unused entries based on `last_accessed_at`.
 *
 * Filenames are SHA-256 hex without a file extension because we don't
 * care what the original format was (Coil sniffs the magic bytes). Same
 * bytes ⇒ same hash ⇒ same file ⇒ duplicate art across every track of an
 * album is stored once on disk.
 */
class AndroidArtworkPersister(private val context: Context) : ArtworkPersister {

    private val artworkDir: File by lazy {
        File(context.filesDir, "artwork").also { it.mkdirs() }
    }

    override suspend fun persist(bytes: ByteArray): String? = withContext(IODispatcher) {
        if (bytes.isEmpty()) return@withContext null
        runCatching {
            val hash = sha256Hex(bytes)
            val target = File(artworkDir, hash)
            if (!target.exists() || target.length().toInt() != bytes.size) {
                // Write atomically: tmp → rename. Avoids leaving a half-written
                // file readable if the process dies mid-write (unlikely on a
                // ~50KB image, but cheap to do right).
                val tmp = File(artworkDir, "$hash.tmp")
                try {
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(target)) {
                        // renameTo can fail across filesystems or when the
                        // target was created by a concurrent persist of the
                        // same bytes. Fall through to verify the (already-
                        // existing) target — its sha256 matches by
                        // construction.
                        if (!target.exists() || target.length().toInt() != bytes.size) {
                            return@runCatching null
                        }
                    }
                } finally {
                    // Always clean up the tmp file: writeBytes can throw
                    // mid-write on disk-full, leaving a partial .tmp on
                    // disk. Without this finally, a series of disk-full
                    // attempts would accumulate orphan .tmp files in
                    // filesDir/artwork/, making the disk-full condition
                    // self-reinforcing.
                    if (tmp.exists()) tmp.delete()
                }
            }
            "file://${target.absolutePath}"
        }.getOrNull()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
