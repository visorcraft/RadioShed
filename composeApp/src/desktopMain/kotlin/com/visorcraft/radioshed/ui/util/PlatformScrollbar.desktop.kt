package com.visorcraft.radioshed.ui.util

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Desktop scrollbar — wraps the JetBrains-extension
 * [androidx.compose.foundation.VerticalScrollbar] with a
 * [rememberScrollbarAdapter] bound to the supplied list state. The
 * scrollbar's modifier is the caller's responsibility (typically
 * `Modifier.align(Alignment.CenterEnd).fillMaxHeight()` inside a
 * `Box`); we add nothing on top so callers can still tweak width /
 * padding / interaction source if a future surface needs to.
 */
@Composable
actual fun ListScrollbar(state: LazyListState, modifier: Modifier) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier,
    )
}

@Composable
actual fun GridScrollbar(state: LazyGridState, modifier: Modifier) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier,
    )
}
