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
                    .map {
                        DirectoryEntry(
                            name = it.name,
                            path = it.absolutePath,
                            isDirectory = true,
                            lastModifiedMs = runCatching { it.lastModified() }.getOrDefault(0L),
                        )
                    }
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
                            lastModifiedMs = runCatching { f.lastModified() }.getOrDefault(0L),
                        )
                    }
                dirEntries + fileEntries
            }.getOrDefault(emptyList())
        }

    override suspend fun listAudioRecursive(path: String, limit: Int): List<DirectoryEntry> =
        withContext(IODispatcher) {
            runCatching {
                val root = File(path)
                if (!root.exists() || !root.isDirectory) return@withContext emptyList()
                val out = mutableListOf<DirectoryEntry>()
                // BFS keeps the queue order roughly directory-by-
                // directory. DFS via File.walk would interleave nested
                // dirs with parent-level files — fine for some uses,
                // worse for "play this whole album folder" since the
                // album's own files would be split by subdir traversal.
                val queue = ArrayDeque<File>()
                queue.add(root)
                while (queue.isNotEmpty() && out.size < limit) {
                    val dir = queue.removeFirst()
                    val children = runCatching { dir.listFiles() }.getOrNull().orEmpty()
                    val (subdirs, files) = children
                        .filter { !it.isHidden }
                        .partition { it.isDirectory }
                    for (f in files.sortedBy { it.name.lowercase() }) {
                        if (out.size >= limit) break
                        val ext = f.name.substringAfterLast('.', "").lowercase()
                        if (ext in AUDIO_EXTENSIONS) {
                            out += DirectoryEntry(
                                name = f.name,
                                path = f.absolutePath,
                                isDirectory = false,
                                sizeBytes = runCatching { f.length() }.getOrDefault(0L),
                                isAudio = true,
                                lastModifiedMs = runCatching { f.lastModified() }.getOrDefault(0L),
                            )
                        }
                    }
                    for (sub in subdirs.sortedBy { it.name.lowercase() }) {
                        queue.addLast(sub)
                    }
                }
                out
            }.getOrDefault(emptyList())
        }

    override suspend fun commonLocations(): List<DiscoveredFolder> = withContext(IODispatcher) {
        // Storage root + standard Music / Downloads. We probe each
        // path's existence so we don't show dead chips on devices
        // that don't have those directories (some custom Android
        // forks rename Music to MyFiles/Music, etc.).
        val externalRoot = android.os.Environment.getExternalStorageDirectory().absolutePath
        val candidates = listOf(
            "Storage" to ROOT,
            "Internal" to externalRoot,
            "Music" to "$externalRoot/Music",
            "Downloads" to "$externalRoot/Download",
        )
        candidates.mapNotNull { (label, path) ->
            if (runCatching { File(path).exists() }.getOrDefault(false)) {
                DiscoveredFolder(name = label, path = path)
            } else null
        }
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
