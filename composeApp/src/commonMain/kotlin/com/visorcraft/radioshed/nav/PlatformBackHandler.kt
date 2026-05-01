package com.visorcraft.radioshed.nav

import androidx.compose.runtime.Composable

/**
 * Cross-platform system-back handler. Android wires this to
 * [androidx.activity.compose.BackHandler]; iOS no-ops because the standard
 * gesture (swipe from left edge) is handled by the host UIViewController
 * stack rather than by composables.
 *
 * Use inside any non-root screen that needs to navigate back through the
 * app's [AppScreen] state machine instead of finishing the activity.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
