package com.visorcraft.radioshed.data

import kotlin.system.getTimeMillis

// Pure Kotlin. The previous NSDate factory calls do not resolve in the
// current Kotlin/Native Foundation bindings, and this target is still a stub.

internal actual fun nowMillis(): Long = getTimeMillis()

internal actual fun currentYear(): Int = civilFromEpochMs(nowMillis()).year

internal actual fun formatShortDate(epochMs: Long): String {
    val date = civilFromEpochMs(epochMs)
    return "${MONTHS[date.month - 1]} ${date.day}"
}

internal actual fun formatIsoUtc(epochMs: Long): String {
    val date = civilFromEpochMs(epochMs)
    return buildString {
        append(date.year.toString().padStart(4, '0'))
        append('-')
        append(date.month.toString().padStart(2, '0'))
        append('-')
        append(date.day.toString().padStart(2, '0'))
        append('T')
        append(date.hour.toString().padStart(2, '0'))
        append(':')
        append(date.minute.toString().padStart(2, '0'))
        append(':')
        append(date.second.toString().padStart(2, '0'))
        append('Z')
    }
}

internal actual fun localMinutesOfDay(epochMs: Long): Int {
    val date = civilFromEpochMs(epochMs)
    return date.hour * 60 + date.minute
}

internal actual fun formatClockTime(epochMs: Long): String {
    val date = civilFromEpochMs(epochMs)
    val hour12 = ((date.hour + 11) % 12) + 1
    val suffix = if (date.hour < 12) "AM" else "PM"
    return "$hour12:${date.minute.toString().padStart(2, '0')} $suffix"
}

private val MONTHS =
    arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private data class Civil(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
)

/**
 * UTC civil date. Days algorithm is Howard Hinnant's civil_from_days.
 * `localMinutesOfDay` and `formatClockTime` also report UTC: the Foundation
 * timezone factories this stub used to call do not resolve, and iOS is not
 * a shipping target yet.
 */
private fun civilFromEpochMs(epochMs: Long): Civil {
    val secs = epochMs / 1000
    val secondOfDay = secs.mod(86_400L).toInt()
    val days = secs.floorDiv(86_400L)
    val hour = secondOfDay / 3600
    val minute = (secondOfDay % 3600) / 60
    val second = secondOfDay % 60

    val z = days + 719_468L
    // Toward-zero division, matching the C++ algorithm's adjusted numerator.
    val era = (if (z >= 0) z else z - 146_096L) / 146_097L
    val doe = (z - era * 146_097L).toInt()
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    var year = (yoe + era * 400).toInt()
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = mp + if (mp < 10) 3 else -9
    if (month <= 2) year += 1
    return Civil(year, month, day, hour, minute, second)
}
