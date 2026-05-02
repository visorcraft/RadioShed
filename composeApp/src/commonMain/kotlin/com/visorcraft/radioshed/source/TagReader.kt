package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.domain.Personnel
import com.visorcraft.radioshed.domain.Tag

/**
 * Pulls metadata from an audio file. Per-platform impls wrap whatever native
 * library reads tags well on that platform (jaudiotagger on Android,
 * AVAsset+custom parsers on iOS).
 */
interface TagReader {
    /**
     * Reads tags from the file at [uri]. Returns null if the file cannot
     * be opened or has no readable header. Implementations MUST NOT throw
     * on bad files — a corrupted MP3 should yield null, not crash the scan.
     */
    suspend fun read(uri: String): ParsedTags?
}

/**
 * Tags + audio-format info extracted from a file. Every field is optional
 * because real-world libraries are messy: untagged MP3s, weird FLACs, etc.
 *
 * Multi-value fields (multiple artists, multiple genres) and "personnel"
 * fields (composer, conductor, performer) survive in [customTags] and
 * [personnel] respectively rather than being collapsed.
 */
data class ParsedTags(
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val genre: String? = null,
    val composer: String? = null,
    val durationMs: Long = 0,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val bpm: Double? = null,
    val musicbrainzTrackId: String? = null,
    val musicbrainzAlbumId: String? = null,
    val musicbrainzArtistId: String? = null,
    val replayGainTrack: Double? = null,
    val replayGainAlbum: Double? = null,
    val replayGainTrackPeak: Double? = null,
    /** Multi-value tags, custom TXXX fields, etc. — preserves ordering. */
    val customTags: List<Tag> = emptyList(),
    /** Composers, conductors, performers — keyed by role. */
    val personnel: List<Personnel> = emptyList(),
    /**
     * Embedded album art (cover.jpg / APIC frame / FLAC METADATA_BLOCK_PICTURE).
     * Null when the file has none. Equality on this field is reference-based
     * intentionally — comparing 50KB byte arrays for `==` is too expensive
     * and we don't compare ParsedTags instances by value anywhere.
     */
    val artworkBytes: ByteArray? = null,
    /**
     * Embedded lyrics text — USLT (Unsynchronised Lyrics) for ID3v2,
     * `LYRICS` Vorbis comment for FLAC/Opus/OGG, `©lyr` atom for M4A.
     * jaudiotagger's `FieldKey.LYRICS` flattens all of these into one
     * field. Plain text only — synced (SYLT) lyrics aren't surfaced
     * by FieldKey.LYRICS and are deferred. Null when the file has no
     * lyrics tag.
     */
    val embeddedLyrics: String? = null,
)
