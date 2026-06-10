package com.visorcraft.radioshed.ui.library

import com.visorcraft.radioshed.source.TagPatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BatchTagMergeTest {

    @Test
    fun uniformFieldShowsCommonValue() {
        val tracks = listOf(
            snapshot(title = "Song A", artist = "Artist X"),
            snapshot(title = "Song B", artist = "Artist X"),
        )

        val merged = BatchTagMerge.merge(tracks)

        assertEquals(MergedValue.Uniform("Artist X"), merged.artist)
    }

    @Test
    fun mixedFieldShowsMixedMarker() {
        val tracks = listOf(
            snapshot(title = "Song A", artist = "Artist X"),
            snapshot(title = "Song B", artist = "Artist Y"),
        )

        val merged = BatchTagMerge.merge(tracks)

        assertEquals(MergedValue.Mixed, merged.artist)
    }

    @Test
    fun emptyFieldShowsEmptyMarker() {
        val tracks = listOf(
            snapshot(title = "Song A", artist = ""),
            snapshot(title = "Song B", artist = ""),
        )

        val merged = BatchTagMerge.merge(tracks)

        assertEquals(MergedValue.Empty, merged.artist)
    }

    @Test
    fun singleTrackIsUniform() {
        val tracks = listOf(snapshot(title = "Only", artist = "Solo"))

        val merged = BatchTagMerge.merge(tracks)

        assertEquals(MergedValue.Uniform("Only"), merged.title)
        assertEquals(MergedValue.Uniform("Solo"), merged.artist)
    }

    @Test
    fun emptyListProducesAllEmpty() {
        val merged = BatchTagMerge.merge(emptyList())

        assertEquals(MergedValue.Empty, merged.title)
        assertEquals(MergedValue.Empty, merged.artist)
    }

    @Test
    fun buildPatchIncludesOnlyEditedFields() {
        val merged = BatchTagMerge.merge(
            listOf(
                snapshot(title = "A", artist = "X", genre = "Rock"),
                snapshot(title = "B", artist = "Y", genre = "Rock"),
            ),
        )

        // User only changed genre to "Jazz"
        val patch = BatchTagMerge.buildPatch(
            edits = listOf(BatchTagEdit(TagField.Genre, "Jazz")),
            merged = merged,
        )

        assertEquals(TagPatch.Update("Jazz"), patch.genre)
        assertNull(patch.title)
        assertNull(patch.artist)
        assertNull(patch.album)
    }

    @Test
    fun buildPatchParsesYearAndTrackNumberToInt() {
        val merged = BatchTagMerge.merge(listOf(snapshot(year = "2020", trackNumber = "5")))

        val patch = BatchTagMerge.buildPatch(
            edits = listOf(
                BatchTagEdit(TagField.Year, "2021"),
                BatchTagEdit(TagField.TrackNumber, "7"),
            ),
            merged = merged,
        )

        assertEquals(TagPatch.Update(2021), patch.year)
        assertEquals(TagPatch.Update(7), patch.trackNumber)
    }

    @Test
    fun buildPatchIgnoresBlankIntFields() {
        val merged = BatchTagMerge.merge(listOf(snapshot(year = "2020")))

        val patch = BatchTagMerge.buildPatch(
            edits = listOf(BatchTagEdit(TagField.Year, "")),
            merged = merged,
        )

        assertNull(patch.year)
    }

    private fun snapshot(
        title: String = "",
        artist: String = "",
        album: String = "",
        albumArtist: String = "",
        genre: String = "",
        year: String = "",
        trackNumber: String = "",
        discNumber: String = "",
    ): TrackTagSnapshot = TrackTagSnapshot(
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        genre = genre,
        year = year,
        trackNumber = trackNumber,
        discNumber = discNumber,
    )
}
