package com.visorcraft.radioshed.source

/**
 * Writes embedded album-art bytes (extracted by [TagReader]) to a stable,
 * cacheable on-disk location and returns a URI the player + UI can use.
 *
 * Implementation contract:
 *  - The same input bytes MUST yield the same returned URI across calls
 *    (content-addressed storage — typically SHA-256 of the bytes as the
 *    filename). This means duplicate art (e.g. embedded in every track of
 *    an album) is written exactly once per scan; subsequent calls just
 *    return the same path.
 *  - Returns null if the bytes can't be persisted (I/O error, no writable
 *    cache dir). The scanner treats null as "leave the album row's
 *    artwork_uri alone" — better than overwriting with a broken pointer.
 *  - URIs returned should be loadable by Coil / ExoPlayer without extra
 *    permission grants. On Android that means `file://` paths under
 *    `Context.filesDir` (the app's private storage), which the app can
 *    read without `MANAGE_EXTERNAL_STORAGE`.
 */
interface ArtworkPersister {
    /** Persist the bytes and return a URI to load them, or null on failure. */
    suspend fun persist(bytes: ByteArray): String?

    /**
     * Wipe every previously-persisted artwork blob (the user-facing
     * "Clear artwork cache" reset path). Returns the number of files
     * removed — best effort, individual delete failures are swallowed
     * since the goal is recovery, not a per-file audit. After clearing
     * the disk, callers MUST also clear `album.artwork_uri` and the
     * `artwork_cache` DB table; the previously-returned URIs all point
     * at deleted files.
     */
    suspend fun clearAll(): Int

    /**
     * Enumerate every persisted blob as `(filename, bytes)`. Default
     * impl returns empty for platforms that don't implement filesystem
     * walks yet (iOS). Filename is the on-disk basename — typically
     * `<sha256>.<ext>` — and round-trips via [restoreBlobs] back to
     * the same content-addressed slot.
     *
     * Used by [BackupService] to snapshot the artwork cache into a
     * `.rsbak` zip alongside the JSON manifest. A backup with no
     * artwork falls back to the JSON-only format.
     */
    suspend fun listBlobs(): List<Pair<String, ByteArray>> = emptyList()

    /**
     * Restore previously-bundled blobs back to disk under the same
     * filenames they were saved under. Returns the number of blobs
     * actually written. Implementations MUST sanitise filenames to
     * reject any path that tries to escape the artwork dir
     * (`/foo`, `../etc/passwd`, etc).
     *
     * Default impl is a no-op for platforms that don't yet implement
     * blob restore (iOS today).
     */
    suspend fun restoreBlobs(blobs: Map<String, ByteArray>): Int = 0
}
