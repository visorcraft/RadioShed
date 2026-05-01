package com.visorcraft.radioshed.source

import kotlinx.coroutines.flow.Flow

/**
 * A source of music tracks. v1 ships [LocalFileSource]; future plug-ins
 * (Jellyfin, Subsonic, Plex, DLNA, SMB, cloud providers) implement the
 * same interface so stations don't care where tracks live.
 */
interface MediaSource {
    /** Stable identifier for this source (e.g. `local:42` or `jellyfin:server-x`). */
    val id: String

    /** Human-readable label. */
    val displayName: String

    /** What this source can do. Lets the UI hide irrelevant features. */
    val capabilities: SourceCapabilities

    /**
     * Lazily emits every track this source contains. Cold flow — collection
     * starts the enumeration. Suitable for huge libraries: nothing is held
     * in memory beyond the consumer's processing window.
     */
    fun listTracks(filter: SourceFilter = SourceFilter()): Flow<DiscoveredTrack>
}

data class SourceCapabilities(
    val supportsSearch: Boolean = false,
    val supportsStreaming: Boolean = false,
    val supportsPlaylists: Boolean = false,
    val supportsArtwork: Boolean = false,
)

/**
 * Constraints applied during enumeration. Empty values mean "no constraint."
 */
data class SourceFilter(
    // Lowercase format extensions to include (e.g. ["mp3", "flac"]). Empty = all known audio formats.
    val formats: List<String> = emptyList(),
    // Glob patterns to include (e.g. "Music/**"). Empty = include all.
    val includeGlobs: List<String> = emptyList(),
    // Glob patterns to exclude (e.g. "Spoken Word/**").
    val excludeGlobs: List<String> = emptyList(),
    /** Minimum track duration in milliseconds; null = no minimum. */
    val minDurationMs: Long? = null,
    /** Maximum track duration in milliseconds; null = no maximum. */
    val maxDurationMs: Long? = null,
)

/**
 * A track surfaced by a [MediaSource] before tag parsing. Just enough
 * info to identify and triage the file; full metadata comes from
 * [TagReader.read].
 */
data class DiscoveredTrack(
    /** Platform-specific URI / absolute path / opaque identifier. */
    val uri: String,
    val fileName: String,
    val fileSize: Long,
    /** Epoch millis. Best-effort — falls back to mtime if ctime unavailable. */
    val fileCtime: Long,
    /** Epoch millis. */
    val fileMtime: Long,
    /** Lowercase extension without dot, e.g. `"flac"`. */
    val format: String,
)

/** Extensions we recognize as audio. Used by enumerators to pre-filter. */
internal val SUPPORTED_AUDIO_EXTENSIONS: Set<String> = setOf(
    "mp3", "m4a", "aac", "flac", "alac", "ogg", "oga", "opus",
    "wav", "wave", "aiff", "aif", "wma", "ape", "wv", "tta",
    "dsf", "dff", "mpc", "mp+",
)

internal val SUPPORTED_CUE_EXTENSIONS: Set<String> = setOf("cue")

internal fun String.audioExtensionOrNull(): String? {
    val ext = substringAfterLast('.', "").lowercase()
    return if (ext.isNotEmpty() && ext in SUPPORTED_AUDIO_EXTENSIONS) ext else null
}
