package com.visorcraft.radioshed.source

/**
 * Opens the system file manager pointed at the parent directory of a
 * track file, so users with deeply nested libraries can locate the
 * actual on-disk file behind a track. Used by the Track Info dialog's
 * "Open folder" action.
 *
 * Returns true when a file manager picked up the intent. Returns
 * false if the platform either has no compatible viewer (no Files
 * app installed) or doesn't support the operation (iOS, where there's
 * no programmatic equivalent — the Files app is sandboxed per-app).
 *
 * Implementations:
 *  - **Android**: builds a `DocumentsContract` document URI from the
 *    primary external storage path and launches `ACTION_VIEW` with
 *    `vnd.android.document/directory`. The stock Files app handles
 *    that and opens at the directory.
 *  - **iOS**: stub — null/false.
 */
interface FolderRevealer {
    suspend fun revealParent(absolutePath: String): Boolean
}
