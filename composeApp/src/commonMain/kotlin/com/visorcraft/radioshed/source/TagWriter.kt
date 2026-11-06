package com.visorcraft.radioshed.source

/**
 * Writes basic tags back to an audio file. Counterpart to [TagReader],
 * but write-only. Implementations:
 *  - Android: jaudiotagger-backed write of ID3v2 / Vorbis / MP4 frames.
 *  - iOS: stub that returns [Result.failure] (write requires a Mac to
 *    integrate AVAsset / custom mutators).
 *
 * Scope (v1) intentionally narrow: title / artist / album / genre /
 * year / track number / disc number. Multi-value tags, custom TXXX
 * fields, lyrics writes, replay-gain writes are out of scope — they
 * live in the deferred "advanced tag editor" feature.
 *
 * After a successful write the caller is responsible for re-scanning
 * the track's folder so the library DB picks up the new metadata —
 * `Scanner.scanFolder(folderId)` is the canonical refresh path.
 */
interface TagWriter {
    /**
     * Apply [patch] to the file at [uri]. Returns success or a
     * descriptive failure (file not writable, format unsupported, etc).
     * Implementations MUST NOT throw on invalid input — bad files
     * surface as a failure result, not a crash.
     */
    suspend fun write(
        uri: String,
        patch: TagPatch,
    ): Result<Unit>
}

/**
 * Subset of tag fields the in-app editor exposes. Every field is
 * optional: a null entry leaves the existing tag untouched, so the
 * editor can change just the title without erasing artist/album/etc.
 * Use [Update] to set a value (including empty string to clear) and
 * leave the field unset to preserve the existing value.
 */
data class TagPatch(
    val title: Update<String>? = null,
    val artist: Update<String>? = null,
    val album: Update<String>? = null,
    val albumArtist: Update<String>? = null,
    val genre: Update<String>? = null,
    val year: Update<Int>? = null,
    val trackNumber: Update<Int>? = null,
    val discNumber: Update<Int>? = null,
) {
    /**
     * "Set this field to [value]". The wrapper exists so a null
     * outer field means "leave alone" while `Update("")` means
     * "clear it" — important because some tag containers
     * differentiate empty-string from absent.
     */
    data class Update<T>(val value: T)
}
