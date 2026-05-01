package com.visorcraft.radioshed.source

/**
 * Minimal CUE-sheet parser. Handles the cases we actually see in the wild:
 * single-FILE FLAC/APE/WV rips, optional PERFORMER/TITLE at album and track
 * levels, INDEX 01 timestamps, ISRC, and REM-prefixed comments (ignored).
 *
 * Not handled (intentionally for v1):
 * - PREGAP / POSTGAP
 * - Multi-FILE cues that point at multiple audio files
 * - Catalog numbers / FLAGS / CDTEXTFILE
 * - INDEX values other than 01 (some rips include 00 for pregap; we ignore)
 *
 * Frame math: SQLite-friendly milliseconds. CUE format is `MM:SS:FF` where
 * `FF` is a frame index 0..74 (75 frames per second on Red Book CDDA).
 *
 * Pure-Kotlin, commonMain. No platform dependencies.
 */
data class CueSheet(
    val performer: String? = null,
    val title: String? = null,
    val files: List<CueFile> = emptyList(),
)

data class CueFile(
    val fileName: String,
    val fileType: String, // WAVE, MP3, AIFF, BINARY, FLAC, APE
    val tracks: List<CueTrack>,
)

data class CueTrack(
    val number: Int,
    val type: String, // typically "AUDIO"
    val title: String? = null,
    val performer: String? = null,
    val isrc: String? = null,
    /** Start offset within the file, milliseconds. */
    val startMs: Long,
    /** End offset within the file, milliseconds. Null if it's the last track. */
    val endMs: Long? = null,
)

object CueParser {

    /**
     * Parse a cue-sheet. Returns a sheet with whatever could be salvaged;
     * malformed lines are silently skipped rather than aborting.
     */
    fun parse(text: String): CueSheet {
        var albumPerformer: String? = null
        var albumTitle: String? = null
        val files = mutableListOf<CueFile>()

        var currentFileName: String? = null
        var currentFileType: String? = null
        val currentTracks = mutableListOf<MutableTrackBuilder>()
        var currentTrack: MutableTrackBuilder? = null

        fun finalizeFile() {
            val fn = currentFileName ?: return
            val ft = currentFileType ?: "WAVE"
            // Compute end times: track[i].endMs = track[i+1].startMs
            val builders = currentTracks.toList()
            val finalized = builders.mapIndexed { idx, b ->
                val nextStart = builders.getOrNull(idx + 1)?.startMs
                CueTrack(
                    number = b.number,
                    type = b.type,
                    title = b.title,
                    performer = b.performer ?: albumPerformer,
                    isrc = b.isrc,
                    startMs = b.startMs ?: 0,
                    endMs = nextStart,
                )
            }
            files += CueFile(fileName = fn, fileType = ft, tracks = finalized)
            currentFileName = null
            currentFileType = null
            currentTracks.clear()
            currentTrack = null
        }

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("REM ", ignoreCase = true) || line.equals("REM", ignoreCase = true)) {
                return@forEach
            }
            val tokens = tokenize(line)
            if (tokens.isEmpty()) return@forEach
            val cmd = tokens[0].uppercase()
            when (cmd) {
                "PERFORMER" -> {
                    val value = tokens.getOrNull(1) ?: return@forEach
                    if (currentTrack != null) currentTrack!!.performer = value
                    else albumPerformer = value
                }
                "TITLE" -> {
                    val value = tokens.getOrNull(1) ?: return@forEach
                    if (currentTrack != null) currentTrack!!.title = value
                    else albumTitle = value
                }
                "FILE" -> {
                    finalizeFile()
                    currentFileName = tokens.getOrNull(1)
                    currentFileType = tokens.getOrNull(2)?.uppercase() ?: "WAVE"
                }
                "TRACK" -> {
                    val number = tokens.getOrNull(1)?.toIntOrNull() ?: return@forEach
                    val type = tokens.getOrNull(2) ?: "AUDIO"
                    val builder = MutableTrackBuilder(number = number, type = type.uppercase())
                    currentTracks += builder
                    currentTrack = builder
                }
                "INDEX" -> {
                    val indexNumber = tokens.getOrNull(1)?.toIntOrNull() ?: return@forEach
                    val timestamp = tokens.getOrNull(2) ?: return@forEach
                    if (indexNumber == 1) currentTrack?.startMs = parseTimestampToMs(timestamp)
                    // INDEX 00 (pregap) ignored for v1
                }
                "ISRC" -> {
                    currentTrack?.isrc = tokens.getOrNull(1)
                }
                // CATALOG, FLAGS, PREGAP, POSTGAP, CDTEXTFILE, SONGWRITER — ignored for v1
            }
        }
        finalizeFile()

        return CueSheet(
            performer = albumPerformer,
            title = albumTitle,
            files = files,
        )
    }

    private class MutableTrackBuilder(
        val number: Int,
        val type: String,
        var title: String? = null,
        var performer: String? = null,
        var isrc: String? = null,
        var startMs: Long? = null,
    )

    /**
     * Tokenizes a cue line, respecting double-quoted strings. Quoted strings
     * keep spaces; unquoted tokens split on whitespace. Backslash escapes
     * inside quotes are not standard CUE so we don't honor them.
     */
    internal fun tokenize(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> {
                    if (inQuotes) {
                        tokens += cur.toString()
                        cur.clear()
                        inQuotes = false
                    } else {
                        if (cur.isNotEmpty()) {
                            tokens += cur.toString()
                            cur.clear()
                        }
                        inQuotes = true
                    }
                }
                ch.isWhitespace() && !inQuotes -> {
                    if (cur.isNotEmpty()) {
                        tokens += cur.toString()
                        cur.clear()
                    }
                }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) tokens += cur.toString()
        return tokens
    }

    /**
     * Parse `MM:SS:FF` to milliseconds. 75 frames per second is the CDDA
     * standard. Returns 0 on malformed input.
     */
    internal fun parseTimestampToMs(s: String): Long {
        val parts = s.split(':')
        if (parts.size != 3) return 0L
        val mm = parts[0].toLongOrNull() ?: return 0L
        val ss = parts[1].toLongOrNull() ?: return 0L
        val ff = parts[2].toLongOrNull() ?: return 0L
        return (mm * 60L + ss) * 1000L + (ff * 1000L) / 75L
    }
}
