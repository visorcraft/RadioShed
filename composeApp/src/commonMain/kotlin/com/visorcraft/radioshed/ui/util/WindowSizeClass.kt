package com.visorcraft.radioshed.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Coarse window-width class used by every adaptive layout in the
 * app. The breakpoints match Material 3's `WindowWidthSizeClass`:
 *
 *  - **Compact** — `maxWidth < 600.dp` — phone portrait
 *  - **Medium** — `600.dp..839.dp` — large phone landscape, small tablet portrait
 *  - **Expanded** — `>=840.dp` — tablet landscape, foldable unfolded, desktop window
 *
 * We define our own enum (instead of pulling Material 3's
 * `WindowWidthSizeClass` directly) because the official accessor
 * needs an `Activity`/`Window`, which doesn't exist in Compose
 * Multiplatform's commonMain — a homegrown enum lets the two-pane
 * / three-pane responsive logic stay shared across Android, iOS,
 * and desktop.
 */
enum class RsWindowSizeClass {
    Compact,
    Medium,
    Expanded,
    ;

    /** True when at least Medium — i.e. wider than a typical phone portrait. */
    val atLeastMedium: Boolean get() = this != Compact

    /** True when Expanded — i.e. tablet/desktop class width. */
    val isExpanded: Boolean get() = this == Expanded
}

/** Resolve the size class from a measured width. */
fun rsWindowSizeClassOf(width: Dp): RsWindowSizeClass =
    when {
        width < 600.dp -> RsWindowSizeClass.Compact
        width < 840.dp -> RsWindowSizeClass.Medium
        else -> RsWindowSizeClass.Expanded
    }

/**
 * Composable accessor — returns the class for the *measured*
 * width passed in. Call this from inside a `BoxWithConstraints`
 * with `rsWindowSizeClassOf(maxWidth)`. There is no
 * "current LocalWindowSizeClass" because a single screen can
 * legitimately host multiple panes that each compute their own
 * size class against their own slot.
 */
@Composable
@ReadOnlyComposable
fun rememberWindowSizeClassFor(width: Dp): RsWindowSizeClass = rsWindowSizeClassOf(width)
