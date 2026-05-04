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
}
