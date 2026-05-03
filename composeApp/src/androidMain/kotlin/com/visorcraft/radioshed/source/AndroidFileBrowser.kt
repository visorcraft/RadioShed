package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android impl: walks the filesystem with [java.io.File]. With
 * `MANAGE_EXTERNAL_STORAGE` granted (see `StoragePermissions`), this can
 * read every mounted volume under `/storage/` — primary internal storage
 * (`/storage/emulated/0`), SD cards (`/storage/<UUID>`), and OTG drives.
 *
 * Without that permission, listing `/storage/` itself returns whatever
 * subset Android's scoped-storage rules grant, which is usually nothing
 * useful — the browser surfaces this as "(no subfolders)" and the rest of
 * Library Settings is already nudging the user to grant the permission.
 *
 * Navigation root is `/storage/` — going above it (i.e. into `/`) returns
 * a tree of system directories the user has no business adding as a music
 * folder, so [parentOf] caps the climb there.
 */
class AndroidFileBrowser : FileBrowser {

    override suspend fun listSubdirectories(path: String): List<DiscoveredFolder> =
        withContext(IODispatcher) {
            runCatching {
                val dir = File(path)
                if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()
                dir.listFiles { f -> f.isDirectory && !f.isHidden }
                    ?.sortedBy { it.name.lowercase() }
                    ?.map { DiscoveredFolder(name = it.name, path = it.absolutePath) }
                    .orEmpty()
            }.getOrDefault(emptyList())
        }

    override suspend fun listEntries(path: String): List<DirectoryEntry> =
        withContext(IODispatcher) {
            runCatching {
                val dir = File(path)
                if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()
                val all = dir.listFiles { f -> !f.isHidden }.orEmpty()
                val (dirs, files) = all.partition { it.isDirectory }
                val dirEntries = dirs
                    .sortedBy { it.name.lowercase() }
                    .map { DirectoryEntry(it.name, it.absolutePath, isDirectory = true) }
                val fileEntries = files
                    .sortedBy { it.name.lowercase() }
                    .map { f ->
                        DirectoryEntry(
                            name = f.name,
                            path = f.absolutePath,
                            isDirectory = false,
                            sizeBytes = runCatching { f.length() }.getOrDefault(0L),
                            isAudio = f.name.substringAfterLast('.', "")
                                .lowercase() in AUDIO_EXTENSIONS,
                        )
                    }
                dirEntries + fileEntries
            }.getOrDefault(emptyList())
        }

    override fun parentOf(path: String): String? {
        if (path == ROOT || path == "/") return null
        val parent = runCatching { File(path).parentFile?.absolutePath }.getOrNull()
            ?: return null
        // Don't navigate above /storage — there's nothing useful to a music
        // app up there (system / vendor / mnt).
        if (parent == "/" && path != ROOT) return ROOT
        return parent.takeUnless { it == "/" }
    }

    private companion object {
        /** Browser navigation root — the directory containing all volumes. */
        const val ROOT = "/storage"

        /**
         * Extensions the in-app browser tints as audio (clickable to
         * play, music-note icon). Mirror of the formats the scanner
         * accepts. Future-proof: adding a new format to the scanner
         * should add it here too so the UI stays consistent.
         */
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "aac", "flac", "ogg", "opus",
            "wav", "wma", "aiff", "aif", "ape", "alac",
        )
    }
}
