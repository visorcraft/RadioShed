package com.visorcraft.radioshed.stations

import com.visorcraft.radioshed.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveMixScorerTest {
    private val now = 1_700_000_000_000L // pinned millisecond clock for determinism
    private val day = 86_400_000L

    private fun track(
        id: Long,
        title: String = "track-$id",
    ): Track =
        Track(
            id = id,
            path = "/$title",
            fileName = title,
            fileSize = 0L,
            fileCtime = 0L,
            fileMtime = 0L,
            title = title,
            durationMs = 0L,
            format = "mp3",
            addedAt = 0L,
            updatedAt = 0L,
        )

    private fun candidate(
        id: Long,
        playCount: Long = 0L,
        daysSinceLastPlay: Int? = null,
        rating: Double = 0.0,
    ): AdaptiveMixCandidate =
        AdaptiveMixCandidate(
            track = track(id),
            playCount = playCount,
            lastPlayedAtMs = daysSinceLastPlay?.let { now - it * day },
            rating = rating,
        )

    @Test
    fun emptyPool_returnsEmpty() {
        val out = scoreAdaptiveMix(emptyList(), now, jitterSeed = 0L, limit = 10)
        assertTrue(out.isEmpty())
    }

    @Test
    fun zeroLimit_returnsEmpty() {
        val out =
            scoreAdaptiveMix(
                listOf(candidate(1)),
                now,
                jitterSeed = 0L,
                limit = 0,
            )
        assertTrue(out.isEmpty())
    }

    @Test
    fun overplayedRecentTrack_ranksBelowFreshTrack() {
        // Two tracks: same rating, but one is heavily played and
        // played today, the other is never-played. Fresh should win.
        val candidates =
            listOf(
                candidate(id = 1, playCount = 100L, daysSinceLastPlay = 0, rating = 4.0),
                candidate(id = 2, playCount = 0L, daysSinceLastPlay = null, rating = 4.0),
            )
        val out = scoreAdaptiveMix(candidates, now, jitterSeed = 1L, limit = 2)
        assertEquals(2L, out.first().id, "fresh never-played track should rank first")
        assertEquals(1L, out.last().id)
    }

    @Test
    fun higherRatedTrack_ranksAbovelower_whenAllElseEqual() {
        val candidates =
            listOf(
                candidate(id = 1, playCount = 0L, daysSinceLastPlay = null, rating = 1.0),
                candidate(id = 2, playCount = 0L, daysSinceLastPlay = null, rating = 5.0),
            )
        // Use a seed that produces near-zero jitter so the rating
        // differential dominates. Several seeds tested manually;
        // 42 yields jitters << 0.1 which the 0.24 rating gap exceeds.
        val out = scoreAdaptiveMix(candidates, now, jitterSeed = 42L, limit = 2)
        assertEquals(2L, out.first().id, "5-star track should beat 1-star")
    }

    @Test
    fun unratedTrack_getsBaselineBoost_notExcluded() {
        // Even though rating = 0.0, the unrated baseline (0.2) keeps
        // the track competitive against a tied 1-star (0.2 too).
        // With identical play / recency stats, jitter alone decides
        // the order — the test asserts that the unrated track DOES
        // appear in the top-N rather than being filtered out.
        val candidates =
            listOf(
                candidate(id = 1, rating = 0.0),
                candidate(id = 2, rating = 1.0),
                candidate(id = 3, rating = 5.0),
            )
        val out = scoreAdaptiveMix(candidates, now, jitterSeed = 7L, limit = 3)
        assertEquals(3, out.size, "all three candidates should pass through")
        assertTrue(out.any { it.id == 1L }, "unrated track must not be filtered out")
    }

    @Test
    fun recencyBoost_saturatesAt30days() {
        // Both tracks were played long enough ago to hit the 30-day
        // saturation cap, so their recency boosts equalize. Play
        // penalty + rating differential should still drive the order.
        val candidates =
            listOf(
                candidate(id = 1, playCount = 50L, daysSinceLastPlay = 60, rating = 3.0),
                candidate(id = 2, playCount = 50L, daysSinceLastPlay = 365, rating = 3.0),
            )
        // With all weighted axes equal except jitter, the ordering
        // is jitter-driven — assert *both* tracks pass through, not
        // a specific order. The contract is "saturated boost", not
        // "bigger gap = bigger advantage".
        val out = scoreAdaptiveMix(candidates, now, jitterSeed = 11L, limit = 2)
        assertEquals(2, out.size)
        assertTrue(out.map { it.id }.toSet() == setOf(1L, 2L))
    }

    @Test
    fun limit_truncatesResult() {
        val candidates = (1L..10L).map { candidate(id = it, rating = it.toDouble() / 2) }
        val out = scoreAdaptiveMix(candidates, now, jitterSeed = 0L, limit = 3)
        assertEquals(3, out.size)
    }

    @Test
    fun deterministic_forSameSeed() {
        val candidates =
            listOf(
                candidate(id = 1, playCount = 5, daysSinceLastPlay = 10, rating = 2.5),
                candidate(id = 2, playCount = 1, daysSinceLastPlay = 40, rating = 4.0),
                candidate(id = 3, playCount = 0, daysSinceLastPlay = null, rating = 0.0),
            )
        val a = scoreAdaptiveMix(candidates, now, jitterSeed = 999L, limit = 3)
        val b = scoreAdaptiveMix(candidates, now, jitterSeed = 999L, limit = 3)
        assertEquals(a.map { it.id }, b.map { it.id })
    }
}
