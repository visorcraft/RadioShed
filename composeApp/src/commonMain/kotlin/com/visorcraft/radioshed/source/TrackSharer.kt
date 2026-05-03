package com.visorcraft.radioshed.source

/**
 * Hands an audio file to the platform share sheet. Used by the
 * Track Info dialog's Share action so users can send a song to
 * messaging / file-saving apps without leaving RadioShed.
 *
 * On Android the file is wrapped as a `content://` URI via
 * FileProvider so the receiving app can read it without our
 * MANAGE_EXTERNAL_STORAGE grant. iOS implementation deferred —
 * UIActivityViewController is the equivalent but needs a Mac to
 * build / test.
 */
interface TrackSharer {
    /** Returns true when the share sheet was successfully launched. */
    suspend fun share(absolutePath: String, displayName: String): Boolean
}
