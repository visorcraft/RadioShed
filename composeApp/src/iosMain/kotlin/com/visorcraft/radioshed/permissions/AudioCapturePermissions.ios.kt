package com.visorcraft.radioshed.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberAudioCaptureGate(): AudioCaptureGate =
    remember {
        object : AudioCaptureGate {
            override val granted: Boolean = false

            override fun request() {}
        }
    }
