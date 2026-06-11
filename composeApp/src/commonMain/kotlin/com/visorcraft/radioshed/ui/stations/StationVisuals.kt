package com.visorcraft.radioshed.ui.stations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import com.visorcraft.radioshed.domain.Station

/**
 * Shared station-tile visuals (accent colour, fallback gradient, artwork tile)
 * used by Stations Home's tiles, Drive Mode's
 * [com.visorcraft.radioshed.ui.car.DriveStationPicker] tiles, and the
 * station-launcher widget's config rows. Extracted so the surfaces can't drift
 * apart (the per-screen copies were verbatim duplicates).
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

/**
 * Station artwork-or-fallback tile: the station's artwork (cropped to fill)
 * over an accent-coloured backdrop, or a music-note glyph when no artwork is
 * set. Size and corner rounding come from [modifier] so each surface keeps its
 * own geometry.
 *
 * [station] is nullable so the widget config's empty slots render the same
 * placeholder (neutral backdrop + glyph) without duplicating this layout.
 * [background] exists because Drive Mode paints [stationGradient] behind its
 * artwork-dominant tiles while list thumbs use the flat accent colour, and
 * [fallbackIconTint] / [fallbackIconSize] because Drive's glyph is oversized
 * and white for at-a-glance legibility on that gradient.
 */
@Composable
internal fun StationArtwork(
    station: Station?,
    modifier: Modifier = Modifier,
    background: Brush = SolidColor(
        station?.let { stationColor(it) } ?: MaterialTheme.colorScheme.surfaceVariant,
    ),
    fallbackIconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    fallbackIconSize: Dp? = null,
) {
    Box(
        modifier = modifier.background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (station?.artworkUri != null) {
            AsyncImage(
                model = station.artworkUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                modifier = fallbackIconSize?.let { Modifier.size(it) } ?: Modifier,
                tint = fallbackIconTint,
            )
        }
    }
}
