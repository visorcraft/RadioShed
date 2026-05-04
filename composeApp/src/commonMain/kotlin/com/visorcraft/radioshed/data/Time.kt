package com.visorcraft.radioshed.data

/** Wall-clock epoch millis. Implemented per-platform to avoid extra deps. */
internal expect fun nowMillis(): Long

/** Current local-time year — used by date-driven UI features that
 *  need a runtime upper bound (e.g. the decade auto-station generator)
 *  rather than baking a literal `2030` into the source. */
internal expect fun currentYear(): Int

/** Short locale-friendly date label like "Apr 4" / "Dec 31" — used
 *  for the listening-stats date-range header. Falls back to ISO
 *  "MM-DD" on platforms that don't expose a locale formatter. */
internal expect fun formatShortDate(epochMs: Long): String
