package com.visorcraft.radioshed.data

import java.util.Calendar

internal actual fun nowMillis(): Long = System.currentTimeMillis()

internal actual fun currentYear(): Int =
    Calendar.getInstance().get(Calendar.YEAR)
