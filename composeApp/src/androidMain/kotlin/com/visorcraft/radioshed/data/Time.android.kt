package com.visorcraft.radioshed.data

import java.util.Calendar

internal actual fun nowMillis(): Long = System.currentTimeMillis()

internal actual fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

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

internal actual fun formatIsoUtc(epochMs: Long): String {
    // Locale.ROOT prevents the formatter from substituting localized
    // digits (Arabic, Bengali, etc.) — RFC-3339 requires ASCII.
    val fmt =
        java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            java.util.Locale.ROOT,
        )
    fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return fmt.format(java.util.Date(epochMs))
}

internal actual fun localMinutesOfDay(epochMs: Long): Int {
    val cal = Calendar.getInstance(java.util.TimeZone.getDefault())
    cal.timeInMillis = epochMs
    return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
}

internal actual fun formatClockTime(epochMs: Long): String {
    // getTimeInstance(SHORT) follows the device 12/24-hour preference,
    // producing "7:45 AM" or "07:45" as the user has set.
    val fmt =
        java.text.DateFormat.getTimeInstance(
            java.text.DateFormat.SHORT,
            java.util.Locale.getDefault(),
        )
    fmt.timeZone = java.util.TimeZone.getDefault()
    return fmt.format(java.util.Date(epochMs))
}
