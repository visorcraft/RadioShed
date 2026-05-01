package com.visorcraft.radioshed.source

import com.visorcraft.radioshed.data.IODispatcher
import com.visorcraft.radioshed.domain.Personnel
import com.visorcraft.radioshed.domain.Tag
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.AudioHeader
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag as JTag
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * jaudiotagger-backed tag reader. Handles MP3 (ID3v1/v2.3/v2.4), FLAC + Vorbis
 * comments, Opus, OGG Vorbis, M4A/AAC (iTunes-style atoms), WAV, AIFF, WMA.
 *
 * Errors are swallowed and surfaced as `null` — the scanner skips files that
 * can't be parsed rather than aborting the whole scan.
 */
class AndroidTagReader : TagReader {

    init {
        // jaudiotagger logs at INFO/FINER for every successful read. Mute it.
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    override suspend fun read(uri: String): ParsedTags? = withContext(IODispatcher) {
        val file = File(uri)
        if (!file.exists() || !file.canRead()) return@withContext null
        runCatching {
            val audioFile = AudioFileIO.read(file)
            val header: AudioHeader? = audioFile.audioHeader
            val tag: JTag? = audioFile.tag
            buildParsedTags(header, tag)
        }.getOrNull()
    }

    private fun buildParsedTags(header: AudioHeader?, tag: JTag?): ParsedTags {
        val customTags = mutableListOf<Tag>()
        val personnel = mutableListOf<Personnel>()

        // Multi-value artists -> custom tags so we don't lose them
        if (tag != null) {
            collectMultiValue(tag, FieldKey.ARTIST, "ARTIST", customTags)
            collectMultiValue(tag, FieldKey.ALBUM_ARTIST, "ALBUM_ARTIST", customTags)
            collectMultiValue(tag, FieldKey.GENRE, "GENRE", customTags)
            collectMultiValue(tag, FieldKey.MOOD, "MOOD", customTags)
            collectMultiValue(tag, FieldKey.LANGUAGE, "LANGUAGE", customTags)
            collectMultiValue(tag, FieldKey.RECORD_LABEL, "LABEL", customTags)
            collectMultiValue(tag, FieldKey.GROUPING, "GROUPING", customTags)

            // Personnel-style fields — stored separately for clean queries
            collectPersonnel(tag, FieldKey.COMPOSER, "composer", personnel)
            collectPersonnel(tag, FieldKey.CONDUCTOR, "conductor", personnel)
            collectPersonnel(tag, FieldKey.PRODUCER, "producer", personnel)
            collectPersonnel(tag, FieldKey.ARRANGER, "arranger", personnel)
            collectPersonnel(tag, FieldKey.LYRICIST, "lyricist", personnel)
            collectPersonnel(tag, FieldKey.REMIXER, "remixer", personnel)
        }

        return ParsedTags(
            title = tag?.firstOrNull(FieldKey.TITLE),
            artist = tag?.firstOrNull(FieldKey.ARTIST),
            albumArtist = tag?.firstOrNull(FieldKey.ALBUM_ARTIST),
            album = tag?.firstOrNull(FieldKey.ALBUM),
            year = tag?.firstOrNull(FieldKey.YEAR)?.take(4)?.toIntOrNull(),
            trackNumber = tag?.firstOrNull(FieldKey.TRACK)?.substringBefore('/')?.toIntOrNull(),
            discNumber = tag?.firstOrNull(FieldKey.DISC_NO)?.substringBefore('/')?.toIntOrNull(),
            genre = tag?.firstOrNull(FieldKey.GENRE),
            composer = tag?.firstOrNull(FieldKey.COMPOSER),
            durationMs = (header?.preciseTrackLength?.let { (it * 1000L).toLong() }
                ?: header?.trackLength?.toLong()?.let { it * 1000L } ?: 0L),
            bitrateKbps = header?.bitRateAsNumber?.toInt()?.takeIf { it > 0 },
            sampleRateHz = header?.sampleRateAsNumber?.takeIf { it > 0 },
            channels = header?.channels?.let { parseChannels(it) },
            bpm = tag?.firstOrNull(FieldKey.BPM)?.toDoubleOrNull(),
            musicbrainzTrackId = tag?.firstOrNull(FieldKey.MUSICBRAINZ_TRACK_ID),
            musicbrainzAlbumId = tag?.firstOrNull(FieldKey.MUSICBRAINZ_RELEASEID),
            musicbrainzArtistId = tag?.firstOrNull(FieldKey.MUSICBRAINZ_ARTISTID),
            replayGainTrack = parseReplayGainGain(tag?.firstOrNull(FieldKey.RATING)),
            replayGainAlbum = null, // jaudiotagger surfaces RG via custom keys; safer to skip in v1
            replayGainTrackPeak = null,
            customTags = customTags,
            personnel = personnel,
        )
    }

    private fun JTag.firstOrNull(key: FieldKey): String? =
        runCatching { getFirst(key)?.takeUnless { it.isBlank() } }.getOrNull()

    private fun collectMultiValue(
        tag: JTag,
        key: FieldKey,
        canonicalKey: String,
        sink: MutableList<Tag>,
    ) {
        val all = runCatching { tag.getAll(key) }.getOrNull().orEmpty()
        // jaudiotagger sometimes returns the joined-string in [0] when the
        // underlying format only stores one value; treat that as "one value"
        // and skip the position bookkeeping. For multi-value formats (FLAC
        // Vorbis) each entry is a real distinct value.
        all.filter { it.isNotBlank() }
            .forEachIndexed { idx, value -> sink += Tag(canonicalKey, value, idx) }
    }

    private fun collectPersonnel(
        tag: JTag,
        key: FieldKey,
        role: String,
        sink: MutableList<Personnel>,
    ) {
        val all = runCatching { tag.getAll(key) }.getOrNull().orEmpty()
        all.filter { it.isNotBlank() }
            .forEachIndexed { idx, name -> sink += Personnel(role, name, idx) }
    }

    private fun parseChannels(s: String): Int? = when (s.lowercase().trim()) {
        "mono", "1" -> 1
        "stereo", "2" -> 2
        "5.1", "6 channels", "6" -> 6
        "7.1", "8 channels", "8" -> 8
        else -> s.toIntOrNull()
    }

    /**
     * ReplayGain values are stored as strings like `"-3.42 dB"` in tags.
     * Strip the unit and parse. Returns null on failure (no replaygain
     * present, or unparseable).
     */
    private fun parseReplayGainGain(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.replace(Regex("(?i)\\s*db\\s*$"), "").trim()
        return cleaned.toDoubleOrNull()
    }
}
