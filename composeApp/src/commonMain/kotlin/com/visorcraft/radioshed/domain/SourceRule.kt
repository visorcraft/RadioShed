package com.visorcraft.radioshed.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Source-side of the Station model (SPEC §4.1). A station's source is built
 * from one or more selectors combined with set operators.
 */
@Serializable
sealed interface SourceRule {
    // ---- Selectors ----

    @Serializable
    @SerialName("folder")
    data class FolderSelector(val folderIds: List<Long>) : SourceRule

    @Serializable
    @SerialName("tag_filter")
    data class TagFilter(
        val key: String,
        val values: List<String>,
        val matchAll: Boolean = false,
    ) : SourceRule

    @Serializable
    @SerialName("smart_query")
    data class SmartQuery(val kind: SmartQueryKind, val limit: Int = 100) : SourceRule

    @Serializable
    @SerialName("playlist")
    data class PlaylistSelector(val playlistId: Long) : SourceRule

    @Serializable
    @SerialName("artist")
    data class ArtistSelector(val artistIds: List<Long>) : SourceRule

    @Serializable
    @SerialName("album")
    data class AlbumSelector(val albumIds: List<Long>) : SourceRule

    @Serializable
    @SerialName("duration")
    data class DurationFilter(val minSeconds: Int? = null, val maxSeconds: Int? = null) : SourceRule

    @Serializable
    @SerialName("file_format")
    data class FileFormatFilter(val formats: List<String>) : SourceRule

    @Serializable
    @SerialName("path_pattern")
    data class PathPattern(val include: List<String>, val exclude: List<String>) : SourceRule

    @Serializable
    @SerialName("media_source")
    data class MediaSourceSelector(val mediaSourceId: String) : SourceRule

    // ---- Set operators ----

    @Serializable
    @SerialName("union")
    data class Union(val children: List<SourceRule>) : SourceRule

    @Serializable
    @SerialName("intersection")
    data class Intersection(val children: List<SourceRule>) : SourceRule

    @Serializable
    @SerialName("difference")
    data class Difference(val left: SourceRule, val right: SourceRule) : SourceRule

    @Serializable
    @SerialName("top_n")
    data class TopN(
        val source: SourceRule,
        val limit: Int,
        val sortBy: SortBy = SortBy.Newest,
    ) : SourceRule
}

@Serializable
enum class SmartQueryKind {
    NewestN,
    OldestN,
    LeastPlayed,
    MostPlayed,
    HighestRated,
    LongestUnheard,
    RecentlyAdded,
    RecentlyRated,
    NeverPlayed,
    AdaptiveMix,

    /**
     * Highly-rated tracks (≥4★) that haven't been played in 90+ days.
     * Distinct from [LongestUnheard] which is "least played by count" —
     * Forgotten Gems is "loved but recently neglected". Sorted by
     * longest-unheard first.
     */
    ForgottenGems,

    /**
     * Same gem set as [ForgottenGems] but sorted by rating descending —
     * surfaces the top-rated tracks among the never-played-in-a-while
     * set. Pick this when "absolute best forgotten" matters more than
     * "most overdue for a replay".
     */
    ForgottenGemsByRating,

    /**
     * The user's Liked-Songs collection. Picks track-ids from
     * [com.visorcraft.radioshed.data.RatingRepository.likedTracks]
     * — order is most-recently-liked first inside the source rule
     * evaluator, but the playback rule is free to shuffle.
     */
    LikedSongs,
}

@Serializable
enum class SortBy { Newest, Oldest, MostPlayed, HighestRated, Title, Random }
