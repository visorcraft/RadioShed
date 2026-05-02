package com.visorcraft.radioshed.ui.util

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Compact "currently playing" indicator drop-in for track-row layouts.
 * Renders an equalizer-style icon tinted in the primary colour next
 * to the row's title when the row's track is the one the audio
 * engine is currently outputting.
 *
 * Usage:
 * ```
 * Row {
 *     Text(track.title, color = if (isCurrent) primary else default)
 *     NowPlayingIndicator(isCurrent)
 *     ...
 * }
 * ```
 *
 * The icon is the equalizer glyph rather than a play arrow because
 * "play" reads as "tap me to play" — confusing on a list where any
 * row is tappable to play. The bars more clearly convey "this is
 * the one already playing." Static (not animated) to keep paint
 * cost zero in long lists.
 */
@Composable
fun NowPlayingIndicator(isCurrent: Boolean, modifier: Modifier = Modifier) {
    if (!isCurrent) return
    Spacer(Modifier.size(6.dp))
    Icon(
        imageVector = Icons.Filled.Equalizer,
        contentDescription = "Now playing",
        modifier = modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
}
