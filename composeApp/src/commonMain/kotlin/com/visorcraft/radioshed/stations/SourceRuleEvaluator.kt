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
            SmartQueryKind.LongestUnheard ->
                ratingRepo.leastPlayedTracks(limit)
            SmartQueryKind.AdaptiveMix -> evaluateAdaptiveMix(limit)
            SmartQueryKind.MostPlayed ->
                ratingRepo.mostPlayedTracks(limit)
            SmartQueryKind.HighestRated, SmartQueryKind.RecentlyRated ->
                ratingRepo.topRatedTracks(minStars = 4.0, limit = limit)
            SmartQueryKind.NeverPlayed ->
                ratingRepo.neverPlayedTracks(limit)
            SmartQueryKind.ForgottenGems,
            SmartQueryKind.ForgottenGemsByRating -> {
                // 90 days ago. We could expose this as a parameter on
                // the SmartQuery rule itself in a later schema bump,
                // but a fixed cutoff matches Spotify / Apple Music's
                // "On Repeat" / "Time Capsule" cadence.
                val cutoff = nowMs() - FORGOTTEN_GEMS_THRESHOLD_DAYS * 86_400_000L
                ratingRepo.forgottenGemTracks(
                    minStars = 4.0,
                    olderThanMs = cutoff,
                    limit = limit,
                    byRating = rule.kind == SmartQueryKind.ForgottenGemsByRating,
                )
            }
            SmartQueryKind.LikedSongs ->
                ratingRepo.likedTracks().take(limit.toInt())
        }
    }

    /** Hook so tests can pin a deterministic clock; production = nowMillis(). */
    private fun nowMs(): Long = com.visorcraft.radioshed.data.nowMillis()

    /**
     * AdaptiveMix scoring. Pulls a 3× over-fetched candidate pool
     * (so we have meaningful headroom to score-and-trim) and ranks
     * each track by:
     *   - **Play penalty.** High lifetime plays push the track
     *     down; never-played tracks get the full unpenalised weight.
     *   - **Recency boost.** A track not played in the last 30 days
     *     gets a boost; one played today gets nothing. NULL
     *     last_played_at counts as max boost (never played).
     *   - **Rating boost.** Higher star ratings boost the score.
     *     Unrated tracks get a small baseline so they aren't
     *     systematically excluded — discovery still works for an
     *     un-rated library.
     *   - **Jitter.** ±5 % random noise breaks ties so consecutive
     *     plays of the same station don't produce identical
     *     orderings. Without jitter the same top-N would replay
     *     every queue refresh.
     *
     * Falls back to plain leastPlayedTracks if the candidate pool
     * is empty (which only happens on a freshly-imported library
     * with zero rows in `track`).
     */
    private suspend fun evaluateAdaptiveMix(limit: Long): List<Track> {
        val poolSize = (limit * ADAPTIVE_MIX_POOL_MULT).coerceAtMost(absoluteCeiling)
        val pool = ratingRepo.leastPlayedTracks(poolSize)
        if (pool.isEmpty()) return emptyList()

        val ids = pool.map { it.id }
        val plays = ratingRepo.playCountsForTracks(ids)
        val ratings = ratingRepo.ratingsForTracks(ids)

        val now = nowMs()
        val candidates = pool.map { track ->
            val playStats = plays[track.id]
            AdaptiveMixCandidate(
                track = track,
                playCount = playStats?.count ?: 0L,
                lastPlayedAtMs = playStats?.lastPlayedAt,
                rating = ratings[track.id] ?: 0.0,
            )
        }
        return scoreAdaptiveMix(
            candidates = candidates,
            nowMs = now,
            jitterSeed = now,
            limit = limit.toInt(),
        )
    }

    private suspend fun evaluateArtists(rule: SourceRule.ArtistSelector): List<Track> =
        rule.artistIds.flatMap { libraryRepo.tracksForArtist(it) }
            .distinctBy { it.id }

    private suspend fun evaluateAlbums(rule: SourceRule.AlbumSelector): List<Track> {
        // Use the indexed per-album query rather than scanning the
        // whole library once per album id. The previous implementation
        // was O(albums × N) where N could be 100k tracks; this is
        // O(sum-of-albums) using the album_id index.
        return rule.albumIds.flatMap { libraryRepo.tracksForAlbum(it) }
            .distinctBy { it.id }
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

        /** AdaptiveMix candidate-pool multiplier: pull 3× the
         *  requested limit so the scorer has headroom to trim. */
        const val ADAPTIVE_MIX_POOL_MULT = 3L
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
