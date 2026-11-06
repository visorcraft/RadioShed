package com.visorcraft.radioshed.domain

import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val id: Long,
    val path: String,
    val fileName: String,
    val fileSize: Long,
    val fileCtime: Long,
    val fileMtime: Long,
    val title: String,
    val albumId: Long? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationMs: Long,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val format: String,
    val replayGainTrack: Double? = null,
    val replayGainAlbum: Double? = null,
    val replayGainTrackPeak: Double? = null,
    val bpm: Double? = null,
    val musicbrainzId: String? = null,
    val acoustidFingerprint: String? = null,
    val cueOffsetStartMs: Long? = null,
    val cueOffsetEndMs: Long? = null,
    val isAvailable: Boolean = true,
    val addedAt: Long,
    val updatedAt: Long,
) {
    /** Title for display. Square-bracket groups such as YouTube ids are hidden. */
    fun displayTitle(): String = title.ifBlank { fileName }.withoutBracketGroups()
}

private val bracketGroup = Regex("""\[[^\[\]]*\]""")

/**
 * Remove square-bracket groups and the extra space they leave.
 * Parentheses stay, because those are often part of the title.
 * A title that is only a bracket group is returned unchanged.
 */
fun String.withoutBracketGroups(): String {
    if ('[' !in this) return this
    var work = this
    repeat(4) {
        val next = bracketGroup.replace(work, "")
        if (next == work) return@repeat
        work = next
    }
    val cleaned =
        work
            .replace(Regex("""\s+\."""), ".")
            .replace(Regex("""[ \t]{2,}"""), " ")
            .trim()
    return cleaned.ifBlank { trim() }
}

@Serializable
data class Tag(val key: String, val value: String, val position: Int = 0)

@Serializable
data class Personnel(val role: String, val name: String, val position: Int = 0)
