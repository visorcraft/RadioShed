package com.visorcraft.radioshed.ui.util

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * No-op on Android. Touch users get the system's transient fling
 * indicator on a scrolling list — adding a persistent track here
 * would steal a few dp of content width and read as
 * "this scrolls" twice. The desktop actual is where the actual
 * VerticalScrollbar lives.
 */
@Composable
actual fun ListScrollbar(
    state: LazyListState,
    modifier: Modifier,
) {
    // Intentionally empty.
}

@Composable
actual fun GridScrollbar(
    state: LazyGridState,
    modifier: Modifier,
) {
    // Intentionally empty.
}
