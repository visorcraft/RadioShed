package com.visorcraft.radioshed.ui.util

import androidx.compose.runtime.Composable

/**
 * Holds the screen on while [active] is true. Implementations apply
 * `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` on Android and
 * `UIApplication.idleTimerDisabled` on iOS (when wired in).
 *
 * Composes as a `DisposableEffect` so the flag is automatically
 * cleared when the screen leaves composition (the user navigates
 * away from Now Playing) — without that, the flag would leak across
 * the rest of the app and stop honouring system display-timeout.
 */
@Composable
expect fun KeepScreenOn(active: Boolean)
