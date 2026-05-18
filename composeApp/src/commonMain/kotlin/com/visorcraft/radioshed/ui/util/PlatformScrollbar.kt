package com.visorcraft.radioshed.ui.util

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Desktop-only draggable scrollbar for a [LazyListState] / [LazyGridState].
 *
 * On Compose Desktop (Windows / Linux / macOS via Skiko) this renders the
 * standard `androidx.compose.foundation.VerticalScrollbar` overlay — the
 * thing Windows users expect on a long list, with drag-to-scroll and
 * track-click-to-page-jump. On Android (and iOS) it's a no-op: touch
 * users get the system's transient fling-indicator without an extra
 * always-on bar competing for content width.
 *
 * Call site pattern: wrap the Lazy* composable in a `Box` and apply
 * `Modifier.align(Alignment.CenterEnd).fillMaxHeight()` to the
 * scrollbar so it sits inside the right edge of the list:
 *
 *     Box(modifier = Modifier.fillMaxSize()) {
 *         LazyColumn(state = state) { ... }
 *         ListScrollbar(
 *             state = state,
 *             modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
 *         )
 *     }
 */
@Composable
expect fun ListScrollbar(state: LazyListState, modifier: Modifier = Modifier)

/** Grid-state variant of [ListScrollbar]. See its kdoc for the call site pattern. */
@Composable
expect fun GridScrollbar(state: LazyGridState, modifier: Modifier = Modifier)
