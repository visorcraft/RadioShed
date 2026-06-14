package com.visorcraft.radioshed.data

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal actual fun nowMillis(): Long = System.currentTimeMillis()

internal actual fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

internal actual fun formatShortDate(epochMs: Long): String {
    val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
    fmt.timeZone = TimeZone.getDefault()
    return fmt.format(Date(epochMs))
}

internal actual fun formatIsoUtc(epochMs: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
    fmt.timeZone = TimeZone.getTimeZone("UTC")
    return fmt.format(Date(epochMs))
}

internal actual fun localMinutesOfDay(epochMs: Long): Int {
    val cal = Calendar.getInstance(TimeZone.getDefault())
    cal.timeInMillis = epochMs
    return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
}

internal actual fun formatClockTime(epochMs: Long): String {
    val fmt = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())
    fmt.timeZone = TimeZone.getDefault()
    return fmt.format(Date(epochMs))
}
