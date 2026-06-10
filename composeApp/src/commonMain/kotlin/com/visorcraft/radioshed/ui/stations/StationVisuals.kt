package com.visorcraft.radioshed.ui.stations

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.visorcraft.radioshed.domain.Station

/**
 * Shared station-tile visuals (accent colour + fallback gradient) used by both
 * Stations Home's tiles and Drive Mode's [com.visorcraft.radioshed.ui.car.DriveStationPicker]
 * tiles. Extracted so the two surfaces can't drift apart (the Drive copies were
 * verbatim duplicates).
 */

/** Brand-amber default when the station has no custom colour. */
internal fun stationColor(station: Station): Color {
    if (station.color == 0) return Color(0xFFFF7F1A) // brand amber default
    return Color(station.color.toLong() and 0xFFFFFFFFL)
}

/**
 * Three-stop diagonal gradient: full-saturation accent at top-left, a
 * darkened version at center, lighter-blended at bottom-right. Reads as
 * "designed" rather than "placeholder" without needing actual artwork
 * for each station.
 */
internal fun stationGradient(accent: Color): Brush {
    // Slightly darken/lighten via alpha + black/white mix at the stops.
    val darker = Color(
        red = accent.red * 0.55f,
        green = accent.green * 0.55f,
        blue = accent.blue * 0.65f,
        alpha = 1f,
    )
    val lighter = Color(
        red = (accent.red + 0.1f).coerceAtMost(1f),
        green = (accent.green + 0.1f).coerceAtMost(1f),
        blue = (accent.blue + 0.1f).coerceAtMost(1f),
        alpha = 0.85f,
    )
    return Brush.linearGradient(
        colorStops = arrayOf(
            0f to accent,
            0.55f to darker,
            1f to lighter,
        ),
    )
}
