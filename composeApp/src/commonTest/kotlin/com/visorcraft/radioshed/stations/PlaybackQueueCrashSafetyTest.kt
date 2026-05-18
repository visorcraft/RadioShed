package com.visorcraft.radioshed.stations

import com.visorcraft.radioshed.domain.PlayOrder
import com.visorcraft.radioshed.domain.PlaybackRule
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.domain.Weighting
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Exhaustive crash-safety harness for [PlaybackQueue].
 *
 * Motivated by a hard crash users hit when a station's source rule
 * evaluated to zero tracks (or to a pool that the no-repeat / album /
 * artist avoid filters fully exhausted): `pickWeighted` would call
 * `random.nextInt(0)` and throw `IllegalArgumentException: bound
 * must be positive`, which surfaced as the whole RadioShed process
 * being killed. The fix is the `nextSmartShuffleSafe` guard that
 * returns null for empty pools — these tests lock that guarantee in.
 *
 * Test strategy: rather than enumerate the "interesting" combos by
 * hand and miss one, drive every combination of:
 *   - PlayOrder ∈ {Sequential, Shuffle, SmartShuffle, WeightedRandom}
 *   - Weighting ∈ {Uniform, FavorRecent, FavorUnderPlayed, FavorHigherRated}
 *   - candidate sets ∈ {empty, single-track, multi-track}
 *   - dislike sets ∈ {none, all-disliked}
 *   - noRepeatDepth ∈ {0, > pool.size}
 *   - artistSpacing / albumSpacing ∈ {0, > pool.size}
 * and assert next() either returns a Track or null — never throws.
 */
class PlaybackQueueCrashSafetyTest {

    private fun track(id: Long, albumId: Long? = null, ctime: Long = id * 1_000L): Track =
        Track(
            id = id,
            path = "/x/$id.mp3",
            fileName = "$id.mp3",
            fileSize = 1L,
            fileCtime = ctime,
            fileMtime = ctime,
            title = "T$id",
            albumId = albumId,
            durationMs = 1L,
            format = "mp3",
            addedAt = 0L,
            updatedAt = 0L,
        )

    private val allPlayOrders = listOf(
        PlayOrder.Sequential,
        PlayOrder.Shuffle,
        PlayOrder.SmartShuffle,
        PlayOrder.WeightedRandom,
    )

    private val allWeightings = listOf(
        Weighting.Uniform,
        Weighting.FavorRecent,
        Weighting.FavorUnderPlayed,
        Weighting.FavorHigherRated,
    )

    /**
     * The reported regression scenario plus its neighbors: an empty
     * candidate set across every play-order × weighting combination.
     * Pre-fix this trapped in `pickWeighted` via the shuffle paths.
     * Post-fix every combo must return null without throwing.
     */
    @Test
    fun empty_candidates_never_throw_for_any_order_or_weighting() {
        for (order in allPlayOrders) {
            for (weighting in allWeightings) {
                val q = PlaybackQueue(
                    candidates = emptyList(),
                    rule = PlaybackRule(order = order, weighting = weighting),
                )
                // 10 calls so a stateful queue (the smart-shuffle path
                // mutates recent / recentAlbums between picks) can't
                // crash on the second or third invocation if it
                // happened to survive the first by luck.
                repeat(10) {
                    val result = runCatching { q.next() }
                    assertSucceedsOrNull(
                        result,
                        context = "order=$order, weighting=$weighting, iteration=$it",
                    )
                }
            }
        }
    }

    /**
     * A queue whose dislike set covers every candidate. PlaybackQueue
     * filters the candidate list against dislikes at construction
     * time, so the public-facing candidate pool ends up empty even
     * though the caller passed tracks. Same crash surface as the
     * straight empty-candidates case; included separately so future
     * refactors can't accidentally introduce a path that bypasses
     * the dislike filter.
     */
    @Test
    fun fully_disliked_pool_never_throws() {
        val tracks = (1L..5L).map(::track)
        val allIds = tracks.map { it.id }.toSet()
        for (order in allPlayOrders) {
            for (weighting in allWeightings) {
                val q = PlaybackQueue(
                    candidates = tracks,
                    rule = PlaybackRule(order = order, weighting = weighting),
                    dislikedIds = allIds,
                )
                repeat(10) {
                    val result = runCatching { q.next() }
                    assertSucceedsOrNull(
                        result,
                        context = "order=$order, weighting=$weighting, iteration=$it",
                    )
                }
            }
        }
    }

    /**
     * noRepeatDepth larger than the pool used to be an "exhaust the
     * avoid set" path; the implementation now coerces depth down to
     * `pool.size - 1`. Verify every combo of inflated avoidance
     * windows is still safe.
     */
    @Test
    fun inflated_avoidance_windows_never_throw() {
        val tracks = (1L..3L).map(::track)
        val inflatedRules = listOf(
            PlaybackRule(noRepeatDepth = 100, artistSpacing = 0, albumSpacing = 0),
            PlaybackRule(noRepeatDepth = 0, artistSpacing = 100, albumSpacing = 0),
            PlaybackRule(noRepeatDepth = 0, artistSpacing = 0, albumSpacing = 100),
            PlaybackRule(noRepeatDepth = 100, artistSpacing = 100, albumSpacing = 100),
        )
        for (baseRule in inflatedRules) {
            for (order in allPlayOrders) {
                for (weighting in allWeightings) {
                    val rule = baseRule.copy(order = order, weighting = weighting)
                    val q = PlaybackQueue(
                        candidates = tracks,
                        rule = rule,
                        // Every track has the same album/artist id so
                        // the avoid sets exhaust the pool — the worst
                        // case for the shuffle inner loop.
                        artistByTrackId = tracks.associate { it.id to 42L },
                    )
                    repeat(20) {
                        val result = runCatching { q.next() }
                        assertSucceedsOrNull(
                            result,
                            context = "order=$order, weighting=$weighting, rule=$baseRule, iteration=$it",
                        )
                    }
                }
            }
        }
    }

    /**
     * The shuffle picker's `if (pool.size == 1) return pool.single()`
     * short-circuit used to be the only thing keeping single-track
     * pools out of the weighted path. Verify all combos still pick
     * the lone track repeatedly (and never null/throw).
     */
    @Test
    fun single_track_pool_picks_that_track_for_every_combo() {
        val only = track(1L, albumId = 7L)
        for (order in allPlayOrders) {
            for (weighting in allWeightings) {
                val q = PlaybackQueue(
                    candidates = listOf(only),
                    rule = PlaybackRule(order = order, weighting = weighting),
                    artistByTrackId = mapOf(1L to 42L),
                    playCountByTrackId = mapOf(1L to 5L),
                    ratingByTrackId = mapOf(1L to 4.0),
                    random = Random(0),
                )
                repeat(5) {
                    val picked = runCatching { q.next() }
                    val track = picked.getOrNull()
                    assertNotNull(track, "order=$order, weighting=$weighting, iter=$it -> null")
                }
            }
        }
    }

    /**
     * priorityNext + priorityLast (Play next / Add to queue) on an
     * empty rule pool. The priority head should still drain before
     * the candidate-pool guard returns null, even with zero
     * candidates.
     */
    @Test
    fun priority_queue_drains_even_with_empty_candidates() {
        val extra = track(99L)
        for (order in allPlayOrders) {
            for (weighting in allWeightings) {
                val q = PlaybackQueue(
                    candidates = emptyList(),
                    rule = PlaybackRule(order = order, weighting = weighting),
                )
                q.enqueueLast(extra)
                val first = runCatching { q.next() }
                assertNotNull(first.getOrNull(), "priority pick failed: order=$order, weighting=$weighting")
                // Pool is empty, priority is empty → must return null.
                val second = runCatching { q.next() }
                assertSucceedsOrNull(second, context = "post-drain: order=$order, weighting=$weighting")
            }
        }
    }

    /**
     * "Burn 1000 picks" stress test: a small pool with avoid windows
     * that constantly need relaxing. Catches state-machine drift in
     * `recent` / `recentAlbums` / `recentArtists` over many
     * iterations.
     */
    @Test
    fun thousand_pick_stress_never_throws() {
        val tracks = (1L..7L).map { track(it, albumId = it % 3) }
        val rule = PlaybackRule(
            order = PlayOrder.SmartShuffle,
            weighting = Weighting.FavorUnderPlayed,
            noRepeatDepth = 5,
            artistSpacing = 3,
            albumSpacing = 2,
        )
        val q = PlaybackQueue(
            candidates = tracks,
            rule = rule,
            artistByTrackId = tracks.associate { it.id to (it.id % 4) },
            playCountByTrackId = tracks.associate { it.id to it.id },
            random = Random(12345),
        )
        repeat(1_000) {
            val picked = runCatching { q.next() }
            assertNotNull(picked.getOrNull(), "iteration $it produced null on a non-empty pool")
        }
    }

    private fun assertSucceedsOrNull(result: Result<Track?>, context: String) {
        result.exceptionOrNull()?.let { ex ->
            kotlin.test.fail("PlaybackQueue.next() threw $ex [$context]")
        }
    }
}
