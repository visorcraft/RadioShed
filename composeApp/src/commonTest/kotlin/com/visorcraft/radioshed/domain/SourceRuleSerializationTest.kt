package com.visorcraft.radioshed.domain

import com.visorcraft.radioshed.data.RadioShedJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceRuleSerializationTest {
    @Test
    fun folderSelector_round_trip() {
        val original: SourceRule = SourceRule.FolderSelector(folderIds = listOf(1, 2, 3))
        val json = RadioShedJson.encodeToString(SourceRule.serializer(), original)
        val decoded = RadioShedJson.decodeFromString(SourceRule.serializer(), json)
        assertEquals(original, decoded)
        assertTrue(json.contains("\"type\":\"folder\""), "polymorphic discriminator present: $json")
    }

    @Test
    fun smartQuery_round_trip() {
        val original: SourceRule = SourceRule.SmartQuery(SmartQueryKind.NewestN, limit = 100)
        val decoded =
            RadioShedJson.decodeFromString(
                SourceRule.serializer(),
                RadioShedJson.encodeToString(SourceRule.serializer(), original),
            )
        assertEquals(original, decoded)
    }

    @Test
    fun nested_topN_intersection_round_trip() {
        val inner =
            SourceRule.Intersection(
                children =
                    listOf(
                        SourceRule.FolderSelector(folderIds = listOf(7)),
                        SourceRule.TagFilter(key = "genre", values = listOf("Metal")),
                    ),
            )
        val original: SourceRule = SourceRule.TopN(inner, limit = 100, sortBy = SortBy.Newest)
        val json = RadioShedJson.encodeToString(SourceRule.serializer(), original)
        val decoded = RadioShedJson.decodeFromString(SourceRule.serializer(), json)
        assertEquals(original, decoded)
    }

    @Test
    fun all_selector_variants_round_trip() {
        val variants: List<SourceRule> =
            listOf(
                SourceRule.FolderSelector(folderIds = listOf(1)),
                SourceRule.TagFilter(key = "year", values = listOf("1972", "1973"), matchAll = true),
                SourceRule.SmartQuery(SmartQueryKind.LongestUnheard, limit = 50),
                SourceRule.PlaylistSelector(playlistId = 42),
                SourceRule.ArtistSelector(artistIds = listOf(8, 9)),
                SourceRule.AlbumSelector(albumIds = listOf(5)),
                SourceRule.DurationFilter(minSeconds = 30, maxSeconds = 900),
                SourceRule.FileFormatFilter(formats = listOf("flac", "mp3")),
                SourceRule.PathPattern(include = listOf("*/Music/*"), exclude = listOf("*/Spoken Word/*")),
                SourceRule.Union(children = listOf(SourceRule.PlaylistSelector(1), SourceRule.PlaylistSelector(2))),
                SourceRule.Difference(
                    left = SourceRule.FolderSelector(listOf(1)),
                    right = SourceRule.TagFilter("genre", listOf("Christmas")),
                ),
            )
        for (v in variants) {
            val json = RadioShedJson.encodeToString(SourceRule.serializer(), v)
            val back = RadioShedJson.decodeFromString(SourceRule.serializer(), json)
            assertEquals(v, back, "round trip failed for $v: json=$json")
        }
    }
}
