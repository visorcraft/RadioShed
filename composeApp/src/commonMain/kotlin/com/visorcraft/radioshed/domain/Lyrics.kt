package com.visorcraft.radioshed.domain

/**
 * Resolved lyrics for a track. Either or both of [synced] and [plain]
 * may be present; the UI prefers synced when available (auto-scroll +
 * highlight) and falls back to plain text otherwise.
 *
 * [instrumental] is set true when the lyrics provider explicitly told
 * us "this track has no vocals" — distinct from "we don't have lyrics".
 * The UI shows a "♪ Instrumental" pill instead of "Lyrics not found".
 */
data class Lyrics(
    val trackId: Long,
    val synced: List<LyricLine>?,
    val plain: String?,
    val instrumental: Boolean = false,
    val source: String,
) {
    val hasContent: Boolean
        get() = !synced.isNullOrEmpty() || !plain.isNullOrBlank() || instrumental
}

/** One synced line: text appears at [timeMs] and stays until the next line. */
data class LyricLine(val timeMs: Long, val text: String)
