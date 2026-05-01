package com.visorcraft.radioshed.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CueParserTest {

    @Test
    fun parses_basic_album_cue() {
        val cue = """
            PERFORMER "Pink Floyd"
            TITLE "The Dark Side of the Moon"
            FILE "DSOTM.flac" WAVE
              TRACK 01 AUDIO
                TITLE "Speak to Me"
                PERFORMER "Pink Floyd"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Breathe (In the Air)"
                INDEX 01 01:08:25
              TRACK 03 AUDIO
                TITLE "On the Run"
                INDEX 01 03:54:64
        """.trimIndent()

        val sheet = CueParser.parse(cue)
        assertEquals("Pink Floyd", sheet.performer)
        assertEquals("The Dark Side of the Moon", sheet.title)
        assertEquals(1, sheet.files.size)
        val file = sheet.files.single()
        assertEquals("DSOTM.flac", file.fileName)
        assertEquals("WAVE", file.fileType)
        assertEquals(3, file.tracks.size)

        val t1 = file.tracks[0]
        assertEquals(1, t1.number)
        assertEquals("Speak to Me", t1.title)
        assertEquals("Pink Floyd", t1.performer)
        assertEquals(0L, t1.startMs)
        assertEquals(file.tracks[1].startMs, t1.endMs)

        val t2 = file.tracks[1]
        assertEquals("Breathe (In the Air)", t2.title)
        // Falls back to album performer when track-level not specified
        assertEquals("Pink Floyd", t2.performer)
        // 1:08:25 = 68 seconds + 25/75 of a second
        val expectedT2Start = (68L * 1000L) + (25L * 1000L) / 75L
        assertEquals(expectedT2Start, t2.startMs)

        val t3 = file.tracks[2]
        assertNull(t3.endMs)  // last track has no successor
    }

    @Test
    fun handles_rem_comments_and_isrc() {
        val cue = """
            REM GENRE Rock
            REM DATE 1973
            PERFORMER "Pink Floyd"
            TITLE "Album"
            FILE "audio.flac" WAVE
              TRACK 01 AUDIO
                ISRC US00000001
                TITLE "Track One"
                INDEX 01 00:00:00
        """.trimIndent()

        val sheet = CueParser.parse(cue)
        val track = sheet.files.single().tracks.single()
        assertEquals("US00000001", track.isrc)
        assertEquals("Track One", track.title)
    }

    @Test
    fun handles_unquoted_strings() {
        val cue = """
            PERFORMER Beatles
            TITLE Revolver
            FILE Revolver.flac WAVE
              TRACK 01 AUDIO
                TITLE Taxman
                INDEX 01 00:00:00
        """.trimIndent()

        val sheet = CueParser.parse(cue)
        assertEquals("Beatles", sheet.performer)
        assertEquals("Revolver", sheet.title)
        assertEquals("Taxman", sheet.files.single().tracks.single().title)
    }

    @Test
    fun ignores_index_00_pregap() {
        val cue = """
            FILE "x.flac" WAVE
              TRACK 01 AUDIO
                INDEX 00 00:00:00
                INDEX 01 00:02:00
        """.trimIndent()

        val track = CueParser.parse(cue).files.single().tracks.single()
        // INDEX 01 wins over INDEX 00
        assertEquals(2L * 1000L, track.startMs)
    }

    @Test
    fun parses_multi_file_cue_concatenated() {
        val cue = """
            FILE "side-a.flac" WAVE
              TRACK 01 AUDIO
                TITLE "A1"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "A2"
                INDEX 01 02:30:00
            FILE "side-b.flac" WAVE
              TRACK 03 AUDIO
                TITLE "B1"
                INDEX 01 00:00:00
        """.trimIndent()

        val sheet = CueParser.parse(cue)
        assertEquals(2, sheet.files.size)
        assertEquals(2, sheet.files[0].tracks.size)
        assertEquals(1, sheet.files[1].tracks.size)
        assertEquals("A2", sheet.files[0].tracks[1].title)
        assertEquals("B1", sheet.files[1].tracks[0].title)
    }

    @Test
    fun malformed_lines_are_skipped() {
        val cue = """
            PERFORMER
            TITLE "Album"
            INDEX 01 invalid:format:here
            FILE "x.flac" WAVE
              TRACK 01 AUDIO
                INDEX 01 00:00:00
        """.trimIndent()

        // Should not throw; should still parse what it can
        val sheet = CueParser.parse(cue)
        assertEquals("Album", sheet.title)
        assertNotNull(sheet.files.singleOrNull())
    }

    @Test
    fun timestamp_conversion_75_frames_per_second() {
        // 1:30:75 — but FF should be 0..74. 1:30:00 = 90 seconds = 90000ms
        assertEquals(90_000L, CueParser.parseTimestampToMs("01:30:00"))
        // 0:00:75 = 75 frames / 75 fps = 1 second exactly
        assertEquals(1_000L, CueParser.parseTimestampToMs("00:00:75"))
        // 0:00:37 = 37/75 seconds ≈ 493ms (37*1000/75 = 493.33 → 493)
        assertEquals(493L, CueParser.parseTimestampToMs("00:00:37"))
    }

    @Test
    fun tokenize_handles_quoted_with_spaces() {
        val tokens = CueParser.tokenize("""TITLE "The Dark Side of the Moon"""")
        assertEquals(listOf("TITLE", "The Dark Side of the Moon"), tokens)
    }

    @Test
    fun tokenize_handles_unquoted() {
        val tokens = CueParser.tokenize("TRACK 01 AUDIO")
        assertEquals(listOf("TRACK", "01", "AUDIO"), tokens)
    }
}
