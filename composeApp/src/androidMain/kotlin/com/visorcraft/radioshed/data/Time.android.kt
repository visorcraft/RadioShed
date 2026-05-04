package com.visorcraft.radioshed.data

import java.util.Calendar

internal actual fun nowMillis(): Long = System.currentTimeMillis()

internal actual fun currentYear(): Int =
    Calendar.getInstance().get(Calendar.YEAR)

internal actual fun formatShortDate(epochMs: Long): String {
    // SimpleDateFormat with "MMM d" produces "Apr 4" / "Dec 31" in the
    // device locale. We pin the timezone to default so a user in Tokyo
    // sees the date in their wall-clock zone, not UTC. A single shared
    // formatter would be marginally more efficient but isn't
    // thread-safe; instances are cheap on Android so we re-create.
    val fmt = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
    fmt.timeZone = java.util.TimeZone.getDefault()
    return fmt.format(java.util.Date(epochMs))
}
