package com.visorcraft.radioshed.data

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

internal actual fun nowMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()
