package com.visorcraft.radioshed.data

/** Wall-clock epoch millis. Implemented per-platform to avoid extra deps. */
internal expect fun nowMillis(): Long

/** Current local-time year — used by date-driven UI features that
 *  need a runtime upper bound (e.g. the decade auto-station generator)
 *  rather than baking a literal `2030` into the source. */
internal expect fun currentYear(): Int
