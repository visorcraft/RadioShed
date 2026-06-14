package com.visorcraft.radioshed.ui.library

import com.visorcraft.radioshed.source.TagPatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BatchTagMergeTest {
    @Test
    fun uniformFieldShowsCommonValue() {
        val tracks =
            listOf(
                snapshot(title = "Song A", artist = "Artist X"),
                snapshot(title = "Song B", artist = "Artist X"),
            )

        val merged = BatchTagMerge.merge(tracks)

        assertEquals(MergedValue.Uniform("Artist X"), merged.artist)
    }

    @Test
    fun mixedFieldShowsMixedMarker() {
        val tracks =
            listOf(
                snapshot(title = "Song A", artist = "Artist X"),
                snapshot(title = "Song B", artist = "Artist Y"),
            )

        val merged = BatchTagMerge.merge(tracks)

        assertEquals(MergedValue.Mixed, merged.artist)
    }

    @Test
    fun emptyFieldShowsEmptyMarker() {
        val tracks =
            listOf(
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
        // User only changed genre to "Jazz"
        val patch = BatchTagMerge.buildPatch(mapOf(TagField.Genre to "Jazz"))

        assertEquals(TagPatch.Update("Jazz"), patch.genre)
        assertNull(patch.title)
        assertNull(patch.artist)
        assertNull(patch.album)
    }

    @Test
    fun buildPatchParsesYearAndTrackNumberToInt() {
        val patch =
            BatchTagMerge.buildPatch(
                mapOf(
                    TagField.Year to "2021",
                    TagField.TrackNumber to "7",
                ),
            )

        assertEquals(TagPatch.Update(2021), patch.year)
        assertEquals(TagPatch.Update(7), patch.trackNumber)
    }

    @Test
    fun buildPatchIgnoresBlankIntFields() {
        val patch = BatchTagMerge.buildPatch(mapOf(TagField.Year to ""))

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
    ): TrackTagSnapshot =
        TrackTagSnapshot(
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
