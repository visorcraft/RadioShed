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
) {

    /** Recently-played track ids, oldest first. Bounded at `2 × noRepeatDepth`. */
    private val recent: ArrayDeque<Long> = ArrayDeque()

    /** Index for sequential mode. */
    private var sequentialCursor: Int = 0

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
        recordPlayed(track.id)
        return track
    }

    private fun nextSmartShuffle(pool: List<Track>): Track {
        // Apply no-repeat memory: prefer tracks not in `recent`. If everything
        // in `pool` is recent (small pool), age out the oldest until something
        // is available — never block.
        var avoid = effectiveAvoidSet(pool.size)
        var available = pool.filter { it.id !in avoid }
        while (available.isEmpty() && recent.isNotEmpty()) {
            recent.removeFirst()
            avoid = effectiveAvoidSet(pool.size)
            available = pool.filter { it.id !in avoid }
        }
        if (available.isEmpty()) available = pool   // last-ditch

        val picked = pickWeighted(available)
        recordPlayed(picked.id)
        return picked
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

    private fun recordPlayed(id: Long) {
        recent.addLast(id)
        val cap = (rule.noRepeatDepth.coerceAtLeast(0) * 2)
            .coerceAtLeast(MIN_RECENT_CAP)
        while (recent.size > cap) recent.removeFirst()
    }

    /** Snapshot of recently-played ids, oldest first. Useful for tests. */
    fun recentSnapshot(): List<Long> = recent.toList()

    private companion object {
        const val MIN_RECENT_CAP = 16
    }
}
