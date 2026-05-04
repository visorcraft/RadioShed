package com.visorcraft.radioshed.ui.util

import androidx.compose.runtime.Composable

@Composable
actual fun KeepScreenOn(active: Boolean) {
    // iOS impl will set UIApplication.sharedApplication.idleTimerDisabled
    // once Mac access is available. No-op for now — matches the rest
    // of the iOS shim coverage.
}
