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
