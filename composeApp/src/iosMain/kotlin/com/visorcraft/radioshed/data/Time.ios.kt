package com.visorcraft.radioshed.data

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

internal actual fun nowMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()

internal actual fun currentYear(): Int =
    NSCalendar.currentCalendar.component(NSCalendarUnitYear, NSDate()).toInt()
