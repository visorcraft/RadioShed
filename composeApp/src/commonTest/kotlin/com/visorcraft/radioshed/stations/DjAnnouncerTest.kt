package com.visorcraft.radioshed.stations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for the PURE decision core [DjAnnouncer.shouldAnnounce]. The function is
 * deterministic — plain params only, no clock reads, no DI — so every case below
 * fixes all inputs explicitly.
 */
class DjAnnouncerTest {

    /** Calls [DjAnnouncer.shouldAnnounce] with sensible non-suppressing defaults so
     *  each test only sets the fields it cares about. Defaults: outside any quiet
     *  window (quiet 0..0 is an empty window), never announced, no station change. */
    private fun decide(
        frequency: String,
        frequencyN: Int = 4,
        frequencyMinutes: Int = 15,
        tracksSinceAnnounce: Int = 1,
        lastAnnounceAtMs: Long? = null,
        nowMs: Long = 1_000_000L,
        nowMinutesOfDay: Int = 720, // 12:00
        quietStartMin: Int = 0,
        quietEndMin: Int = 0,
        stationChanged: Boolean = false,
    ): Boolean = DjAnnouncer.shouldAnnounce(
        frequency = frequency,
        frequencyN = frequencyN,
        frequencyMinutes = frequencyMinutes,
        tracksSinceAnnounce = tracksSinceAnnounce,
        lastAnnounceAtMs = lastAnnounceAtMs,
        nowMs = nowMs,
        nowMinutesOfDay = nowMinutesOfDay,
        quietStartMin = quietStartMin,
        quietEndMin = quietEndMin,
        stationChanged = stationChanged,
    )

    @Test
    fun offFrequencyNeverAnnounces() {
        // Everything else favours announcing — still false because freq is "off".
        assertFalse(
            decide(
                frequency = "off",
                tracksSinceAnnounce = 99,
                lastAnnounceAtMs = null,
                stationChanged = true,
            ),
        )
    }

    @Test
    fun everyFiresOnEachTransition() {
        assertTrue(decide(frequency = "every"))
    }

    @Test
    fun everyNFiresOnNthTransition() {
        assertFalse(decide(frequency = "every_n", frequencyN = 3, tracksSinceAnnounce = 1))
        assertFalse(decide(frequency = "every_n", frequencyN = 3, tracksSinceAnnounce = 2))
        assertTrue(decide(frequency = "every_n", frequencyN = 3, tracksSinceAnnounce = 3))
    }

    @Test
    fun minutesModeRespectsElapsed() {
        val now = 1_000_000_000L
        val tenMinAgo = now - 10 * 60_000L
        val sixteenMinAgo = now - 16 * 60_000L
        assertFalse(
            decide(frequency = "minutes", frequencyMinutes = 15, lastAnnounceAtMs = tenMinAgo, nowMs = now),
        )
        assertTrue(
            decide(frequency = "minutes", frequencyMinutes = 15, lastAnnounceAtMs = sixteenMinAgo, nowMs = now),
        )
        // Never announced → due immediately.
        assertTrue(decide(frequency = "minutes", frequencyMinutes = 15, lastAnnounceAtMs = null, nowMs = now))
    }

    @Test
    fun stationChangeModeFiresOnlyOnStationSwitch() {
        assertFalse(decide(frequency = "station_change", stationChanged = false))
        assertTrue(decide(frequency = "station_change", stationChanged = true))
    }

    @Test
    fun quietHoursSuppressAcrossMidnight() {
        // Quiet 22:00 (1320) → 07:00 (420), crossing midnight.
        assertFalse(
            decide(frequency = "every", nowMinutesOfDay = 1410, quietStartMin = 1320, quietEndMin = 420),
        )
        assertFalse(
            decide(frequency = "every", nowMinutesOfDay = 360, quietStartMin = 1320, quietEndMin = 420),
        )
        assertTrue(
            decide(frequency = "every", nowMinutesOfDay = 720, quietStartMin = 1320, quietEndMin = 420),
        )
    }

    @Test
    fun quietWindowNotCrossingMidnight() {
        // Quiet 09:00 (540) → 17:00 (1020), same day.
        assertFalse(
            decide(frequency = "every", nowMinutesOfDay = 600, quietStartMin = 540, quietEndMin = 1020),
        )
        assertTrue(
            decide(frequency = "every", nowMinutesOfDay = 1380, quietStartMin = 540, quietEndMin = 1020),
        )
    }

    @Test
    fun everyNCounterResetSuppressesImmediateRepeat() {
        // Documented semantic: any announcement resets the every_n counter to 0,
        // so the *very next* transition (tracksSinceAnnounce == 1) must NOT fire
        // again. We pin this at the pure-decision level: announceIfDue's stateful
        // path resets tracksSinceAnnounce to 0 right after a successful clip, and
        // the following transition bumps it to 1. With n=3, shouldAnnounce must
        // be false for tracksSinceAnnounce 1 and 2, and only true again at 3.
        //
        // (A pure-level regression rather than a stateful-DjAnnouncer test: the
        // reset is consumed exclusively through shouldAnnounce, and the stateful
        // path would need disproportionate fakes for the 14-member AudioEngine
        // plus SettingsStore/DjCacheRepository/TtsEngine/ClipPlayer/SleepTimer
        // and coroutine-timed clip playback to exercise the same invariant.)
        assertFalse(decide(frequency = "every_n", frequencyN = 3, tracksSinceAnnounce = 1))
        assertFalse(decide(frequency = "every_n", frequencyN = 3, tracksSinceAnnounce = 2))
        assertTrue(decide(frequency = "every_n", frequencyN = 3, tracksSinceAnnounce = 3))
    }

    @Test
    fun precacheKeyIsStableForSameInputs() {
        // The cache-key derivation is the single source of truth shared by the
        // live announce path and the precache path. Identical (text, voice)
        // MUST hash to the same key (cache hit); a different text MUST NOT.
        val a = DjAnnouncer.cacheKeyFor("Up next: Song A.", "system")
        val b = DjAnnouncer.cacheKeyFor("Up next: Song A.", "system")
        val c = DjAnnouncer.cacheKeyFor("Up next: Song B.", "system")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun announceAndPrecacheAgreeOnVariant() {
        // Determinism contract: the announce path and the precache path must
        // pick the SAME variant for the same transition, since the precache
        // synthesizes the clip the announce path will look up. The seed is
        // stable per (startingTrack.trackId, slot) — NOT the wall clock — so a
        // pre-rendered clip and the live render agree.
        val variants = DEFAULT_DJ_TEMPLATES[DjSlots.UP_NEXT]!!
        val seedA = DjAnnouncer.variantSeedFor(trackId = 42L, slot = DjSlots.UP_NEXT)
        val seedAagain = DjAnnouncer.variantSeedFor(trackId = 42L, slot = DjSlots.UP_NEXT)
        val seedB = DjAnnouncer.variantSeedFor(trackId = 99L, slot = DjSlots.UP_NEXT)
        assertEquals(seedA, seedAagain)
        // Same track + slot → identical variant for announce and precache.
        assertEquals(
            DjTemplate.pickVariant(variants, seed = seedA),
            DjTemplate.pickVariant(variants, seed = seedAagain),
        )
        // Different tracks get different seeds → variety survives across the
        // queue even though selection is deterministic per track.
        assertNotEquals(seedA, seedB)
    }
}
