package com.visorcraft.radioshed.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsQueryCandidatesTest {
    @Test
    fun verbatimTagsAreFirstCandidate() {
        val candidates =
            lyricsQueryCandidates(
                taggedArtist = "Roberta Flack",
                taggedTitle = "Making Love",
                fileName = "anything.mp3",
            )
        assertEquals(LyricsQuery("Roberta Flack", "Making Love"), candidates.first())
    }

    @Test
    fun strippedParentheticalProducesAlternateCandidate() {
        val candidates =
            lyricsQueryCandidates(
                taggedArtist = "Queen",
                taggedTitle = "Bohemian Rhapsody (Remastered 2011)",
                fileName = "01.mp3",
            )
        // The clean variant should appear in the list — exact order
        // doesn't matter, only that it's reachable.
        assertTrue(
            candidates.any { it.title == "Bohemian Rhapsody" },
            "expected stripped title in $candidates",
        )
    }

    @Test
    fun featuredArtistSuffixIsDropped() {
        val candidates =
            lyricsQueryCandidates(
                taggedArtist = "Roberta Flack feat. Donny Hathaway",
                taggedTitle = "The Closer I Get to You",
                fileName = "track.mp3",
            )
        assertTrue(
            candidates.any { it.artist == "Roberta Flack" },
            "expected primary-artist candidate in $candidates",
        )
    }

    @Test
    fun filenamePatternFallsBackWhenTagsMissing() {
        val candidates =
            lyricsQueryCandidates(
                taggedArtist = null,
                taggedTitle = null,
                fileName = "46. Roberta Flack - Making Love.mp3",
            )
        assertTrue(
            candidates.any { it.artist == "Roberta Flack" && it.title == "Making Love" },
            "expected filename-derived candidate in $candidates",
        )
    }

    @Test
    fun filenameParserStripsTrackNumberPrefix() {
        val parsed = parseFilenameForArtistTitle("01. The Beatles - Hey Jude.flac")
        assertEquals(FilenameParse("The Beatles", "Hey Jude"), parsed)
    }

    @Test
    fun filenameParserHandlesNoPrefix() {
        val parsed = parseFilenameForArtistTitle("Pink Floyd - Time.mp3")
        assertEquals(FilenameParse("Pink Floyd", "Time"), parsed)
    }

    @Test
    fun filenameParserReturnsNullForNonMatchingFormat() {
        assertEquals(null, parseFilenameForArtistTitle("track01.mp3"))
        assertEquals(null, parseFilenameForArtistTitle(""))
    }

    @Test
    fun stripParentheticalsHandlesTrailingRemaster() {
        assertEquals("Hotel California", stripParentheticals("Hotel California (2013 Remaster)"))
        assertEquals("Stairway to Heaven", stripParentheticals("Stairway to Heaven (Live)"))
        assertEquals("Smells Like Teen Spirit", stripParentheticals("Smells Like Teen Spirit - Remastered"))
    }

    @Test
    fun stripParentheticalsLeavesLeadingParenAlone() {
        assertEquals(
            "(I Can't Get No) Satisfaction",
            stripParentheticals("(I Can't Get No) Satisfaction"),
        )
    }

    @Test
    fun primaryArtistOfDropsFeat() {
        assertEquals("Daft Punk", primaryArtistOf("Daft Punk feat. Pharrell"))
        assertEquals("Eminem", primaryArtistOf("Eminem ft. Rihanna"))
        assertEquals("The Weeknd", primaryArtistOf("The Weeknd featuring Daft Punk"))
    }

    @Test
    fun primaryArtistOfDropsCommaCollaborator() {
        assertEquals("Beyoncé", primaryArtistOf("Beyoncé, Jay-Z"))
        assertEquals("Calvin Harris", primaryArtistOf("Calvin Harris & Sam Smith"))
    }

    @Test
    fun primaryArtistOfPreservesAndOates() {
        // Don't break two-name acts where "and" is part of the canonical
        // artist name. Our split is only on "& " and ", ", not " and ".
        assertEquals("Hall and Oates", primaryArtistOf("Hall and Oates"))
    }

    @Test
    fun candidateListDeduplicates() {
        val candidates =
            lyricsQueryCandidates(
                taggedArtist = "Adele",
                taggedTitle = "Hello",
                fileName = "Adele - Hello.mp3",
            )
        // Verbatim + filename-derived should produce the same query
        // and be deduplicated rather than fired twice.
        val matching = candidates.filter { it.artist == "Adele" && it.title == "Hello" }
        assertEquals(1, matching.size, "expected dedupe in $candidates")
    }

    @Test
    fun emptyTagsDontProduceEmptyCandidates() {
        val candidates =
            lyricsQueryCandidates(
                taggedArtist = "",
                taggedTitle = "",
                fileName = "noisy_filename.mp3",
            )
        assertTrue(candidates.all { it.artist.isNotBlank() && it.title.isNotBlank() })
    }
}
