package com.visorcraft.radioshed.source

/**
 * Bundles the JSON manifest and binary blobs (artwork) into a single
 * archive file. Counterpart to the in-memory `BackupBundle` model —
 * [BackupPackager] handles only the *transport* (zip in / out), not
 * the data model.
 *
 * Why an interface (rather than direct `java.util.zip` use in
 * `BackupService`):
 *  - iOS doesn't have `java.util.zip`. Routing through
 *    expect/actual would mean `BackupService` itself needs a
 *    platform-specific peer.
 *  - Future encrypted bundles (the deferred AES-256-GCM
 *    backup-passphrase feature) slot in here as a different
 *    [BackupPackager] impl rather than a parallel code path through
 *    [BackupService].
 *
 * Format (v1):
 *  - First entry: `manifest.json` — UTF-8 JSON of [BackupBundle].
 *  - Remaining entries: `artwork/<filename>` — raw image bytes,
 *    filename is the content hash so they round-trip into the same
 *    on-disk slot via [ArtworkPersister.restoreBlobs].
 *  - File extension: `.rsbak` (RadioShed backup). Plain `.json`
 *    bundles still work — see [BackupService.restoreFromBytes] which
 *    detects the format by peeking the leading bytes.
 */
interface BackupPackager {
    /**
     * Produce a packaged byte array containing the manifest plus each
     * blob keyed by its archive entry name. Returns null on
     * unrecoverable failure (out-of-memory, IO error). Implementations
     * MUST NOT throw — a packaging failure should fall back to plain
     * JSON.
     */
    suspend fun pack(
        manifestJson: ByteArray,
        blobs: Map<String, ByteArray>,
    ): ByteArray?

    /**
     * Reverse of [pack]. Returns null if the bytes don't look like
     * the expected format (caller will fall back to plain-JSON
     * decoding). On success, [Unpacked.manifestJson] holds the
     * extracted manifest and [Unpacked.blobs] maps `artwork/<filename>`
     * → bytes for caller to hand to [ArtworkPersister.restoreBlobs].
     */
    suspend fun unpack(bytes: ByteArray): Unpacked?

    data class Unpacked(
        val manifestJson: ByteArray,
        val blobs: Map<String, ByteArray>,
    )
}

/**
 * Cheap zip-magic peek that lets [BackupService] decide whether to
 * route through [BackupPackager.unpack] or the plain-JSON parser.
 * The check is the standard PKZip local file header: `PK\x03\x04`.
 * False positives are essentially impossible — JSON never starts
 * with these four bytes (or any 0x03/0x04 control bytes).
 */
fun looksLikeZip(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
        bytes[0] == 0x50.toByte() &&
        bytes[1] == 0x4B.toByte() &&
        bytes[2] == 0x03.toByte() &&
        bytes[3] == 0x04.toByte()

/**
 * No-op packager for platforms that haven't implemented zip-IO yet
 * (iOS today). Returning null from [pack] is the signal
 * [BackupService] uses to fall back to plain-JSON output.
 */
class NullBackupPackager : BackupPackager {
    override suspend fun pack(
        manifestJson: ByteArray,
        blobs: Map<String, ByteArray>,
    ): ByteArray? = null

    override suspend fun unpack(bytes: ByteArray): BackupPackager.Unpacked? = null
}
