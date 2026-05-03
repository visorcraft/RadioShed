package com.visorcraft.radioshed.source

/**
 * Generates a sequence of (artist, title) query candidates for a
 * single track. LRClib is strict about exact matches, so a track
 * tagged "Song (Remastered 2008)" by "Artist feat. Other" will miss
 * the canonical entry for "Song" by "Artist". We try a few fallbacks
 * before giving up:
 *
 *  1. Tagged artist + tagged title verbatim.
 *  2. Tagged artist + title with trailing parenthetical removed
 *     ("Song (Remastered)" → "Song").
 *  3. Primary-artist-only + tagged title (drops "feat. X" / ", X").
 *  4. Primary-artist-only + simplified title.
 *  5. Filename-parsed (artist, title) when filenames follow the
 *     common "[N.] Artist - Title.ext" pattern. Disambiguates the
 *     case where the file was added without ID3 tags.
 *
 * Variants 6+ would dip into MusicBrainz's recording-search /
 * AcoustID fingerprinting territory; that's a much larger build-out
 * (network calls, ID lookups, ~200KB Chromaprint native lib) and is
 * tracked separately on SPEC's deferred list.
 *
 * Pure-data utility so it's easy to unit-test the parsing without
 * touching the network.
 */
data class LyricsQuery(val artist: String, val title: String)

internal fun lyricsQueryCandidates(
    taggedArtist: String?,
    taggedTitle: String?,
    fileName: String,
): List<LyricsQuery> {
    val results = mutableListOf<LyricsQuery>()
    val seen = mutableSetOf<Pair<String, String>>()

    fun add(artist: String?, title: String?) {
        val a = artist?.trim().orEmpty()
        val t = title?.trim().orEmpty()
        if (a.isBlank() || t.isBlank()) return
        val key = a.lowercase() to t.lowercase()
        if (key in seen) return
        seen += key
        results += LyricsQuery(artist = a, title = t)
    }

    val title = taggedTitle?.takeIf { it.isNotBlank() }
    val artist = taggedArtist?.takeIf { it.isNotBlank() }

    // 1. Verbatim tags.
    add(artist, title)

    // 2. Title with trailing parenthetical stripped.
    val cleanTitle = title?.let { stripParentheticals(it) }
    add(artist, cleanTitle)

    // 3. Primary artist only + tagged title.
    val primaryArtist = artist?.let { primaryArtistOf(it) }
    add(primaryArtist, title)

    // 4. Primary artist + cleaned title.
    add(primaryArtist, cleanTitle)

    // 5. Filename-parsed when tagged metadata fails. This is the
    // big win for unsorted libraries — files named like "01. Artist -
    // Title.mp3" are common, and the album-artist column is often
    // empty for compilations.
    val parsed = parseFilenameForArtistTitle(fileName)
    if (parsed != null) {
        add(parsed.artist, parsed.title)
        add(primaryArtistOf(parsed.artist), parsed.title)
        add(parsed.artist, stripParentheticals(parsed.title))
    }

    return results
}

/**
 * Strip trailing parentheticals and bracket suffixes commonly used
 * for remasters / live versions / radio edits. Leaves leading
 * parenthetical text alone since some titles legitimately start
 * with one ("(I Can't Get No) Satisfaction"). Also drops trailing
 * " - Live" / " - Remastered" suffixes that some tagging programs
 * use instead of parentheses.
 */
internal fun stripParentheticals(title: String): String {
    var work = title.trim()
    // Trailing parentheses or brackets, repeated.
    val parenSuffix = Regex("""\s*[(\[][^()\[\]]*[)\]]\s*$""")
    while (parenSuffix.containsMatchIn(work)) {
        work = work.replace(parenSuffix, "").trim()
    }
    // Trailing " - <suffix>" where suffix contains keywords like
    // Live / Remastered / Demo / Edit / Mix / Version. Avoids
    // stripping legitimate em-dash separated titles by requiring
    // the keyword.
    val dashSuffix = Regex(
        pattern = """\s*[-–—]\s*(live|remaster(ed)?|demo|radio\s*edit|edit|mix|version|remix|acoustic|stereo|mono|extended)\b.*$""",
        option = RegexOption.IGNORE_CASE,
    )
    work = work.replace(dashSuffix, "").trim()
    return work.ifBlank { title }
}

/**
 * Drop "feat." / "ft." / "featuring" / ", & X" / "& X" suffixes
 * from an artist string so LRClib matches the canonical primary
 * artist's entry. The patterns are intentionally conservative —
 * splitting on "and" too eagerly breaks "Hall and Oates".
 */
internal fun primaryArtistOf(artist: String): String {
    val featSplit = Regex(
        pattern = """\s+(feat\.?|ft\.?|featuring|with)\s+.*$""",
        option = RegexOption.IGNORE_CASE,
    )
    var work = artist.replace(featSplit, "").trim()
    // ", " or " & " comma/ampersand collaborator suffix.
    val commaSplit = Regex("""\s*[,&]\s*.+$""")
    work = work.replace(commaSplit, "").trim()
    return work.ifBlank { artist }
}

/**
 * Parse "[track number]. [Artist] - [Title].ext" or "[Artist] -
 * [Title].ext" filenames into (artist, title). Returns null when
 * the filename doesn't match a recognisable pattern — caller
 * silently skips that candidate.
 */
internal data class FilenameParse(val artist: String, val title: String)

internal fun parseFilenameForArtistTitle(fileName: String): FilenameParse? {
    val withoutExt = fileName.substringBeforeLast('.', fileName)
    // Strip a leading "<digits>. " or "<digits> - " track-number prefix.
    val noPrefix = withoutExt
        .replace(Regex("""^\d+\s*[.\-]\s*"""), "")
        .trim()
    val sep = " - "
    val idx = noPrefix.indexOf(sep)
    if (idx <= 0 || idx >= noPrefix.length - sep.length) return null
    val artist = noPrefix.substring(0, idx).trim()
    val title = noPrefix.substring(idx + sep.length).trim()
    if (artist.isBlank() || title.isBlank()) return null
    return FilenameParse(artist = artist, title = title)
}
