package com.visorcraft.radioshed.source

/**
 * Cross-platform filesystem browser for the "pick a music folder" UX. Lets
 * users navigate by tapping rather than typing a path like
 * `/storage/04FF-FCEC/Music`.
 *
 * Implementation contract:
 *  - [listSubdirectories] returns ONLY directories — files are filtered.
 *  - Returned entries are sorted alphabetically (case-insensitive) for
 *    stable scrolling.
 *  - Hidden directories (starting with `.`) are excluded.
 *  - Errors (path doesn't exist, isn't readable, isn't a directory) result
 *    in an empty list — never throw. The browser UI shows "(no subfolders)"
 *    in that case rather than an error toast.
 */
interface FileBrowser {
    suspend fun listSubdirectories(path: String): List<DiscoveredFolder>

    /**
     * Full directory listing — both subdirectories AND files — used by
     * the in-app folder browser ("Open folder" from Track Info). The
     * separate [listSubdirectories] stays for the existing folder-picker
     * UX where files would be noise. Caller decides whether to filter
     * by extension or display everything.
     *
     * Sorted alphabetically (case-insensitive), directories first, then
     * files. Hidden entries (starting with `.`) are excluded.
     */
    suspend fun listEntries(path: String): List<DirectoryEntry> = emptyList()

    /**
     * Recursive walk that returns every audio file under [path]. Used
     * by the file manager's "Play all music here" / "Shuffle all"
     * folder-long-press actions. Capped at [limit] so a tap on
     * `/storage/emulated/0` doesn't try to enumerate 100k files.
     * Sorted alphabetically by full path so the queue plays in
     * directory-walk order.
     */
    suspend fun listAudioRecursive(
        path: String,
        limit: Int = 5_000,
    ): List<DirectoryEntry> = emptyList()

    /**
     * Common known-good navigation jumping points for the file
     * manager's bookmark bar — primary external storage root, the
     * default Music folder, Downloads, etc. Each entry has a display
     * label and an absolute path. Empty list on platforms without
     * standardized locations (iOS).
     */
    suspend fun commonLocations(): List<DiscoveredFolder> = emptyList()

    /**
     * The directory containing [path], or null if [path] is at a navigation
     * root (the system-storage root or filesystem root). The browser uses
     * this to decide whether to show an "up" affordance.
     */
    fun parentOf(path: String): String?
}

data class DiscoveredFolder(
    /** Just the directory name, no leading path. */
    val name: String,
    /** Absolute filesystem path. */
    val path: String,
)

/**
 * One entry in a directory listing — either a subdirectory or a file.
 * Files carry [sizeBytes] and [isAudio] so the UI can show a size hint
 * and tint music files distinctly. Audio detection is by extension; we
 * don't sniff magic bytes for the browser.
 */
data class DirectoryEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val isAudio: Boolean = false,
    /** Last-modified epoch ms; 0 when unknown or directory. */
    val lastModifiedMs: Long = 0L,
)
