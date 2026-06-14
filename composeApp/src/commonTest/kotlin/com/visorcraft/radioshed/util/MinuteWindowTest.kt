package com.visorcraft.radioshed.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the shared [isInMinuteWindow] helper. Edge semantics MUST match the
 * DJ quiet-hours behaviour they were extracted from (see [MinuteWindow.kt] KDoc):
 * empty window (start == end) is never active, the end is exclusive, and
 * start > end wraps across midnight.
 */
class MinuteWindowTest {
    @Test
    fun sameDayWindowIsHalfOpen() {
        // 09:00 (540) → 17:00 (1020).
        assertTrue(540.isInMinuteWindow(540, 1020)) // start is inclusive
        assertTrue(600.isInMinuteWindow(540, 1020)) // interior
        assertFalse(1020.isInMinuteWindow(540, 1020)) // end is exclusive
        assertFalse(1380.isInMinuteWindow(540, 1020)) // after
        assertFalse(300.isInMinuteWindow(540, 1020)) // before
    }

    @Test
    fun crossMidnightWindowWraps() {
        // 23:00 (1380) → 01:00 (60).
        assertTrue(1380.isInMinuteWindow(1380, 60)) // at start
        assertTrue(1410.isInMinuteWindow(1380, 60)) // late evening, after start
        assertTrue(30.isInMinuteWindow(1380, 60)) // early morning, before end
        assertFalse(60.isInMinuteWindow(1380, 60)) // end is exclusive
        assertFalse(720.isInMinuteWindow(1380, 60)) // mid-day, outside
    }

    @Test
    fun emptyWindowIsNeverActive() {
        // start == end suppresses nothing / activates nothing, regardless of
        // where the probe lands.
        assertFalse(0.isInMinuteWindow(0, 0))
        assertFalse(720.isInMinuteWindow(720, 720))
        assertFalse(540.isInMinuteWindow(540, 540))
    }

    @Test
    fun fullDayWindowMinusOneMinute() {
        // 00:00 → 23:59 is active everywhere except the last minute.
        assertTrue(0.isInMinuteWindow(0, 1439))
        assertTrue(720.isInMinuteWindow(0, 1439))
        assertFalse(1439.isInMinuteWindow(0, 1439)) // end exclusive
    }
}
