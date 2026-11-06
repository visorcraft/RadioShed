package com.visorcraft.radioshed.ui.util

import androidx.compose.runtime.Composable

/**
 * Holds the screen on while [active] is true. Implementations apply
 * `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` on Android and
 * `UIApplication.idleTimerDisabled` on iOS (when wired in).
 *
 * Composes as a `DisposableEffect` so the flag is cleared when
 * [active] becomes false or this composable leaves the tree. The
 * app root owns it for the whole playback session; a screen that
 * also called it would clear the window flag on navigation.
 */
@Composable
expect fun KeepScreenOn(active: Boolean)
