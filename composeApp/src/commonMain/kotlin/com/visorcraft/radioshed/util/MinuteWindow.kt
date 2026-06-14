package com.visorcraft.radioshed.util

/**
 * True when this minute-of-day (0..1439) falls inside the half-open window
 * `[startMin, endMin)`.
 *
 * Edge semantics (chosen to MATCH the DJ quiet-hours logic that previously
 * lived inline in `DjAnnouncer.inQuietHours`, so refactoring that call site
 * onto this helper is behaviour-preserving):
 *  - `startMin == endMin` is an EMPTY window → never active (returns false).
 *    A zero-width quiet window suppresses nothing; a zero-width drive-schedule
 *    activates nothing. Treating `start == end` as full-day-active would make a
 *    misconfigured 0..0 window silently active 24/7, which is the more
 *    surprising failure mode.
 *  - `startMin < endMin` is a same-day window: active for `start <= m < end`.
 *  - `startMin > endMin` wraps across midnight (e.g. 23:00 → 01:00): active
 *    when `m >= start` OR `m < end`.
 *
 * The window is half-open on the end so two adjacent windows (…→09:00,
 * 09:00→…) don't both claim minute 540.
 */
fun Int.isInMinuteWindow(
    startMin: Int,
    endMin: Int,
): Boolean {
    if (startMin == endMin) return false
    return if (startMin < endMin) {
        this in startMin until endMin
    } else {
        // Crosses midnight: inside if at/after start OR before end.
        this >= startMin || this < endMin
    }
}
