package com.visorcraft.radioshed.nav

import androidx.compose.runtime.Composable

/**
 * Desktop has no system back gesture; the Esc-key / window-close
 * paths are handled by the Compose Window itself. Wiring a global
 * keyboard shortcut for "back" inside the app is deferred — too
 * easy to interfere with focused text input. So this is a no-op.
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // intentionally empty
}
