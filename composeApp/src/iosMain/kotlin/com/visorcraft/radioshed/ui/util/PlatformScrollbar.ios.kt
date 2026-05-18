package com.visorcraft.radioshed.ui.util

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * iOS no-op. Same rationale as Android — touch-first platform,
 * native UIScrollView indicator handles the affordance.
 */
@Composable
actual fun ListScrollbar(state: LazyListState, modifier: Modifier) {
    // Intentionally empty.
}

@Composable
actual fun GridScrollbar(state: LazyGridState, modifier: Modifier) {
    // Intentionally empty.
}
