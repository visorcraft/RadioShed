package com.visorcraft.radioshed.stations

import com.visorcraft.radioshed.domain.PlayOrder
import com.visorcraft.radioshed.domain.PlaybackRule
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.domain.Weighting
import kotlin.random.Random

/**
 * Pulls the next track for a station given a candidate set + [PlaybackRule].
 *
 * Smart-shuffle invariants (v1):
 *  - **No-repeat memory**: the last `noRepeatDepth` track-ids never come up
 *    again in the same window. When the depth is larger than the candidate
 *    set we let the oldest entries fall off rather than refusing to pick.
 *  - **Weighting**: `Uniform` is true random; `FavorRecent` biases towards
 *    higher `file_ctime`; the remaining policies fall back to `Uniform`
 *    in v1 because they need richer per-track stats (play counts, ratings)
 *    that Phase 5 part 2 wires through.
 *
 * Deferred to Phase 5 part 2:
 *  - **Artist / album spacing** — needs the artist-id pre-loaded per
 *    candidate; cheap to add once `SourceRuleEvaluator` returns enriched
 *    candidates.
 *  - **Per-station dislikes** — `RatingRepository.dislikedTrackIdsForStation`
 *    is plumbed; the queue accepts a `dislikedIds` set to filter out.
 *
 * [random] is parametric so unit tests can pin a deterministic seed.
 */
class PlaybackQueue(
    private val candidates: List<Track>,
    private val rule: PlaybackRule,
    private val dislikedIds: Set<Long> = emptySet(),
    private val random: Random = Random.Default,
    initialSequentialCursor: Int = 0,
) {

    /** Recently-played track ids, oldest first. Bounded at `2 × noRepeatDepth`. */
    private val recent: ArrayDeque<Long> = ArrayDeque()

    /**
     * Recently-picked album ids (most recent at the END). Used to space
     * out same-album hits in shuffle modes — without this, an album-
     * heavy candidate set tends to pick three in a row from the same
     * album, especially with small libraries. Sized at [ALBUM_SPACING],
     * which is intentionally small (default 3) so we don't completely
     * starve out a small library.
     */
    private val recentAlbums: ArrayDeque<Long> = ArrayDeque()

    /** Index for sequential mode. */
    private var sequentialCursor: Int = initialSequentialCursor.coerceAtLeast(0)

    val isEmpty: Boolean get() = candidates.all { it.id in dislikedIds }

    val candidateCount: Int get() = candidates.count { it.id !in dislikedIds }

    /** Pick the next track, advancing internal state. Returns null if no
     *  candidates remain (after dislikes are filtered). */
    fun next(): Track? {
        if (candidates.isEmpty()) return null
        val pool = candidates.filter { it.id !in dislikedIds }
        if (pool.isEmpty()) return null

        return when (rule.order) {
            PlayOrder.Sequential -> nextSequential(pool)
            PlayOrder.Shuffle, PlayOrder.SmartShuffle, PlayOrder.WeightedRandom ->
                nextSmartShuffle(pool)
        }
    }

    private fun nextSequential(pool: List<Track>): Track {
        val track = pool[sequentialCursor.coerceIn(0, pool.size - 1)]
        sequentialCursor = (sequentialCursor + 1) % pool.size
        recordPlayed(track.id, track.albumId)
        return track
    }

    private fun nextSmartShuffle(pool: List<Track>): Track {
        // Two avoid filters in priority order:
        //   1. Recent track ids (no-repeat memory, configurable depth).
        //   2. Recent album ids (album-spacing, fixed small window).
        // Apply both, then age them out — track-id recents first, then
        // album recents — until we have at least one candidate. Never
        // block: a one-track library still plays that track every time.
        var avoid = effectiveAvoidSet(pool.size)
        var avoidAlbums = effectiveAlbumAvoidSet()
        var available = pool.filter { it.id !in avoid && it.albumId !in avoidAlbums }
        while (available.isEmpty()) {
            when {
                recent.isNotEmpty() -> recent.removeFirst()
                recentAlbums.isNotEmpty() -> recentAlbums.removeFirst()
                else -> { available = pool; break }  // last-ditch fallback
            }
            avoid = effectiveAvoidSet(pool.size)
            avoidAlbums = effectiveAlbumAvoidSet()
            available = pool.filter { it.id !in avoid && it.albumId !in avoidAlbums }
        }
        if (available.isEmpty()) available = pool

        val picked = pickWeighted(available)
        recordPlayed(picked.id, picked.albumId)
        return picked
    }

    /** Last [ALBUM_SPACING] picked album ids — used to space repeats. */
    private fun effectiveAlbumAvoidSet(): Set<Long> {
        if (recentAlbums.isEmpty()) return emptySet()
        val end = recentAlbums.size
        val start = (end - ALBUM_SPACING).coerceAtLeast(0)
        return recentAlbums.toList().subList(start, end).toSet()
    }

    /**
     * Returns the set of recent ids to avoid. Capped at `pool.size - 1` so
     * we always leave at least one candidate available.
     */
    private fun effectiveAvoidSet(poolSize: Int): Set<Long> {
        val depth = rule.noRepeatDepth.coerceAtLeast(0).coerceAtMost(poolSize - 1)
        if (depth == 0) return emptySet()
        // recent stores oldest-first; the LAST `depth` entries are the most
        // recent and the ones we want to avoid.
        val end = recent.size
        val start = (end - depth).coerceAtLeast(0)
        return recent.toList().subList(start, end).toSet()
    }

    private fun pickWeighted(pool: List<Track>): Track {
        if (pool.size == 1) return pool.single()
        return when (rule.weighting) {
            Weighting.FavorRecent -> {
                // Weight ∝ rank by ctime, oldest=1, newest=N. Cheap softmax-ish.
                val sorted = pool.sortedBy { it.fileCtime }
                val weights = sorted.indices.map { (it + 1).toDouble() }
                weightedPick(sorted, weights)
            }
            // Other policies fall back to uniform in v1; documented in KDoc.
            Weighting.Uniform,
            Weighting.FavorUnderPlayed,
            Weighting.FavorHigherRated -> pool[random.nextInt(pool.size)]
        }
    }

    private fun weightedPick(items: List<Track>, weights: List<Double>): Track {
        val total = weights.sum()
        var r = random.nextDouble() * total
        for (i in items.indices) {
            r -= weights[i]
            if (r <= 0.0) return items[i]
        }
        return items.last()
    }

    private fun recordPlayed(id: Long, albumId: Long?) {
        recent.addLast(id)
        val cap = (rule.noRepeatDepth.coerceAtLeast(0) * 2)
            .coerceAtLeast(MIN_RECENT_CAP)
        while (recent.size > cap) recent.removeFirst()
        if (albumId != null) {
            recentAlbums.addLast(albumId)
            // Album buffer just needs to be at least ALBUM_SPACING long;
            // 2× gives some slack so we don't fall back to immediate
            // re-eligibility on the next pick.
            while (recentAlbums.size > ALBUM_SPACING * 2) recentAlbums.removeFirst()
        }
    }

    /** Snapshot of recently-played ids, oldest first. Useful for tests. */
    fun recentSnapshot(): List<Long> = recent.toList()

    /**
     * Peek up to [count] upcoming tracks WITHOUT advancing internal state.
     *
     * For [PlayOrder.Sequential] the result is exact — same tracks in
     * the same order the queue would actually play.
     *
     * For shuffle / weighted modes the next pick is randomized, so the
     * returned list is a "preview pool" — the eligible candidates after
     * applying no-repeat memory and dislikes, NOT a literal prediction
     * of what the queue will actually pick. The Up Next UI labels this
     * accordingly so users don't expect deterministic ordering on a
     * shuffle station.
     */
    fun peekUpcoming(count: Int): List<Track> {
        if (count <= 0 || candidates.isEmpty()) return emptyList()
        val pool = candidates.filter { it.id !in dislikedIds }
        if (pool.isEmpty()) return emptyList()
        return when (rule.order) {
            PlayOrder.Sequential -> {
                val n = count.coerceAtMost(pool.size)
                (0 until n).map { offset ->
                    pool[(sequentialCursor + offset) % pool.size]
                }
            }
            PlayOrder.Shuffle, PlayOrder.SmartShuffle, PlayOrder.WeightedRandom -> {
                val avoid = effectiveAvoidSet(pool.size)
                val available = pool.filter { it.id !in avoid }
                available.take(count)
            }
        }
    }

    private companion object {
        const val MIN_RECENT_CAP = 16

        /** How many of the most recent picks' album ids to avoid. Larger
         *  values feel more diverse but starve small libraries. 3 is a
         *  reasonable default that keeps "three songs from the same
         *  album in a row" from happening without preventing the same
         *  album from ever recurring on a small set. */
        const val ALBUM_SPACING = 3
    }
}
