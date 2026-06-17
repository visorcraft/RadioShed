package com.visorcraft.radioshed.ui.util

import kotlin.math.absoluteValue
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Format milliseconds as a compact duration string.
 *
 * - `showHours = false` (default): `mm:ss` (e.g. "3:09").
 * - `showHours = true`: `h:mm:ss` only when an hour is present,
 *   otherwise `mm:ss`.
 *
 * Non-positive durations render as "—".
 */
fun formatDurationMs(
    ms: Long,
    showHours: Boolean = false,
): String {
    if (ms <= 0L) return "—"
    val totalSec = (ms / 1000L).toInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (showHours && h > 0) {
        "${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "${m + (h * 60)}:${s.toString().padStart(2, '0')}"
    }
}

/** Format seconds as [formatDurationMs]. */
fun formatDurationSeconds(
    seconds: Long,
    showHours: Boolean = false,
): String = formatDurationMs(seconds * 1000L, showHours)

/**
 * Format bytes as a human-readable size: "1.23 GB", "45.1 MB",
 * "900 KB", or "8 B". Non-positive sizes render as "—".
 */
fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val gb = bytes / 1_073_741_824.0
    if (gb >= 1.0) return "${gb.formatFixed(2)} GB"
    val mb = bytes / 1_048_576.0
    if (mb >= 1.0) return "${mb.formatFixed(1)} MB"
    val kb = bytes / 1024.0
    if (kb >= 1.0) return "${kb.formatFixed(0)} KB"
    return "$bytes B"
}

/**
 * Fixed-precision formatter replacing JVM-only `String.format("%.Nf")`.
 * Rounds half-up and pads with trailing zeros to the requested count.
 */
fun Double.formatFixed(decimals: Int): String {
    require(decimals >= 0) { "decimals must be non-negative" }
    // Round the magnitude, then attach the sign only when the rounded result
    // is non-zero. Rounding the magnitude separately preserves the minus for
    // values in (-1, 0) — plain integer division truncates toward zero and
    // would drop it (a -0.5 dB EQ cut rendering as "0.5", i.e. a boost) — while
    // the non-zero guard avoids the opposite wart of "-0" / "-0.0" for a value
    // that rounds away to zero magnitude.
    val magnitude = absoluteValue
    if (decimals == 0) {
        val rounded = magnitude.roundToLong()
        val sign = if (this < 0 && rounded != 0L) "-" else ""
        return "$sign$rounded"
    }
    val factor = 10.0.pow(decimals)
    val scaled = (magnitude * factor).roundToLong()
    val sign = if (this < 0 && scaled != 0L) "-" else ""
    val intPart = scaled / factor.toLong()
    val frac = scaled % factor.toLong()
    return "$sign$intPart.${frac.toString().padStart(decimals, '0')}"
}

/** [Double.formatFixed] for `Float`. */
fun Float.formatFixed(decimals: Int): String = toDouble().formatFixed(decimals)

/**
 * Fixed-precision formatter that always includes a sign (`+` / `-`).
 * Replaces JVM-only `String.format("%+.Nf")`.
 */
fun Double.formatSignedFixed(decimals: Int): String =
    (if (this >= 0) "+" else "-") + absoluteValue.formatFixed(decimals)

/** [Double.formatSignedFixed] for `Float`. */
fun Float.formatSignedFixed(decimals: Int): String = toDouble().formatSignedFixed(decimals)
