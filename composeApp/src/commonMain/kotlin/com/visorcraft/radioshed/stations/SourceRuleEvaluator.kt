package com.visorcraft.radioshed.stations

import com.visorcraft.radioshed.data.LibraryRepository
import com.visorcraft.radioshed.data.PlaylistRepository
import com.visorcraft.radioshed.data.RatingRepository
import com.visorcraft.radioshed.domain.SmartQueryKind
import com.visorcraft.radioshed.domain.SortBy
import com.visorcraft.radioshed.domain.SourceRule
import com.visorcraft.radioshed.domain.Track

/**
 * Resolves any [SourceRule] tree into a flat list of candidate tracks for
 * the playback engine. Pure suspending Kotlin — no platform deps, all the
 * data fetching goes through the existing repositories.
 *
 * Filters that aren't meaningful in isolation ([SourceRule.DurationFilter],
 * [SourceRule.FileFormatFilter], [SourceRule.PathPattern]) currently
 * evaluate to the empty set when standalone — they're designed to be
 * composed via [SourceRule.Intersection] with another source. The full
 * filter wiring lands when the Station Editor UI ships in part 2.
 */
class SourceRuleEvaluator(
    private val libraryRepo: LibraryRepository,
    private val ratingRepo: RatingRepository,
    private val playlistRepo: PlaylistRepository,
) {

    /** Hard cap on "everything in the library" smart-queries so we don't
     *  load 500k rows into memory accidentally. v1 ships with 100 k. */
    private val absoluteCeiling: Long = 100_000

    suspend fun evaluate(rule: SourceRule): List<Track> = when (rule) {
        is SourceRule.FolderSelector -> evaluateFolders(rule)
        is SourceRule.SmartQuery -> evaluateSmart(rule)
        is SourceRule.PlaylistSelector -> playlistRepo.tracksIn(rule.playlistId)
        is SourceRule.ArtistSelector -> evaluateArtists(rule)
        is SourceRule.AlbumSelector -> evaluateAlbums(rule)
        is SourceRule.TagFilter -> evaluateTagFilter(rule)
        is SourceRule.DurationFilter -> emptyList() // standalone n/a; see KDoc
        is SourceRule.FileFormatFilter -> emptyList()
        is SourceRule.PathPattern -> emptyList()
        is SourceRule.Union -> evaluateUnion(rule)
        is SourceRule.Intersection -> evaluateIntersection(rule)
        is SourceRule.Difference -> evaluateDifference(rule)
        is SourceRule.TopN -> evaluateTopN(rule)
    }

    private suspend fun evaluateFolders(rule: SourceRule.FolderSelector): List<Track> =
        rule.folderIds.flatMap { libraryRepo.listTracksByFolder(it) }
            .distinctBy { it.id }

    private suspend fun evaluateSmart(rule: SourceRule.SmartQuery): List<Track> {
        val limit = rule.limit.toLong().coerceAtMost(absoluteCeiling)
        return when (rule.kind) {
            SmartQueryKind.NewestN, SmartQueryKind.RecentlyAdded ->
                libraryRepo.newestTracks(limit)
            SmartQueryKind.OldestN ->
                // No dedicated query yet; pull a generous slice + sort. Fine
                // for v1 since only Latest Hits / All Music presets currently
                // use SmartQuery and neither uses OldestN.
                libraryRepo.listAvailableTracks(absoluteCeiling, 0L)
                    .sortedBy { it.fileCtime }
                    .take(limit.toInt())
            SmartQueryKind.LeastPlayed,
            SmartQueryKind.LongestUnheard,
            SmartQueryKind.AdaptiveMix ->
                ratingRepo.leastPlayedTracks(limit)
            SmartQueryKind.MostPlayed ->
                ratingRepo.mostPlayedTracks(limit)
            SmartQueryKind.HighestRated, SmartQueryKind.RecentlyRated ->
                ratingRepo.topRatedTracks(minStars = 4.0, limit = limit)
            SmartQueryKind.NeverPlayed ->
                ratingRepo.neverPlayedTracks(limit)
            SmartQueryKind.ForgottenGems -> {
                // 90 days ago. We could expose this as a parameter on
                // the SmartQuery rule itself in a later schema bump,
                // but a fixed cutoff matches Spotify / Apple Music's
                // "On Repeat" / "Time Capsule" cadence.
                val cutoff = nowMs() - FORGOTTEN_GEMS_THRESHOLD_DAYS * 86_400_000L
                ratingRepo.forgottenGemTracks(
                    minStars = 4.0,
                    olderThanMs = cutoff,
                    limit = limit,
                )
            }
        }
    }

    /** Hook so tests can pin a deterministic clock; production = nowMillis(). */
    private fun nowMs(): Long = com.visorcraft.radioshed.data.nowMillis()

    private suspend fun evaluateArtists(rule: SourceRule.ArtistSelector): List<Track> =
        rule.artistIds.flatMap { libraryRepo.tracksForArtist(it) }
            .distinctBy { it.id }

    private suspend fun evaluateAlbums(rule: SourceRule.AlbumSelector): List<Track> {
        // No bulk-by-album query in the repo yet; iterate. v1 stations rarely
        // pin >5 albums so this is cheap; can add a multi-id query later.
        return rule.albumIds.flatMap { albumId ->
            libraryRepo.listAvailableTracks(absoluteCeiling, 0L)
                .filter { it.albumId == albumId }
        }.distinctBy { it.id }
    }

    private suspend fun evaluateTagFilter(rule: SourceRule.TagFilter): List<Track> {
        val perValue = rule.values.map { value -> libraryRepo.tracksByTag(rule.key, value) }
        return if (rule.matchAll) {
            // Tracks present in every value's result set
            if (perValue.isEmpty()) emptyList()
            else perValue.reduce { acc, list ->
                val ids = list.map { it.id }.toSet()
                acc.filter { it.id in ids }
            }
        } else {
            perValue.flatten().distinctBy { it.id }
        }
    }

    private suspend fun evaluateUnion(rule: SourceRule.Union): List<Track> =
        rule.children.flatMap { evaluate(it) }.distinctBy { it.id }

    private suspend fun evaluateIntersection(rule: SourceRule.Intersection): List<Track> {
        if (rule.children.isEmpty()) return emptyList()
        val first = evaluate(rule.children.first())
        if (first.isEmpty() || rule.children.size == 1) return first
        val acc = first.associateBy { it.id }.toMutableMap()
        for (child in rule.children.drop(1)) {
            val ids = evaluate(child).mapTo(mutableSetOf()) { it.id }
            acc.keys.retainAll { it in ids }
            if (acc.isEmpty()) return emptyList()
        }
        return acc.values.toList()
    }

    private suspend fun evaluateDifference(rule: SourceRule.Difference): List<Track> {
        val left = evaluate(rule.left)
        if (left.isEmpty()) return emptyList()
        val rightIds = evaluate(rule.right).mapTo(mutableSetOf()) { it.id }
        return left.filterNot { it.id in rightIds }
    }

    private companion object {
        /** "Loved but neglected" cutoff for ForgottenGems, in days. */
        const val FORGOTTEN_GEMS_THRESHOLD_DAYS = 90L
    }

    private suspend fun evaluateTopN(rule: SourceRule.TopN): List<Track> {
        val all = evaluate(rule.source)
        val sorted = when (rule.sortBy) {
            SortBy.Newest -> all.sortedByDescending { it.fileCtime }
            SortBy.Oldest -> all.sortedBy { it.fileCtime }
            SortBy.Title -> all.sortedBy { it.title.lowercase() }
            SortBy.Random -> all.shuffled()
            // MostPlayed / HighestRated need joining with play_count / rating
            // tables; v1 falls back to source order. Phase 5 part 2 wires these
            // up properly via dedicated queries.
            SortBy.MostPlayed, SortBy.HighestRated -> all
        }
        return sorted.take(rule.limit)
    }
}
