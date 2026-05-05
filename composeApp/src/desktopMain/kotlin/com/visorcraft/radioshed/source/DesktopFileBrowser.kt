package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Desktop FileBrowser — pure java.io.File walks. Same contract as
 * the Android impl: directories only for [listSubdirectories],
 * sorted alphabetically (case-insensitive), hidden entries excluded,
 * errors swallowed to empty lists.
 */
class DesktopFileBrowser : FileBrowser {

    override suspend fun listSubdirectories(path: String): List<DiscoveredFolder> =
        withContext(IODispatcher) {
            // Empty path = synthetic "This PC" / Computer root. Lists
            // every filesystem root the JVM advertises (drive letters
            // on Windows, `/` on POSIX). The caller renders this as
            // the top-level view; the breadcrumb shows "This PC".
            if (path.isEmpty()) {
                return@withContext computerRootEntries()
            }
            runCatching {
                val dir = File(path)
                if (!dir.isDirectory) return@runCatching emptyList<DiscoveredFolder>()
                dir.listFiles()
                    ?.asSequence()
                    ?.filter { it.isDirectory && !it.name.startsWith('.') }
                    ?.sortedBy { it.name.lowercase() }
                    ?.map { DiscoveredFolder(name = it.name, path = it.absolutePath) }
                    ?.toList()
                    ?: emptyList()
            }.getOrDefault(emptyList())
        }

    /**
     * Pseudo-folder list for the synthetic "This PC" root. Each drive
     * is rendered with its label (`Drive C:`, etc.) and absolute
     * path. Doesn't probe network shares — those need a UNC path
     * which the user pastes into the dialog's path field directly.
     */
    private fun computerRootEntries(): List<DiscoveredFolder> {
        val isWindows = System.getProperty("os.name").orEmpty().lowercase()
            .contains("win")
        return File.listRoots().orEmpty().map { root ->
            val abs = root.absolutePath
            val label = if (isWindows) {
                "Drive ${abs.removeSuffix("\\").uppercase()}"
            } else {
                abs
            }
            DiscoveredFolder(name = label, path = abs)
        }
    }

    override suspend fun listEntries(path: String): List<DirectoryEntry> =
        withContext(IODispatcher) {
            runCatching {
                val dir = File(path)
                if (!dir.isDirectory) return@runCatching emptyList<DirectoryEntry>()
                val entries = dir.listFiles().orEmpty()
                    .asSequence()
                    .filter { !it.name.startsWith('.') }
                    .map {
                        DirectoryEntry(
                            name = it.name,
                            path = it.absolutePath,
                            isDirectory = it.isDirectory,
                            sizeBytes = if (it.isFile) it.length() else 0L,
                            isAudio = !it.isDirectory && it.name.audioExtensionOrNull() != null,
                            lastModifiedMs = it.lastModified(),
                        )
                    }
                    .toList()
                entries.sortedWith(
                    compareByDescending<DirectoryEntry> { it.isDirectory }
                        .thenBy { it.name.lowercase() },
                )
            }.getOrDefault(emptyList())
        }

    override suspend fun listAudioRecursive(path: String, limit: Int): List<DirectoryEntry> =
        withContext(IODispatcher) {
            runCatching {
                val root = File(path)
                if (!root.isDirectory) return@runCatching emptyList<DirectoryEntry>()
                root.walkTopDown()
                    .filter { it.isFile && !it.name.startsWith('.') }
                    .filter { it.name.audioExtensionOrNull() != null }
                    .take(limit)
                    .map {
                        DirectoryEntry(
                            name = it.name,
                            path = it.absolutePath,
                            isDirectory = false,
                            sizeBytes = it.length(),
                            isAudio = true,
                            lastModifiedMs = it.lastModified(),
                        )
                    }
                    .sortedBy { it.path.lowercase() }
                    .toList()
            }.getOrDefault(emptyList())
        }

    override suspend fun commonLocations(): List<DiscoveredFolder> {
        val home = File(System.getProperty("user.home").orEmpty())
        val isWindows = System.getProperty("os.name").orEmpty().lowercase()
            .contains("win")
        // Per-user shortcuts come first because they're the highest-
        // hit ratio. We don't probe for existence — if the user's
        // account has no Music folder, tapping the chip just lands
        // them on an empty folder, which is clearer than hiding the
        // chip entirely.
        val homeBookmarks = listOf(
            DiscoveredFolder("Home", home.absolutePath),
            DiscoveredFolder("Music", File(home, "Music").absolutePath),
            DiscoveredFolder("Downloads", File(home, "Downloads").absolutePath),
            DiscoveredFolder("Documents", File(home, "Documents").absolutePath),
            DiscoveredFolder("Desktop", File(home, "Desktop").absolutePath),
        )
        // Drive / mount roots so the user can hop directly to D:, E:,
        // an external SSD, etc. without clicking Up repeatedly. On
        // Windows `File.listRoots()` returns every drive letter
        // (`C:\`, `D:\`, …) including ejected-ish removable mounts;
        // on POSIX it returns just `/`, which is already implicit
        // via Home, so we skip the chip there.
        val driveBookmarks = if (isWindows) {
            File.listRoots().orEmpty().mapNotNull { root ->
                val abs = root.absolutePath
                // Trim the trailing backslash for the chip label so
                // it reads "C:" not "C:\\".
                val label = abs.removeSuffix("\\").uppercase().ifBlank { abs }
                DiscoveredFolder(name = label, path = abs)
            }
        } else {
            emptyList()
        }
        // "This PC" chip teleports to the synthetic Computer view
        // (path = ""), where the folder list is every drive root
        // and the user can paste a UNC / network path into the
        // dialog's path field. Surfaced first so it's always one
        // tap away regardless of how deep the user has navigated.
        val computer = DiscoveredFolder(name = "This PC", path = "")
        return listOf(computer) + homeBookmarks + driveBookmarks
    }

    override fun parentOf(path: String): String? {
        // Empty path is the synthetic "This PC" view — already at
        // the top, no parent.
        if (path.isEmpty()) return null
        val parent = File(path).parentFile?.absolutePath
        // A drive root on Windows (`C:\`) reports null as its
        // parent. Surface the empty-path Computer view as the next
        // level up so the user can hop laterally to D: / E: / a
        // network share via the dialog's path field.
        return parent ?: ""
    }
}
