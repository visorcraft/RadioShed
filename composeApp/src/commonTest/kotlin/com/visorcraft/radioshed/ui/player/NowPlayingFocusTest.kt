package com.visorcraft.radioshed.ui.player

import androidx.compose.ui.focus.FocusRequester
import kotlin.test.Test
import kotlin.test.assertEquals

class NowPlayingFocusTest {
    @Test
    fun pauseMovesToAdjacentTransportControlsAndVerticalRows() {
        val neighbors = nowPlayingNeighbors(NowPlayingControl.PlayPause, compact = false)

        assertEquals(NowPlayingControl.Replay, neighbors.left)
        assertEquals(NowPlayingControl.Forward, neighbors.right)
        assertEquals(NowPlayingControl.Scrubber, neighbors.up)
        assertEquals(NowPlayingControl.Like, neighbors.down)
    }

    @Test
    fun compactTransportSkipsHiddenSeekButtons() {
        val neighbors = nowPlayingNeighbors(NowPlayingControl.PlayPause, compact = true)

        assertEquals(NowPlayingControl.Previous, neighbors.left)
        assertEquals(NowPlayingControl.Next, neighbors.right)
    }

    @Test
    fun closeAndScrubberFormVerticalRouteToPause() {
        assertEquals(NowPlayingControl.PlayPause, nowPlayingNeighbors(NowPlayingControl.Close, false).down)
        assertEquals(NowPlayingControl.PlayPause, nowPlayingNeighbors(NowPlayingControl.Scrubber, false).down)
        assertEquals(NowPlayingControl.Close, nowPlayingNeighbors(NowPlayingControl.Scrubber, false).up)
    }

    @Test
    fun bottomActionsFollowTheirVisibleOrder() {
        val expected =
            listOf(
                NowPlayingControl.Volume,
                NowPlayingControl.Visualizer,
                NowPlayingControl.Lyrics,
                NowPlayingControl.Shuffle,
                NowPlayingControl.Repeat,
                NowPlayingControl.Queue,
                NowPlayingControl.Sleep,
                NowPlayingControl.More,
            )

        expected.zipWithNext().forEach { (current, next) ->
            assertEquals(next, nowPlayingNeighbors(current, compact = false).right)
            assertEquals(current, nowPlayingNeighbors(next, compact = false).left)
        }
    }

    @Test
    fun compactBottomActionsSkipHiddenVisualizer() {
        assertEquals(NowPlayingControl.Lyrics, nowPlayingNeighbors(NowPlayingControl.Volume, compact = true).right)
        assertEquals(NowPlayingControl.Volume, nowPlayingNeighbors(NowPlayingControl.Lyrics, compact = true).left)
    }

    @Test
    fun ratingRowTraversesHeartThenAllFiveStars() {
        val expected =
            listOf(
                NowPlayingControl.Like,
                NowPlayingControl.Star1,
                NowPlayingControl.Star2,
                NowPlayingControl.Star3,
                NowPlayingControl.Star4,
                NowPlayingControl.Star5,
            )

        expected.zipWithNext().forEach { (current, next) ->
            assertEquals(next, nowPlayingNeighbors(current, compact = false).right)
            assertEquals(current, nowPlayingNeighbors(next, compact = false).left)
        }
    }

    @Test
    fun unattachedControlsFallBackWithoutCrashing() {
        val registry = NowPlayingFocusRegistry()

        assertEquals(FocusRequester.Default, registry.requesterOrDefault(NowPlayingControl.Replay))
        registry.attach(NowPlayingControl.Replay)
        assertEquals(registry[NowPlayingControl.Replay], registry.requesterOrDefault(NowPlayingControl.Replay))
        registry.detach(NowPlayingControl.Replay)
        assertEquals(FocusRequester.Default, registry.requesterOrDefault(NowPlayingControl.Replay))
    }
}
