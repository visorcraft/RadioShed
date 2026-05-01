package com.visorcraft.radioshed.data

/** Wall-clock epoch millis. Implemented per-platform to avoid extra deps. */
internal expect fun nowMillis(): Long
