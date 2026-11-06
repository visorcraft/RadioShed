package com.visorcraft.radioshed.source

/**
 * Best-effort delete of a file at [path]. Returns true when a file
 * actually existed and was removed, false otherwise (missing file,
 * directory, or platform error). Used by user-initiated cache-clear
 * actions where partial success is acceptable.
 */
internal expect fun deleteFileIfExists(path: String): Boolean
