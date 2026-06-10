package com.visorcraft.radioshed.data

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

internal actual fun nowMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()

internal actual fun currentYear(): Int =
    NSCalendar.currentCalendar.component(NSCalendarUnitYear, NSDate()).toInt()

internal actual fun formatShortDate(epochMs: Long): String {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)
    val fmt = platform.Foundation.NSDateFormatter()
    fmt.dateFormat = "MMM d"
    return fmt.stringFromDate(date)
}

internal actual fun formatIsoUtc(epochMs: Long): String {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)
    val fmt = platform.Foundation.NSDateFormatter()
    fmt.dateFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'"
    fmt.timeZone = platform.Foundation.NSTimeZone.timeZoneWithAbbreviation("UTC")
        ?: platform.Foundation.NSTimeZone.systemTimeZone
    return fmt.stringFromDate(date)
}

internal actual fun localMinutesOfDay(epochMs: Long): Int {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)
    val cal = NSCalendar.currentCalendar
    val hour = cal.component(NSCalendarUnitHour, date).toInt()
    val minute = cal.component(NSCalendarUnitMinute, date).toInt()
    return hour * 60 + minute
}

internal actual fun formatClockTime(epochMs: Long): String {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)
    val fmt = platform.Foundation.NSDateFormatter()
    // dateFormat string is the only NSDateFormatter surface verified in this file; no Mac to compile-check new API.
    fmt.dateFormat = "h:mm a"
    return fmt.stringFromDate(date)
}
