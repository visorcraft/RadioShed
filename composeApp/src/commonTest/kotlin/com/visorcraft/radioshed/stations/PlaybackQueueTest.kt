package com.visorcraft.radioshed.stations

import com.visorcraft.radioshed.domain.PlayOrder
import com.visorcraft.radioshed.domain.PlaybackRule
import com.visorcraft.radioshed.domain.Track
import com.visorcraft.radioshed.domain.Weighting
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackQueueTest {

    private fun track(id: Long, title: String = "T$id", ctime: Long = id * 1000L): Track =
        Track(
            id = id,
            path = "/x/$id.mp3",
            fileName = "$id.mp3",
            fileSize = 1L,
            fileCtime = ctime,
            fileMtime = ctime,
            title = title,
            durationMs = 1L,
            format = "mp3",
            addedAt = 0L,
            updatedAt = 0L,
        )

    @Test
    fun returns_null_for_empty_candidates() {
        val q = PlaybackQueue(candidates = emptyList(), rule = PlaybackRule())
        assertNull(q.next())
    }

    @Test
    fun sequential_wraps_around() {
        val tracks = (1L..3L).map(::track)
        val q = PlaybackQueue(
            tracks,
            PlaybackRule(order = PlayOrder.Sequential),
        )
        assertEquals(1L, q.next()?.id)
        assertEquals(2L, q.next()?.id)
        assertEquals(3L, q.next()?.id)
        assertEquals(1L, q.next()?.id)
    }

    @Test
    fun sequential_stops_after_last_when_wrap_disabled() {
        // Regression: Repeat = Off used to behave the same as Repeat
        // = All because nextSequential's `(cursor + 1) % size` always
        // wrapped. The user-facing symptom was a single track played
        // via `playTracks(listOf(track))` looping forever despite the
        // toggle reading "Repeat off".
        val tracks = (1L..3L).map(::track)
        val q = PlaybackQueue(
            tracks,
            PlaybackRule(order = PlayOrder.Sequential),
        )
        assertEquals(1L, q.next(allowWrap = false)?.id)
        assertEquals(2L, q.next(allowWrap = false)?.id)
        assertEquals(3L, q.next(allowWrap = false)?.id)
        assertNull(q.next(allowWrap = false))
    }

    @Test
    fun sequential_single_track_stops_when_wrap_disabled() {
        // The single-track case the original bug report hit head-on.
        val tracks = listOf(track(1L))
        val q = PlaybackQueue(
            tracks,
            PlaybackRule(order = PlayOrder.Sequential),
        )
        assertEquals(1L, q.next(allowWrap = false)?.id)
        assertNull(q.next(allowWrap = false))
    }

    @Test
    fun smart_shuffle_avoids_recent_within_depth() {
        // 5 tracks, no-repeat depth 3, pin random for determinism
        val tracks = (1L..5L).map(::track)
        val q = PlaybackQueue(
            candidates = tracks,
            rule = PlaybackRule(
                order = PlayOrder.SmartShuffle,
                noRepeatDepth = 3,
                weighting = Weighting.Uniform,
            ),
            random = Random(seed = 42L),
        )
        // Pull 4 picks — first three set up the no-repeat window, the
        // fourth must not equal any of the previous three.
        val picks = (0 until 4).map { q.next()!!.id }
        assertEquals(4, picks.size)
        val avoid = picks.subList(0, 3).toSet()
        assertTrue(picks[3] !in avoid, "fourth pick collided with the no-repeat window: $picks")
    }

    @Test
    fun no_repeat_depth_clamped_when_pool_smaller() {
        // depth=10 but pool=2. The queue must still return a track on every call.
        val tracks = (1L..2L).map(::track)
        val q = PlaybackQueue(
            candidates = tracks,
            rule = PlaybackRule(noRepeatDepth = 10),
            random = Random(seed = 1L),
        )
        repeat(20) {
            assertNotNull(q.next(), "queue stalled at iteration $it")
        }
    }

    @Test
    fun disliked_tracks_are_excluded() {
        val tracks = (1L..5L).map(::track)
        val q = PlaybackQueue(
            candidates = tracks,
            rule = PlaybackRule(),
            dislikedIds = setOf(2L, 4L),
            random = Random(seed = 7L),
        )
        val picks = (0 until 50).map { q.next()!!.id }.toSet()
        assertTrue(2L !in picks)
        assertTrue(4L !in picks)
    }

    @Test
    fun favor_recent_biases_towards_newer_ctimes() {
        // 100 tracks, ctime 1..100. Pull many picks; mean id should skew high.
        val tracks = (1L..100L).map { track(it, ctime = it) }
        val q = PlaybackQueue(
            candidates = tracks,
            rule = PlaybackRule(
                order = PlayOrder.SmartShuffle,
                noRepeatDepth = 0,
                weighting = Weighting.FavorRecent,
            ),
            random = Random(seed = 2024L),
        )
        val picks = (0 until 1000).map { q.next()!!.id }
        val mean = picks.average()
        // Uniform mean would be 50.5; FavorRecent should clearly beat it.
        assertTrue(mean > 60.0, "FavorRecent didn't bias toward newer; mean=$mean")
    }

    @Test
    fun is_empty_when_all_disliked() {
        val tracks = (1L..3L).map(::track)
        val q = PlaybackQueue(
            candidates = tracks,
            rule = PlaybackRule(),
            dislikedIds = setOf(1L, 2L, 3L),
        )
        assertTrue(q.isEmpty)
        assertNull(q.next())
    }
}
