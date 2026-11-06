package com.visorcraft.radioshed.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class TrackDisplayTitleTest {
    @Test
    fun stripsYoutubeIdBrackets() {
        assertEquals(
            "Zach Bryan - Something In The Orange",
            "Zach Bryan - Something In The Orange [IA8F9slhGdg]".withoutBracketGroups(),
        )
    }

    @Test
    fun stripsEveryBracketGroup() {
        assertEquals(
            "Song",
            "Song [Official Audio] [dQw4w9WgXcQ]".withoutBracketGroups(),
        )
    }

    @Test
    fun keepsParentheses() {
        assertEquals(
            "Song (Official Video)",
            "Song (Official Video) [abc123]".withoutBracketGroups(),
        )
    }

    @Test
    fun keepsTitleWithNoBrackets() {
        assertEquals("Plain Title", "Plain Title".withoutBracketGroups())
    }

    @Test
    fun keepsATitleThatIsOnlyABracketGroup() {
        assertEquals("[IA8F9slhGdg]", "[IA8F9slhGdg]".withoutBracketGroups())
    }

    @Test
    fun displayTitleFallsBackToFileName() {
        val track = sampleTrack(title = "", fileName = "Orange [IA8F9slhGdg].mp3")
        assertEquals("Orange.mp3", track.displayTitle())
    }

    private fun sampleTrack(title: String, fileName: String) =
        Track(
            id = 1L,
            path = "/music/$fileName",
            fileName = fileName,
            fileSize = 1L,
            fileCtime = 0L,
            fileMtime = 0L,
            title = title,
            durationMs = 1L,
            format = "mp3",
            addedAt = 0L,
            updatedAt = 0L,
        )
}
