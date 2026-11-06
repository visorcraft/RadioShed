package com.visorcraft.radioshed.ui.util

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.visorcraft.radioshed.isDesktopHost

/**
 * Drop-in replacement for [IconButton] that surfaces a hover tooltip
 * on desktop. On touch builds (Android / iOS) it falls through to a
 * plain [IconButton] — Material's tooltip on touch fires on a long
 * press, which collides with the codebase's existing
 * `combinedClickable` long-press affordances (track context sheets,
 * sleep-timer quick-arm, repeat-mode reverse, etc.).
 *
 * Use anywhere a previously-mute icon needs explanation: the
 * Lyrics / Shuffle / Repeat / Queue / Sleep buttons in Now Playing,
 * the transport buttons in the MiniPlayer, the close affordances on
 * dialogs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipIconButton(
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!isDesktopHost || tooltip.isBlank()) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier, content = content)
        return
    }
    val state = rememberTooltipState()
    TooltipBox(
        // Compose Multiplatform 1.10 deprecated the no-arg overload that
        // implicitly anchored above the trigger. The new overload takes
        // a `TooltipAnchorPosition`; `Above` preserves the previous
        // visual behaviour (tooltip floats above the icon button).
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = state,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            content = content,
        )
    }
}

/**
 * Generic tooltip wrapper for non-IconButton affordances — anything
 * built on `Surface + combinedClickable`, `Box + clickable`, etc.
 * Wraps [content] in a [TooltipBox] on desktop and hands [content]
 * through unchanged on touch builds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Tooltipped(
    tooltip: String,
    content: @Composable () -> Unit,
) {
    if (!isDesktopHost || tooltip.isBlank()) {
        content()
        return
    }
    val state = rememberTooltipState()
    TooltipBox(
        // Compose Multiplatform 1.10 deprecated the no-arg overload that
        // implicitly anchored above the trigger. The new overload takes
        // a `TooltipAnchorPosition`; `Above` preserves the previous
        // visual behaviour (tooltip floats above the icon button).
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = state,
    ) { content() }
}
