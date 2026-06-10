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

/**
 * RFC-3339 / ISO-8601 timestamp at UTC, e.g. "2026-05-03T19:24:11Z".
 * Used by exporters that need a parser-friendly date column —
 * spreadsheet apps reliably sniff this format as a date.
 */
internal expect fun formatIsoUtc(epochMs: Long): String

/**
 * Local wall-clock minutes-of-day (0..1439) for [epochMs]. Implemented
 * per-platform because the conversion is timezone-dependent — the DJ
 * quiet-hours window is configured in the user's local zone, so a UTC
 * derivation would shift the window. Used by the DJ announcer to test the
 * current time against the quiet window. */
internal expect fun localMinutesOfDay(epochMs: Long): Int

/**
 * Locale-friendly clock time like "7:45 AM" / "23:14" for [epochMs] — the
 * `{time}` DJ template variable. Format follows the device's 12/24-hour
 * preference (platform short-time style). */
internal expect fun formatClockTime(epochMs: Long): String
