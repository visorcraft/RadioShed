package com.visorcraft.radioshed.ui.util

import androidx.compose.runtime.Composable

/**
 * No-op on desktop. The OS owns screensaver / display-timeout
 * behaviour and there's no sanctioned cross-platform way to
 * suppress it from a JVM app. Users who want their screen on
 * during long playback sessions disable the timeout in the OS.
 */
@Composable
actual fun KeepScreenOn(active: Boolean) {
    // intentionally empty
}
