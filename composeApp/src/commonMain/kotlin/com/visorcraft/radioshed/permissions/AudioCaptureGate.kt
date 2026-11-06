package com.visorcraft.radioshed.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable

/** Runtime permission gate for live output analysis on Android. */
@Composable
expect fun rememberAudioCaptureGate(): AudioCaptureGate

@Stable
interface AudioCaptureGate {
    val granted: Boolean

    fun request()
}
