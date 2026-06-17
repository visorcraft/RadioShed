package com.visorcraft.radioshed.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobMatcherTest {
    @Test
    fun singleStarStaysWithinASegment() {
        assertTrue(matchesGlob("song.mp3", "*.mp3"))
        // `*` does not cross `/`.
        assertFalse(matchesGlob("a/b/song.mp3", "*.mp3"))
    }

    @Test
    fun doubleStarCrossesSlashes() {
        assertTrue(matchesGlob("a/b/song.mp3", "**/*.mp3"))
        assertTrue(matchesGlob("a/b/c/d.flac", "**/d.flac"))
    }

    @Test
    fun questionMarkMatchesExactlyOneNonSlashChar() {
        assertTrue(matchesGlob("a.mp3", "?.mp3"))
        assertFalse(matchesGlob("ab.mp3", "?.mp3"))
        assertFalse(matchesGlob("/.mp3", "?.mp3"))
    }

    @Test
    fun regexMetacharactersInThePathAreMatchedLiterally() {
        assertTrue(matchesGlob("a.b", "a.b"))
        // The `.` must be a literal dot, not "any character".
        assertFalse(matchesGlob("axb", "a.b"))
        assertTrue(matchesGlob("dir (1)/t.mp3", "**/t.mp3"))
    }

    @Test
    fun matchIsFullyAnchored() {
        assertFalse(matchesGlob("prefix-song.mp3-suffix", "*song.mp3"))
        assertTrue(matchesGlob("song.mp3", "*song.mp3"))
    }

    @Test
    fun globToRegexAgreesWithMatchesGlob() {
        // The enumerator precompiles via globToRegex and calls .matches();
        // it must produce identical results to the matchesGlob wrapper.
        val cases =
            listOf(
                "song.mp3" to "*.mp3",
                "a/b/song.mp3" to "*.mp3",
                "a/b/song.mp3" to "**/*.mp3",
                "a.b" to "a.b",
                "axb" to "a.b",
            )
        for ((path, glob) in cases) {
            assertEquals(matchesGlob(path, glob), globToRegex(glob).matches(path), "glob=$glob path=$path")
        }
    }
}
